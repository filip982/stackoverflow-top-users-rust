package dev.filip.stackoverflowusers.mvi

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Minimal hand-rolled MVI store: `send(intent)` runs the pure [reduce] and then hands the
 * transition to [onTransition], the only place side effects (core calls) may start. Effects
 * report back by sending result intents. Must be driven from the main thread.
 */
abstract class Store<S, I>(initial: S) : ViewModel() {
    private val mutableState = MutableStateFlow(initial)
    val state: StateFlow<S> = mutableState.asStateFlow()

    protected abstract fun reduce(state: S, intent: I): S

    protected open fun onTransition(intent: I, previous: S, current: S) = Unit

    fun send(intent: I) {
        val previous = mutableState.value
        val current = reduce(previous, intent)
        mutableState.value = current
        onTransition(intent, previous, current)
    }
}
