package dev.filip.stackoverflowusers.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import dev.filip.stackoverflowusers.core.CoreError
import java.text.NumberFormat

/** Test tags shared by Robolectric and instrumented tests. */
object Tags {
    const val LOADING = "loading"
    const val ERROR_STATE = "error_state"
    const val EMPTY_STATE = "empty_state"
    const val RETRY = "retry_button"
    const val USER_LIST = "user_list"
    const val SORT_BUTTON = "sort_button"
    fun userRow(id: Long) = "user_row_$id"
    fun followButton(id: Long) = "follow_button_$id"
    fun followedIndicator(id: Long) = "followed_indicator_$id"

    const val DETAIL_NAME = "detail_name"
    const val DETAIL_REPUTATION = "detail_reputation"
    const val DETAIL_LOCATION = "detail_location"
    const val DETAIL_WEBSITE = "detail_website"
    const val DETAIL_FOLLOW = "detail_follow_button"
    const val DETAIL_FOLLOWED_INDICATOR = "detail_followed_indicator"
    const val DETAIL_NOT_FOUND = "detail_not_found"

    fun sortField(name: String) = "sort_field_$name"
    fun sortDirection(name: String) = "sort_direction_$name"
    const val SORT_APPLY = "sort_apply"
    const val SORT_CANCEL = "sort_cancel"
}

@Composable
fun AppTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = lightColorScheme(), content = content)

@Composable
fun Avatar(url: String?, size: Dp, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        placeholder = ColorPainter(Color.LightGray),
        error = ColorPainter(Color.LightGray),
        fallback = ColorPainter(Color.LightGray),
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(CircleShape),
    )
}

fun formatReputation(reputation: Long): String = NumberFormat.getIntegerInstance().format(reputation)

fun CoreError.userMessage(): String = when (this) {
    is CoreError.Network -> "Can't reach the server. Check your connection and try again."
    is CoreError.Http -> "The server returned an error ($code). Please try again."
    is CoreError.Decoding -> "The server sent an unexpected response."
    is CoreError.Storage -> "Couldn't access saved follows. Follow state may have been reset."
}
