import Foundation
import SoCore

/// `CoreGateway` over the generated UniFFI bindings. Pure type mapping — no logic.
@MainActor
final class RustCoreGateway: CoreGateway {
    private let core: FFICore

    init(core: FFICore) {
        self.core = core
    }

    /// `baseUrl` is the API origin; `storagePath` the follow-state file inside the app sandbox.
    convenience init(baseUrl: String, storagePath: String) {
        self.init(core: ffiNewCore(baseUrl: baseUrl, storagePath: storagePath))
    }

    func getTopUsers() async throws -> [User] {
        do {
            return try await core.getTopUsers().map(User.init(ffi:))
        } catch let error as FFICoreError {
            throw CoreError(ffi: error)
        }
    }

    func toggleFollow(userId: Int64) async throws -> Bool {
        do {
            return try await core.toggleFollow(userId: UInt64(bitPattern: userId))
        } catch let error as FFICoreError {
            throw CoreError(ffi: error)
        }
    }

    func followedIds() throws -> Set<Int64> {
        do {
            return Set(try core.followedIds().map { Int64(bitPattern: $0) })
        } catch let error as FFICoreError {
            throw CoreError(ffi: error)
        }
    }

    func followUpdates() -> AsyncStream<Set<Int64>> {
        // Snapshots are complete, so only the newest undelivered one matters (conflated).
        let (stream, continuation) = AsyncStream.makeStream(
            of: Set<Int64>.self,
            bufferingPolicy: .bufferingNewest(1)
        )
        // The core calls the observer on one of its threads; the stream hands each snapshot to
        // the consuming task, which runs on the main actor.
        let observation = core.addFollowObserver(observer: FollowObserverBridge { ids in
            continuation.yield(Set(ids.map { Int64(bitPattern: $0) }))
        })
        continuation.onTermination = { _ in
            observation.dispose()
        }
        return stream
    }

    func sortUsers(_ users: [User], by sort: SortSpec) -> [User] {
        ffiSortUsers(users.map(\.ffi), field: sort.field.ffi, direction: sort.direction.ffi)
            .map(User.init(ffi:))
    }
}

/// Bridges the generated callback interface to a `@Sendable` closure.
final class FollowObserverBridge: FFIFollowObserver {
    private let onChange: @Sendable ([UInt64]) -> Void

    init(_ onChange: @escaping @Sendable ([UInt64]) -> Void) {
        self.onChange = onChange
    }

    func onFollowsChanged(followedIds: [UInt64]) {
        onChange(followedIds)
    }
}

// MARK: - Mapping (generated mutable structs <-> immutable app models)

extension User {
    init(ffi user: FFIUser) {
        self.init(
            id: Int64(bitPattern: user.id),
            displayName: user.displayName,
            reputation: Int64(bitPattern: user.reputation),
            avatarUrl: user.avatarUrl,
            location: user.location,
            websiteUrl: user.websiteUrl,
            creationDate: user.creationDate,
            lastModifiedDate: user.lastModifiedDate
        )
    }

    var ffi: FFIUser {
        FFIUser(
            id: UInt64(bitPattern: id),
            displayName: displayName,
            reputation: UInt64(bitPattern: reputation),
            avatarUrl: avatarUrl,
            location: location,
            websiteUrl: websiteUrl,
            creationDate: creationDate,
            lastModifiedDate: lastModifiedDate
        )
    }
}

extension CoreError {
    init(ffi error: FFICoreError) {
        switch error {
        case let .Network(reason): self = .network(reason)
        case let .Http(code): self = .http(Int(code))
        case let .Decoding(reason): self = .decoding(reason)
        case let .Storage(reason): self = .storage(reason)
        }
    }
}

extension SortField {
    var ffi: FFISortField {
        switch self {
        case .reputation: .reputation
        case .name: .name
        case .creationDate: .creationDate
        case .modifiedDate: .modifiedDate
        }
    }
}

extension SortDirection {
    var ffi: FFISortDirection {
        switch self {
        case .ascending: .asc
        case .descending: .desc
        }
    }
}
