package dev.filip.stackoverflowusers.userlist

import app.cash.turbine.test
import dev.filip.stackoverflowusers.core.CoreError
import dev.filip.stackoverflowusers.core.SortDirection
import dev.filip.stackoverflowusers.core.SortField
import dev.filip.stackoverflowusers.core.SortSpec
import dev.filip.stackoverflowusers.testing.FakeCoreGateway
import dev.filip.stackoverflowusers.testing.MainDispatcherRule
import dev.filip.stackoverflowusers.testing.TestUsers
import dev.filip.stackoverflowusers.testing.TestUsers.gordon
import dev.filip.stackoverflowusers.testing.TestUsers.jon
import dev.filip.stackoverflowusers.testing.TestUsers.vonc
import dev.filip.stackoverflowusers.userlist.UserListIntent.ApplySort
import dev.filip.stackoverflowusers.userlist.UserListIntent.DismissFollowError
import dev.filip.stackoverflowusers.userlist.UserListIntent.Retry
import dev.filip.stackoverflowusers.userlist.UserListIntent.ToggleFollow
import dev.filip.stackoverflowusers.userlist.UserListState.Content
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UserListStoreTest {
    @get:Rule val main = MainDispatcherRule()

    private val gateway = FakeCoreGateway()

    private fun store() = UserListStore(gateway)

    @Test
    fun `starts loading and fetches once on creation`() = runTest {
        val store = store()
        advanceUntilIdle()

        assertEquals(Content.Loading, store.state.value.content)
        assertEquals(1, gateway.loads.size)
    }

    @Test
    fun `success shows users sorted by the core with the current sort`() = runTest {
        val store = store()
        store.state.test {
            assertEquals(Content.Loading, awaitItem().content)
            advanceUntilIdle()
            gateway.completeLoad(0, listOf(gordon, vonc, jon))
            advanceUntilIdle()

            val loaded = expectMostRecentItem()
            assertEquals(Content.Loaded(listOf(jon, gordon, vonc)), loaded.content)
            assertEquals(listOf(SortSpec()), gateway.sortCalls)
        }
    }

    @Test
    fun `empty success is distinct from error`() = runTest {
        val store = store()
        advanceUntilIdle()
        gateway.completeLoad(0, emptyList())
        advanceUntilIdle()

        assertEquals(Content.Empty, store.state.value.content)
    }

    @Test
    fun `failure exposes the typed core error`() = runTest {
        val store = store()
        advanceUntilIdle()
        gateway.failLoad(0, CoreError.Network("offline"))
        advanceUntilIdle()

        assertEquals(Content.Failed(CoreError.Network("offline")), store.state.value.content)
    }

    @Test
    fun `retry after error goes through loading and recovers`() = runTest {
        val store = store()
        advanceUntilIdle()
        gateway.failLoad(0, CoreError.Http(500))
        advanceUntilIdle()

        store.state.test {
            assertEquals(Content.Failed(CoreError.Http(500)), awaitItem().content)
            store.send(Retry)
            assertEquals(Content.Loading, awaitItem().content)
            advanceUntilIdle()
            gateway.completeLoad(1, TestUsers.all)
            advanceUntilIdle()
            assertEquals(Content.Loaded(listOf(jon, gordon, vonc)), awaitItem().content)
        }
    }

    @Test
    fun `stale completion of an earlier request is suppressed - latest request wins`() = runTest {
        val store = store()
        advanceUntilIdle()
        store.send(Retry)
        advanceUntilIdle()
        assertEquals(2, gateway.loads.size)

        gateway.completeLoad(1, listOf(jon))
        advanceUntilIdle()
        gateway.failLoad(0, CoreError.Network("late failure from the first request"))
        advanceUntilIdle()

        assertEquals(Content.Loaded(listOf(jon)), store.state.value.content)
    }

    @Test
    fun `rapid toggles on the same user while one is in flight make a single core call`() = runTest {
        val store = loadedStore()

        repeat(3) { store.send(ToggleFollow(jon.id)) }
        advanceUntilIdle()

        assertEquals(1, gateway.toggles.size)
        assertEquals(setOf(jon.id), store.state.value.pendingFollows)

        gateway.completeToggle(0)
        advanceUntilIdle()
        assertEquals(setOf(jon.id), store.state.value.followed)
        assertTrue(store.state.value.pendingFollows.isEmpty())

        store.send(ToggleFollow(jon.id))
        advanceUntilIdle()
        assertEquals("a new toggle is accepted once the previous one settled", 2, gateway.toggles.size)
    }

    @Test
    fun `toggles for different users are independent`() = runTest {
        val store = loadedStore()

        store.send(ToggleFollow(jon.id))
        store.send(ToggleFollow(gordon.id))
        advanceUntilIdle()

        assertEquals(listOf(jon.id, gordon.id), gateway.toggles.map { it.first })
    }

    @Test
    fun `follow failure is surfaced and leaves follow state unchanged`() = runTest {
        val store = loadedStore()

        store.send(ToggleFollow(jon.id))
        advanceUntilIdle()
        gateway.failToggle(0, CoreError.Storage("disk full"))
        advanceUntilIdle()

        with(store.state.value) {
            assertEquals(CoreError.Storage("disk full"), followError)
            assertTrue(followed.isEmpty())
            assertTrue(pendingFollows.isEmpty())
        }
        store.send(DismissFollowError)
        assertNull(store.state.value.followError)
    }

    @Test
    fun `follow changes committed elsewhere are reflected`() = runTest {
        val store = loadedStore()

        gateway.emitFollows(setOf(gordon.id))
        advanceUntilIdle()

        assertEquals(setOf(gordon.id), store.state.value.followed)
    }

    @Test
    fun `initial follow snapshot is loaded from the core`() = runTest {
        gateway.followed = setOf(vonc.id)
        val store = store()
        advanceUntilIdle()

        assertEquals(setOf(vonc.id), store.state.value.followed)
    }

    @Test
    fun `storage reset on startup is surfaced as a follow error`() = runTest {
        gateway.followedIdsError = CoreError.Storage("corrupt file reset")
        val store = store()
        advanceUntilIdle()

        assertEquals(CoreError.Storage("corrupt file reset"), store.state.value.followError)
        assertTrue(store.state.value.followed.isEmpty())
    }

    @Test
    fun `applying a sort re-sorts loaded users through the core`() = runTest {
        val store = loadedStore()
        val byName = SortSpec(SortField.NAME, SortDirection.ASCENDING)

        store.send(ApplySort(byName))
        advanceUntilIdle()

        with(store.state.value) {
            assertEquals(byName, sort)
            assertEquals(listOf(gordon, jon, vonc), users)
        }
        assertEquals(byName, gateway.sortCalls.last())
    }

    @Test
    fun `sort applied while loading is used for the arriving result`() = runTest {
        val store = store()
        advanceUntilIdle()
        val byName = SortSpec(SortField.NAME, SortDirection.DESCENDING)

        store.send(ApplySort(byName))
        gateway.completeLoad(0, TestUsers.all)
        advanceUntilIdle()

        assertEquals(listOf(vonc, jon, gordon), store.state.value.users)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.loadedStore(): UserListStore {
        val store = store()
        advanceUntilIdle()
        gateway.completeLoad(0, TestUsers.all)
        advanceUntilIdle()
        check(store.state.value.content is Content.Loaded)
        return store
    }
}
