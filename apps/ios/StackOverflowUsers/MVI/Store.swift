import Combine

/// Minimal hand-rolled MVI store: `send(_:)` runs the pure `reduce` and then hands the transition
/// to `onTransition`, the only place side effects (core calls) may start. Effects report back by
/// sending result intents. Main-actor isolated, so every reduction is serialized on the main thread.
///
/// Subclasses override `reduce` (pure; returning an unchanged state means "intent ignored") and,
/// if they have effects, `onTransition`.
@MainActor
class Store<State: Equatable, Intent>: ObservableObject {
    @Published private(set) var state: State

    init(initial: State) {
        state = initial
    }

    func reduce(_ state: State, _ intent: Intent) -> State {
        state
    }

    func onTransition(_ intent: Intent, previous: State, current: State) {}

    final func send(_ intent: Intent) {
        let previous = state
        let current = reduce(previous, intent)
        if current != previous {
            state = current
        }
        onTransition(intent, previous: previous, current: current)
    }
}
