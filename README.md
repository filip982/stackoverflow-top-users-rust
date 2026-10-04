# StackOverflow Users

Top-20 Stack Overflow users with local follow/unfollow, a detail screen and client-side
sorting — built as a **Rust shared core** (data + business logic) exposed through
**UniFFI** to native **SwiftUI** (iOS) and **Jetpack Compose** (Android) apps using MVI.

- Product requirements: [`docs/PROJECT_SPEC.md`](docs/PROJECT_SPEC.md)
- Architecture (authoritative): [`docs/architecture.md`](docs/architecture.md)
- Rebuild plan: [`docs/REWRITE_PLAN.md`](docs/REWRITE_PLAN.md)

## Layout

```
core/rust/                 Cargo workspace
  so-core/                 shared core: entities, DTOs, reqwest API service, JSON follow
                           store, repository, use cases, UniFFI exports (src/ffi.rs)
  so-core/tests/           integration tests against an in-process mock server
  mock-server/             axum mock of GET /2.3/users (scenarios, avatars, control endpoints)
  fixtures/                wire-format fixtures shared by all test levels
  scripts/generate-bindings.sh   Kotlin + Swift binding generation check
  bindings/                generated bindings (gitignored)
apps/ios/                  SwiftUI app (XcodeGen skeleton; wired to the core in a later phase)
apps/android/              Compose app (later phase)
tests/e2e/                 e2e plan (later phase)
.github/workflows/         linux.yml (Rust gate), macos.yml (iOS slices), pr-review, tag-to-main
```

## Local commands

Requires a Rust stable toolchain (CI uses latest stable; developed on 1.99).

```sh
cd core/rust

cargo fmt --all --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace                 # unit + integration (spawns mock servers on ephemeral ports)

./scripts/generate-bindings.sh         # -> core/rust/bindings/{kotlin,swift}

# Standalone mock server (e2e / manual testing)
cargo run -p mock-server -- --port 8080 [--host 0.0.0.0] [--scenario success]
curl 'http://127.0.0.1:8080/2.3/users?site=stackoverflow&pagesize=20&order=desc&sort=reputation'
curl -H 'X-Mock-Scenario: error' 'http://127.0.0.1:8080/2.3/users?site=stackoverflow'
curl -X POST -d '{"scenario":"slow"}' http://127.0.0.1:8080/__scenario
```

## Mock server contract

| Endpoint | Behaviour |
|---|---|
| `GET /2.3/users?site=stackoverflow…` | 20-user fixture; `400 bad_parameter` without `site` |
| `X-Mock-Scenario: success\|error\|empty\|slow\|malformed` | per-request scenario (`error` = HTTP 500 + StackExchange error object; `slow` = 3 s, override with `X-Mock-Delay-Ms`) |
| `GET`/`POST /__scenario` | read / set this instance's default scenario (JSON `{"scenario":"…"}` or plain text) |
| `GET /__ready` | readiness probe |
| `POST /__shutdown` | graceful stop |
| `GET /avatars/{id}.png` | tiny generated PNG avatar |

## Core FFI surface

`new_core(base_url, storage_path) -> SoCore`; `SoCore.get_top_users()` (async),
`SoCore.toggle_follow(id)` (async), `SoCore.followed_ids()`,
`SoCore.add_follow_observer(observer) -> FollowObservation` (`dispose()`),
`sort_users(users, field, direction)` (pure, deterministic), typed
`CoreError { Network, Http{code}, Decoding, Storage }`. See `docs/architecture.md`.
