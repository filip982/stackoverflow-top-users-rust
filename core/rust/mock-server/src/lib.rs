//! axum mock of the StackExchange `GET /2.3/users` endpoint.
//!
//! Every instance has its own scenario state, so parallel tests each spawn an
//! instance on an ephemeral port (`127.0.0.1:0`) and never share globals.

use std::net::SocketAddr;
use std::str::FromStr;
use std::time::Duration;

/// Fixture served by the `success` scenario. `{{BASE_URL}}` is replaced with
/// the request's origin so avatar URLs point back at this server.
pub const USERS_FIXTURE: &str = include_str!("../../fixtures/users.json");
pub const BASE_URL_PLACEHOLDER: &str = "{{BASE_URL}}";
/// Per-request scenario header.
pub const SCENARIO_HEADER: &str = "x-mock-scenario";
/// Optional per-request delay override for the `slow` scenario.
pub const DELAY_HEADER: &str = "x-mock-delay-ms";
pub const DEFAULT_SLOW_DELAY: Duration = Duration::from_secs(3);

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Scenario {
    #[default]
    Success,
    Error,
    Empty,
    Slow,
    Malformed,
}

impl Scenario {
    pub fn as_str(self) -> &'static str {
        todo!()
    }
}

impl FromStr for Scenario {
    type Err = String;
    fn from_str(s: &str) -> Result<Self, Self::Err> {
        let _ = s;
        todo!()
    }
}

/// Running server. Dropping it (or calling [`ServerHandle::shutdown`]) stops it.
pub struct ServerHandle {
    _private: (),
}

impl ServerHandle {
    pub async fn shutdown(self) {
        todo!()
    }
}

/// Binds `addr` (use port 0 for an ephemeral port) and serves in the background
/// on the current Tokio runtime.
pub async fn spawn_server(addr: SocketAddr) -> std::io::Result<(ServerHandle, SocketAddr)> {
    let _ = addr;
    todo!()
}

/// Tiny solid-colour PNG avatar for `id` (deterministic).
pub fn avatar_png(id: u64) -> Vec<u8> {
    let _ = id;
    todo!()
}

pub fn crc32(data: &[u8]) -> u32 {
    let _ = data;
    todo!()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn crc32_matches_reference_check_value() {
        assert_eq!(crc32(b"123456789"), 0xCBF4_3926);
    }

    #[test]
    fn avatar_is_a_well_formed_png() {
        let png = avatar_png(22656);
        assert_eq!(&png[..8], b"\x89PNG\r\n\x1a\n");
        assert_eq!(&png[12..16], b"IHDR");
        assert_eq!(u32::from_be_bytes(png[16..20].try_into().unwrap()), 16);
        assert_eq!(u32::from_be_bytes(png[20..24].try_into().unwrap()), 16);
        // Every chunk's CRC must validate.
        let mut pos = 8;
        let mut kinds = vec![];
        while pos < png.len() {
            let len = u32::from_be_bytes(png[pos..pos + 4].try_into().unwrap()) as usize;
            let body = &png[pos + 4..pos + 8 + len];
            let crc = u32::from_be_bytes(png[pos + 8 + len..pos + 12 + len].try_into().unwrap());
            assert_eq!(crc32(body), crc);
            kinds.push(String::from_utf8_lossy(&body[..4]).into_owned());
            pos += 12 + len;
        }
        assert_eq!(kinds, ["IHDR", "IDAT", "IEND"]);
        assert_ne!(avatar_png(1), avatar_png(2), "colour derived from id");
    }

    #[test]
    fn scenario_round_trips_through_strings() {
        for s in ["success", "error", "empty", "slow", "malformed"] {
            assert_eq!(s.parse::<Scenario>().unwrap().as_str(), s);
        }
        assert_eq!(" SLOW ".parse::<Scenario>().unwrap(), Scenario::Slow);
        assert!("nope".parse::<Scenario>().is_err());
    }

    #[test]
    fn fixture_has_twenty_users_with_placeholder_avatars() {
        let v: serde_json::Value = serde_json::from_str(USERS_FIXTURE).unwrap();
        let items = v["items"].as_array().unwrap();
        assert_eq!(items.len(), 20);
        assert!(USERS_FIXTURE.contains(BASE_URL_PLACEHOLDER));
    }
}
