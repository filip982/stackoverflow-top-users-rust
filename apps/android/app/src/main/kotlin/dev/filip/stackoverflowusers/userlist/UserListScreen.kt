package dev.filip.stackoverflowusers.userlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.filip.stackoverflowusers.core.User
import dev.filip.stackoverflowusers.ui.Avatar
import dev.filip.stackoverflowusers.ui.Tags
import dev.filip.stackoverflowusers.ui.formatReputation
import dev.filip.stackoverflowusers.ui.userMessage
import dev.filip.stackoverflowusers.userlist.UserListState.Content

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserListScreen(
    state: UserListState,
    onIntent: (UserListIntent) -> Unit,
    onUserClick: (Long) -> Unit,
    onSortClick: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.followError) {
        state.followError?.let {
            snackbar.showSnackbar(it.userMessage())
            onIntent(UserListIntent.DismissFollowError)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Top users") },
                actions = {
                    TextButton(onClick = onSortClick, modifier = Modifier.testTag(Tags.SORT_BUTTON)) { Text("Sort") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val content = state.content) {
                Content.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center).testTag(Tags.LOADING))
                is Content.Failed -> Message(
                    tag = Tags.ERROR_STATE,
                    title = "Couldn't load users",
                    body = content.error.userMessage(),
                    onRetry = { onIntent(UserListIntent.Retry) },
                )
                Content.Empty -> Message(
                    tag = Tags.EMPTY_STATE,
                    title = "No users",
                    body = "The server returned no users.",
                    onRetry = { onIntent(UserListIntent.Retry) },
                )
                is Content.Loaded -> LazyColumn(Modifier.fillMaxSize().testTag(Tags.USER_LIST)) {
                    items(content.users, key = { it.id }) { user ->
                        UserRow(
                            user = user,
                            followed = user.id in state.followed,
                            pending = user.id in state.pendingFollows,
                            onToggle = { onIntent(UserListIntent.ToggleFollow(user.id)) },
                            onClick = { onUserClick(user.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun UserRow(user: User, followed: Boolean, pending: Boolean, onToggle: () -> Unit, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(Tags.userRow(user.id))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(user.avatarUrl, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(user.displayName, style = MaterialTheme.typography.titleMedium)
                if (followed) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = "Followed",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp).testTag(Tags.followedIndicator(user.id)),
                    )
                }
            }
            Text("Reputation ${formatReputation(user.reputation)}", style = MaterialTheme.typography.bodyMedium)
        }
        FollowButton(
            followed = followed,
            enabled = !pending,
            modifier = Modifier
                .testTag(Tags.followButton(user.id))
                .semantics { contentDescription = "${if (followed) "Unfollow" else "Follow"} ${user.displayName}" },
            onClick = onToggle,
        )
    }
}

@Composable
fun FollowButton(followed: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (followed) {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text("Unfollow") }
    } else {
        Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text("Follow") }
    }
}

@Composable
private fun Message(tag: String, title: String, body: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).testTag(tag),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry, modifier = Modifier.testTag(Tags.RETRY)) { Text("Retry") }
    }
}
