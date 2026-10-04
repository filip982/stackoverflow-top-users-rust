package dev.filip.stackoverflowusers.userdetail

import androidx.lifecycle.viewModelScope
import dev.filip.stackoverflowusers.core.CoreError
import dev.filip.stackoverflowusers.core.CoreGateway
import dev.filip.stackoverflowusers.core.User
import dev.filip.stackoverflowusers.mvi.Store
import dev.filip.stackoverflowusers.userdetail.UserDetailIntent.FollowFailed
import dev.filip.stackoverflowusers.userdetail.UserDetailIntent.FollowToggled
import dev.filip.stackoverflowusers.userdetail.UserDetailIntent.FollowsChanged
import dev.filip.stackoverflowusers.userdetail.UserDetailIntent.ToggleFollow
import kotlinx.coroutines.launch

/** Detail screen store; follow state is shared with the list through the core's observer. */
class UserDetailStore(
    user: User?,
    private val gateway: CoreGateway,
) : Store<UserDetailState, UserDetailIntent>(UserDetailState(user)) {

    init {
        if (user != null) {
            viewModelScope.launch { gateway.followUpdates().collect { send(FollowsChanged(it)) } }
            try {
                send(FollowsChanged(gateway.followedIds()))
            } catch (e: CoreError) {
                send(FollowFailed(e))
            }
        }
    }

    override fun reduce(state: UserDetailState, intent: UserDetailIntent) = reduceUserDetail(state, intent)

    override fun onTransition(intent: UserDetailIntent, previous: UserDetailState, current: UserDetailState) {
        val user = current.user
        if (intent == ToggleFollow && current !== previous && user != null) {
            viewModelScope.launch {
                val result = try {
                    FollowToggled(gateway.toggleFollow(user.id))
                } catch (e: CoreError) {
                    FollowFailed(e)
                }
                send(result)
            }
        }
    }
}
