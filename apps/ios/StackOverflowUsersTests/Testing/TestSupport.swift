import XCTest
@testable import StackOverflowUsers

enum TestUsers {
    static let jon = user(22656, "Jon Skeet", 1_520_345, created: 1_222_430_705, modified: 1_727_187_919)
    static let gordon = user(1_144_035, "Gordon Linoff", 1_350_123, created: 1_325_878_478, modified: 1_726_441_261)
    static let vonc = user(6309, "VonC", 1_300_000, created: 1_221_000_000, modified: nil)
    static let all = [jon, gordon, vonc]

    static func user(_ id: Int64, _ name: String, _ reputation: Int64, created: Int64 = 0, modified: Int64? = nil) -> User {
        User(
            id: id,
            displayName: name,
            reputation: reputation,
            avatarUrl: nil,
            location: "Somewhere",
            websiteUrl: nil,
            creationDate: created,
            lastModifiedDate: modified
        )
    }
}

/// Lets every main-actor job that is ready run (store effects, fake continuations, stream
/// deliveries) — the XCTest counterpart of `advanceUntilIdle()`.
@MainActor
func settle() async {
    for _ in 0..<200 {
        await Task.yield()
    }
}
