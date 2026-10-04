package dev.filip.stackoverflowusers.userdetail

import dev.filip.stackoverflowusers.core.CoreGateway
import dev.filip.stackoverflowusers.core.User
import dev.filip.stackoverflowusers.mvi.Store

class UserDetailStore(
    user: User?,
    private val gateway: CoreGateway,
) : Store<UserDetailState, UserDetailIntent>(UserDetailState(user)) {
    override fun reduce(state: UserDetailState, intent: UserDetailIntent) = reduceUserDetail(state, intent)
}
