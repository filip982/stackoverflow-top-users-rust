import Foundation

struct UserListState: Equatable, Sendable {
    enum Content: Equatable, Sendable {
        case loading
        case loaded([User])
        /// The server answered successfully with no users — not an error.
        case empty
        case failed(CoreError)
    }

    var content: Content = .loading
    var followed: Set<Int64> = []
    /// Users with a toggle in flight; further taps on them are ignored (rapid-toggle policy).
    var pendingFollows: Set<Int64> = []
    var sort = SortSpec()
    /// Monotonic load id; completions carrying an older id are stale and dropped.
    var requestId = 0
    /// Follow failure to surface once (toggle or storage reset).
    var followError: CoreError?

    var users: [User] {
        guard case let .loaded(users) = content else { return [] }
        return users
    }
}

enum UserListIntent: Equatable, Sendable {
    case load
    case retry
    case toggleFollow(userId: Int64)
    case applySort(SortSpec)
    case dismissFollowError

    // Results produced by store effects.
    case usersLoaded(requestId: Int, users: [User])
    case usersFailed(requestId: Int, error: CoreError)
    case usersSorted(sort: SortSpec, users: [User])
    case followsChanged(Set<Int64>)
    case followToggled(userId: Int64, followed: Bool)
    case followFailed(userId: Int64?, error: CoreError)
}

/// Pure reducer: no I/O, no core calls. Ignored/stale intents return the state unchanged.
func reduceUserList(_ state: UserListState, _ intent: UserListIntent) -> UserListState {
    var next = state
    switch intent {
    case .load, .retry:
        next.content = .loading
        next.requestId = state.requestId + 1

    case let .usersLoaded(requestId, users):
        guard requestId == state.requestId else { return state }
        next.content = users.isEmpty ? .empty : .loaded(users)

    case let .usersFailed(requestId, error):
        guard requestId == state.requestId else { return state }
        next.content = .failed(error)

    case let .applySort(sort):
        next.sort = sort

    case let .usersSorted(sort, users):
        guard sort == state.sort, case .loaded = state.content else { return state }
        next.content = .loaded(users)

    case let .toggleFollow(userId):
        guard !state.pendingFollows.contains(userId) else { return state }
        next.pendingFollows.insert(userId)
        next.followError = nil

    case let .followToggled(userId, followed):
        next.pendingFollows.remove(userId)
        if followed {
            next.followed.insert(userId)
        } else {
            next.followed.remove(userId)
        }

    case let .followFailed(userId, error):
        if let userId {
            next.pendingFollows.remove(userId)
        }
        next.followError = error

    case let .followsChanged(followed):
        next.followed = followed

    case .dismissFollowError:
        next.followError = nil
    }
    return next
}
