//! Socket-level tests: each test spawns its own instance on an ephemeral port.

use std::net::SocketAddr;
use std::time::{Duration, Instant};

use mock_server::{spawn_server, ServerHandle};
use serde_json::Value;

async fn start() -> (ServerHandle, String) {
    let (handle, addr) = spawn_server(SocketAddr::from(([127, 0, 0, 1], 0)))
        .await
        .expect("bind");
    (handle, format!("http://{addr}"))
}

fn users_url(base: &str) -> String {
    format!("{base}/2.3/users?site=stackoverflow&pagesize=20&order=desc&sort=reputation")
}

async fn get_users(base: &str, scenario: Option<&str>) -> reqwest::Response {
    let mut req = reqwest::Client::new().get(users_url(base));
    if let Some(s) = scenario {
        req = req.header("X-Mock-Scenario", s);
    }
    req.send().await.unwrap()
}

#[tokio::test]
async fn ready_endpoint_reports_ok() {
    let (_h, base) = start().await;
    let res = reqwest::get(format!("{base}/__ready")).await.unwrap();
    assert_eq!(res.status(), 200);
}

#[tokio::test]
async fn success_serves_twenty_users_with_avatar_urls_on_this_server() {
    let (_h, base) = start().await;
    let res = get_users(&base, None).await;
    assert_eq!(res.status(), 200);
    let body: Value = res.json().await.unwrap();
    let items = body["items"].as_array().unwrap();
    assert_eq!(items.len(), 20);
    assert_eq!(
        items[0]["profile_image"].as_str().unwrap(),
        format!("{base}/avatars/22656.png")
    );
}

#[tokio::test]
async fn error_scenario_returns_500_with_stackexchange_error_object() {
    let (_h, base) = start().await;
    let res = get_users(&base, Some("error")).await;
    assert_eq!(res.status(), 500);
    let body: Value = res.json().await.unwrap();
    assert_eq!(body["error_id"], 500);
    assert!(body["error_name"].is_string());
}

#[tokio::test]
async fn empty_scenario_returns_no_items() {
    let (_h, base) = start().await;
    let body: Value = get_users(&base, Some("empty")).await.json().await.unwrap();
    assert_eq!(body["items"].as_array().unwrap().len(), 0);
}

#[tokio::test]
async fn malformed_scenario_returns_unparseable_200() {
    let (_h, base) = start().await;
    let res = get_users(&base, Some("malformed")).await;
    assert_eq!(res.status(), 200);
    let text = res.text().await.unwrap();
    assert!(serde_json::from_str::<Value>(&text).is_err());
}

#[tokio::test]
async fn slow_scenario_delays_response() {
    let (_h, base) = start().await;
    let started = Instant::now();
    let res = reqwest::Client::new()
        .get(users_url(&base))
        .header("X-Mock-Scenario", "slow")
        .header("X-Mock-Delay-Ms", "300")
        .send()
        .await
        .unwrap();
    assert_eq!(res.status(), 200);
    assert!(started.elapsed() >= Duration::from_millis(300));
}

#[tokio::test]
async fn unknown_scenario_header_is_rejected() {
    let (_h, base) = start().await;
    assert_eq!(get_users(&base, Some("bogus")).await.status(), 400);
}

#[tokio::test]
async fn missing_site_parameter_is_bad_request() {
    let (_h, base) = start().await;
    let res = reqwest::get(format!("{base}/2.3/users?pagesize=20")).await.unwrap();
    assert_eq!(res.status(), 400);
    let body: Value = res.json().await.unwrap();
    assert_eq!(body["error_name"], "bad_parameter");
}

#[tokio::test]
async fn scenario_endpoint_sets_instance_default_and_header_overrides_it() {
    let (_h, base) = start().await;
    let (_other, other_base) = start().await;
    let client = reqwest::Client::new();
    let res = client
        .post(format!("{base}/__scenario"))
        .json(&serde_json::json!({"scenario": "empty"}))
        .send()
        .await
        .unwrap();
    assert!(res.status().is_success());
    let current: Value = client
        .get(format!("{base}/__scenario"))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(current["scenario"], "empty");

    let body: Value = get_users(&base, None).await.json().await.unwrap();
    assert_eq!(body["items"].as_array().unwrap().len(), 0);
    let body: Value = get_users(&base, Some("success")).await.json().await.unwrap();
    assert_eq!(body["items"].as_array().unwrap().len(), 20);
    // Other instances are unaffected.
    let body: Value = get_users(&other_base, None).await.json().await.unwrap();
    assert_eq!(body["items"].as_array().unwrap().len(), 20);
}

#[tokio::test]
async fn invalid_scenario_body_is_rejected() {
    let (_h, base) = start().await;
    let res = reqwest::Client::new()
        .post(format!("{base}/__scenario"))
        .json(&serde_json::json!({"scenario": "nope"}))
        .send()
        .await
        .unwrap();
    assert_eq!(res.status(), 400);
}

#[tokio::test]
async fn serves_png_avatars() {
    let (_h, base) = start().await;
    let res = reqwest::get(format!("{base}/avatars/22656.png")).await.unwrap();
    assert_eq!(res.status(), 200);
    assert_eq!(res.headers()["content-type"], "image/png");
    let bytes = res.bytes().await.unwrap();
    assert_eq!(&bytes[..8], b"\x89PNG\r\n\x1a\n");
    let missing = reqwest::get(format!("{base}/avatars/abc.png")).await.unwrap();
    assert_eq!(missing.status(), 404);
}

#[tokio::test]
async fn shutdown_stops_accepting_connections() {
    let (handle, base) = start().await;
    handle.shutdown().await;
    assert!(reqwest::get(format!("{base}/__ready")).await.is_err());
}

#[tokio::test]
async fn shutdown_endpoint_stops_server() {
    let (_h, base) = start().await;
    let res = reqwest::Client::new()
        .post(format!("{base}/__shutdown"))
        .send()
        .await
        .unwrap();
    assert!(res.status().is_success());
    let mut stopped = false;
    for _ in 0..50 {
        if reqwest::get(format!("{base}/__ready")).await.is_err() {
            stopped = true;
            break;
        }
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    assert!(stopped);
}
