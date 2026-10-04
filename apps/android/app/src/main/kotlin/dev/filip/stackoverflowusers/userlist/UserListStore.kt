package dev.filip.stackoverflowusers.userlist

import dev.filip.stackoverflowusers.core.CoreGateway
import dev.filip.stackoverflowusers.mvi.Store

class UserListStore(
    private val gateway: CoreGateway,
) : Store<UserListState, UserListIntent>(UserListState()) {
    override fun reduce(state: UserListState, intent: UserListIntent) = reduceUserList(state, intent)
}
