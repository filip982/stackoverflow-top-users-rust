import Foundation

/// List screen store. Thin: fetching, sorting and follow persistence are all core calls.
@MainActor
final class UserListStore: Store<UserListState, UserListIntent> {
    private let gateway: any CoreGateway
    // Touched from `deinit` (nonisolated) only to cancel; all other access is on the main actor.
    nonisolated(unsafe) private var loadTask: Task<Void, Never>?
    nonisolated(unsafe) private var followUpdatesTask: Task<Void, Never>?

    init(gateway: any CoreGateway) {
        self.gateway = gateway
        super.init(initial: UserListState())
        // Subscribe before the snapshot so no committed change can slip between the two.
        let updates = gateway.followUpdates()
        followUpdatesTask = Task { [weak self] in
            for await followed in updates {
                self?.send(.followsChanged(followed))
            }
        }
        do {
            send(.followsChanged(try gateway.followedIds()))
        } catch {
            send(.followFailed(userId: nil, error: CoreError(error)))
        }
        send(.load)
    }

    deinit {
        loadTask?.cancel()
        // Ends the follow stream, which disposes the core observer.
        followUpdatesTask?.cancel()
    }

    override func reduce(_ state: UserListState, _ intent: UserListIntent) -> UserListState {
        reduceUserList(state, intent)
    }

    override func onTransition(_ intent: UserListIntent, previous: UserListState, current: UserListState) {
        switch intent {
        case .load, .retry:
            load(requestId: current.requestId)
        case let .toggleFollow(userId):
            if current != previous { toggle(userId: userId) }
        case let .applySort(sort):
            if case let .loaded(users) = current.content {
                send(.usersSorted(sort: sort, users: gateway.sortUsers(users, by: sort)))
            }
        default:
            break
        }
    }

    private func load(requestId: Int) {
        loadTask?.cancel() // latest request wins; the reducer also drops stale ids
        loadTask = Task { [weak self, gateway] in
            let result: UserListIntent
            do {
                let users = try await gateway.getTopUsers()
                guard let self, !Task.isCancelled else { return }
                result = .usersLoaded(requestId: requestId, users: gateway.sortUsers(users, by: self.state.sort))
            } catch {
                result = .usersFailed(requestId: requestId, error: CoreError(error))
            }
            guard !Task.isCancelled else { return }
            self?.send(result)
        }
    }

    private func toggle(userId: Int64) {
        Task { [weak self, gateway] in
            let result: UserListIntent
            do {
                result = .followToggled(userId: userId, followed: try await gateway.toggleFollow(userId: userId))
            } catch {
                result = .followFailed(userId: userId, error: CoreError(error))
            }
            self?.send(result)
        }
    }
}
