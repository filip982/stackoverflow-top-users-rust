//! Wire DTOs for the StackExchange `/2.3/users` endpoint and their mapping to
//! domain entities. Nothing outside this module (and [`crate::api`]) sees a DTO.

use serde::Deserialize;

use crate::{CoreError, User};

/// Top-level wrapper. A StackExchange error response has no `items` but carries
/// `error_id` / `error_name` / `error_message`. Unknown fields are ignored.
#[derive(Debug, Deserialize)]
pub struct UsersResponseDto {
    #[serde(default)]
    pub items: Option<Vec<UserDto>>,
    #[serde(default)]
    pub error_id: Option<i64>,
    #[serde(default)]
    pub error_name: Option<String>,
    #[serde(default)]
    pub error_message: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct UserDto {
    pub user_id: u64,
    pub display_name: String,
    pub reputation: u64,
    pub creation_date: i64,
    #[serde(default, alias = "last_modify_date")]
    pub last_modified_date: Option<i64>,
    #[serde(default)]
    pub profile_image: Option<String>,
    #[serde(default)]
    pub location: Option<String>,
    #[serde(default)]
    pub website_url: Option<String>,
}

impl From<UserDto> for User {
    fn from(dto: UserDto) -> Self {
        User {
            id: dto.user_id,
            display_name: decode_html(&dto.display_name),
            reputation: dto.reputation,
            avatar_url: non_blank(dto.profile_image),
            location: non_blank(dto.location).map(|l| decode_html(&l)),
            website_url: non_blank(dto.website_url),
            creation_date: dto.creation_date,
            last_modified_date: dto.last_modified_date,
        }
    }
}

/// Decodes a `/2.3/users` response body into domain users.
///
/// - `{"items": [...]}` -> users (in wire order; unknown fields ignored)
/// - StackExchange error object (`error_id`) -> [`CoreError::Http`] with that id
/// - anything else (malformed JSON, missing required field, no `items`) ->
///   [`CoreError::Decoding`]
pub fn parse_users_response(body: &[u8]) -> Result<Vec<User>, CoreError> {
    let dto: UsersResponseDto = serde_json::from_slice(body).map_err(|e| CoreError::Decoding {
        reason: e.to_string(),
    })?;
    if let Some(error_id) = dto.error_id {
        return Err(CoreError::Http {
            code: u16::try_from(error_id).unwrap_or(0),
        });
    }
    let items = dto.items.ok_or_else(|| CoreError::Decoding {
        reason: "response has neither `items` nor `error_id`".into(),
    })?;
    Ok(items.into_iter().map(User::from).collect())
}

/// Decodes HTML entities (named + numeric) as returned by StackExchange.
/// Unknown entities are left untouched.
pub fn decode_html(input: &str) -> String {
    html_escape::decode_html_entities(input).into_owned()
}

fn non_blank(value: Option<String>) -> Option<String> {
    value.filter(|v| !v.trim().is_empty())
}

#[cfg(test)]
mod tests {
    use super::*;

    const USERS: &str = include_str!("../../fixtures/users.json");
    const EDGE: &str = include_str!("../../fixtures/edge_users.json");
    const API_ERROR: &str = include_str!("../../fixtures/api_error.json");
    const MALFORMED: &str = include_str!("../../fixtures/malformed.json");
    const EMPTY: &str = include_str!("../../fixtures/empty.json");

    #[test]
    fn maps_full_fixture_of_twenty_users_in_wire_order() {
        let users = parse_users_response(USERS.as_bytes()).unwrap();
        assert_eq!(users.len(), 20);
        let jon = &users[0];
        assert_eq!(
            jon,
            &User {
                id: 22656,
                display_name: "Jon Skeet".into(),
                reputation: 1_520_345,
                avatar_url: Some("{{BASE_URL}}/avatars/22656.png".into()),
                location: Some("Reading, United Kingdom".into()),
                website_url: Some("http://csharpindepth.com".into()),
                creation_date: 1_222_430_705,
                last_modified_date: Some(1_727_187_919),
            }
        );
    }

