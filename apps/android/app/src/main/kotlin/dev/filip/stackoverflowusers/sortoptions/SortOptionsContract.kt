package dev.filip.stackoverflowusers.sortoptions

import dev.filip.stackoverflowusers.core.SortDirection
import dev.filip.stackoverflowusers.core.SortField
import dev.filip.stackoverflowusers.core.SortSpec

/**
 * Draft-state semantics: edits change only [draft]; Apply publishes the draft as the outcome,
 * Cancel discards it. Once an outcome exists the screen is finished and further intents are ignored.
 */
data class SortOptionsState(
    val applied: SortSpec,
    val draft: SortSpec = applied,
    val outcome: Outcome? = null,
) {
    sealed interface Outcome {
        data class Applied(val sort: SortSpec) : Outcome
        data object Cancelled : Outcome
    }
}

sealed interface SortOptionsIntent {
    data class SelectField(val field: SortField) : SortOptionsIntent
    data class SelectDirection(val direction: SortDirection) : SortOptionsIntent
    data object Apply : SortOptionsIntent
    data object Cancel : SortOptionsIntent
}

/** Pure reducer. */
fun reduceSortOptions(state: SortOptionsState, intent: SortOptionsIntent): SortOptionsState {
    if (state.outcome != null) return state
    return when (intent) {
        is SortOptionsIntent.SelectField -> state.copy(draft = state.draft.copy(field = intent.field))
        is SortOptionsIntent.SelectDirection -> state.copy(draft = state.draft.copy(direction = intent.direction))
        SortOptionsIntent.Apply -> state.copy(outcome = SortOptionsState.Outcome.Applied(state.draft))
        SortOptionsIntent.Cancel -> state.copy(draft = state.applied, outcome = SortOptionsState.Outcome.Cancelled)
    }
}
