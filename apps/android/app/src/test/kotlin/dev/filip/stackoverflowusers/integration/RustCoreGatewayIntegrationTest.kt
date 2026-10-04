package dev.filip.stackoverflowusers.integration

import app.cash.turbine.test
import dev.filip.stackoverflowusers.core.CoreError
import dev.filip.stackoverflowusers.core.RustCoreGateway
import dev.filip.stackoverflowusers.core.SortDirection
import dev.filip.stackoverflowusers.core.SortField
import dev.filip.stackoverflowusers.core.SortSpec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Kotlin ↔ real Rust core: generated UniFFI bindings over JNA loading the host cdylib
 * (`jna.library.path` → core/rust/target/debug), talking to a spawned mock-server process.
 * Run with `./gradlew hostIntegrationTest`.
 */
class RustCoreGatewayIntegrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private val servers = mutableListOf<MockServerProcess>()

    @After
    fun stopServers() = servers.forEach { it.close() }

    private fun server(scenario: String = "success") = MockServerProcess.start(scenario).also { servers += it }

    private fun followsFile() = File(tmp.root, "follows.json")

    private fun gateway(baseUrl: String) = RustCoreGateway.create(baseUrl, followsFile().path)

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { withTimeout(30.seconds) { block() } }

    private inline fun <reified E : CoreError> expectError(block: () -> Unit): E {
        try {
            block()
        } catch (e: CoreError) {
            if (e is E) return e
            fail("expected ${E::class.simpleName} but got $e")
        }
        fail("expected ${E::class.simpleName} but nothing was thrown")
        error("unreachable")
    }

    @Test
    fun `fetch success maps the fixture through the real core`() {
        val server = server()
        val users = blocking { gateway(server.baseUrl).getTopUsers() }

        assertEquals(20, users.size)
        with(users.first()) {
            assertEquals(22656L, id)
            assertEquals("Jon Skeet", displayName)
            assertEquals(1_520_345L, reputation)
            assertEquals("Reading, United Kingdom", location)
            assertEquals("${server.baseUrl}/avatars/22656.png", avatarUrl)
        }
        assertTrue("HTML entities decoded by the core", users.any { it.displayName == "Günter Zöchbauer" })
        assertEquals("reputation descending", users.sortedByDescending { it.reputation }, users)

        val (status, png) = server.get("/avatars/22656.png")
        assertEquals(200, status)
        assertEquals(0x89.toByte(), png.first())
    }

    @Test
    fun `server error maps to typed Http error`() {
        val server = server("error")
        val error = expectError<CoreError.Http> { blocking { gateway(server.baseUrl).getTopUsers() } }
        assertEquals(500, error.code)
    }

    @Test
    fun `connection refused maps to typed Network error`() {
        expectError<CoreError.Network> { blocking { gateway(MockServerProcess.refusedBaseUrl()).getTopUsers() } }
    }

    @Test
    fun `malformed body maps to Decoding and empty is a successful empty list`() {
        expectError<CoreError.Decoding> { blocking { gateway(server("malformed").baseUrl).getTopUsers() } }
        assertEquals(emptyList<Any>(), blocking { gateway(server("empty").baseUrl).getTopUsers() })
    }

    @Test
    fun `retry after scenario switch recovers on the same core instance`() {
        val server = server("error")
        val core = gateway(server.baseUrl)
        expectError<CoreError.Http> { blocking { core.getTopUsers() } }

        assertEquals(200, server.post("/__scenario", """{"scenario":"success"}"""))
        assertEquals(20, blocking { core.getTopUsers() }.size)
    }

    @Test
    fun `follow persists and a fresh core instance reloads it`() {
        val base = MockServerProcess.refusedBaseUrl() // follows never touch the network
        val first = gateway(base)
        assertTrue(blocking { first.toggleFollow(22656) })
        assertTrue(blocking { first.toggleFollow(1144035) })
        assertFalse(blocking { first.toggleFollow(1144035) })
        assertEquals(setOf(22656L), first.followedIds())

        val reloaded = gateway(base)
        assertEquals(setOf(22656L), reloaded.followedIds())
    }

    @Test
    fun `corrupt follow file surfaces Storage once then resets to empty`() {
        followsFile().writeText("{not json")
        val core = gateway(MockServerProcess.refusedBaseUrl())

        expectError<CoreError.Storage> { core.followedIds() }
        assertEquals(emptySet<Long>(), core.followedIds())
    }

    @Test
    fun `observer receives committed snapshots and is disposed on cancellation`() = runBlocking {
        val core = gateway(MockServerProcess.refusedBaseUrl())
        withTimeout(30.seconds) {
            core.followUpdates().test {
                core.toggleFollow(22656)
                assertEquals(setOf(22656L), awaitItem())
                core.toggleFollow(6309)
                assertEquals(setOf(6309L, 22656L), awaitItem())
                core.toggleFollow(22656)
                assertEquals(setOf(6309L), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            // After cancellation (observer disposed), further changes must not break anything.
            assertFalse(core.toggleFollow(6309))
        }
    }

    @Test
    fun `sortUsers runs the core's deterministic sort`() {
        val users = blocking { gateway(server().baseUrl).getTopUsers() }
        val core = gateway(MockServerProcess.refusedBaseUrl())

        val byNameAsc = core.sortUsers(users, SortSpec(SortField.NAME, SortDirection.ASCENDING))
        assertEquals("akrun", byNameAsc.first().displayName)
        assertEquals(users.toSet(), byNameAsc.toSet())

        val byModifiedDesc = core.sortUsers(users, SortSpec(SortField.MODIFIED_DATE, SortDirection.DESCENDING))
        assertEquals("users without a modified date sort last", null, byModifiedDesc.last().lastModifiedDate)
    }
}
