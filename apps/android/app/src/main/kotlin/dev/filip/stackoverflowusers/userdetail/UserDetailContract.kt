package dev.filip.stackoverflowusers.userdetail

import dev.filip.stackoverflowusers.core.CoreError
import dev.filip.stackoverflowusers.core.User

data class UserDetailState(
    /** `null` when the user is no longer known (e.g. list not loaded after process death). */
    val user: User?,
    val isFollowed: Boolean = false,
    /** A toggle is in flight; further taps are ignored (same policy as the list). */
    val togglePending: Boolean = false,
    val followError: CoreError? = null,
)

sealed interface UserDetailIntent {
    data object ToggleFollow : UserDetailIntent
    data object DismissFollowError : UserDetailIntent

    // Results produced by store effects.
    data class FollowsChanged(val followed: Set<Long>) : UserDetailIntent
    data class FollowToggled(val followed: Boolean) : UserDetailIntent
    data class FollowFailed(val error: CoreError) : UserDetailIntent
}

/** Pure reducer. Ignored intents return the same state instance. */
fun reduceUserDetail(state: UserDetailState, intent: UserDetailIntent): UserDetailState = when (intent) {
    UserDetailIntent.ToggleFollow ->
        if (state.user == null || state.togglePending) state
        else state.copy(togglePending = true, followError = null)

    is UserDetailIntent.FollowToggled -> state.copy(togglePending = false, isFollowed = intent.followed)

    is UserDetailIntent.FollowFailed -> state.copy(togglePending = false, followError = intent.error)

    is UserDetailIntent.FollowsChanged ->
        state.user?.let { state.copy(isFollowed = it.id in intent.followed) } ?: state

    UserDetailIntent.DismissFollowError -> state.copy(followError = null)
}
