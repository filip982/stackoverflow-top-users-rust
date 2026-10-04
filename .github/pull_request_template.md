## Summary
<!-- What does this PR do? Link the GH issue it closes. -->
Closes #

## Changes
<!-- Bullet list of what changed and why -->
-

## Architecture checklist
<!-- docs/architecture.md is authoritative. -->
- [ ] MVI: one immutable `State` per screen, pure reducer, side effects only in the store (`send` → reduce → effects)
- [ ] iOS stores are `@MainActor` (`Store<State, Intent>`); Android stores are `ViewModel` + `StateFlow`
- [ ] No business logic in Views/Composables. Stores never parse, sort or persist; the Rust core does
- [ ] Stores depend only on `CoreGateway` (initializer/constructor injection, no DI framework); generated binding types stay inside `RustCoreGateway`
- [ ] No DTO crosses the core boundary; FFI surface changes are reflected in `docs/architecture.md`
- [ ] Generated artifacts not committed (`.xcodeproj`, `core/rust/bindings/`, `core/rust/build/`, `target/`)
- [ ] No force-unwraps on network data or optional API fields
- [ ] No new third-party dependencies without updating `docs/architecture.md` (iOS: none; Android: Compose, Navigation, Lifecycle, Coil, JNA)

## Tests
- [ ] Happy path covered
- [ ] Error/edge paths covered
- [ ] No real network in store unit tests (fake `CoreGateway`); integration/contract tests use mock-server or a temp dir
- [ ] iOS: Swift changes verified in CI (macOS) or on a Mac; Linux check (`apps/ios/scripts/linux-swift-check.sh`) where applicable

## Test plan
<!-- How did you verify this works? Steps to reproduce the happy path manually. -->
1.
