# Roadmap

Phases are implemented sequentially. Within a phase, features are also sequential unless marked parallel-safe.

Each line item maps to exactly one GitHub issue. When pm-agent creates the issue it updates the checkbox and appends the issue number. On subsequent visits pm-agent skips checked items.

**Checkbox legend:**
- `[ ]` — not started, no issue exists yet
- `[~]` — issue created, not yet started (see issue number)
- `[>]` — in progress
- `[x]` — merged to develop
- `[R]` — merged to main (released)

> **Architecture note (supersedes the stacks originally listed here):** the rebuild follows
> [`docs/REWRITE_PLAN.md`](REWRITE_PLAN.md) §7/§9 and [`docs/architecture.md`](architecture.md).
> The Rust core (`core/rust/so-core`) is the data + business layer **from day one** (phases B1–B6:
> core → mock-server → bindings → Android → iOS → e2e/CI), not extracted later. Both apps are thin
> MVI clients over UniFFI bindings. There are no per-platform networking/persistence layers and
> no third-party DI or image libraries on iOS.

---

## Phase 1 — iOS

> SwiftUI app: user list, detail, sorting, local follow state.
> Stack: SwiftUI · XcodeGen · hand-rolled MVI (`@MainActor` stores) · Rust core via UniFFI Swift bindings + prebuilt XCFramework · `AsyncImage` · no third-party packages (see `apps/ios/README.md`)
> Sequencing: features must land in order. Networking and persistence come from the Rust core.

- [>] User list screen + follow/unfollow <!-- #1 -->
- [ ] User detail screen + follow/unfollow <!-- depends: #1 -->
- [ ] Sort order screen <!-- depends: #2 -->
- [ ] Advanced testing: integration + e2e <!-- depends: #3 -->

---

## Phase 2 — Android

> Jetpack Compose app mirroring Phase 1 feature-for-feature.
> Stack: Jetpack Compose · single `:app` module · constructor injection · hand-rolled MVI · Rust core via UniFFI Kotlin bindings (JNA + cargo-ndk) · Coil (see `apps/android/README.md`)
> Sequencing: mirrors iOS feature order. Under the rewrite plan, Android (B4) lands before iOS (B5).

- [ ] User list screen + follow/unfollow
- [ ] User detail screen + follow/unfollow
- [ ] Sort order screen
- [ ] Advanced testing: integration + e2e

---

## Phase 3 — Rust Core

> Shared Rust crate (entities, API client, follow persistence, repository, use cases) exposed via UniFFI.
> Both apps consume generated bindings directly; there is no platform domain layer to replace.
> Stack: Rust · Mozilla UniFFI 0.32 · reqwest/rustls · `core/rust/`
> Sequencing: comes first under the rewrite plan (B1–B3).

- [ ] Core Rust crate setup (workspace, UniFFI scaffold)
- [ ] Extract User domain model and repository protocol to Rust
- [ ] iOS UniFFI binding — replace Swift domain layer
- [ ] Android UniFFI binding — replace Kotlin domain layer
- [ ] Cross-platform parity tests

---

## Phase 4 — Cross-Platform Testing

> Integration and e2e tests spanning iOS, Android, and Rust core.
> Stack: `tests/e2e/` · tooling TBD
> Sequencing: starts after Phase 3.

- [ ] E2e test infrastructure setup
- [ ] User list flow: fetch → display → follow → persist
- [ ] User detail flow: navigate → display → follow toggle
- [ ] Sort order flow: open → select → apply → list updates
- [ ] Offline / error state coverage

---

## pm-agent instructions for this file

When you create a GitHub issue for a line item:
1. Change `[ ]` to `[~]` and append `<!-- #<issue_number> -->` on that line
2. When the issue is labeled `in-progress`, change `[~]` to `[>`]`
3. When the PR is merged to develop, change `[>]` to `[x]`
4. When the release is tagged and merged to main, change `[x]` to `[R]`
5. Commit the updated ROADMAP.md with message: `docs(roadmap): update phase <N> item <title>`

Never create an issue for a line that already has a checkbox other than `[ ]`.
Never start a phase if the previous phase has unchecked `[ ]` or `[~]` or `[>]` items,
unless the item is explicitly marked `parallel-safe`.
