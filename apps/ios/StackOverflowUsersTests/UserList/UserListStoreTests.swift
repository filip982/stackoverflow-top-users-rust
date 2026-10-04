import XCTest
@testable import StackOverflowUsers

/// Mirrors apps/android/.../userlist/UserListStoreTest.kt. Every effect is driven by the fake
/// gateway's deferred responses; `settle()` plays the role of `advanceUntilIdle()`.
final class UserListStoreTests: XCTestCase {
    private let jon = TestUsers.jon
    private let gordon = TestUsers.gordon
    private let vonc = TestUsers.vonc

    @MainActor
    func test_startsLoadingAndFetchesOnceOnCreation() async {
        let gateway = FakeCoreGateway()
        let store = UserListStore(gateway: gateway)
        await settle()

        XCTAssertEqual(store.state.content, .loading)
        XCTAssertEqual(gateway.loads.count, 1)
    }

    @MainActor
    func test_successShowsUsersSortedByTheCoreWithTheCurrentSort() async {
        let gateway = FakeCoreGateway()
        let store = UserListStore(gateway: gateway)
        await settle()

        gateway.completeLoad(0, [gordon, vonc, jon])
        await settle()

        XCTAssertEqual(store.state.content, .loaded([jon, gordon, vonc]))
        XCTAssertEqual(gateway.sortCalls, [SortSpec()])
    }

    @MainActor
    func test_emptySuccessIsDistinctFromError() async {
        let gateway = FakeCoreGateway()
        let store = UserListStore(gateway: gateway)
        await settle()

        gateway.completeLoad(0, [])
        await settle()

        XCTAssertEqual(store.state.content, .empty)
    }

    @MainActor
    func test_failureExposesTheTypedCoreError() async {
        let gateway = FakeCoreGateway()
        let store = UserListStore(gateway: gateway)
        await settle()

        gateway.failLoad(0, .network("offline"))
        await settle()

        XCTAssertEqual(store.state.content, .failed(.network("offline")))
    }

    @MainActor
    func test_retryAfterErrorGoesThroughLoadingAndRecovers() async {
        let gateway = FakeCoreGateway()
        let store = UserListStore(gateway: gateway)
        await settle()
        gateway.failLoad(0, .http(500))
        await settle()
        XCTAssertEqual(store.state.content, .failed(.http(500)))

        store.send(.retry)
        XCTAssertEqual(store.state.content, .loading)
        await settle()
        gateway.completeLoad(1, TestUsers.all)
        await settle()

        XCTAssertEqual(store.state.content, .loaded([jon, gordon, vonc]))
    }

    @MainActor
    func test_staleCompletionOfAnEarlierRequestIsSuppressed_latestRequestWins() async {
        let gateway = FakeCoreGateway()
        let store = UserListStore(gateway: gateway)
        await settle()
        store.send(.retry)
        await settle()
        XCTAssertEqual(gateway.loads.count, 2)

        gateway.completeLoad(1, [jon])
        await settle()
        gateway.failLoad(0, .network("late failure from the first request"))
        await settle()

        XCTAssertEqual(store.state.content, .loaded([jon]))
    }

    @MainActor
    func test_rapidTogglesOnTheSameUserWhileOneIsInFlightMakeASingleCoreCall() async {
        let (store, gateway) = await loadedStore()

        for _ in 0..<3 { store.send(.toggleFollow(userId: jon.id)) }
        await settle()

        XCTAssertEqual(gateway.toggles.count, 1)
        XCTAssertEqual(store.state.pendingFollows, [jon.id])

        gateway.completeToggle(0)
        await settle()
        XCTAssertEqual(store.state.followed, [jon.id])
        XCTAssertEqual(store.state.pendingFollows, [])

        store.send(.toggleFollow(userId: jon.id))
        await settle()
        XCTAssertEqual(gateway.toggles.count, 2, "a new toggle is accepted once the previous one settled")
    }

