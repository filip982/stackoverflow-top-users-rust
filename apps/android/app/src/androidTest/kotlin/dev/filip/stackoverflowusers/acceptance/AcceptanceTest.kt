package dev.filip.stackoverflowusers.acceptance

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onChildAt
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import dev.filip.stackoverflowusers.MainActivity
import dev.filip.stackoverflowusers.StackOverflowUsersApp
import dev.filip.stackoverflowusers.ui.Tags
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URI

/**
 * CI-ONLY acceptance suite (emulator job in .github/workflows/linux.yml). Not run locally.
 *
 * Drives the real app + real Rust core (.so from cargo-ndk) against a standalone mock-server
 * on the host, reached from the emulator via 10.0.2.2 (override with the `mockBaseUrl`
 * instrumentation argument). Each test resets the server scenario and starts from cleared follows.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<StackOverflowUsersApp>()
    private val mockBaseUrl: String =
        InstrumentationRegistry.getArguments().getString("mockBaseUrl") ?: "http://10.0.2.2:8080"
    private var activity: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        setScenario("success")
        app.container.resetCore(baseUrl = mockBaseUrl, clearFollows = true)
    }

    @After
    fun tearDown() {
        activity?.close()
        setScenario("success")
    }

    @Test
    fun navigatesFromListToDetailAndBack() {
        launch()
        awaitTag(Tags.USER_LIST)

        compose.onNodeWithTag(Tags.userRow(JON)).performClick()

        awaitTag(Tags.DETAIL_NAME)
        compose.onNodeWithTag(Tags.DETAIL_NAME).assertIsDisplayed()
        compose.onNodeWithText("Location: Reading, United Kingdom").assertIsDisplayed()
        compose.onNodeWithTag(Tags.DETAIL_WEBSITE).assertIsDisplayed()

        compose.onNodeWithContentDescription("Back").performClick()
        awaitTag(Tags.USER_LIST)
    }

    @Test
    fun followPersistsAcrossRelaunchWithAFreshCore() {
        launch()
        awaitTag(Tags.USER_LIST)
        compose.onNodeWithTag(Tags.followButton(JON)).performClick()
        awaitTag(Tags.followedIndicator(JON))

        // Simulate a process restart: close the activity and build a fresh core over the same file.
        activity?.close()
        app.container.resetCore()
        launch()

        awaitTag(Tags.USER_LIST)
        awaitTag(Tags.followedIndicator(JON))
        compose.onNodeWithTag(Tags.userRow(JON)).performClick()
        awaitTag(Tags.DETAIL_FOLLOWED_INDICATOR)
    }

    @Test
    fun sortApplyReordersAndCancelKeepsTheAppliedSort() {
        launch()
        awaitTag(Tags.USER_LIST)
        firstRow().assert(hasTestTag(Tags.userRow(JON)))

        compose.onNodeWithTag(Tags.SORT_BUTTON).performClick()
        awaitTag(Tags.SORT_APPLY)
        compose.onNodeWithTag(Tags.sortField("NAME")).performClick()
        compose.onNodeWithTag(Tags.sortDirection("ASCENDING")).performClick()
        compose.onNodeWithTag(Tags.SORT_APPLY).performClick()

        awaitTag(Tags.USER_LIST)
        compose.waitUntil(TIMEOUT) { runCatching { firstRow().assert(hasTestTag(Tags.userRow(AKRUN))) }.isSuccess }

        compose.onNodeWithTag(Tags.SORT_BUTTON).performClick()
        awaitTag(Tags.SORT_CANCEL)
        compose.onNodeWithTag(Tags.sortField("REPUTATION")).performClick()
        compose.onNodeWithTag(Tags.SORT_CANCEL).performClick()

        awaitTag(Tags.USER_LIST)
        firstRow().assert(hasTestTag(Tags.userRow(AKRUN)))
    }

    @Test
    fun errorStateRetryRecovers() {
        setScenario("error")
        launch()
        awaitTag(Tags.ERROR_STATE)

        setScenario("success")
        compose.onNodeWithTag(Tags.RETRY).performClick()

        awaitTag(Tags.USER_LIST)
        compose.onNodeWithTag(Tags.userRow(JON)).assertIsDisplayed()
    }

    private fun launch() {
        activity = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun firstRow(): SemanticsNodeInteraction = compose.onNodeWithTag(Tags.USER_LIST).onChildAt(0)

    private fun awaitTag(tag: String) = compose.waitUntil(TIMEOUT) {
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    /** Per-instance default scenario on the standalone mock-server (`POST /__scenario`). */
    private fun setScenario(name: String) {
        val connection = URI("$mockBaseUrl/__scenario").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"scenario":"$name"}""".toByteArray()) }
            check(connection.responseCode == 200) { "set scenario $name -> ${connection.responseCode}" }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val JON = 22656L
        const val AKRUN = 3732271L
        const val TIMEOUT = 20_000L
    }
}
