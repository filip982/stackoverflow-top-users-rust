import Foundation

/// The app's only view of the Rust core. Stores depend on this protocol; production uses
/// `RustCoreGateway` (generated UniFFI bindings), tests use fakes. Mirrors the FFI contract
/// one-to-one — no business logic lives on this side.
///
/// Main-actor isolated: stores call it synchronously from the main actor; the async methods
/// suspend (the core runs the work on its own Tokio runtime), so the main thread never blocks.
@MainActor
protocol CoreGateway: AnyObject, Sendable {
    /// Top users in the core's default order. Throws `CoreError`.
    func getTopUsers() async throws -> [User]

    /// Flips the follow state; returns the new state (`true` = followed). Throws `CoreError`.
    func toggleFollow(userId: Int64) async throws -> Bool

    /// Snapshot of followed ids. Throws `CoreError.storage` once after a corrupt store was reset.
    func followedIds() throws -> Set<Int64>

    /// Every committed follow change as a full snapshot. The core observer is registered when this
    /// is called and disposed when the stream terminates (consumer task cancelled / released).
    func followUpdates() -> AsyncStream<Set<Int64>>

    /// Deterministic client-side sort, performed by the core.
    func sortUsers(_ users: [User], by sort: SortSpec) -> [User]
}

/// App-side immutable mirror of the core `User` record (dates are epoch seconds).
struct User: Identifiable, Hashable, Sendable {
    let id: Int64
    let displayName: String
    let reputation: Int64
    let avatarUrl: String?
    let location: String?
    let websiteUrl: String?
    let creationDate: Int64
    let lastModifiedDate: Int64?
}

enum SortField: String, CaseIterable, Sendable {
    case reputation = "REPUTATION"
    case name = "NAME"
    case creationDate = "CREATION_DATE"
    case modifiedDate = "MODIFIED_DATE"
}

enum SortDirection: String, CaseIterable, Sendable {
    case ascending = "ASCENDING"
    case descending = "DESCENDING"
}

struct SortSpec: Hashable, Sendable {
    var field: SortField = .reputation
    var direction: SortDirection = .descending
}

/// Typed mirror of the core's `CoreError`.
enum CoreError: Error, Hashable, Sendable {
    case network(String)
    case http(Int)
    case decoding(String)
    case storage(String)

    /// Any error reaching a store: core errors pass through, anything else is treated as transport.
    init(_ error: any Error) {
        self = (error as? CoreError) ?? .network(String(describing: error))
    }
}
