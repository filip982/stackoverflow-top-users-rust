package dev.filip.stackoverflowusers.sortoptions

import dev.filip.stackoverflowusers.core.SortSpec
import dev.filip.stackoverflowusers.mvi.Store

/** No effects: the caller hands an Applied outcome to the list store. */
class SortOptionsStore(applied: SortSpec) :
    Store<SortOptionsState, SortOptionsIntent>(SortOptionsState(applied)) {
    override fun reduce(state: SortOptionsState, intent: SortOptionsIntent) = reduceSortOptions(state, intent)
}
