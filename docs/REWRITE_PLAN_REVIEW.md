1. **[IMPROVE] The architecture is sound, but the shared-core boundary needs a concrete public contract.** Native MVI stores over shared entities, repositories and services are appropriate for both implementations; sharing UI reducers is unnecessary. However, “three use cases” does not specify how clients obtain initial follow state, observe changes, configure endpoints/storage, or receive typed failures. Those omissions invite business logic to leak into four platform stores.
   **Fix:** Specify a small host-facing core interface, including construction, follow snapshots/change delivery and error types; keep sorting, mapping and persistence policy inside each shared core.

2. **[BLOCKER] The plan conflicts with repository documentation that explicitly declares itself authoritative.** `stackoverflow-users-rust/docs/PROJECT_SPEC.md` defers technical decisions to `docs/architecture.md`, which currently mandates different layering, Hilt, Retrofit, Swift data packages and other choices. The existing iOS `project.yml` still links Networking/Persistence, Kingfisher and swift-dependencies. Implementers following those documents could preserve a second native data/business layer instead of replacing it.
   **Fix:** Make reconciling the architecture document and project dependencies a first-phase deliverable, explicitly superseding old technical decisions while retaining product requirements.

3. **[BLOCKER] Cross-language async and observation are not designed or proved.** UniFFI async support alone does not establish the Tokio runtime needed by reqwest, cancellation behavior, callback lifetime or the follow-observation bridge. KMP similarly needs an explicit Swift-facing approach for suspend calls and Flow; Kotlin Flow does not automatically become a lifecycle-safe Swift AsyncSequence. Swift 6 strict concurrency is already enabled in the Rust repo.
   **Fix:** Begin each repo with a minimal real-core bridge spike covering fetch, typed failure, cancellation and follow updates on Android and iOS, including runtime ownership, MainActor delivery and observer disposal.

4. **[BLOCKER] CI arrives after the phases that depend on it.** A4/B5 cannot demonstrate iOS red/green runs while CI is postponed until A5/B6, and a final PR per repo delays discovery of linker, binding and Swift concurrency failures. Linux plus macOS CI and the owner's Mac is feasible, but Linux cannot substantiate Apple compilation or native behavior.
   **Fix:** Establish Linux and macOS build/test workflows plus draft PRs at bootstrap; run the initial bridge slice on both platforms before expanding features, and keep required checks green before merge.

5. **[IMPROVE] Apple packaging mixes two distinct KMP integration paths and omits a target decision.** The layout promises an XCFramework, while the risk mitigation names `embedAndSignAppleFrameworkForXcode`, normally used for direct Gradle/Xcode framework integration. Only ARM64 simulator targets are listed, so Intel Mac runners or an Intel owner machine would not be covered. Rust also needs generated headers/module maps and correctly matched Apple libraries.
   **Fix:** Choose and document one integration path per repo, pin the macOS runner architecture, and provide device plus required simulator slices with reproducible binding/framework generation.

6. **[IMPROVE] Native Android delivery is under-specified.** Generating UniFFI Kotlin bindings and running host-JVM/JNA tests proves neither Android loading nor ABI packaging. `assembleDebug` can succeed while the installed app fails to load its native library; reqwest also needs an intentional mobile TLS configuration. Ktor client engines must be selected for JVM, Android and Darwin.
   **Fix:** Pin compatible toolchains/dependencies, package arm64-v8a and the chosen emulator ABI, select mobile TLS/client engines explicitly, and require an emulator smoke test that calls the real core over HTTPS or a controlled equivalent.

7. **[IMPROVE] API and sorting edge cases could make realistic responses fail.** The entity makes avatar and modification date mandatory despite potentially missing API fields; display names can contain HTML entities. “Top 20” should be fetched explicitly with reputation descending, then sorted locally as the plan intends. Missing dates, equal values and name collation otherwise produce platform-dependent results.
   **Fix:** Define nullable/default mapping and display-name decoding, explicit HTTPS query parameters, deterministic tie-breaking/null ordering, and Apply/Cancel draft-state semantics with focused tests.

