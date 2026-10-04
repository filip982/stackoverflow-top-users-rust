import SwiftUI

@main
struct StackOverflowUsersApp: App {
    /// `nil` while hosted unit tests run: they use fakes, so the real core (and its network
    /// fetch) is never started inside the test host.
    private let container: AppContainer?

    init() {
        container = AppConfiguration.isRunningUnitTests ? nil : AppContainer.live()
    }

    var body: some Scene {
        WindowGroup {
            if let container {
                RootView(gateway: container.gateway)
            } else {
                Text("Running unit tests")
            }
        }
    }
}
