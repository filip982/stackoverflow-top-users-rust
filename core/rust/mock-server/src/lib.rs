//! axum mock of the StackExchange `GET /2.3/users` endpoint.
//!
//! Every instance has its own scenario state, so parallel tests each spawn an
//! instance on an ephemeral port (`127.0.0.1:0`) and never share globals.

use std::collections::HashMap;
use std::net::SocketAddr;
use std::str::FromStr;
use std::sync::{Arc, RwLock};
use std::time::Duration;

use axum::body::Bytes;
use axum::extract::{Path, Query, State};
use axum::http::{header, HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde_json::json;
use tokio::sync::{oneshot, Notify};
use tokio::task::JoinHandle;

/// Fixture served by the `success` scenario. `{{BASE_URL}}` is replaced with
/// the request's origin so avatar URLs point back at this server.
pub const USERS_FIXTURE: &str = include_str!("../../fixtures/users.json");
pub const EMPTY_FIXTURE: &str = include_str!("../../fixtures/empty.json");
pub const MALFORMED_FIXTURE: &str = include_str!("../../fixtures/malformed.json");
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
        match self {
            Scenario::Success => "success",
            Scenario::Error => "error",
            Scenario::Empty => "empty",
            Scenario::Slow => "slow",
            Scenario::Malformed => "malformed",
        }
    }
}

impl FromStr for Scenario {
    type Err = String;
    fn from_str(s: &str) -> Result<Self, Self::Err> {
        match s.trim().to_ascii_lowercase().as_str() {
            "success" => Ok(Scenario::Success),
            "error" => Ok(Scenario::Error),
            "empty" => Ok(Scenario::Empty),
            "slow" => Ok(Scenario::Slow),
            "malformed" => Ok(Scenario::Malformed),
            other => Err(format!(
                "unknown scenario {other:?} (expected success|error|empty|slow|malformed)"
            )),
        }
    }
}

/// Per-instance state. Nothing is process-global.
#[derive(Clone)]
struct AppState {
    scenario: Arc<RwLock<Scenario>>,
    shutdown: Arc<Notify>,
}

/// Builds the router with its own scenario state (default `initial`).
fn router(initial: Scenario, shutdown: Arc<Notify>) -> Router {
    let state = AppState {
        scenario: Arc::new(RwLock::new(initial)),
        shutdown,
    };
    Router::new()
        .route("/2.3/users", get(users))
        .route("/__ready", get(|| async { "ok" }))
        .route("/__scenario", get(get_scenario).post(set_scenario))
        .route("/__shutdown", post(request_shutdown))
        .route("/avatars/{file}", get(avatar))
        .with_state(state)
}

fn api_error(status: StatusCode, id: u16, name: &str, message: &str) -> Response {
    let body = json!({"error_id": id, "error_name": name, "error_message": message});
    (status, Json(body)).into_response()
}

fn json_body(body: String) -> Response {
    (
        [(header::CONTENT_TYPE, "application/json; charset=utf-8")],
        body,
    )
        .into_response()
}

async fn users(
    State(state): State<AppState>,
    Query(query): Query<HashMap<String, String>>,
    headers: HeaderMap,
) -> Response {
    if query.get("site").map(String::as_str) != Some("stackoverflow") {
        return api_error(
            StatusCode::BAD_REQUEST,
            400,
            "bad_parameter",
            "site is required",
        );
    }
    let scenario = match headers.get(SCENARIO_HEADER).map(|v| v.to_str()) {
        None => *state.scenario.read().unwrap_or_else(|e| e.into_inner()),
        Some(Ok(raw)) => match raw.parse::<Scenario>() {
            Ok(s) => s,
            Err(msg) => return api_error(StatusCode::BAD_REQUEST, 400, "bad_parameter", &msg),
        },
        Some(Err(_)) => {
            return api_error(StatusCode::BAD_REQUEST, 400, "bad_parameter", "bad header")
        }
    };
    match scenario {
        Scenario::Success => json_body(users_body(&headers)),
        Scenario::Slow => {
            let delay = headers
                .get(DELAY_HEADER)
                .and_then(|v| v.to_str().ok())
                .and_then(|v| v.parse().ok())
                .map(Duration::from_millis)
                .unwrap_or(DEFAULT_SLOW_DELAY);
            tokio::time::sleep(delay).await;
            json_body(users_body(&headers))
        }
        Scenario::Empty => json_body(EMPTY_FIXTURE.to_owned()),
        Scenario::Malformed => json_body(MALFORMED_FIXTURE.to_owned()),
        Scenario::Error => api_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            500,
            "internal_error",
            "mock server error scenario",
        ),
    }
}

fn users_body(headers: &HeaderMap) -> String {
    let host = headers
        .get(header::HOST)
        .and_then(|h| h.to_str().ok())
        .unwrap_or("localhost");
    USERS_FIXTURE.replace(BASE_URL_PLACEHOLDER, &format!("http://{host}"))
}

async fn get_scenario(State(state): State<AppState>) -> Json<serde_json::Value> {
    let current = *state.scenario.read().unwrap_or_else(|e| e.into_inner());
    Json(json!({"scenario": current.as_str()}))
}

/// Accepts `{"scenario": "error"}` or a plain-text scenario name.
async fn set_scenario(State(state): State<AppState>, body: Bytes) -> Response {
    let raw = match serde_json::from_slice::<serde_json::Value>(&body) {
        Ok(v) => v["scenario"].as_str().unwrap_or_default().to_owned(),
        Err(_) => String::from_utf8_lossy(&body).into_owned(),
    };
    match raw.parse::<Scenario>() {
        Ok(scenario) => {
            *state.scenario.write().unwrap_or_else(|e| e.into_inner()) = scenario;
            Json(json!({"scenario": scenario.as_str()})).into_response()
        }
        Err(msg) => api_error(StatusCode::BAD_REQUEST, 400, "bad_parameter", &msg),
    }
}

