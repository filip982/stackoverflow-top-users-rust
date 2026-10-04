# Cross-Platform Rebuild Plan — stackoverflow-top-users-kmp & stackoverflow-top-users-rust

**Author:** Fable (orchestrator). **Implementers:** Opus subagents. **Plan reviewer:** GPT-6 Astra.
**Date:** 2026-10-04

## 1. Product spec (identical for both repos)

From `stackoverflow-top-users-rust/docs/PROJECT_SPEC.md` (authoritative, both repos implement the same product):

- **List screen:** top 20 Stack Overflow users (StackExchange API `/2.3/users?site=stackoverflow&pagesize=20`). Cell: avatar, name, reputation, follow/unfollow toggle + followed indicator. Follow is local-only, persisted across sessions. Server error/offline → empty state with error message + retry.
- **Detail screen:** avatar, name, reputation, follow toggle, location, website URL (if present).
- **Sort options screen:** radio group — reputation (default), name, date created, date updated; ascending/descending toggle; Apply/Cancel.

## 2. Shared architecture (both repos, both platforms)

- **Pattern:** MVI. Unidirectional: `Intent -> Store(reduce) -> State -> View`; one immutable `State` per screen; side effects isolated in the store/middleware, never in reducers. Hand-rolled minimal MVI (no Orbit/TCA) — small, fully testable.
- **Layering:** View (SwiftUI/Compose) → Store/ViewModel (MVI) → UseCases (business) → Repository → Services (API client, persistence).
- **Entities:** `User {id, displayName, reputation, avatarUrl, location?, websiteUrl?, creationDate, lastModifiedDate}`, `SortField {REPUTATION|NAME|CREATION|MODIFIED}`, `SortDirection {ASC|DESC}`.
- **Repository pattern:** `UserRepository` (fetch top users, observe/toggle follows) over `UserApiService` + `FollowStore` (persistence). DTOs mapped to entities at the service boundary; UI never sees DTOs.
- **What lives in the shared core:** entities, DTOs + parsing, API service, persistence, repository, use cases (`GetTopUsers`, `ToggleFollow`, `SortUsers` applied client-side). Business layer goes in the shared core in both repos. MVI stores stay platform-side (Kotlin/Swift) but are thin: reduce + delegate to shared use cases.

## 3. Repo A — stackoverflow-top-users-kmp (Kotlin Multiplatform core)

Existing UIKit app moves to `legacy/ios-uikit/` (git mv, history preserved). New layout:

```
shared/        KMP module: commonMain (entities, DTOs, Ktor UserApiService, FollowStore
               via multiplatform-settings, UserRepository, use cases)
               targets: android, jvm (fast tests), iosArm64, iosSimulatorArm64
mockserver/    Ktor JVM server module; serves /2.3/users fixtures; scenario toggles
               (success/error/slow) via header or route; embeddable in-process for
               integration tests, standalone jar for e2e
androidApp/    Jetpack Compose, MVI ViewModels over shared use cases
iosApp/        SwiftUI + XcodeGen; consumes shared XCFramework; Swift MVI store
.github/workflows/  linux (shared+android tests), macOS (iOS build/tests), emulator e2e
```

Key deps: Ktor client/server, kotlinx.serialization, kotlinx.coroutines, multiplatform-settings, Coil (Android images), Turbine (flow tests). iOS images: AsyncImage.

## 4. Repo B — stackoverflow-top-users-rust (Rust core)

Fits the existing `core/rust`, `apps/ios`, `apps/android`, `tests/e2e` skeleton.

```
core/rust/           cargo workspace:
  so-core/           entities, serde DTOs, reqwest UserApiService, FollowStore (JSON file
                     via trait, path injected by host app), UserRepository, use cases;
                     exposed via UniFFI proc-macros → Swift + Kotlin bindings
  mock-server/       axum server, same fixtures/scenarios as repo A's mockserver
apps/android/        Compose MVI app over UniFFI Kotlin bindings (JNA on desktop JVM
                     enables real Kotlin↔Rust integration tests on Linux; cargo-ndk for
                     device .so in CI)
apps/ios/            SwiftUI MVI app over UniFFI Swift bindings (XCFramework built in CI)
tests/e2e/           e2e suites wired to mock-server
```

Async: so-core exposes async fns via UniFFI's async support (maps to Kotlin suspend / Swift async).

## 5. TDD + test matrix (both repos)

Every work package: write failing tests first, commit (`test: ...`), then implement to green (`feat: ...`), refactor. Implementers must show the red run in their report.

| Level | Repo A | Repo B |
|---|---|---|
| Unit | shared commonTest (entities, mapping, use cases, repo w/ fakes); Android ViewModel tests (JUnit+Turbine); iOS store tests (XCTest) | cargo unit tests (same scope); Kotlin ViewModel tests (fake core); Swift store tests |
| Integration | repository + real Ktor client vs in-process mockserver; FollowStore round-trip | repository + reqwest vs axum mock-server (cargo integration tests); Kotlin-vs-real-Rust-core via JNA on host JVM |
| UI | Compose tests via Robolectric locally + instrumented in CI; SwiftUI XCTest in CI | same approach |
| E2E | app vs standalone mockserver: Android instrumented (CI emulator), iOS XCUITest (CI simulator) | same, vs axum mock-server |

Mock server contract (both): `GET /2.3/users?...` → fixture of 20 users; `X-Mock-Scenario: error|empty|slow` header (and `/__scenario` control endpoint for e2e) switches behavior. Fixtures checked into each repo and shared by all test levels, so every layer tests against identical data.

## 6. Local-vs-CI verification (this host is Linux, no Xcode)

