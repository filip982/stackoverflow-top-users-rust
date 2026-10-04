//! StackOverflow Users shared core: entities, wire DTOs, HTTP service, follow
//! persistence, repository and use cases. The UniFFI surface lives in [`ffi`].

pub mod api;
pub mod dto;
pub mod entities;
pub mod error;
pub mod ffi;
pub mod follow_store;
pub mod repository;
pub mod use_cases;

pub use api::UserApiService;
pub use entities::{SortDirection, SortField, User, UserId};
pub use error::CoreError;
pub use ffi::{new_core, FollowObservation, FollowObserver, SoCore};
pub use follow_store::{FollowStore, JsonFileFollowStore};
pub use repository::UserRepository;
pub use use_cases::{GetTopUsers, SortUsers, ToggleFollow};

uniffi::setup_scaffolding!();
