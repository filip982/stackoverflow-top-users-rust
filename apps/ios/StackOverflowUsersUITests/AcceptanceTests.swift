import XCTest

/// CI-ONLY acceptance suite (`ios-ui-tests` job in .github/workflows/macos.yml; runnable on a Mac,
/// see apps/ios/README.md). Not run on the Linux host.
///
/// Drives the real app + real Rust core against a standalone mock-server on the host; the
/// simulator shares the host network, so the app reaches it at http://localhost:8080 (override
/// with `TEST_RUNNER_MOCK_BASE_URL`, which xcodebuild exposes to this runner as `MOCK_BASE_URL`).
/// Each test resets the server scenario and starts the app from cleared follows.
/// Mirrors apps/android/.../acceptance/AcceptanceTest.kt.
@MainActor
final class AcceptanceTests: XCTestCase {
    private let jon: Int64 = 22656
    private let akrun: Int64 = 3_732_271
    private let timeout: TimeInterval = 20

    private var mockBaseURL: String {
        ProcessInfo.processInfo.environment["MOCK_BASE_URL"] ?? "http://localhost:8080"
    }

    func test_navigatesFromListToDetailAndBack() throws {
        try prepare()
        let app = launch(resetFollows: true)
        wait(for: "user_list", in: app)

        element("user_row_\(jon)", in: app).tap()

        wait(for: "detail_name", in: app)
        XCTAssertTrue(element("detail_location", in: app).label.contains("Reading, United Kingdom"))
        XCTAssertTrue(element("detail_website", in: app).exists)

        app.navigationBars.buttons.element(boundBy: 0).tap()
        wait(for: "user_list", in: app)
    }

    func test_followPersistsAcrossRelaunch() throws {
        try prepare()
        var app = launch(resetFollows: true)
        wait(for: "user_list", in: app)
        element("follow_button_\(jon)", in: app).tap()
        wait(for: "followed_indicator_\(jon)", in: app)

        // Real process restart: a fresh core instance re-reads the follow file.
        app.terminate()
        app = launch(resetFollows: false)

        wait(for: "user_list", in: app)
        wait(for: "followed_indicator_\(jon)", in: app)
        element("user_row_\(jon)", in: app).tap()
        wait(for: "detail_followed_indicator", in: app)
    }

    func test_sortApplyReordersAndCancelKeepsTheAppliedSort() throws {
        try prepare()
        let app = launch(resetFollows: true)
        wait(for: "user_list", in: app)
        waitUntil("first row is Jon Skeet") { self.firstRowIdentifier(in: app) == "user_row_\(self.jon)" }

        element("sort_button", in: app).tap()
        wait(for: "sort_apply", in: app)
        element("sort_field_NAME", in: app).tap()
        element("sort_direction_ASCENDING", in: app).tap()
        element("sort_apply", in: app).tap()

        wait(for: "user_list", in: app)
        waitUntil("first row is akrun") { self.firstRowIdentifier(in: app) == "user_row_\(self.akrun)" }

        element("sort_button", in: app).tap()
        wait(for: "sort_cancel", in: app)
        element("sort_field_REPUTATION", in: app).tap()
        element("sort_cancel", in: app).tap()

        wait(for: "user_list", in: app)
        XCTAssertEqual(firstRowIdentifier(in: app), "user_row_\(akrun)")
    }

    func test_errorStateRetryRecovers() throws {
        try prepare()
        try MockServerControl.setScenario("error", baseURL: mockBaseURL)
        let app = launch(resetFollows: true)
        wait(for: "error_state", in: app)

        try MockServerControl.setScenario("success", baseURL: mockBaseURL)
        element("retry_button", in: app).tap()

        wait(for: "user_list", in: app)
        wait(for: "user_row_\(jon)", in: app)
    }

    // MARK: - Helpers

    private func prepare() throws {
        continueAfterFailure = false
        try MockServerControl.setScenario("success", baseURL: mockBaseURL)
        let baseURL = mockBaseURL
        addTeardownBlock { try? MockServerControl.setScenario("success", baseURL: baseURL) }
    }

    private func launch(resetFollows: Bool) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["MOCK_BASE_URL"] = mockBaseURL
        if resetFollows {
            app.launchEnvironment["UITEST_RESET_FOLLOWS"] = "1"
        }
        app.launch()
        return app
    }

    private func element(_ identifier: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any)[identifier].firstMatch
    }

    private func wait(for identifier: String, in app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(
            element(identifier, in: app).waitForExistence(timeout: timeout),
            "'\(identifier)' did not appear within \(timeout)s",
            file: file,
            line: line
        )
    }

    private func firstRowIdentifier(in app: XCUIApplication) -> String {
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'user_row_'")).firstMatch.identifier
    }

    private func waitUntil(_ description: String, file: StaticString = #filePath, line: UInt = #line, _ condition: () -> Bool) {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        XCTFail("timed out waiting until \(description)", file: file, line: line)
    }
}
