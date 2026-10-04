import Foundation

/// Draft-state semantics: edits change only `draft`; Apply publishes the draft as the outcome,
/// Cancel discards it. Once an outcome exists the screen is finished and further intents are ignored.
struct SortOptionsState: Equatable, Sendable {
    enum Outcome: Equatable, Sendable {
        case applied(SortSpec)
        case cancelled
    }

    var applied: SortSpec
    var draft: SortSpec
    var outcome: Outcome?

    init(applied: SortSpec, draft: SortSpec? = nil, outcome: Outcome? = nil) {
        self.applied = applied
        self.draft = draft ?? applied
        self.outcome = outcome
    }
}

enum SortOptionsIntent: Equatable, Sendable {
    case selectField(SortField)
    case selectDirection(SortDirection)
    case apply
    case cancel
}

/// Pure reducer.
func reduceSortOptions(_ state: SortOptionsState, _ intent: SortOptionsIntent) -> SortOptionsState {
    guard state.outcome == nil else { return state }
    var next = state
    switch intent {
    case let .selectField(field):
        next.draft.field = field
    case let .selectDirection(direction):
        next.draft.direction = direction
    case .apply:
        next.outcome = .applied(state.draft)
    case .cancel:
        next.draft = state.applied
        next.outcome = .cancelled
    }
    return next
}

/// No effects: the caller hands an `.applied` outcome to the list store, which sorts via the core.
@MainActor
final class SortOptionsStore: Store<SortOptionsState, SortOptionsIntent> {
    init(applied: SortSpec) {
        super.init(initial: SortOptionsState(applied: applied))
    }

    override func reduce(_ state: SortOptionsState, _ intent: SortOptionsIntent) -> SortOptionsState {
        reduceSortOptions(state, intent)
    }
}
