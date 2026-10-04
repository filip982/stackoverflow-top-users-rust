import Foundation

/// Detail screen store; follow state is shared with the list through the core's observer.
@MainActor
final class UserDetailStore: Store<UserDetailState, UserDetailIntent> {
    private let gateway: any CoreGateway
    nonisolated(unsafe) private var followUpdatesTask: Task<Void, Never>?

    init(user: User?, gateway: any CoreGateway) {
        self.gateway = gateway
        super.init(initial: UserDetailState(user: user))
        guard user != nil else { return }
        let updates = gateway.followUpdates()
        followUpdatesTask = Task { [weak self] in
            for await followed in updates {
                self?.send(.followsChanged(followed))
            }
        }
        do {
            send(.followsChanged(try gateway.followedIds()))
        } catch {
            send(.followFailed(CoreError(error)))
        }
    }

    deinit {
        followUpdatesTask?.cancel()
    }

    override func reduce(_ state: UserDetailState, _ intent: UserDetailIntent) -> UserDetailState {
        reduceUserDetail(state, intent)
    }

    override func onTransition(_ intent: UserDetailIntent, previous: UserDetailState, current: UserDetailState) {
        guard intent == .toggleFollow, current != previous, let user = current.user else { return }
        Task { [weak self, gateway] in
            let result: UserDetailIntent
            do {
                result = .followToggled(try await gateway.toggleFollow(userId: user.id))
            } catch {
                result = .followFailed(CoreError(error))
            }
            self?.send(result)
        }
    }
}