- **Verified locally before merge:** all Kotlin/JVM/Android-unit/Robolectric tests, all cargo tests, binding generation, Android assembleDebug.
- **CI-only (GitHub Actions):** iOS compile + XCTest/XCUITest (macOS runners), Android instrumented e2e (emulator runner). Swift code is written TDD-style but its red/green runs happen in CI; PRs merge only when CI is green.
- **Owner's Mac (manual handoff):** the user has a macOS machine available. Each repo ships an `ios/README` with exact bootstrap + test commands (XcodeGen, `xcodebuild test`, mock-server launch) so iOS/UI suites can be run and iterated there without waiting on CI.

## 7. Execution phases (Opus implementers, Fable reviews + runs gates)

Repo A: **A1** shared core (TDD) → **A2** mockserver → **A3** androidApp (MVI+Compose+tests) → **A4** iosApp (SwiftUI+MVI) → **A5** e2e + CI workflows.
Repo B: **B1** so-core (TDD) → **B2** mock-server → **B3** UniFFI bindings + host-JVM integration → **B4** apps/android → **B5** apps/ios → **B6** e2e + CI.
A and B proceed in parallel; within a repo phases are sequential. Work on branches `feature/kmp-rewrite` / `feature/rust-core`; PR per repo at the end.

## 8. Risks

- UniFFI async + lifecycle edge cases → pin uniffi version, keep API surface small (3 use cases).
- KMP iOS framework consumption unverifiable locally → XcodeGen + Gradle embedAndSignAppleFrameworkForXcode, standard template, CI proves it.
- Emulator e2e flake → scenario control endpoint, idling-free sync via test tags and awaiting state, retries only at CI job level.

## 9. Revisions after Astra review (supersede conflicting text above)

Astra verdict: REVISE (`PLAN_REVIEW.md`). Changes adopted:

1. **Core public contract defined up front** (per repo, before any app code): factory/constructor taking `baseUrl` + storage path/settings; `getTopUsers(): Result<List<User>, CoreError>`; `followedIds(): Set<Id>` snapshot + observation callback/flow for changes; `toggleFollow(id)`; typed `CoreError {Network, Http(code), Decoding, Storage}`. Sorting, mapping, persistence policy live inside the core. Platform stores are thin reducers only.
2. **Phase 0 (Rust repo): reconcile docs.** Rewrite `docs/architecture.md` to supersede Hilt/Retrofit/Kingfisher/swift-dependencies-era decisions (keep product requirements); prune `apps/ios/project.yml` stale packages. First commit of B-track.
3. **Bridge spike + vertical slice first.** Each repo starts with one vertical slice: real core fetch + typed failure + cancellation + follow update, through mock server, bindings, and both apps — before broadening features. Proves Tokio runtime ownership (Rust/reqwest), UniFFI async + callback lifetimes, KMP suspend/Flow→Swift concurrency (SKIE or hand-rolled wrappers; decide in A1), MainActor delivery, observer disposal.
4. **CI at bootstrap, draft PRs immediately.** Linux + macOS workflows land in phase 0; draft PR opened per repo on first push; required checks green before merge. iOS red/green evidence comes from CI/owner's Mac.
5. **One Apple integration path per repo:** Repo A: direct Gradle `embedAndSignAppleFrameworkForXcode` (no separate XCFramework step); Repo B: prebuilt XCFramework + generated modulemap committed via CI artifact flow. macOS runners pinned `macos-14` (arm64); slices: device arm64 + sim arm64 (Intel out of scope — owner's Mac is Apple Silicon; verify in handoff README).
6. **Android native delivery:** package arm64-v8a + x86_64 (emulator ABI); explicit TLS (rustls for reqwest); Ktor engines pinned per target (OkHttp/Android, Darwin, CIO/JVM); emulator smoke test calling real core.
7. **API/sort edge cases:** nullable `location`, `website_url`, missing avatar/modified date tolerated; HTML-entity decoding of display names; explicit query `site=stackoverflow&pagesize=20&order=desc&sort=reputation`; deterministic tie-break (id asc) and null-last ordering; Apply/Cancel = draft state, tested.
8. **Follow persistence:** single app-scoped repository; serialized mutations; atomic file replace (Rust); corruption → reset-to-empty + surfaced storage error; fresh-instance reload and list/detail sync tests.
9. **MVI behavioral invariants tested:** stale-completion suppression (latest-request-wins), rapid toggle policy, loading/empty-success/error distinction, retry recovery, controlled schedulers/deferred responses.
10. **Mock server contract hardened:** per-test scenario isolation (scenario key per session/port, no global mutable scenario in parallel tests); readiness + shutdown endpoints; base URL injected via debug build config; Android emulator uses 10.0.2.2, cleartext permitted debug-only; iOS ATS exception debug-only; avatars served by mock server (no internet in e2e); explicit connection-refused case.
11. **Honest integration tests:** real ephemeral-port servers for socket-level tests; small native persistence adapter tests (SharedPreferences/NSUserDefaults); Swift↔core contract tests on macOS.
12. **Test pyramid rebalanced:** bulk of coverage in core + store unit tests; one early Compose Robolectric smoke (pinned stack); small instrumented/XCUITest acceptance suite (navigation, follow+relaunch persistence, sorting, retry). No scenario duplicated across 4 layers.
13. **Fixtures validated against real StackExchange wire contract** (documented fields, `items` wrapper, epoch seconds); edge fixtures: missing fields, unknown fields, encoded names, malformed JSON, API error object.
14. **TDD as verification discipline:** red/green evidence required in reports/logs for behavior changes; coherent green commits allowed; no contrived tests for generated/config code (build/smoke checks instead).
15. **No framework ceremony:** no generic middleware, no module-per-layer, no pass-through use-case classes beyond the 3 real ones.
16. **Handoff docs live beside each app** (`iosApp/README.md`, `apps/ios/README.md`) with exact Mac bootstrap/test commands.
