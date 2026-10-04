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

/** Pure reducer. */
fun reduceUserDetail(state: UserDetailState, intent: UserDetailIntent): UserDetailState = state
