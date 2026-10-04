import XCTest
@testable import StackOverflowUsers

/// Mirrors apps/android/.../sortoptions/SortOptionsStoreTest.kt (Apply/Cancel draft semantics).
final class SortOptionsStoreTests: XCTestCase {
    private let byName = SortSpec(field: .name, direction: .ascending)

    func test_defaultSortIsReputationDescending() {
        XCTAssertEqual(SortSpec(), SortSpec(field: .reputation, direction: .descending))
    }

    @MainActor
    func test_draftStartsFromTheAppliedSort() async {
        let store = SortOptionsStore(applied: byName)

        XCTAssertEqual(store.state.draft, byName)
        XCTAssertNil(store.state.outcome)
    }

    @MainActor
    func test_selectionsChangeOnlyTheDraftUntilApply() async {
        let store = SortOptionsStore(applied: SortSpec())

        store.send(.selectField(.creationDate))
        store.send(.selectDirection(.ascending))

        let expected = SortSpec(field: .creationDate, direction: .ascending)
        XCTAssertEqual(store.state.draft, expected)
        XCTAssertEqual(store.state.applied, SortSpec())
        XCTAssertNil(store.state.outcome)

        store.send(.apply)
        XCTAssertEqual(store.state.outcome, .applied(expected))
    }

    @MainActor
    func test_cancelDiscardsTheDraft() async {
        let store = SortOptionsStore(applied: byName)

        store.send(.selectField(.modifiedDate))
        store.send(.selectDirection(.descending))
        store.send(.cancel)

        XCTAssertEqual(store.state.outcome, .cancelled)
        XCTAssertEqual(store.state.draft, byName)
        XCTAssertEqual(store.state.applied, byName)
    }

    @MainActor
    func test_intentsAfterAnOutcomeAreIgnored() async {
        let store = SortOptionsStore(applied: SortSpec())
        store.send(.apply)
        let finished = store.state

        store.send(.selectField(.name))
        store.send(.cancel)

        XCTAssertEqual(store.state, finished)
    }
}
