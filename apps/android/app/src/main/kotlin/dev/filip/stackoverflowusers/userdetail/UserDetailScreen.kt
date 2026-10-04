package dev.filip.stackoverflowusers.userdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.filip.stackoverflowusers.ui.Avatar
import dev.filip.stackoverflowusers.ui.Tags
import dev.filip.stackoverflowusers.ui.formatReputation
import dev.filip.stackoverflowusers.ui.userMessage
import dev.filip.stackoverflowusers.userlist.FollowButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserDetailScreen(state: UserDetailState, onIntent: (UserDetailIntent) -> Unit, onBack: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.followError) {
        state.followError?.let {
            snackbar.showSnackbar(it.userMessage())
            onIntent(UserDetailIntent.DismissFollowError)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("User details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val user = state.user
        if (user == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("User not available", modifier = Modifier.testTag(Tags.DETAIL_NOT_FOUND))
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(user.avatarUrl, 120.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    user.displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.testTag(Tags.DETAIL_NAME),
                )
                if (state.isFollowed) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = "Followed",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp).size(20.dp).testTag(Tags.DETAIL_FOLLOWED_INDICATOR),
                    )
                }
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Reputation: ${formatReputation(user.reputation)}", Modifier.testTag(Tags.DETAIL_REPUTATION))
                Text("Location: ${user.location ?: "Not specified"}", Modifier.testTag(Tags.DETAIL_LOCATION))
                user.websiteUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    val uriHandler = LocalUriHandler.current
                    Text(
                        url,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .testTag(Tags.DETAIL_WEBSITE)
                            .clickable { runCatching { uriHandler.openUri(url) } },
                    )
                }
            }
            FollowButton(
                followed = state.isFollowed,
                enabled = !state.togglePending,
                modifier = Modifier.fillMaxWidth().testTag(Tags.DETAIL_FOLLOW),
                onClick = { onIntent(UserDetailIntent.ToggleFollow) },
            )
        }
    }
}