async fn request_shutdown(State(state): State<AppState>) -> StatusCode {
    state.shutdown.notify_one();
    StatusCode::ACCEPTED
}

async fn avatar(Path(file): Path<String>) -> Response {
    match file
        .strip_suffix(".png")
        .and_then(|id| id.parse::<u64>().ok())
    {
        Some(id) => ([(header::CONTENT_TYPE, "image/png")], avatar_png(id)).into_response(),
        None => StatusCode::NOT_FOUND.into_response(),
    }
}

/// Running server. Dropping it (or calling [`ServerHandle::shutdown`]) stops it.
pub struct ServerHandle {
    stop: Option<oneshot::Sender<()>>,
    task: Option<JoinHandle<()>>,
}

impl ServerHandle {
    /// Stops accepting connections and waits for the server task to finish.
    pub async fn shutdown(mut self) {
        if let Some(stop) = self.stop.take() {
            let _ = stop.send(());
        }
        if let Some(task) = self.task.take() {
            let _ = task.await;
        }
    }

    /// Waits until the server stops (e.g. via `POST /__shutdown`).
    pub async fn wait(mut self) {
        if let Some(task) = self.task.take() {
            let _ = task.await;
        }
    }
}

impl Drop for ServerHandle {
    fn drop(&mut self) {
        if let Some(stop) = self.stop.take() {
            let _ = stop.send(());
        }
    }
}

/// Binds `addr` (use port 0 for an ephemeral port) and serves in the background
/// on the current Tokio runtime with the default `success` scenario.
pub async fn spawn_server(addr: SocketAddr) -> std::io::Result<(ServerHandle, SocketAddr)> {
    spawn_server_with(addr, Scenario::default()).await
}

/// Like [`spawn_server`], with an explicit initial scenario.
pub async fn spawn_server_with(
    addr: SocketAddr,
    initial: Scenario,
) -> std::io::Result<(ServerHandle, SocketAddr)> {
    let listener = tokio::net::TcpListener::bind(addr).await?;
    let bound = listener.local_addr()?;
    let shutdown = Arc::new(Notify::new());
    let app = router(initial, Arc::clone(&shutdown));
    let (stop_tx, stop_rx) = oneshot::channel::<()>();
    let task = tokio::spawn(async move {
        let signal = async move {
            tokio::select! {
                _ = stop_rx => {}
                _ = shutdown.notified() => {}
            }
        };
        if let Err(err) = axum::serve(listener, app)
            .with_graceful_shutdown(signal)
            .await
        {
            eprintln!("mock-server error: {err}");
        }
    });
    Ok((
        ServerHandle {
            stop: Some(stop_tx),
            task: Some(task),
        },
        bound,
    ))
}

/// Tiny 16x16 solid-colour PNG avatar for `id` (deterministic, ~100 bytes).
pub fn avatar_png(id: u64) -> Vec<u8> {
    const SIZE: u32 = 16;
    let hash = id.wrapping_mul(0x9E37_79B9_7F4A_7C15);
    let rgb = [(hash >> 16) as u8, (hash >> 32) as u8, (hash >> 48) as u8];

    let mut row = vec![0u8]; // filter: none
    row.extend(rgb.iter().copied().cycle().take(3 * SIZE as usize));
    let raw = row.repeat(SIZE as usize);

    // zlib stream with a single stored (uncompressed) deflate block.
    let len = raw.len() as u16;
    let mut zlib = vec![0x78, 0x01, 0x01];
    zlib.extend_from_slice(&len.to_le_bytes());
    zlib.extend_from_slice(&(!len).to_le_bytes());
    zlib.extend_from_slice(&raw);
    zlib.extend_from_slice(&adler32(&raw).to_be_bytes());

    let mut ihdr = Vec::with_capacity(13);
    ihdr.extend_from_slice(&SIZE.to_be_bytes());
    ihdr.extend_from_slice(&SIZE.to_be_bytes());
    ihdr.extend_from_slice(&[8, 2, 0, 0, 0]); // 8-bit RGB, no interlace

    let mut png = b"\x89PNG\r\n\x1a\n".to_vec();
    for (kind, data) in [
        (b"IHDR", &ihdr[..]),
        (b"IDAT", &zlib[..]),
        (b"IEND", &[][..]),
    ] {
        png.extend_from_slice(&(data.len() as u32).to_be_bytes());
        let start = png.len();
        png.extend_from_slice(kind);
        png.extend_from_slice(data);
        let crc = crc32(&png[start..]);
        png.extend_from_slice(&crc.to_be_bytes());
    }
    png
}

/// CRC-32 (IEEE 802.3), as used by PNG chunks.
pub fn crc32(data: &[u8]) -> u32 {
    let mut crc = 0xFFFF_FFFFu32;
    for &byte in data {
        crc ^= u32::from(byte);
        for _ in 0..8 {
            crc = if crc & 1 != 0 {
                (crc >> 1) ^ 0xEDB8_8320
            } else {
                crc >> 1
            };
        }
    }
    !crc
}

fn adler32(data: &[u8]) -> u32 {
    let (mut a, mut b) = (1u32, 0u32);
    for &byte in data {
        a = (a + u32::from(byte)) % 65_521;
        b = (b + a) % 65_521;
    }
    (b << 16) | a
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
