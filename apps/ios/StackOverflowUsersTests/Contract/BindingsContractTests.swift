import XCTest
import SoCore
@testable import StackOverflowUsers

// Both `SoCore` and `StackOverflowUsers` declare `User`/`CoreError`/`SortField`/`SortDirection`; qualify the app's ones here.
private typealias AppUser = StackOverflowUsers.User
private typealias AppCoreError = StackOverflowUsers.CoreError
private typealias AppSortField = StackOverflowUsers.SortField
private typealias AppSortDirection = StackOverflowUsers.SortDirection

/// Swift <-> real Rust core contract (docs/REWRITE_PLAN.md §9.11). Runs the generated bindings and
/// the linked static core in-process; no network: the only HTTP call targets a closed local port.
final class BindingsContractTests: XCTestCase {
    private func temporaryFollowsFile() throws -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("so-core-contract-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        return directory.appendingPathComponent("follows.json")
    }

    @MainActor
    private func makeGateway(_ file: URL) -> RustCoreGateway {
        // Port 1 on loopback: connection refused, immediately.
        RustCoreGateway(baseUrl: "http://127.0.0.1:1", storagePath: file.path)
    }

    private func user(_ id: Int64, _ name: String, _ reputation: Int64, created: Int64, modified: Int64?) -> AppUser {
        AppUser(
            id: id, displayName: name, reputation: reputation, avatarUrl: nil, location: nil,
            websiteUrl: nil, creationDate: created, lastModifiedDate: modified
        )
    }

    func test_userMappingRoundTripsThroughTheGeneratedRecord() {
        let user = AppUser(
            id: 22656, displayName: "Jon Skeet", reputation: 1_520_345,
            avatarUrl: "http://localhost:8080/avatars/22656.png", location: "Reading, United Kingdom",
            websiteUrl: "http://csharpindepth.com", creationDate: 1_222_430_705, lastModifiedDate: nil
        )

        let ffi: FFIUser = user.ffi

        XCTAssertEqual(ffi.id, 22656 as UInt64)
        XCTAssertEqual(ffi.reputation, 1_520_345 as UInt64)
        XCTAssertEqual(ffi.displayName, "Jon Skeet")
        XCTAssertNil(ffi.lastModifiedDate)
        XCTAssertEqual(AppUser(ffi: ffi), user)
    }

    @MainActor
    func test_sortUsersRunsTheCoresDeterministicSort() async throws {
        let gateway = makeGateway(try temporaryFollowsFile())
        let users = [
            user(5, "bob", 100, created: 50, modified: 500),
            user(2, "Alice", 300, created: 10, modified: nil),
            user(9, "carol", 100, created: 30, modified: 900),
        ]
        func ids(_ field: AppSortField, _ direction: AppSortDirection) -> [Int64] {
            gateway.sortUsers(users, by: SortSpec(field: field, direction: direction)).map(\.id)
        }

        XCTAssertEqual(ids(.name, .ascending), [2, 5, 9])
        XCTAssertEqual(ids(.reputation, .descending), [2, 5, 9], "ties broken by id ascending")
        XCTAssertEqual(ids(.modifiedDate, .descending), [9, 5, 2], "missing modified date sorts last")
        XCTAssertEqual(ids(.creationDate, .ascending), [2, 9, 5])
    }

    @MainActor
    func test_connectionRefusedMapsToTypedNetworkError() async throws {
        let gateway = makeGateway(try temporaryFollowsFile())
        do {
            _ = try await gateway.getTopUsers()
            XCTFail("expected a network error")
        } catch let error as AppCoreError {
            guard case .network = error else { return XCTFail("expected .network, got \(error)") }
        }
    }

    @MainActor
    func test_followPersistsAndAFreshCoreInstanceReloadsIt() async throws {
        let file = try temporaryFollowsFile()
        let first = makeGateway(file)

        let followed = try await first.toggleFollow(userId: 22656)
        XCTAssertTrue(followed)
        XCTAssertEqual(try first.followedIds(), [22656])

        XCTAssertEqual(try makeGateway(file).followedIds(), [22656])
    }

    @MainActor
    func test_corruptFollowFileSurfacesStorageOnceThenResetsToEmpty() async throws {
        let file = try temporaryFollowsFile()
        try Data("{not json".utf8).write(to: file)
        let gateway = makeGateway(file)

        XCTAssertThrowsError(try gateway.followedIds()) { error in
            guard case .storage = error as? AppCoreError else { return XCTFail("expected .storage, got \(error)") }
        }
        XCTAssertEqual(try gateway.followedIds(), [])
    }

    @MainActor
    func test_observerReceivesCommittedSnapshots() async throws {
        let gateway = makeGateway(try temporaryFollowsFile())
        let updates = gateway.followUpdates()

        _ = try await gateway.toggleFollow(userId: 7)

        for await snapshot in updates {
            XCTAssertEqual(snapshot, [7])
            break
        }
    }
}
