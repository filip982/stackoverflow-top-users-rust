import XCTest
@testable import StackOverflowUsers

/// Mirrors apps/android/.../userdetail/UserDetailStoreTest.kt.
final class UserDetailStoreTests: XCTestCase {
    private let jon = TestUsers.jon
    private let gordon = TestUsers.gordon

    @MainActor
    func test_showsTheUserWithTheFollowStateFromTheCoreSnapshot() async {
        let gateway = FakeCoreGateway(followed: [jon.id])
        let store = UserDetailStore(user: jon, gateway: gateway)
        await settle()

        XCTAssertEqual(store.state, UserDetailState(user: jon, isFollowed: true))
    }

    @MainActor
    func test_toggleGoesPendingThenReflectsTheCoreResult() async {
        let gateway = FakeCoreGateway()
        let store = UserDetailStore(user: jon, gateway: gateway)
        await settle()
        XCTAssertFalse(store.state.isFollowed)

        store.send(.toggleFollow)
        XCTAssertTrue(store.state.togglePending)
        await settle()
        gateway.completeToggle(0)
        await settle()

        XCTAssertTrue(store.state.isFollowed)
        XCTAssertFalse(store.state.togglePending)
    }

    @MainActor
    func test_rapidTogglesWhilePendingMakeASingleCoreCall() async {
        let gateway = FakeCoreGateway()
        let store = UserDetailStore(user: jon, gateway: gateway)
        await settle()

        for _ in 0..<4 { store.send(.toggleFollow) }
        await settle()

        XCTAssertEqual(gateway.toggles.map(\.userId), [jon.id])
    }

    @MainActor
    func test_followFailureIsSurfacedAndDismissable() async {
        let gateway = FakeCoreGateway()
        let store = UserDetailStore(user: jon, gateway: gateway)
        await settle()

        store.send(.toggleFollow)
        await settle()
        gateway.failToggle(0, .storage("read-only"))
        await settle()

        XCTAssertEqual(store.state.followError, .storage("read-only"))
        XCTAssertFalse(store.state.isFollowed)
        XCTAssertFalse(store.state.togglePending)

        store.send(.dismissFollowError)
        XCTAssertNil(store.state.followError)
    }

    @MainActor
    func test_staysInSyncWithFollowChangesMadeOnAnotherScreen() async {
        let gateway = FakeCoreGateway()
        let store = UserDetailStore(user: jon, gateway: gateway)
        await settle()

        gateway.emitFollows([gordon.id, jon.id])
        await settle()
        XCTAssertTrue(store.state.isFollowed)

        gateway.emitFollows([gordon.id])
        await settle()
        XCTAssertFalse(store.state.isFollowed)
    }

    @MainActor
    func test_unknownUserIgnoresTogglesAndNeverCallsTheCore() async {
        let gateway = FakeCoreGateway()
        let store = UserDetailStore(user: nil, gateway: gateway)
        store.send(.toggleFollow)
        await settle()

        XCTAssertEqual(store.state, UserDetailState(user: nil))
        XCTAssertTrue(gateway.toggles.isEmpty)
        XCTAssertEqual(gateway.activeObservers, 0)
    }
}
