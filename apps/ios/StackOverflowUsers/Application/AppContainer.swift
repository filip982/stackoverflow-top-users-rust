import Foundation

/// Base URL + storage location resolution. The core appends `/2.3/users` to the base URL.
enum AppConfiguration {
    static let productionBaseURL = "https://api.stackexchange.com"

    /// Release: always the real API. Debug: `MOCK_BASE_URL` from the launch environment (UI tests),
    /// then the `MOCK_BASE_URL` Info.plist key (build setting), then the real API.
    static func baseURL(
        environment: [String: String] = ProcessInfo.processInfo.environment,
        infoDictionary: [String: Any] = Bundle.main.infoDictionary ?? [:]
    ) -> String {
        #if DEBUG
        if let url = environment["MOCK_BASE_URL"], !url.isEmpty {
            return url
        }
        if let url = infoDictionary["MOCK_BASE_URL"] as? String, !url.isEmpty {
            return url
        }
        #endif
        return productionBaseURL
    }

    /// Follow-state JSON file inside the app sandbox (Application Support).
    static func followsFileURL() -> URL {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return directory.appendingPathComponent("follows.json")
    }

    /// True while hosted XCTest unit tests run inside the app process.
    static var isRunningUnitTests: Bool {
        ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
    }
}

/// App-scoped composition root (initializer injection, no DI framework). Holds the single core.
@MainActor
final class AppContainer {
    let gateway: any CoreGateway

    init(gateway: any CoreGateway) {
        self.gateway = gateway
    }

    static func live(environment: [String: String] = ProcessInfo.processInfo.environment) -> AppContainer {
        let followsFile = AppConfiguration.followsFileURL()
        #if DEBUG
        // UI tests start from cleared follows; a relaunch without the flag keeps them.
        if environment["UITEST_RESET_FOLLOWS"] == "1" {
            try? FileManager.default.removeItem(at: followsFile)
        }
        #endif
        return AppContainer(gateway: RustCoreGateway(
            baseUrl: AppConfiguration.baseURL(environment: environment),
            storagePath: followsFile.path
        ))
    }
}
