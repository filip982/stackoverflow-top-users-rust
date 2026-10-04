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
        let url = users_url(&self.base_url)?;
        let response = self
            .client
            .get(url)
            .header(reqwest::header::ACCEPT, "application/json")
            .send()
            .await
            .map_err(network_error)?;
        let status = response.status();
        let body = response.bytes().await.map_err(network_error)?;
        if !status.is_success() {
            return Err(CoreError::Http {
                code: status.as_u16(),
            });
        }
        parse_users_response(&body)
    }
}

/// Builds `{base}/2.3/users?site=stackoverflow&pagesize=20&order=desc&sort=reputation`.
pub fn users_url(base_url: &str) -> Result<Url, CoreError> {
    let invalid = |reason: String| CoreError::Network {
        reason: format!("invalid base url {base_url:?}: {reason}"),
    };
    let mut url = Url::parse(base_url).map_err(|e| invalid(e.to_string()))?;
    if url.cannot_be_a_base() {
        return Err(invalid("cannot be a base".into()));
    }
    url.path_segments_mut()
        .map_err(|()| invalid("cannot be a base".into()))?
        .pop_if_empty()
        .extend(["2.3", "users"]);
    url.query_pairs_mut().clear().extend_pairs(USERS_QUERY);
    Ok(url)
}

fn network_error(err: reqwest::Error) -> CoreError {
    // Body read failures after headers arrived are still transport problems.
    CoreError::Network {
        reason: err.to_string(),
    }
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
