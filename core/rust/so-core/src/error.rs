/// Typed core error surfaced across the FFI boundary.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    /// Transport failure: offline, DNS, connection refused, timeout, TLS.
    #[error("network error: {reason}")]
    Network { reason: String },
    /// Non-success HTTP status, or a StackExchange error object (`error_id`).
    #[error("http error {code}")]
    Http { code: u16 },
    /// Response body could not be decoded into users.
    #[error("decoding error: {reason}")]
    Decoding { reason: String },
    /// Follow persistence failed (I/O, or corrupt file that was reset).
    #[error("storage error: {reason}")]
    Storage { reason: String },
}

impl CoreError {
    pub(crate) fn storage(err: impl std::fmt::Display) -> Self {
        Self::Storage {
            reason: err.to_string(),
        }
    }
}
