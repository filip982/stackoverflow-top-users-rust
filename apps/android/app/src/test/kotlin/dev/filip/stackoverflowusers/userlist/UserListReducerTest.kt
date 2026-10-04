package dev.filip.stackoverflowusers.userlist

import dev.filip.stackoverflowusers.core.CoreError
import dev.filip.stackoverflowusers.core.SortField
import dev.filip.stackoverflowusers.core.SortSpec
import dev.filip.stackoverflowusers.testing.TestUsers.gordon
import dev.filip.stackoverflowusers.testing.TestUsers.jon
import dev.filip.stackoverflowusers.userlist.UserListIntent.FollowToggled
import dev.filip.stackoverflowusers.userlist.UserListIntent.Load
import dev.filip.stackoverflowusers.userlist.UserListIntent.ToggleFollow
import dev.filip.stackoverflowusers.userlist.UserListIntent.UsersFailed
import dev.filip.stackoverflowusers.userlist.UserListIntent.UsersLoaded
import dev.filip.stackoverflowusers.userlist.UserListIntent.UsersSorted
import dev.filip.stackoverflowusers.userlist.UserListState.Content
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class UserListReducerTest {

    @Test
    fun `load bumps the request id and shows loading`() {
        val state = reduceUserList(UserListState(content = Content.Empty, requestId = 4), Load)
        assertEquals(Content.Loading, state.content)
        assertEquals(5, state.requestId)
    }

    @Test
    fun `completion with an old request id is ignored`() {
        val current = UserListState(requestId = 2)
        assertSame(current, reduceUserList(current, UsersLoaded(requestId = 1, users = listOf(jon))))
        assertSame(current, reduceUserList(current, UsersFailed(requestId = 1, error = CoreError.Http(500))))
    }

    @Test
    fun `empty result is Empty, non-empty is Loaded`() {
        val base = UserListState(requestId = 1)
        assertEquals(Content.Empty, reduceUserList(base, UsersLoaded(1, emptyList())).content)
        assertEquals(Content.Loaded(listOf(jon)), reduceUserList(base, UsersLoaded(1, listOf(jon))).content)
    }

    @Test
    fun `sorted users for a superseded sort are ignored`() {
        val current = UserListState(content = Content.Loaded(listOf(jon, gordon)), sort = SortSpec(SortField.NAME))
        assertSame(current, reduceUserList(current, UsersSorted(SortSpec(), listOf(gordon, jon))))
    }

    @Test
    fun `toggle on a pending user is a no-op and completion clears pending`() {
        val pending = reduceUserList(UserListState(), ToggleFollow(jon.id))
        assertEquals(setOf(jon.id), pending.pendingFollows)
        assertSame(pending, reduceUserList(pending, ToggleFollow(jon.id)))

        val done = reduceUserList(pending, FollowToggled(jon.id, followed = true))
        assertEquals(setOf(jon.id), done.followed)
        assertEquals(emptySet<Long>(), done.pendingFollows)
        assertEquals(emptySet<Long>(), reduceUserList(done, FollowToggled(jon.id, followed = false)).followed)
    }
}