8. **[IMPROVE] Follow persistence needs concurrency and failure semantics.** JSON is adequate for a tiny ID set, but concurrent toggles can lose updates and interrupted writes can corrupt it. A round-trip test alone does not prove durability. Separate list/detail stores also need a common authoritative follow snapshot, including startup hydration and write-failure behavior.
   **Fix:** Use one app-scoped repository, serialize mutations, atomically replace the Rust file, define corruption/write-error handling, and test fresh-instance reload plus list/detail synchronization.

9. **[IMPROVE] MVI tests need behavioral invariants, not only state snapshots.** Slow requests completing after retry, navigation or cancellation can overwrite newer state; rapid toggles and loading failures need explicit policies. Empty success is different from an error, even if both display an empty list.
   **Fix:** Define latest-request/cancellation behavior and test loading, empty success, retry recovery, stale completion suppression, follow failures and sorting cancellation with controlled schedulers or deferred responses.

10. **[IMPROVE] Mock servers are the right choices, but their connectivity and isolation contract is incomplete.** Android emulator localhost is not host localhost; Android cleartext and iOS ATS can block local HTTP. A global `/__scenario` introduces parallel-test races, and a slow response does not simulate offline connectivity. External avatar URLs would also make “mocked” E2E depend on the internet.
    **Fix:** Inject debug-only base URLs, define emulator/simulator routing and narrowly scoped transport exceptions, isolate/reset scenarios per test, expose readiness/shutdown controls, serve local avatars, and add a real connection-failure case.

11. **[IMPROVE] Keep integration tests honest about transport and platform coverage.** A Ktor MockEngine or testApplication client is useful but does not prove a real socket/client-engine boundary. JVM settings tests do not verify Android SharedPreferences or iOS NSUserDefaults adapters. Likewise host-JVM UniFFI tests do not replace Swift binding tests.
    **Fix:** Run a real ephemeral-port Ktor server and axum listener for socket integration tests, with bounded readiness/cleanup, and add small native persistence and Swift-to-core contract tests.

12. **[IMPROVE] The test matrix is plausible but ambiguous and over-duplicated.** XCTest is a framework, not by itself a SwiftUI interaction harness; SwiftUI behavioral UI testing here should be XCUITest. Compose Robolectric support requires a compatible pinned stack and configuration. Repeating every scenario through Robolectric, instrumentation, UI and E2E would add cost without proportional confidence.
    **Fix:** Keep most state/edge coverage in core and store unit tests, prove one Compose Robolectric smoke test early, and use a small instrumented/XCUITest acceptance suite for navigation, follow/relaunch persistence, sorting and retry.

13. **[IMPROVE] Shared happy-path fixtures are insufficient contract evidence.** Reusing one fixture everywhere can make implementation and mock server agree on the same incorrect schema. The historical spec's illustrative schema is not reliable enough to generate DTOs mechanically.
    **Fix:** Check fixtures against the documented StackExchange wire contract and add missing-field, unknown-field, encoded-name, malformed-response and API-error cases; verify request parameters without making CI depend on the live API.

14. **[IMPROVE] TDD should remain a verification discipline, not a commit-format constraint.** Mandatory separate red commits for every work package can create intentionally failing branch checks and artificial tests for generated bindings, configuration or scaffolding. Real feature behavior should still have recorded failing-then-passing evidence, including macOS evidence for iOS.
    **Fix:** Require meaningful red/green logs for behavior changes, allow coherent green commits, and gate generated/configuration work with build or smoke checks instead of contrived unit tests.

15. **[IMPROVE] Cut framework ceremony and late integration risk.** Minimal hand-rolled MVI, multiplatform-settings and a small Rust JSON store are appropriately sized. Generic middleware, one module per trivial layer, duplicated native repositories, or one class per pass-through use case would not be. Fully completing each core before any app slice increases integration risk.
    **Fix:** Keep the stated layers as lightweight code boundaries and implement one vertical fetch/list/follow slice through mock server, bindings, both apps and CI before broadening feature coverage.

16. **[NIT] Repository status and handoff paths need correction.** Repo A's UIKit app is already under `legacy/ios-uikit/`, so another relocation is unnecessary. A generic `ios/README` does not match `iosApp/` in repo A or `apps/ios/` in repo B.
    **Fix:** Treat the legacy move as already done and put exact bootstrap/test commands, prerequisites and supported Mac architectures beside each actual iOS app.

Verdict: REVISE
