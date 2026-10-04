# Architecture Guide

This document is the **authoritative, current** architecture for this monorepo. It supersedes
every earlier architecture decision (see [Superseded decisions](#superseded-decisions)).
Product requirements live in [`docs/PROJECT_SPEC.md`](PROJECT_SPEC.md); the rebuild plan and its
review live in [`docs/REWRITE_PLAN.md`](REWRITE_PLAN.md) (section 9 is binding).

---

## Summary

A **Rust shared core** owns all data and business logic (entities, DTOs + parsing, HTTP client,
follow persistence, repository, use cases). It is exposed through **UniFFI** (proc-macro style)
to two thin native apps:

- **iOS** — SwiftUI, consuming generated Swift bindings via a prebuilt XCFramework.
- **Android** — Jetpack Compose, consuming generated Kotlin bindings (JNA) + `cargo-ndk` `.so`s.

Both apps use a hand-rolled, minimal **MVI** pattern. Platform stores are thin reducers that
delegate every data/business decision to the core.

```
┌──────────── SwiftUI View ────────────┐   ┌──────────── Compose UI ─────────────┐
│ Intent → Store.reduce → State → View │   │ Intent → Store.reduce → State → UI  │
└──────────────────┬───────────────────┘   └──────────────────┬──────────────────┘
                   │  generated Swift bindings     generated Kotlin bindings │
                   └───────────────────┬──────────────────────────────────────┘
                                       ▼  UniFFI (async + callback interface)
                ┌────────────────────── so-core (Rust) ──────────────────────┐
                │ SoCore (exported facade, owns its Tokio runtime)           │
                │   └ use cases: GetTopUsers · ToggleFollow · SortUsers      │
                │       └ UserRepository (single instance, serialized writes)│
                │           ├ UserApiService (reqwest + rustls)              │
                │           └ FollowStore (JsonFileFollowStore, atomic write)│
                └────────────────────────────────────────────────────────────┘
```

---

## Monorepo layout

```
core/rust/            Cargo workspace
  so-core/            shared core crate (lib + cdylib + staticlib), UniFFI exports
  mock-server/        axum StackExchange mock (fixtures + scenarios), lib + bin
  scripts/            binding generation script
  bindings/           generated Swift/Kotlin bindings (gitignored)
apps/ios/             SwiftUI app (XcodeGen project.yml) — later phase
apps/android/         Compose app — later phase
tests/e2e/            e2e suites against mock-server — later phase
docs/                 PROJECT_SPEC (product), architecture (this), REWRITE_PLAN
```

---

## Shared core (`core/rust/so-core`)

### Entities

- `User { id, display_name, reputation, avatar_url?, location?, website_url?, creation_date,
  last_modified_date? }` — dates are epoch seconds (`i64`).
- `SortField { Reputation | Name | CreationDate | ModifiedDate }`, `SortDirection { Asc | Desc }`.

### Layers

| Layer | Module | Rule |
|---|---|---|
| Entities | `entities` | Plain data. No I/O. |
| DTOs | `dto` | Mirror the real StackExchange `/2.3/users` wire format (`items` wrapper, snake_case, epoch seconds). Unknown fields ignored, optional fields tolerated. Mapped to entities at the service boundary; nothing above the service sees a DTO. Display names are HTML-entity decoded here. |
| Service | `api` | `UserApiService`: reqwest + rustls. Explicit query `site=stackoverflow&pagesize=20&order=desc&sort=reputation`. Base URL injected (mock server in tests/e2e). |
| Persistence | `follow_store` | `FollowStore` trait; `JsonFileFollowStore` writes via temp file + rename. A corrupt file is reset to empty and a `Storage` error is surfaced once. Path injected by the host app. |
| Repository | `repository` | `UserRepository`: single app-scoped instance; follow mutations serialized through a Tokio mutex; exposes a `followed_ids` snapshot and change observers. |
| Use cases | `use_cases` | Exactly three: `GetTopUsers`, `ToggleFollow`, `SortUsers` (client-side, deterministic tie-break `id` asc, nulls last). |
| FFI facade | `ffi` | The **only** UniFFI-exported surface (see below). |

### Public (FFI) contract

Kept deliberately small:

| Export | Shape |
|---|---|
| `new_core(base_url, storage_path)` | factory → `SoCore` object |
| `SoCore.get_top_users()` | `async` → `Result<Vec<User>, CoreError>` (Kotlin `suspend`, Swift `async throws`) |
| `SoCore.toggle_follow(user_id)` | `async` → `Result<bool, CoreError>` (new followed state) |
| `SoCore.followed_ids()` | snapshot `Result<Vec<u64>, CoreError>` (`Storage` surfaced once after a corrupt-file reset) |
| `SoCore.add_follow_observer(observer)` | callback interface → `FollowObservation` handle; `dispose()` (or drop) unregisters |
| `sort_users(users, field, direction)` | pure `SortUsers` use case → sorted `Vec<User>`; `SortField`/`SortDirection` exported as enums |

`CoreError { Network, Http { code }, Decoding, Storage }` is a typed error enum (thiserror).
The core owns its Tokio runtime (UniFFI's `async_runtime = "tokio"` integration), so hosts never
need to provide an executor.

### Mock server (`core/rust/mock-server`)

axum server used by every test level:

- `GET /2.3/users` → fixture of 20 realistic users (`fixtures/users.json`, includes edge users:
  missing location/website/avatar/modified date, HTML-encoded name).
- Scenarios `success | error | empty | slow | malformed`: per-request `X-Mock-Scenario` header,
  or per-instance default via `POST /__scenario`. No process-global state — each test spawns
  its own instance on an ephemeral port (`spawn_server`).
- `GET /__ready` readiness probe; `GET /avatars/{id}.png` serves tiny generated PNGs (no internet
  needed in e2e).

---

## Apps (later phases)

### MVI (both platforms)

```
Intent → Store.send(intent) → reduce(State, Intent) → new State → View re-renders
                         └→ effect (calls core) → result Intent → reduce …
```

- One immutable `State` per screen; reducers are pure; side effects only in the store.
- Hand-rolled, no TCA/Orbit/middleware frameworks.
- Invariants tested: latest-request-wins (stale completion suppression), rapid toggle policy,
  loading vs empty-success vs error, retry recovery, Sort Apply/Cancel as draft state.

### iOS (`apps/ios`)

- Swift 6 strict concurrency, SwiftUI, iOS 17+, XcodeGen (`.xcodeproj` gitignored).
- `@Observable @MainActor` stores; core callbacks hop to `MainActor`.
- Images: `AsyncImage`. No third-party Swift packages.
- Core delivered as a prebuilt XCFramework (device arm64 + simulator arm64) + generated Swift.

### Android (`apps/android`)

- Kotlin, Jetpack Compose, `ViewModel` + `StateFlow` MVI stores; constructor injection (no DI
  framework). Images: Coil.
- Core delivered via generated Kotlin bindings + `cargo-ndk` `.so` (arm64-v8a, x86_64). JNA on
  the host JVM enables Kotlin↔real-Rust integration tests on Linux.
- Standalone Gradle build under `apps/android` (single `:app` module). Kotlin bindings are
  generated into `app/build/generated/uniffi` by `generateUniffiBindings` (runs
  `core/rust/scripts/generate-bindings.sh`); device `.so`s are built only with `-Pso.ndk=true`.
- `CoreGateway` interface (impl `RustCoreGateway`) maps generated binding types to immutable
  app types; stores (`UserListStore`, `UserDetailStore`, `SortOptionsStore`) depend only on it.
- Policies: latest-request-wins for list loads (job cancel + request-id guard in the reducer);
  rapid toggles on a user with a toggle in flight are ignored; Sort Apply/Cancel is draft state.
- Dependencies: Compose BOM, Navigation Compose, Lifecycle ViewModel, Coil 2, JNA (AAR on device,
  JAR on host tests); tests: JUnit 4, kotlinx-coroutines-test, Turbine, Robolectric.

---

## Testing

| Level | Where |
|---|---|
| Unit | `cargo test` in so-core (mapping, sorting, store, repository); Kotlin store tests; Swift store tests |
| Integration | `core/rust/so-core/tests/` — repository + real reqwest vs in-process axum mock-server on an ephemeral port; Kotlin vs real core via JNA |
| UI | Compose (Robolectric smoke + instrumented), SwiftUI XCTest on CI |
| E2E | apps vs standalone mock-server (`tests/e2e/`) |

Linux CI (`.github/workflows/linux.yml`) runs fmt, clippy, tests and binding generation; the
`android` job runs store unit tests, the Robolectric smoke, the host-JVM integration suite and
`assembleDebug`; `android-emulator` runs the instrumented acceptance suite (x86_64 emulator, real
core `.so`, mock-server on the host via `10.0.2.2`). macOS CI (`.github/workflows/macos.yml`,
`macos-14`) builds the iOS slices; iOS app jobs land later.

---

## Superseded decisions

| Previous decision | Status | Replaced by |
|---|---|---|
| Swift domain layer, later "extracted" to Rust (Milestone 3) | Superseded | Rust core is the data + business layer from day one |
| `apps/ios/Packages/Networking` (URLSession SPM package) | Removed | `so-core` `UserApiService` (reqwest + rustls) |
| `apps/ios/Packages/Persistence` (UserDefaults SPM package) | Removed | `so-core` `JsonFileFollowStore` (path injected by host) |
| Kingfisher (iOS images) | Superseded | SwiftUI `AsyncImage` |
| swift-dependencies (iOS DI) | Superseded | Plain initializer injection of the core / fakes |
| Hilt (Android DI) | Superseded | Constructor injection |
| Retrofit + OkHttp (Android networking) | Superseded | `so-core` via UniFFI |
| Room / DataStore (Android persistence) | Superseded | `so-core` via UniFFI |
| Gradle module-per-layer (`:domain`, `:data:*`) | Superseded | Single app module + core bindings |
| MVVM "send(Action)" ViewModels | Refined | MVI with explicit pure reducers |

---

## Rules

- Views/Composables contain no business logic; stores never parse, sort, or persist — the core does.
- No DTO crosses the core boundary.
- Do not widen the FFI surface without updating this document.
- No new third-party dependencies without updating this document.
- Never commit generated artifacts (`.xcodeproj`, `core/rust/bindings/`, `target/`).
