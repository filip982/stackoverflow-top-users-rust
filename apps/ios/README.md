# iOS app (SwiftUI + MVI over the Rust core)

SwiftUI, iOS 17+, Swift 6 language mode (strict concurrency), XcodeGen, no third-party packages.
The Rust core (`core/rust/so-core`) ships as a prebuilt static **XCFramework** plus generated
UniFFI Swift bindings (docs/REWRITE_PLAN.md §9.5). Bundle id `com.filip982.stackoverflow-users`.

## Layout

```
project.yml                 XcodeGen spec (generated StackOverflowUsers.xcodeproj is gitignored)
Config/Info.plist           Release Info.plist (real API, no ATS exceptions)
Config/Info-Debug.plist     Debug: MOCK_BASE_URL key + ATS exception for http://localhost
SoCoreSupport/FFIAliases.swift   compiled into the SoCore module next to the generated SoCore.swift
StackOverflowUsers/
  Core/          CoreGateway (protocol the stores depend on, app models) + RustCoreGateway (bindings → app types)
  MVI/           Store<State, Intent>: @MainActor ObservableObject; send → pure reduce → onTransition effects
  UserList/      UserListContract (State/Intent/reducer), UserListStore, UserListScreen
  UserDetail/    same shape for the detail screen
  SortOptions/   same shape for the sort sheet (draft state, Apply/Cancel)
  UI/            RootView (NavigationStack + sort sheet), shared views, accessibility identifiers
  Application/   App entry point, AppContainer (single core instance), AppConfiguration (base URL)
StackOverflowUsersTests/    unit + contract tests (XCTest)
StackOverflowUsersUITests/  XCUITest acceptance suite (needs mock-server)
scripts/bootstrap.sh        builds the core XCFramework, then runs xcodegen
scripts/linux-swift-check.sh  optional: compiles + runs the non-UI Swift on Linux (see below)
```

### Targets

| Target | Kind | Swift mode | Notes |
|---|---|---|---|
| `SoCore` | dynamic framework | 5 | generated `SoCore.swift` + `FFIAliases.swift`; links `SoCore.xcframework` (static `libso_core.a`) |
| `StackOverflowUsers` | app | 6 | embeds `SoCore.framework` |
| `StackOverflowUsersTests` | unit tests (hosted) | 6 | stores with a fake gateway; `BindingsContractTests` uses the real core |
| `StackOverflowUsersUITests` | UI tests | 5 | acceptance suite against mock-server |

The generated API lives in its own module because it declares `User`, `SortField`,
`SortDirection` and `CoreError`, the same names as the app models. It also declares a class named
`SoCore`, so `SoCore.User` doesn't resolve to the module. `FFIAliases.swift` exports
unambiguous names (`FFIUser`, `FFICoreError`, `ffiNewCore`, `ffiSortUsers`, …). Only
`RustCoreGateway.swift` and `BindingsContractTests.swift` `import SoCore`.

### Core packaging output (`core/rust/scripts/build-apple-xcframework.sh`)

```
core/rust/build/apple/            (gitignored; override with APPLE_OUT)
  SoCore.xcframework/             libso_core.a: ios-arm64 (device), ios-arm64-simulator
  include/SoCoreFFI.h             generated C header
  include/module.modulemap        clang module SoCoreFFI (SWIFT_INCLUDE_PATHS in project.yml)
  Sources/SoCore/SoCore.swift     generated Swift API
```

Only Apple Silicon is supported: device arm64 plus simulator arm64. x86_64 simulators are
excluded in `project.yml`.

## Prerequisites (Apple Silicon Mac)

- Xcode 16 (Swift 6 toolchain, iOS 18 simulator runtime with an **iPhone 16** device), e.g.
  `sudo xcode-select -s /Applications/Xcode_16.2.app`
- Rust stable via rustup, plus the iOS targets:
  ```sh
  rustup target add aarch64-apple-ios aarch64-apple-ios-sim
  ```
- `uniffi-bindgen`: **nothing to install.** The scripts run the workspace's own pinned binary
  (`cargo run -p so-core --features cli --bin uniffi-bindgen`, UniFFI 0.32.2), so it always
  matches the core.
- XcodeGen: `brew install xcodegen`

## Commands

All commands run from the repo root.

