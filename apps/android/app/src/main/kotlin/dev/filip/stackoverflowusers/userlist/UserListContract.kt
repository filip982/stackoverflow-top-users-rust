package dev.filip.stackoverflowusers.userlist

import dev.filip.stackoverflowusers.core.CoreError
import dev.filip.stackoverflowusers.core.SortSpec
import dev.filip.stackoverflowusers.core.User

data class UserListState(
    val content: Content = Content.Loading,
    val followed: Set<Long> = emptySet(),
    /** Users with a toggle in flight; further taps on them are ignored (rapid-toggle policy). */
    val pendingFollows: Set<Long> = emptySet(),
    val sort: SortSpec = SortSpec(),
    /** Monotonic load id; completions carrying an older id are stale and dropped. */
    val requestId: Long = 0,
    /** Follow failure to surface once (toggle or storage reset). */
    val followError: CoreError? = null,
) {
    sealed interface Content {
        data object Loading : Content
        data class Loaded(val users: List<User>) : Content
        /** The server answered successfully with no users — not an error. */
        data object Empty : Content
        data class Failed(val error: CoreError) : Content
    }

    val users: List<User> get() = (content as? Content.Loaded)?.users.orEmpty()
}

sealed interface UserListIntent {
    data object Load : UserListIntent
    data object Retry : UserListIntent
    data class ToggleFollow(val userId: Long) : UserListIntent
    data class ApplySort(val sort: SortSpec) : UserListIntent
    data object DismissFollowError : UserListIntent

    // Results produced by store effects.
    data class UsersLoaded(val requestId: Long, val users: List<User>) : UserListIntent
    data class UsersFailed(val requestId: Long, val error: CoreError) : UserListIntent
    data class UsersSorted(val sort: SortSpec, val users: List<User>) : UserListIntent
    data class FollowsChanged(val followed: Set<Long>) : UserListIntent
    data class FollowToggled(val userId: Long, val followed: Boolean) : UserListIntent
    data class FollowFailed(val userId: Long?, val error: CoreError) : UserListIntent
}

/** Pure reducer: no I/O, no core calls. Stale results return the same state instance. */
fun reduceUserList(state: UserListState, intent: UserListIntent): UserListState = when (intent) {
    UserListIntent.Load, UserListIntent.Retry ->
        state.copy(content = UserListState.Content.Loading, requestId = state.requestId + 1)

    is UserListIntent.UsersLoaded -> when {
        intent.requestId != state.requestId -> state
        intent.users.isEmpty() -> state.copy(content = UserListState.Content.Empty)
        else -> state.copy(content = UserListState.Content.Loaded(intent.users))
    }

    is UserListIntent.UsersFailed ->
        if (intent.requestId != state.requestId) state
        else state.copy(content = UserListState.Content.Failed(intent.error))

    is UserListIntent.ApplySort -> state.copy(sort = intent.sort)

    is UserListIntent.UsersSorted ->
        if (intent.sort != state.sort || state.content !is UserListState.Content.Loaded) state
        else state.copy(content = UserListState.Content.Loaded(intent.users))

    is UserListIntent.ToggleFollow ->
        if (intent.userId in state.pendingFollows) state
        else state.copy(pendingFollows = state.pendingFollows + intent.userId, followError = null)

    is UserListIntent.FollowToggled -> state.copy(
        pendingFollows = state.pendingFollows - intent.userId,
        followed = if (intent.followed) state.followed + intent.userId else state.followed - intent.userId,
    )

    is UserListIntent.FollowFailed -> state.copy(
        pendingFollows = intent.userId?.let { state.pendingFollows - it } ?: state.pendingFollows,
        followError = intent.error,
    )

    is UserListIntent.FollowsChanged -> state.copy(followed = intent.followed)

    UserListIntent.DismissFollowError -> state.copy(followError = null)
}
