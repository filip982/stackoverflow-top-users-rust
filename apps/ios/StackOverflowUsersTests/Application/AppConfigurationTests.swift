import XCTest
@testable import StackOverflowUsers

/// Unit tests build the Debug configuration, so the mock-server override path is active.
final class AppConfigurationTests: XCTestCase {
    func test_launchEnvironmentOverridesTheBaseUrlInDebug() {
        let url = AppConfiguration.baseURL(
            environment: ["MOCK_BASE_URL": "http://localhost:8080"],
            infoDictionary: ["MOCK_BASE_URL": "http://localhost:9999"]
        )
        XCTAssertEqual(url, "http://localhost:8080")
    }

    func test_infoPlistKeyIsUsedWhenTheEnvironmentHasNoOverride() {
        let url = AppConfiguration.baseURL(environment: [:], infoDictionary: ["MOCK_BASE_URL": "http://localhost:9999"])
        XCTAssertEqual(url, "http://localhost:9999")
    }

    func test_emptyOverridesFallBackToTheRealApi() {
        let url = AppConfiguration.baseURL(environment: ["MOCK_BASE_URL": ""], infoDictionary: ["MOCK_BASE_URL": ""])
        XCTAssertEqual(url, "https://api.stackexchange.com")
    }
}
