import XCTest
@testable import StackOverflowUsers

final class UserListReducerTests: XCTestCase {
    private let jon = TestUsers.jon

    func test_loadBumpsTheRequestIdAndShowsLoading() {
        let failed = UserListState(content: .failed(.http(500)), requestId: 3)

        let next = reduceUserList(failed, .retry)

        XCTAssertEqual(next.content, .loading)
        XCTAssertEqual(next.requestId, 4)
    }

    func test_completionWithAnOldRequestIdIsIgnored() {
        let loading = UserListState(requestId: 2)

        XCTAssertEqual(reduceUserList(loading, .usersLoaded(requestId: 1, users: [jon])), loading)
        XCTAssertEqual(reduceUserList(loading, .usersFailed(requestId: 1, error: .network("late"))), loading)
    }

    func test_emptyResultIsEmpty_nonEmptyIsLoaded() {
        let loading = UserListState(requestId: 1)

        XCTAssertEqual(reduceUserList(loading, .usersLoaded(requestId: 1, users: [])).content, .empty)
        XCTAssertEqual(reduceUserList(loading, .usersLoaded(requestId: 1, users: [jon])).content, .loaded([jon]))
    }

    func test_sortedUsersForASupersededSortAreIgnored() {
        let state = UserListState(content: .loaded([jon]), sort: SortSpec(field: .name, direction: .ascending))

        XCTAssertEqual(reduceUserList(state, .usersSorted(sort: SortSpec(), users: [])), state)
    }

    func test_toggleOnAPendingUserIsANoOpAndCompletionClearsPending() {
        let pending = reduceUserList(UserListState(), .toggleFollow(userId: jon.id))
        XCTAssertEqual(pending.pendingFollows, [jon.id])
        XCTAssertEqual(reduceUserList(pending, .toggleFollow(userId: jon.id)), pending)

        let done = reduceUserList(pending, .followToggled(userId: jon.id, followed: true))
        XCTAssertEqual(done.pendingFollows, [])
        XCTAssertEqual(done.followed, [jon.id])
    }
}
