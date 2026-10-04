import Foundation
@testable import StackOverflowUsers

/// Controllable `CoreGateway`: every fetch/toggle suspends on a continuation that the test resumes
/// explicitly (deferred responses), unless an auto-response is configured.
@MainActor
final class FakeCoreGateway: CoreGateway {
    var followed: Set<Int64>
    private(set) var loads: [CheckedContinuation<[User], any Error>] = []
    private(set) var toggles: [(userId: Int64, continuation: CheckedContinuation<Bool, any Error>)] = []
    private(set) var sortCalls: [SortSpec] = []
    var followedIdsError: CoreError?
    var autoLoad: Result<[User], CoreError>?
    var autoToggle = false
    /// Observers registered and not yet terminated (disposed).
    private(set) var activeObservers = 0

    private var observers: [AsyncStream<Set<Int64>>.Continuation] = []

    init(followed: Set<Int64> = []) {
        self.followed = followed
    }

    func getTopUsers() async throws -> [User] {
        if let autoLoad {
            return try autoLoad.get()
        }
        return try await withCheckedThrowingContinuation { loads.append($0) }
    }

    func toggleFollow(userId: Int64) async throws -> Bool {
        if autoToggle {
            return applyToggle(userId)
        }
        return try await withCheckedThrowingContinuation { toggles.append((userId, $0)) }
    }

    func followedIds() throws -> Set<Int64> {
        if let error = followedIdsError {
            followedIdsError = nil // surfaced once, like the core
            throw error
        }
        return followed
    }

    func followUpdates() -> AsyncStream<Set<Int64>> {
        let (stream, continuation) = AsyncStream.makeStream(of: Set<Int64>.self, bufferingPolicy: .bufferingNewest(1))
        activeObservers += 1
        continuation.onTermination = { [weak self] _ in
            Task { @MainActor in self?.activeObservers -= 1 }
        }
        observers.append(continuation)
        return stream
    }

    func sortUsers(_ users: [User], by sort: SortSpec) -> [User] {
        sortCalls.append(sort)
        let ascending: [User]
        switch sort.field {
        case .reputation: ascending = users.sorted { $0.reputation < $1.reputation }
        case .name: ascending = users.sorted { $0.displayName.lowercased() < $1.displayName.lowercased() }
        case .creationDate: ascending = users.sorted { $0.creationDate < $1.creationDate }
        case .modifiedDate: ascending = users.sorted { ($0.lastModifiedDate ?? .max) < ($1.lastModifiedDate ?? .max) }
        }
        return sort.direction == .ascending ? ascending : ascending.reversed()
    }

    // MARK: - Test controls

    func completeLoad(_ index: Int, _ users: [User]) {
        loads[index].resume(returning: users)
    }

    func failLoad(_ index: Int, _ error: CoreError) {
        loads[index].resume(throwing: error)
    }

    /// Completes a pending toggle the way the real core would (persist, then notify observers).
    func completeToggle(_ index: Int) {
        let toggle = toggles[index]
        toggle.continuation.resume(returning: applyToggle(toggle.userId))
    }

    func failToggle(_ index: Int, _ error: CoreError) {
        toggles[index].continuation.resume(throwing: error)
    }

    /// Simulates a change committed elsewhere (e.g. from another screen).
    func emitFollows(_ ids: Set<Int64>) {
        followed = ids
        observers.forEach { $0.yield(ids) }
    }

    private func applyToggle(_ id: Int64) -> Bool {
        if followed.contains(id) {
            followed.remove(id)
        } else {
            followed.insert(id)
        }
        observers.forEach { $0.yield(followed) }
        return followed.contains(id)
    }
}
