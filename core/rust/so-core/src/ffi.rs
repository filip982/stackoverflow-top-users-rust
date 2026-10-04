//! The complete UniFFI-exported surface. Keep it small (see docs/architecture.md).
//!
//! Async methods run on the Tokio runtime owned by the core (UniFFI's
//! `async_runtime = "tokio"` integration), so hosts need no executor.

use std::sync::{Arc, Mutex, Weak};

use crate::repository::ObserverToken;
use crate::{
    CoreError, GetTopUsers, JsonFileFollowStore, ToggleFollow, User, UserApiService, UserId,
    UserRepository,
};

/// Implemented by the host app (Swift/Kotlin) to observe follow changes.
/// Called with the full sorted snapshot after every committed change, on a
/// core thread — hosts must hop to their UI thread.
#[uniffi::export(callback_interface)]
pub trait FollowObserver: Send + Sync {
    fn on_follows_changed(&self, followed_ids: Vec<UserId>);
}

/// Shared core facade. Create exactly one per app process.
#[derive(uniffi::Object)]
pub struct SoCore {
    repository: Arc<UserRepository>,
    get_top_users: GetTopUsers,
    toggle_follow: ToggleFollow,
}

/// Creates the core. `base_url` is the API origin (real API or mock server);
/// `storage_path` is the follow-state JSON file path inside the app sandbox.
#[uniffi::export]
pub fn new_core(base_url: String, storage_path: String) -> Arc<SoCore> {
    let repository = Arc::new(UserRepository::new(
        UserApiService::new(base_url),
        Arc::new(JsonFileFollowStore::new(storage_path)),
    ));
    Arc::new(SoCore {
        get_top_users: GetTopUsers::new(Arc::clone(&repository)),
        toggle_follow: ToggleFollow::new(Arc::clone(&repository)),
        repository,
    })
}

#[uniffi::export(async_runtime = "tokio")]
impl SoCore {
    /// Top 20 users, reputation descending.
    pub async fn get_top_users(&self) -> Result<Vec<User>, CoreError> {
        self.get_top_users.execute().await
    }

    /// Returns the new follow state (`true` = now followed).
    pub async fn toggle_follow(&self, user_id: UserId) -> Result<bool, CoreError> {
        self.toggle_follow.execute(user_id).await
    }

    /// Snapshot of followed ids (sorted). The first call after a corrupt
    /// store was reset returns `CoreError::Storage` once.
    pub fn followed_ids(&self) -> Result<Vec<UserId>, CoreError> {
        self.repository.followed_ids()
    }

    /// Registers an observer. Keep the returned handle; `dispose()` it (or drop
    /// it) to unregister.
    pub fn add_follow_observer(&self, observer: Box<dyn FollowObserver>) -> Arc<FollowObservation> {
        let token = self.repository.add_observer(Arc::from(observer));
        Arc::new(FollowObservation {
            repository: Arc::downgrade(&self.repository),
            token: Mutex::new(Some(token)),
        })
    }
}

/// Registration handle for a [`FollowObserver`].
#[derive(uniffi::Object)]
pub struct FollowObservation {
    repository: Weak<UserRepository>,
    token: Mutex<Option<ObserverToken>>,
}

#[uniffi::export]
impl FollowObservation {
    /// Unregisters the observer. Idempotent.
    pub fn dispose(&self) {
        let token = self.token.lock().unwrap_or_else(|e| e.into_inner()).take();
        if let (Some(token), Some(repository)) = (token, self.repository.upgrade()) {
            repository.remove_observer(token);
        }
    }
}

impl Drop for FollowObservation {
    fn drop(&mut self) {
        self.dispose();
    }
}
