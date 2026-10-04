//! Single app-scoped repository over the API service and the follow store.

use std::collections::{BTreeSet, HashMap};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};

use crate::{CoreError, FollowObserver, FollowStore, User, UserApiService, UserId};

/// Token returned by [`UserRepository::add_observer`].
pub type ObserverToken = u64;

pub struct UserRepository {
    api: UserApiService,
    store: Arc<dyn FollowStore>,
    /// Serializes follow mutations (load-modify-save-notify) across tasks.
    write_lock: tokio::sync::Mutex<()>,
    /// Committed snapshot; readable synchronously from any thread.
    follows: RwLock<BTreeSet<UserId>>,
    /// Error from the initial load (e.g. corrupt file reset), surfaced once.
    pending_load_error: Mutex<Option<CoreError>>,
    observers: Mutex<HashMap<ObserverToken, Arc<dyn FollowObserver>>>,
    next_token: AtomicU64,
}

impl UserRepository {
    /// Loads persisted follows eagerly. A load failure starts from an empty set
    /// and is reported once by the first [`Self::followed_ids`] call.
    pub fn new(api: UserApiService, store: Arc<dyn FollowStore>) -> Self {
        let (follows, pending_load_error) = match store.load() {
            Ok(ids) => (ids, None),
            Err(err) => (BTreeSet::new(), Some(err)),
        };
        Self {
            api,
            store,
            write_lock: tokio::sync::Mutex::new(()),
            follows: RwLock::new(follows),
            pending_load_error: Mutex::new(pending_load_error),
            observers: Mutex::new(HashMap::new()),
            next_token: AtomicU64::new(1),
        }
    }

    pub async fn top_users(&self) -> Result<Vec<User>, CoreError> {
        self.api.fetch_top_users().await
    }

    /// Flips the follow state of `id`, persists it, then notifies observers.
    /// On a storage failure the in-memory state is left unchanged.
    pub async fn toggle_follow(&self, id: UserId) -> Result<bool, CoreError> {
        let _guard = self.write_lock.lock().await;
        let mut next = self.snapshot();
        let followed = if next.remove(&id) {
            false
        } else {
            next.insert(id);
            true
        };
        self.store.save(&next)?;
        let ids: Vec<UserId> = next.iter().copied().collect();
        *self.follows.write().unwrap_or_else(|e| e.into_inner()) = next;
        // Still under the write lock, so observers see changes in commit order.
        self.notify(ids);
        Ok(followed)
    }

    /// Sorted snapshot of followed ids.
    pub fn followed_ids(&self) -> Result<Vec<UserId>, CoreError> {
        let pending = lock(&self.pending_load_error).take();
        match pending {
            Some(err) => Err(err),
            None => Ok(self.snapshot().into_iter().collect()),
        }
    }

    pub fn add_observer(&self, observer: Arc<dyn FollowObserver>) -> ObserverToken {
        let token = self.next_token.fetch_add(1, Ordering::Relaxed);
        lock(&self.observers).insert(token, observer);
        token
    }

    pub fn remove_observer(&self, token: ObserverToken) {
        lock(&self.observers).remove(&token);
    }

    fn snapshot(&self) -> BTreeSet<UserId> {
        self.follows
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
    }

    fn notify(&self, ids: Vec<UserId>) {
        // Copy out first: observers may (un)register from inside the callback.
        let observers: Vec<_> = lock(&self.observers).values().cloned().collect();
        for observer in observers {
            observer.on_follows_changed(ids.clone());
        }
    }
}

fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|e| e.into_inner())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::JsonFileFollowStore;

    /// In-memory store that can be told to fail saves.
    #[derive(Default)]
    struct MemoryStore {
        ids: Mutex<BTreeSet<UserId>>,
        fail_saves: std::sync::atomic::AtomicBool,
        saves: AtomicU64,
    }

    impl FollowStore for MemoryStore {
        fn load(&self) -> Result<BTreeSet<UserId>, CoreError> {
            Ok(self.ids.lock().unwrap().clone())
        }
        fn save(&self, ids: &BTreeSet<UserId>) -> Result<(), CoreError> {
            if self.fail_saves.load(Ordering::SeqCst) {
                return Err(CoreError::Storage {
                    message: "disk full".into(),
                });
            }
            self.saves.fetch_add(1, Ordering::SeqCst);
            *self.ids.lock().unwrap() = ids.clone();
            Ok(())
        }
    }

    #[derive(Default)]
    struct Recorder(Mutex<Vec<Vec<UserId>>>);

    impl FollowObserver for Recorder {
        fn on_follows_changed(&self, followed_ids: Vec<UserId>) {
            self.0.lock().unwrap().push(followed_ids);
        }
    }

    fn api() -> UserApiService {
        UserApiService::new("http://127.0.0.1:9")
    }

    fn repo_with(store: Arc<dyn FollowStore>) -> UserRepository {
        UserRepository::new(api(), store)
    }

    #[tokio::test]
    async fn toggle_follows_then_unfollows() {
        let repo = repo_with(Arc::new(MemoryStore::default()));
        assert_eq!(repo.followed_ids().unwrap(), Vec::<UserId>::new());
        assert!(repo.toggle_follow(7).await.unwrap());
        assert_eq!(repo.followed_ids().unwrap(), vec![7]);
        assert!(!repo.toggle_follow(7).await.unwrap());
        assert_eq!(repo.followed_ids().unwrap(), Vec::<UserId>::new());
    }

    #[tokio::test]
    async fn starts_from_persisted_follows() {
        let store = Arc::new(MemoryStore::default());
        *store.ids.lock().unwrap() = [3, 1].into_iter().collect();
        let repo = repo_with(store);
        assert_eq!(repo.followed_ids().unwrap(), vec![1, 3]);
    }

    #[tokio::test]
    async fn follows_survive_fresh_instance_with_file_store() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("follows.json");
        {
            let repo = repo_with(Arc::new(JsonFileFollowStore::new(&path)));
            repo.toggle_follow(10).await.unwrap();
            repo.toggle_follow(20).await.unwrap();
            repo.toggle_follow(10).await.unwrap();
        }
        let fresh = repo_with(Arc::new(JsonFileFollowStore::new(&path)));
        assert_eq!(fresh.followed_ids().unwrap(), vec![20]);
    }

    #[tokio::test]
    async fn failed_save_keeps_state_and_does_not_notify() {
        let store = Arc::new(MemoryStore::default());
        let repo = repo_with(store.clone());
        let recorder = Arc::new(Recorder::default());
        repo.add_observer(recorder.clone());
        store.fail_saves.store(true, Ordering::SeqCst);

        let err = repo.toggle_follow(5).await.unwrap_err();
        assert!(matches!(err, CoreError::Storage { .. }), "{err:?}");
        assert_eq!(repo.followed_ids().unwrap(), Vec::<UserId>::new());
        assert!(recorder.0.lock().unwrap().is_empty());
    }

    #[tokio::test]
    async fn observers_receive_snapshots_until_removed() {
        let repo = repo_with(Arc::new(MemoryStore::default()));
        let a = Arc::new(Recorder::default());
        let b = Arc::new(Recorder::default());
        let token_a = repo.add_observer(a.clone());
        repo.add_observer(b.clone());

        repo.toggle_follow(2).await.unwrap();
        repo.remove_observer(token_a);
        repo.toggle_follow(1).await.unwrap();

        assert_eq!(*a.0.lock().unwrap(), vec![vec![2]]);
        assert_eq!(*b.0.lock().unwrap(), vec![vec![2], vec![1, 2]]);
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn concurrent_toggles_of_distinct_ids_are_all_applied() {
        let store = Arc::new(MemoryStore::default());
        let repo = Arc::new(repo_with(store.clone()));
        let recorder = Arc::new(Recorder::default());
        repo.add_observer(recorder.clone());

        let tasks: Vec<_> = (1..=50u64)
            .map(|id| {
                let repo = Arc::clone(&repo);
                tokio::spawn(async move { repo.toggle_follow(id).await })
            })
            .collect();
        for t in tasks {
            assert!(t.await.unwrap().unwrap());
        }

        let expected: Vec<UserId> = (1..=50).collect();
        assert_eq!(repo.followed_ids().unwrap(), expected);
        assert_eq!(store.load().unwrap().len(), 50, "every write persisted");
        assert_eq!(store.saves.load(Ordering::SeqCst), 50);
        // Serialized: each notification grows the set by exactly one.
        let snapshots = recorder.0.lock().unwrap();
        assert_eq!(snapshots.len(), 50);
        for (i, snap) in snapshots.iter().enumerate() {
            assert_eq!(snap.len(), i + 1);
        }
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn concurrent_double_toggle_of_same_id_nets_out() {
        let repo = Arc::new(repo_with(Arc::new(MemoryStore::default())));
        let (r1, r2) = tokio::join!(
            {
                let repo = Arc::clone(&repo);
                async move { repo.toggle_follow(9).await }
            },
            {
                let repo = Arc::clone(&repo);
                async move { repo.toggle_follow(9).await }
            }
        );
        let mut results = vec![r1.unwrap(), r2.unwrap()];
        results.sort();
        assert_eq!(results, vec![false, true], "one follow, one unfollow");
        assert_eq!(repo.followed_ids().unwrap(), Vec::<UserId>::new());
    }

    #[tokio::test]
    async fn corrupt_store_surfaces_storage_error_once_then_empty() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("follows.json");
        std::fs::write(&path, b"{corrupt").unwrap();
        let repo = repo_with(Arc::new(JsonFileFollowStore::new(&path)));

        let first = repo.followed_ids();
        assert!(matches!(first, Err(CoreError::Storage { .. })), "{first:?}");
        assert_eq!(repo.followed_ids().unwrap(), Vec::<UserId>::new());
        // Usable afterwards and persisted over the reset file.
        repo.toggle_follow(4).await.unwrap();
        let fresh = repo_with(Arc::new(JsonFileFollowStore::new(&path)));
        assert_eq!(fresh.followed_ids().unwrap(), vec![4]);
    }
}