```sh
# 1. Build SoCore.xcframework + Swift bindings (release, both slices) and generate the project.
#    Re-run after any change under core/rust/so-core.
apps/ios/scripts/bootstrap.sh
#    (only regenerate the project:  apps/ios/scripts/bootstrap.sh --skip-core)
#    (only the core:                core/rust/scripts/build-apple-xcframework.sh)

# 2. Unit + contract tests
xcodebuild test \
  -project apps/ios/StackOverflowUsers.xcodeproj -scheme StackOverflowUsers \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  -only-testing:StackOverflowUsersTests CODE_SIGNING_ALLOWED=NO

# 3. UI tests: start mock-server in another terminal; the simulator shares the host network
cd core/rust && cargo run -p mock-server -- --host 127.0.0.1 --port 8080
#    wait until it answers:  curl http://127.0.0.1:8080/__ready
#    then, from the repo root:
TEST_RUNNER_MOCK_BASE_URL=http://localhost:8080 xcodebuild test \
  -project apps/ios/StackOverflowUsers.xcodeproj -scheme StackOverflowUsers \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  -only-testing:StackOverflowUsersUITests CODE_SIGNING_ALLOWED=NO

# Run the app against mock-server: open apps/ios/StackOverflowUsers.xcodeproj, and in
# Scheme > Run > Environment set MOCK_BASE_URL=http://localhost:8080. Or build it in:
xcodebuild build -project apps/ios/StackOverflowUsers.xcodeproj -scheme StackOverflowUsers \
  -destination 'platform=iOS Simulator,name=iPhone 16' MOCK_BASE_URL=http://localhost:8080
```

### Base URL

| Build | Base URL |
|---|---|
| Release | always `https://api.stackexchange.com` |
| Debug | `MOCK_BASE_URL` launch environment variable → `MOCK_BASE_URL` Info.plist key (build setting, empty by default) → real API |

Debug also honours `UITEST_RESET_FOLLOWS=1`, which deletes the follow file before the core
starts. UI tests use it to start clean, then relaunch without it to check persistence. Follows
are stored in `Application Support/follows.json`. ATS exceptions (`localhost` insecure HTTP +
`NSAllowsLocalNetworking`) exist in the Debug Info.plist only. They cover `AsyncImage` loading
mock avatars. The core's own HTTP goes through reqwest/rustls, which ATS does not govern.

## Tests

| Suite | Where | Contents |
|---|---|---|
| `UserListReducerTests` | Mac/CI (+ Linux check) | pure reducer: request ids, empty vs loaded, superseded sort, pending toggles |
| `UserListStoreTests` | Mac/CI (+ Linux check) | loading/success/empty/error/retry, latest-request-wins, rapid-toggle ignore, follow failure + dismiss, external follow sync, startup storage error, sort apply (also while loading), observer disposal on release |
| `UserDetailStoreTests` | Mac/CI (+ Linux check) | snapshot, toggle pending → result, rapid toggles, failure + dismiss, cross-screen sync, unknown user |
| `SortOptionsStoreTests` | Mac/CI (+ Linux check) | default sort, draft from applied, Apply publishes the draft, Cancel discards it, finished store ignores intents |
| `AppConfigurationTests` | Mac/CI (+ Linux check) | Debug base-URL resolution order |
| `BindingsContractTests` | Mac/CI (+ Linux check) | real generated types + linked core: record mapping round trip, deterministic sort, connection refused → `.network`, follow persistence + fresh-instance reload, corrupt file → `.storage` once, observer delivery |
| `AcceptanceTests` (XCUITest) | **CI / Mac only** | navigation list → detail → back; follow + app relaunch persistence; sort apply reorders and cancel keeps the applied sort; error → retry recovers. Mock scenarios are set via `POST /__scenario` |

Store tests use `FakeCoreGateway`, which holds every fetch and toggle until the test resumes
it. `settle()` yields the main actor until pending effects run. It plays the same role as
`advanceUntilIdle()` in the Android tests.

### Optional Linux check

`apps/ios/scripts/linux-swift-check.sh` needs a Swift 6 Linux toolchain from swift.org. It
compiles everything in the app target that doesn't import SwiftUI, together with the generated
bindings, in Swift 6 language mode. It then runs the whole unit-test target, contract tests
included, against the host `libso_core.so`. It cannot cover SwiftUI views, the App entry point,
XcodeGen/xcodebuild or XCUITests; those need CI or a Mac.

## CI (`.github/workflows/macos.yml`, `macos-14`)

- `ios-slices` builds the two static slices.
- `ios-unit-tests` selects Xcode 16, runs `bootstrap.sh`, then runs the unit + contract tests
  on an iPhone 16 simulator. It uploads `core/rust/build/apple` as the `so-core-apple`
  artifact.
- `ios-ui-tests` downloads `so-core-apple`, runs `bootstrap.sh --skip-core`, starts mock-server
  on `127.0.0.1:8080` and runs the XCUITests with `TEST_RUNNER_MOCK_BASE_URL`. It uploads the
  `.xcresult` and the mock-server log.
