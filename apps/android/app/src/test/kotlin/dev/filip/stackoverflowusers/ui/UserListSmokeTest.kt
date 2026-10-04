package dev.filip.stackoverflowusers.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.filip.stackoverflowusers.core.User
import dev.filip.stackoverflowusers.testing.FakeCoreGateway
import dev.filip.stackoverflowusers.userlist.UserListScreen
import dev.filip.stackoverflowusers.userlist.UserListStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Compose smoke on Robolectric (pinned: Robolectric 4.14.1, Compose BOM 2024.12.01, SDK 35):
 * real store + screen over a fake gateway fed with the shared core fixture.
 */
@RunWith(RobolectricTestRunner::class)
class UserListSmokeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `list renders fixture users and a follow tap updates the indicator`() {
        val users = fixtureUsers()
        assertEquals(20, users.size)
        val gateway = FakeCoreGateway().apply {
            autoLoad = Result.success(users)
            autoToggle = true
        }
        val store = UserListStore(gateway)
        compose.setContent {
            val state by store.state.collectAsState()
            AppTheme { UserListScreen(state, store::send, onUserClick = {}, onSortClick = {}) }
        }

        compose.onNodeWithText("Jon Skeet").assertIsDisplayed()
        compose.onNodeWithText("Gordon Linoff").assertIsDisplayed()
        compose.onNodeWithTag(Tags.followedIndicator(JON), useUnmergedTree = true).assertDoesNotExist()

        compose.onNodeWithTag(Tags.followButton(JON)).performClick()

        compose.onNodeWithTag(Tags.followedIndicator(JON), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(Tags.followButton(JON)).assertTextEquals("Unfollow")
        assertEquals(setOf(JON), gateway.followed)
    }

    /** `core/rust/fixtures/users.json` (added as a test resource dir); decoding is the core's job, so raw names. */
    private fun fixtureUsers(): List<User> {
        val json = checkNotNull(javaClass.classLoader?.getResource("users.json")).readText()
        val items = JSONObject(json).getJSONArray("items")
        return (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            User(
                id = o.getLong("user_id"),
                displayName = o.getString("display_name"),
                reputation = o.getLong("reputation"),
                avatarUrl = null,
                location = o.optString("location").ifEmpty { null },
                websiteUrl = o.optString("website_url").ifEmpty { null },
                creationDate = o.getLong("creation_date"),
                lastModifiedDate = if (o.has("last_modified_date")) o.getLong("last_modified_date") else null,
            )
        }
    }

    private companion object {
        const val JON = 22656L
    }
}
