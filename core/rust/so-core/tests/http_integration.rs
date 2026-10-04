//! Repository + real reqwest against an in-process axum mock-server bound to an
//! ephemeral port. Each test owns its server instance (no shared scenario).

use std::net::SocketAddr;
use std::sync::{Arc, Mutex};

use mock_server::{spawn_server_with, Scenario, ServerHandle};
use so_core::{
    new_core, CoreError, FollowObserver, JsonFileFollowStore, UserApiService, UserRepository,
};

async fn server(scenario: Scenario) -> (ServerHandle, String) {
    let (handle, addr) = spawn_server_with(SocketAddr::from(([127, 0, 0, 1], 0)), scenario)
        .await
        .expect("bind mock server");
    (handle, format!("http://{addr}"))
}

fn repository(base_url: &str, dir: &tempfile::TempDir) -> UserRepository {
    UserRepository::new(
        UserApiService::new(base_url),
        Arc::new(JsonFileFollowStore::new(dir.path().join("follows.json"))),
    )
}

#[tokio::test]
async fn success_returns_twenty_mapped_users() {
    let (_server, base) = server(Scenario::Success).await;
    let dir = tempfile::tempdir().unwrap();
    let users = repository(&base, &dir).top_users().await.unwrap();

    assert_eq!(users.len(), 20);
    assert_eq!(users[0].id, 22656);
    assert_eq!(users[0].display_name, "Jon Skeet");
    assert_eq!(
        users[0].avatar_url.as_deref(),
        Some(format!("{base}/avatars/22656.png").as_str())
    );
    let wiktor = users.iter().find(|u| u.id == 3832970).unwrap();
    assert_eq!(wiktor.display_name, "Wiktor Stribiżew");
}

#[tokio::test]
async fn avatar_urls_are_served_by_the_mock() {
    let (_server, base) = server(Scenario::Success).await;
    let dir = tempfile::tempdir().unwrap();
    let users = repository(&base, &dir).top_users().await.unwrap();
    let url = users[0].avatar_url.clone().unwrap();
    let res = reqwest::get(url).await.unwrap();
    assert_eq!(res.status(), 200);
    assert_eq!(res.headers()["content-type"], "image/png");
}

#[tokio::test]
async fn http_500_maps_to_http_error() {
    let (_server, base) = server(Scenario::Error).await;
    let dir = tempfile::tempdir().unwrap();
    let err = repository(&base, &dir).top_users().await.unwrap_err();
    assert_eq!(err, CoreError::Http { code: 500 });
}

#[tokio::test]
async fn malformed_body_maps_to_decoding_error() {
    let (_server, base) = server(Scenario::Malformed).await;
    let dir = tempfile::tempdir().unwrap();
    let err = repository(&base, &dir).top_users().await.unwrap_err();
    assert!(matches!(err, CoreError::Decoding { .. }), "{err:?}");
}

#[tokio::test]
async fn empty_scenario_is_success_with_no_users() {
    let (_server, base) = server(Scenario::Empty).await;
    let dir = tempfile::tempdir().unwrap();
    assert_eq!(repository(&base, &dir).top_users().await.unwrap(), vec![]);
}

#[tokio::test]
async fn connection_refused_maps_to_network_error() {
    // Reserve an ephemeral port, then free it so nothing is listening.
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    drop(listener);

    let dir = tempfile::tempdir().unwrap();
    let err = repository(&base, &dir).top_users().await.unwrap_err();
    assert!(matches!(err, CoreError::Network { .. }), "{err:?}");
}

#[tokio::test]
async fn recovers_after_scenario_switch_like_a_retry() {
    let (_server, base) = server(Scenario::Error).await;
    let dir = tempfile::tempdir().unwrap();
    let repo = repository(&base, &dir);
    assert!(repo.top_users().await.is_err());

    reqwest::Client::new()
        .post(format!("{base}/__scenario"))
        .body("success")
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap();
    assert_eq!(repo.top_users().await.unwrap().len(), 20);
}

#[derive(Default)]
struct Recorder(Mutex<Vec<Vec<u64>>>);

struct Forward(Arc<Recorder>);

impl FollowObserver for Forward {
    fn on_follows_changed(&self, followed_ids: Vec<u64>) {
        self.0 .0.lock().unwrap().push(followed_ids);
    }
}

#[tokio::test]
async fn facade_end_to_end_fetch_follow_observe_and_reload() {
    let (_server, base) = server(Scenario::Success).await;
    let dir = tempfile::tempdir().unwrap();
    let storage = dir.path().join("state/follows.json");
    let storage = storage.to_string_lossy().into_owned();

    let core = new_core(base.clone(), storage.clone());
    let users = core.get_top_users().await.unwrap();
    assert_eq!(users.len(), 20);
    assert!(users.windows(2).all(|w| w[0].reputation >= w[1].reputation));

    let recorder = Arc::new(Recorder::default());
    let observation = core.add_follow_observer(Box::new(Forward(Arc::clone(&recorder))));
    assert!(core.toggle_follow(users[1].id).await.unwrap());
    assert!(core.toggle_follow(users[0].id).await.unwrap());
    observation.dispose();
    observation.dispose(); // idempotent
    assert!(!core.toggle_follow(users[1].id).await.unwrap());

    assert_eq!(
        *recorder.0.lock().unwrap(),
        vec![vec![users[1].id], {
            let mut both = vec![users[0].id, users[1].id];
            both.sort();
            both
        }]
    );
    assert_eq!(core.followed_ids().unwrap(), vec![users[0].id]);

    // A fresh core over the same storage sees the persisted follows.
    drop(core);
    let reloaded = new_core(base, storage);
    assert_eq!(reloaded.followed_ids().unwrap(), vec![users[0].id]);
}

#[tokio::test]
async fn dropping_observation_handle_unregisters() {
    let dir = tempfile::tempdir().unwrap();
    let storage = dir.path().join("follows.json").to_string_lossy().into_owned();
    let core = new_core("http://127.0.0.1:9".into(), storage);
    let recorder = Arc::new(Recorder::default());
    drop(core.add_follow_observer(Box::new(Forward(Arc::clone(&recorder)))));
    core.toggle_follow(1).await.unwrap();
    assert!(recorder.0.lock().unwrap().is_empty());
}
