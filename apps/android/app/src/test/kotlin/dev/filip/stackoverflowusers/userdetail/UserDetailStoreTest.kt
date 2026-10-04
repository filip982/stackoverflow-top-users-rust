package dev.filip.stackoverflowusers.userdetail

import app.cash.turbine.test
import dev.filip.stackoverflowusers.core.CoreError
import dev.filip.stackoverflowusers.testing.FakeCoreGateway
import dev.filip.stackoverflowusers.testing.MainDispatcherRule
import dev.filip.stackoverflowusers.testing.TestUsers.gordon
import dev.filip.stackoverflowusers.testing.TestUsers.jon
import dev.filip.stackoverflowusers.userdetail.UserDetailIntent.DismissFollowError
import dev.filip.stackoverflowusers.userdetail.UserDetailIntent.ToggleFollow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UserDetailStoreTest {
    @get:Rule val main = MainDispatcherRule()

    private val gateway = FakeCoreGateway()

    @Test
    fun `shows the user with the follow state from the core snapshot`() = runTest {
        gateway.followed = setOf(jon.id)
        val store = UserDetailStore(jon, gateway)
        advanceUntilIdle()

        assertEquals(UserDetailState(user = jon, isFollowed = true), store.state.value)
    }

    @Test
    fun `toggle goes pending then reflects the core result`() = runTest {
        val store = UserDetailStore(jon, gateway)
        advanceUntilIdle()

        store.state.test {
            assertFalse(awaitItem().isFollowed)
            store.send(ToggleFollow)
            assertTrue(awaitItem().togglePending)
            advanceUntilIdle()
            gateway.completeToggle(0)
            advanceUntilIdle()
            val settled = expectMostRecentItem()
            assertTrue(settled.isFollowed)
            assertFalse(settled.togglePending)
        }
    }

    @Test
    fun `rapid toggles while pending make a single core call`() = runTest {
        val store = UserDetailStore(jon, gateway)
        advanceUntilIdle()

        repeat(4) { store.send(ToggleFollow) }
        advanceUntilIdle()

        assertEquals(listOf(jon.id), gateway.toggles.map { it.first })
    }

    @Test
    fun `follow failure is surfaced and dismissable`() = runTest {
        val store = UserDetailStore(jon, gateway)
        advanceUntilIdle()

        store.send(ToggleFollow)
        advanceUntilIdle()
        gateway.failToggle(0, CoreError.Storage("read-only"))
        advanceUntilIdle()

        with(store.state.value) {
            assertEquals(CoreError.Storage("read-only"), followError)
            assertFalse(isFollowed)
            assertFalse(togglePending)
        }
        store.send(DismissFollowError)
        assertNull(store.state.value.followError)
    }

    @Test
    fun `stays in sync with follow changes made on another screen`() = runTest {
        val store = UserDetailStore(jon, gateway)
        advanceUntilIdle()

        gateway.emitFollows(setOf(gordon.id, jon.id))
        advanceUntilIdle()
        assertTrue(store.state.value.isFollowed)

        gateway.emitFollows(setOf(gordon.id))
        advanceUntilIdle()
        assertFalse(store.state.value.isFollowed)
    }

    @Test
    fun `unknown user ignores toggles and never calls the core`() = runTest {
        val store = UserDetailStore(null, gateway)
        store.send(ToggleFollow)
        advanceUntilIdle()

        assertEquals(UserDetailState(user = null), store.state.value)
        assertTrue(gateway.toggles.isEmpty())
    }
}
