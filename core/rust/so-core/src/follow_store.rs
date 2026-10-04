//! Local persistence of followed user ids.

use std::collections::BTreeSet;
use std::fs::{self, File};
use std::io::{ErrorKind, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

use serde::{Deserialize, Serialize};

use crate::{CoreError, UserId};

/// Persistence port for followed ids. Implementations must be safe to call from
/// any thread; the repository serializes mutations.
pub trait FollowStore: Send + Sync {
    /// Loads the persisted set. A missing file is an empty set. A corrupt file is
    /// reset to empty and reported once as [`CoreError::Storage`].
    fn load(&self) -> Result<BTreeSet<UserId>, CoreError>;
    /// Atomically replaces the persisted set.
    fn save(&self, ids: &BTreeSet<UserId>) -> Result<(), CoreError>;
}

/// JSON file store. Writes go to a unique temp file in the same directory,
/// are fsynced, then renamed over the target (atomic on POSIX filesystems).
#[derive(Debug, Clone)]
pub struct JsonFileFollowStore {
    path: PathBuf,
}

impl JsonFileFollowStore {
    pub fn new(path: impl Into<PathBuf>) -> Self {
        Self { path: path.into() }
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    fn temp_path(&self) -> PathBuf {
        static COUNTER: AtomicU64 = AtomicU64::new(0);
        let n = COUNTER.fetch_add(1, Ordering::Relaxed);
        let mut name = self
            .path
            .file_name()
            .map(|n| n.to_os_string())
            .unwrap_or_else(|| "follows.json".into());
        name.push(format!(".tmp-{}-{n}", std::process::id()));
        self.path.with_file_name(name)
    }

    fn write_atomically(&self, bytes: &[u8]) -> std::io::Result<()> {
        if let Some(parent) = self.path.parent().filter(|p| !p.as_os_str().is_empty()) {
            fs::create_dir_all(parent)?;
        }
        let tmp = self.temp_path();
        let result = (|| {
            let mut file = File::create(&tmp)?;
            file.write_all(bytes)?;
            file.sync_all()?;
            fs::rename(&tmp, &self.path)
        })();
        if result.is_err() {
            let _ = fs::remove_file(&tmp);
        } else if let Some(parent) = self.path.parent().filter(|p| !p.as_os_str().is_empty()) {
            // Best effort: persist the rename itself (POSIX directory fsync).
            if let Ok(dir) = File::open(parent) {
                let _ = dir.sync_all();
            }
        }
        result
    }
}

/// On-disk format. Versioned so the format can evolve.
#[derive(Debug, Serialize, Deserialize)]
struct FollowFile {
    version: u32,
    followed_ids: BTreeSet<UserId>,
}

const FORMAT_VERSION: u32 = 1;

impl FollowStore for JsonFileFollowStore {
    fn load(&self) -> Result<BTreeSet<UserId>, CoreError> {
        let bytes = match fs::read(&self.path) {
            Ok(bytes) => bytes,
            Err(e) if e.kind() == ErrorKind::NotFound => return Ok(BTreeSet::new()),
            Err(e) => return Err(CoreError::storage(e)),
        };
        match serde_json::from_slice::<FollowFile>(&bytes) {
            Ok(file) => Ok(file.followed_ids),
            Err(parse_err) => {
                // Reset so the corruption is reported exactly once.
                let reset = self.save(&BTreeSet::new());
                Err(CoreError::Storage {
                    reason: match reset {
                        Ok(()) => {
                            format!("follow store was corrupt and has been reset: {parse_err}")
                        }
                        Err(e) => {
                            format!("follow store is corrupt ({parse_err}) and reset failed: {e}")
                        }
                    },
                })
            }
        }
    }

    fn save(&self, ids: &BTreeSet<UserId>) -> Result<(), CoreError> {
        let file = FollowFile {
            version: FORMAT_VERSION,
            followed_ids: ids.clone(),
        };
        let bytes = serde_json::to_vec(&file).map_err(CoreError::storage)?;
        self.write_atomically(&bytes).map_err(CoreError::storage)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::sync::Arc;

    fn set(ids: &[UserId]) -> BTreeSet<UserId> {
        ids.iter().copied().collect()
    }

    fn store_in(dir: &tempfile::TempDir) -> JsonFileFollowStore {
        JsonFileFollowStore::new(dir.path().join("follows.json"))
    }

    fn dir_entries(dir: &tempfile::TempDir) -> Vec<String> {
        let mut names: Vec<String> = fs::read_dir(dir.path())
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
            .collect();
        names.sort();
        names
    }

    #[test]
    fn missing_file_loads_empty() {
        let dir = tempfile::tempdir().unwrap();
        assert_eq!(store_in(&dir).load().unwrap(), set(&[]));
    }

    #[test]
    fn save_then_fresh_instance_reloads_same_ids() {
        let dir = tempfile::tempdir().unwrap();
        store_in(&dir).save(&set(&[3, 1, 2])).unwrap();
        assert_eq!(store_in(&dir).load().unwrap(), set(&[1, 2, 3]));
    }

    #[test]
    fn creates_missing_parent_directories() {
        let dir = tempfile::tempdir().unwrap();
        let store = JsonFileFollowStore::new(dir.path().join("a/b/follows.json"));
        store.save(&set(&[7])).unwrap();
        assert_eq!(store.load().unwrap(), set(&[7]));
    }

    #[test]
    fn save_leaves_no_temp_files_behind() {
        let dir = tempfile::tempdir().unwrap();
        let store = store_in(&dir);
        store.save(&set(&[1])).unwrap();
        store.save(&set(&[1, 2])).unwrap();
        assert_eq!(dir_entries(&dir), vec!["follows.json".to_string()]);
    }

    #[test]
    fn interrupted_write_does_not_affect_committed_file() {
        // Simulates a crash after the temp file was written but before rename.
        let dir = tempfile::tempdir().unwrap();
        let store = store_in(&dir);
        store.save(&set(&[42])).unwrap();
        fs::write(
            dir.path().join("follows.json.tmp-crashed"),
            b"{\"followed_i",
        )
        .unwrap();
        assert_eq!(store_in(&dir).load().unwrap(), set(&[42]));
    }

    #[test]
    fn concurrent_saves_always_leave_a_valid_file() {
        let dir = tempfile::tempdir().unwrap();
        let store = Arc::new(store_in(&dir));
        let handles: Vec<_> = (0..16u64)
            .map(|i| {
                let store = Arc::clone(&store);
                std::thread::spawn(move || store.save(&(0..=i).collect()).unwrap())
            })
            .collect();
        for h in handles {
            h.join().unwrap();
        }
        let loaded = store.load().unwrap();
        assert!(!loaded.is_empty());
        assert_eq!(loaded, (0..loaded.len() as u64).collect());
        assert_eq!(dir_entries(&dir), vec!["follows.json".to_string()]);
    }

    #[test]
    fn corrupt_file_resets_to_empty_and_reports_storage_error_once() {
        let dir = tempfile::tempdir().unwrap();
        fs::write(dir.path().join("follows.json"), b"not json at all").unwrap();
        let store = store_in(&dir);

        let first = store.load();
        assert!(matches!(first, Err(CoreError::Storage { .. })), "{first:?}");
        // The file was reset, so the error is not reported again.
        assert_eq!(store.load().unwrap(), set(&[]));
        assert_eq!(store_in(&dir).load().unwrap(), set(&[]));
    }

    #[test]
    fn wrong_shape_json_counts_as_corrupt() {
        let dir = tempfile::tempdir().unwrap();
        fs::write(dir.path().join("follows.json"), b"{\"followed_ids\":\"x\"}").unwrap();
        let store = store_in(&dir);
        assert!(matches!(store.load(), Err(CoreError::Storage { .. })));
        assert_eq!(store.load().unwrap(), set(&[]));
    }

    #[test]
    fn save_into_unwritable_location_is_storage_error() {
        let dir = tempfile::tempdir().unwrap();
        // Parent "directory" is a regular file, so the write must fail.
        fs::write(dir.path().join("blocker"), b"").unwrap();
        let store = JsonFileFollowStore::new(dir.path().join("blocker/follows.json"));
        assert!(matches!(
            store.save(&set(&[1])),
            Err(CoreError::Storage { .. })
        ));
    }
}
