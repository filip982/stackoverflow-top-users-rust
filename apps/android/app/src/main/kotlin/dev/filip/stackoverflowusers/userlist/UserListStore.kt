package dev.filip.stackoverflowusers.userlist

import androidx.lifecycle.viewModelScope
import dev.filip.stackoverflowusers.core.CoreError
import dev.filip.stackoverflowusers.core.CoreGateway
import dev.filip.stackoverflowusers.mvi.Store
import dev.filip.stackoverflowusers.userlist.UserListIntent.ApplySort
import dev.filip.stackoverflowusers.userlist.UserListIntent.FollowFailed
import dev.filip.stackoverflowusers.userlist.UserListIntent.FollowToggled
import dev.filip.stackoverflowusers.userlist.UserListIntent.FollowsChanged
import dev.filip.stackoverflowusers.userlist.UserListIntent.Load
import dev.filip.stackoverflowusers.userlist.UserListIntent.Retry
import dev.filip.stackoverflowusers.userlist.UserListIntent.ToggleFollow
import dev.filip.stackoverflowusers.userlist.UserListIntent.UsersFailed
import dev.filip.stackoverflowusers.userlist.UserListIntent.UsersLoaded
import dev.filip.stackoverflowusers.userlist.UserListIntent.UsersSorted
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** List screen store. Thin: fetching, sorting and follow persistence are all core calls. */
class UserListStore(
    private val gateway: CoreGateway,
) : Store<UserListState, UserListIntent>(UserListState()) {

    private var loadJob: Job? = null

    init {
        // Subscribe before the snapshot so no committed change can slip between the two.
        viewModelScope.launch { gateway.followUpdates().collect { send(FollowsChanged(it)) } }
        try {
            send(FollowsChanged(gateway.followedIds()))
        } catch (e: CoreError) {
            send(FollowFailed(userId = null, error = e))
        }
        send(Load)
    }

    override fun reduce(state: UserListState, intent: UserListIntent) = reduceUserList(state, intent)

    override fun onTransition(intent: UserListIntent, previous: UserListState, current: UserListState) {
        when (intent) {
            Load, Retry -> load(current.requestId)
            is ToggleFollow -> if (current !== previous) toggle(intent.userId)
            is ApplySort -> current.content.let { content ->
                if (content is UserListState.Content.Loaded) {
                    send(UsersSorted(intent.sort, gateway.sortUsers(content.users, intent.sort)))
                }
            }
            else -> Unit
        }
    }

    private fun load(requestId: Long) {
        loadJob?.cancel() // latest request wins; the reducer also drops stale ids
        loadJob = viewModelScope.launch {
            val result = try {
                val users = gateway.getTopUsers()
                UsersLoaded(requestId, gateway.sortUsers(users, state.value.sort))
            } catch (e: CoreError) {
                UsersFailed(requestId, e)
            }
            send(result)
        }
    }

    private fun toggle(userId: Long) {
        viewModelScope.launch {
            val result = try {
                FollowToggled(userId, gateway.toggleFollow(userId))
            } catch (e: CoreError) {
                FollowFailed(userId, e)
            }
            send(result)
        }
    }
}
