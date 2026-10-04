# Android app (Jetpack Compose + MVI over the Rust core)

Package `dev.filip.stackoverflowusers`. A standalone Gradle build lives in this directory
(`settings.gradle.kts`, single `:app` module). It's kept out of the repo root so the Rust
workspace and the iOS app stay independent. The Rust core is reached through relative paths
(`../../core/rust`).

## Layout

```
app/src/main/kotlin/dev/filip/stackoverflowusers/
  core/        CoreGateway (interface the stores depend on) + RustCoreGateway (UniFFI bindings → app types)
  mvi/         Store: tiny MVI base (send → pure reduce → onTransition effects)
  userlist/    UserListContract (State/Intent/reducer), UserListStore, UserListScreen
  userdetail/  same shape for the detail screen
  sortoptions/ same shape for the sort screen (draft state, Apply/Cancel)
  ui/          AppNavHost (navigation + store wiring), shared composables, test tags
  StackOverflowUsersApp.kt  AppContainer: single core instance; resetCore() debug/test hook
app/src/test/…                 store/reducer unit tests, Robolectric smoke (ui/), host-JVM integration (integration/)
app/src/androidTest/…          instrumented acceptance suite (CI emulator only)
```

The generated Kotlin bindings (`com.filip982.socore`) are **not committed**. The
`generateUniffiBindings` task (a dependency of `preBuild`) runs
`core/rust/scripts/generate-bindings.sh` and writes them to `app/build/generated/uniffi/kotlin`.

## Prerequisites

- JDK 17+ (developed with Temurin 21), Android SDK with platform 35
  (`local.properties`: `sdk.dir=$HOME/Android/Sdk`, or set `ANDROID_HOME`)
- Rust stable + `cargo` on `PATH`. Every build generates bindings, and the integration suite builds the host cdylib
- Device/emulator builds only: `cargo-ndk`, an NDK (`ANDROID_NDK_HOME`), and
  `rustup target add aarch64-linux-android x86_64-linux-android`

## Test suites

| Suite | Command | Runs where | What |
|---|---|---|---|
| Store unit tests | `./gradlew test` | local + CI | `UserListStoreTest`, `UserListReducerTest`, `UserDetailStoreTest`, `SortOptionsStoreTest`: fake `CoreGateway` with deferred responses, `StandardTestDispatcher` as Main, Turbine |
| Compose smoke (Robolectric) | `./gradlew test` | local + CI | `UserListSmokeTest`: renders `core/rust/fixtures/users.json` users, follow tap updates indicator |
| Host-JVM integration | `./gradlew hostIntegrationTest` | local + CI | `RustCoreGatewayIntegrationTest`: generated bindings over JNA, loading `core/rust/target/debug/libso_core.so`, against a spawned `mock-server` process per test on an ephemeral port. Covers fetch success, HTTP error, connection refused, malformed/empty, retry, follow persistence + fresh-instance reload, corrupt store, observer delivery, sort |
| Instrumented acceptance | `./gradlew -Pso.ndk=true connectedDebugAndroidTest` | **CI emulator only** | `AcceptanceTest`: navigation, follow + relaunch persistence, sort apply/cancel, error → retry. Uses the real app and core against mock-server at `10.0.2.2:8080` |

`./gradlew test` excludes the `integration` package. Host tests run only for the debug
variant, because release differs only in `BuildConfig`. `hostIntegrationTest` depends on
`buildHostRust`, which runs `cargo build -p so-core --lib -p mock-server --bin mock-server`.
That task also passes `jna.library.path` and the mock-server path as system properties.

Full local gate:

```sh
cd apps/android
./gradlew test hostIntegrationTest assembleDebug
```

### Instrumented suite (CI, or locally with an emulator)

```sh
# terminal 1: mock server on the host (emulator sees it as 10.0.2.2)
cd core/rust && cargo run -p mock-server -- --host 127.0.0.1 --port 8080
# terminal 2: needs cargo-ndk + NDK; -Pso.ndk=true packages arm64-v8a + x86_64 .so files
cd apps/android
./gradlew -Pso.ndk=true connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.mockBaseUrl=http://10.0.2.2:8080
```

Each test resets the server scenario through `POST /__scenario`. It also calls
`AppContainer.resetCore(baseUrl, clearFollows = true)`. To test relaunch persistence, the
suite closes the activity and builds a **fresh core instance** over the same follow file.

## Base URL & cleartext

- Release: `BuildConfig.API_BASE_URL = https://api.stackexchange.com`. The core appends
  `/2.3/users?site=stackoverflow&pagesize=20&order=desc&sort=reputation`.
- Debug: the default is the same. Override at build time with
  `-Pso.baseUrl=http://10.0.2.2:8080`, or at runtime from tests with `AppContainer.resetCore(baseUrl)`.
  The hook only works in debug builds.
- `src/debug` adds a network security config that allows cleartext only for `10.0.2.2`,
  `localhost` and `127.0.0.1`. That config governs JVM/OkHttp traffic, like Coil avatars from the
  mock server. The Rust core's reqwest client (rustls + webpki roots) is not subject to it.

## CI

`.github/workflows/linux.yml`:

- `android`: Rust toolchain, then `./gradlew test hostIntegrationTest`, then `assembleDebug`.
- `android-emulator` (needs `android`): KVM, Android Rust targets and `cargo-ndk`. It starts
  mock-server on `127.0.0.1:8080` and builds with `-Pso.ndk=true`. It then runs
  `connectedDebugAndroidTest` on an API 34 x86_64 emulator via
  `reactivecircus/android-emulator-runner`. Reports and the mock-server log are uploaded as artifacts.