    #[test]
    fn fixture_edge_users_tolerate_missing_optionals() {
        let users = parse_users_response(USERS.as_bytes()).unwrap();
        let by_id = |id| users.iter().find(|u| u.id == id).unwrap();
        assert_eq!(by_id(17034).last_modified_date, None, "Hans: no modified");
        assert_eq!(by_id(34397).avatar_url, None, "SLaks: no avatar");
        assert_eq!(by_id(3732271).location, None, "akrun: no location");
        assert_eq!(by_id(6309).website_url, None, "VonC: no website");
        assert_eq!(by_id(29407).website_url, None, "empty website -> None");
    }

    #[test]
    fn decodes_numeric_entities_in_names_and_locations() {
        let users = parse_users_response(USERS.as_bytes()).unwrap();
        let by_id = |id| users.iter().find(|u| u.id == id).unwrap();
        assert_eq!(by_id(3832970).display_name, "Wiktor Stribiżew");
        assert_eq!(by_id(217408).display_name, "Günter Zöchbauer");
        assert_eq!(
            by_id(157882).location.as_deref(),
            Some("Willemstad, Curaçao")
        );
    }

    #[test]
    fn minimal_user_and_unknown_fields_are_tolerated() {
        let users = parse_users_response(EDGE.as_bytes()).unwrap();
        assert_eq!(users.len(), 2);
        assert_eq!(
            users[0],
            User {
                id: 1,
                display_name: "Minimal & \"Bare\" <User> 'one' ☺".into(),
                reputation: 10,
                avatar_url: None,
                location: None,
                website_url: None,
                creation_date: 1_600_000_000,
                last_modified_date: None,
            }
        );
        let second = &users[1];
        assert_eq!(second.location, None, "blank location -> None");
        assert_eq!(second.website_url, None, "empty website -> None");
        assert_eq!(
            second.avatar_url.as_deref(),
            Some("https://example.com/a.png")
        );
        assert_eq!(second.last_modified_date, Some(1_700_000_001));
    }

    #[test]
    fn accepts_last_modify_date_alias() {
        let body = br#"{"items":[{"user_id":5,"display_name":"A","reputation":1,
            "creation_date":1,"last_modify_date":99}]}"#;
        let users = parse_users_response(body).unwrap();
        assert_eq!(users[0].last_modified_date, Some(99));
    }

    #[test]
    fn empty_items_is_success_with_no_users() {
        assert_eq!(parse_users_response(EMPTY.as_bytes()).unwrap(), vec![]);
    }

    #[test]
    fn malformed_json_is_decoding_error() {
        let err = parse_users_response(MALFORMED.as_bytes()).unwrap_err();
        assert!(matches!(err, CoreError::Decoding { .. }), "{err:?}");
    }

    #[test]
    fn missing_required_field_is_decoding_error() {
        let body = br#"{"items":[{"user_id":5,"reputation":1,"creation_date":1}]}"#;
        let err = parse_users_response(body).unwrap_err();
        assert!(matches!(err, CoreError::Decoding { .. }), "{err:?}");
    }

    #[test]
    fn api_error_object_maps_to_http_error_with_error_id() {
        let err = parse_users_response(API_ERROR.as_bytes()).unwrap_err();
        assert_eq!(err, CoreError::Http { code: 502 });
    }

    #[test]
    fn body_without_items_or_error_is_decoding_error() {
        let err = parse_users_response(br#"{"quota_max":300}"#).unwrap_err();
        assert!(matches!(err, CoreError::Decoding { .. }), "{err:?}");
    }

    #[test]
    fn decode_html_handles_named_and_numeric_entities() {
        assert_eq!(decode_html("a &amp; b"), "a & b");
        assert_eq!(decode_html("&lt;&gt;&quot;&#39;&apos;"), "<>\"''");
        assert_eq!(decode_html("Z&#246;ch &#x17C;"), "Zöch ż");
        assert_eq!(decode_html("plain"), "plain");
        assert_eq!(
            decode_html("broken &notanentity; &"),
            "broken &notanentity; &"
        );
    }
}
