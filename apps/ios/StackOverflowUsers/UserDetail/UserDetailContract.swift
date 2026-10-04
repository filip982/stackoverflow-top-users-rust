import Foundation

struct UserDetailState: Equatable, Sendable {
    /// `nil` when the user is no longer known (e.g. the list hasn't loaded it).
    var user: User?
    var isFollowed = false
    /// A toggle is in flight; further taps are ignored (same policy as the list).
    var togglePending = false
    var followError: CoreError?
}

enum UserDetailIntent: Equatable, Sendable {
    case toggleFollow
    case dismissFollowError

    // Results produced by store effects.
    case followsChanged(Set<Int64>)
    case followToggled(Bool)
    case followFailed(CoreError)
}

/// Pure reducer. Ignored intents return the state unchanged.
func reduceUserDetail(_ state: UserDetailState, _ intent: UserDetailIntent) -> UserDetailState {
    var next = state
    switch intent {
    case .toggleFollow:
        guard state.user != nil, !state.togglePending else { return state }
        next.togglePending = true
        next.followError = nil

    case let .followToggled(followed):
        next.togglePending = false
        next.isFollowed = followed

    case let .followFailed(error):
        next.togglePending = false
        next.followError = error

    case let .followsChanged(followed):
        guard let user = state.user else { return state }
        next.isFollowed = followed.contains(user.id)

    case .dismissFollowError:
        next.followError = nil
    }
    return next
}