    @MainActor
    func test_togglesForDifferentUsersAreIndependent() async {
        let (store, gateway) = await loadedStore()

        store.send(.toggleFollow(userId: jon.id))
        store.send(.toggleFollow(userId: gordon.id))
        await settle()

        XCTAssertEqual(gateway.toggles.map(\.userId), [jon.id, gordon.id])
    }

    @MainActor
    func test_followFailureIsSurfacedAndLeavesFollowStateUnchanged() async {
        let (store, gateway) = await loadedStore()

        store.send(.toggleFollow(userId: jon.id))
        await settle()
        gateway.failToggle(0, .storage("disk full"))
        await settle()

        XCTAssertEqual(store.state.followError, .storage("disk full"))
        XCTAssertEqual(store.state.followed, [])
        XCTAssertEqual(store.state.pendingFollows, [])

        store.send(.dismissFollowError)
        XCTAssertNil(store.state.followError)
    }

    @MainActor
    func test_followChangesCommittedElsewhereAreReflected() async {
        let (store, gateway) = await loadedStore()

        gateway.emitFollows([gordon.id])
        await settle()

        XCTAssertEqual(store.state.followed, [gordon.id])
    }

    @MainActor
    func test_initialFollowSnapshotIsLoadedFromTheCore() async {
        let gateway = FakeCoreGateway(followed: [vonc.id])
        let store = UserListStore(gateway: gateway)
        await settle()

        XCTAssertEqual(store.state.followed, [vonc.id])
    }

    @MainActor
    func test_storageResetOnStartupIsSurfacedAsAFollowError() async {
        let gateway = FakeCoreGateway()
        gateway.followedIdsError = .storage("corrupt file reset")
        let store = UserListStore(gateway: gateway)
        await settle()

        XCTAssertEqual(store.state.followError, .storage("corrupt file reset"))
        XCTAssertEqual(store.state.followed, [])

        // Surfaced once: a second store over the same core sees a clean snapshot.
        let second = UserListStore(gateway: gateway)
        XCTAssertNil(second.state.followError)
    }

    @MainActor
    func test_applyingASortReSortsLoadedUsersThroughTheCore() async {
        let (store, gateway) = await loadedStore()
        let byName = SortSpec(field: .name, direction: .ascending)

        store.send(.applySort(byName))
        await settle()

        XCTAssertEqual(store.state.sort, byName)
        XCTAssertEqual(store.state.users, [gordon, jon, vonc])
        XCTAssertEqual(gateway.sortCalls.last, byName)
    }

    @MainActor
    func test_sortAppliedWhileLoadingIsUsedForTheArrivingResult() async {
        let gateway = FakeCoreGateway()
        let store = UserListStore(gateway: gateway)
        await settle()
        let byName = SortSpec(field: .name, direction: .descending)

        store.send(.applySort(byName))
        gateway.completeLoad(0, TestUsers.all)
        await settle()

        XCTAssertEqual(store.state.users, [vonc, jon, gordon])
    }

    // iOS-specific lifetime check: the core observer must not outlive the store.
    @MainActor
    func test_followObserverIsDisposedWhenTheStoreIsReleased() async {
        let gateway = FakeCoreGateway()
        var store: UserListStore? = UserListStore(gateway: gateway)
        weak var released = store
        await settle()
        XCTAssertEqual(gateway.activeObservers, 1)

        store = nil
        await settle()

        XCTAssertNil(released)
        XCTAssertEqual(gateway.activeObservers, 0)
    }

    @MainActor
    private func loadedStore() async -> (UserListStore, FakeCoreGateway) {
        let gateway = FakeCoreGateway()
        let store = UserListStore(gateway: gateway)
        await settle()
        gateway.completeLoad(0, TestUsers.all)
        await settle()
        XCTAssertEqual(store.state.content, .loaded([jon, gordon, vonc]))
        return (store, gateway)
    }
}
