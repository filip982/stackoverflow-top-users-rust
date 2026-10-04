//! HTTP client for the StackExchange users endpoint (reqwest + rustls).

use std::time::Duration;

use reqwest::Url;

use crate::dto::parse_users_response;
use crate::{CoreError, User};

/// Explicit query: the API defaults are not relied upon.
pub const USERS_QUERY: [(&str, &str); 4] = [
    ("site", "stackoverflow"),
    ("pagesize", "20"),
    ("order", "desc"),
    ("sort", "reputation"),
];

const REQUEST_TIMEOUT: Duration = Duration::from_secs(20);

#[derive(Debug, Clone)]
pub struct UserApiService {
    client: reqwest::Client,
    base_url: String,
}

impl UserApiService {
    /// `base_url` is the API origin, e.g. `https://api.stackexchange.com` or
    /// the mock server's `http://127.0.0.1:PORT`.
    pub fn new(base_url: impl Into<String>) -> Self {
        let client = reqwest::Client::builder()
            .timeout(REQUEST_TIMEOUT)
            .build()
            .expect("static reqwest client configuration is valid");
        Self {
            client,
            base_url: base_url.into(),
        }
    }

    pub async fn fetch_top_users(&self) -> Result<Vec<User>, CoreError> {
        let _ = &self.client;
        todo!()
    }
}

/// Builds `{base}/2.3/users?site=stackoverflow&pagesize=20&order=desc&sort=reputation`.
pub fn users_url(base_url: &str) -> Result<Url, CoreError> {
    let _ = base_url;
    todo!()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builds_explicit_users_query() {
        let url = users_url("https://api.stackexchange.com").unwrap();
        assert_eq!(
            url.as_str(),
            "https://api.stackexchange.com/2.3/users?site=stackoverflow&pagesize=20&order=desc&sort=reputation"
        );
    }

    #[test]
    fn tolerates_trailing_slash_and_port() {
        let url = users_url("http://127.0.0.1:8080/").unwrap();
        assert_eq!(url.host_str(), Some("127.0.0.1"));
        assert_eq!(url.port(), Some(8080));
        assert_eq!(url.path(), "/2.3/users");
    }

    #[test]
    fn invalid_base_url_is_network_error() {
        let err = users_url("not a url").unwrap_err();
        assert!(matches!(err, CoreError::Network { .. }), "{err:?}");
    }
}
