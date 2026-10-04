# E2E tests (planned — phase B6)

Nothing runs from here yet. Planned wiring (see `docs/architecture.md` and
`docs/REWRITE_PLAN.md` §5, §9.10, §9.12):

- **Backend:** the standalone axum mock server from `core/rust/mock-server`
  (`cargo run -p mock-server -- --host 0.0.0.0 --port 8080`). No internet access is
  needed: `/2.3/users` serves `core/rust/fixtures/users.json` and avatars are served
  from `/avatars/{id}.png` on the same server.
- **Readiness / control:** suites wait on `GET /__ready`, switch behaviour per test
  with `POST /__scenario` (`success|error|empty|slow|malformed`), and stop the server
  with `POST /__shutdown`. One server instance per suite run; scenarios are reset in
  each test's setup.
- **Base URL injection:** debug build config only. Android emulator uses
  `http://10.0.2.2:8080` (cleartext allowed in debug only); iOS simulator uses
  `http://127.0.0.1:8080` (ATS exception in debug only).
- **Android:** small instrumented acceptance suite on a CI emulator
  (arm64/x86_64 core `.so` via cargo-ndk).
- **iOS:** small XCUITest acceptance suite on a `macos-14` simulator (core via
  prebuilt XCFramework).
- **Scenarios covered (once each, not duplicated across layers):** list load +
  navigation to detail, follow + relaunch persistence, sorting Apply/Cancel,
  error state + retry recovery.
