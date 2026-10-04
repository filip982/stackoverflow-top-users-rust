package dev.filip.stackoverflowusers.core

import com.filip982.socore.CoreException
import com.filip982.socore.FollowObserver
import com.filip982.socore.SoCore
import com.filip982.socore.newCore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import com.filip982.socore.SortDirection as FfiSortDirection
import com.filip982.socore.SortField as FfiSortField
import com.filip982.socore.User as FfiUser
import com.filip982.socore.sortUsers as ffiSortUsers

/** [CoreGateway] over the generated UniFFI bindings. Pure type mapping — no logic. */
class RustCoreGateway(private val core: SoCore) : CoreGateway {

    override suspend fun getTopUsers(): List<User> =
        mapErrors { core.getTopUsers().map { it.toApp() } }

    override suspend fun toggleFollow(userId: Long): Boolean =
        mapErrors { core.toggleFollow(userId.toULong()) }

    override fun followedIds(): Set<Long> =
        mapErrors { core.followedIds().mapTo(LinkedHashSet()) { it.toLong() } }

    override fun followUpdates(): Flow<Set<Long>> = callbackFlow {
        val observation = core.addFollowObserver(object : FollowObserver {
            // Called on a core thread; the channel hands the snapshot to the collector's dispatcher.
            override fun onFollowsChanged(followedIds: List<ULong>) {
                trySend(followedIds.mapTo(LinkedHashSet()) { it.toLong() })
            }
        })
        awaitClose {
            observation.dispose()
            observation.close()
        }
    }.buffer(Channel.CONFLATED) // snapshots are complete, so only the latest matters

    override fun sortUsers(users: List<User>, sort: SortSpec): List<User> =
        ffiSortUsers(users.map { it.toFfi() }, sort.field.toFfi(), sort.direction.toFfi()).map { it.toApp() }

    companion object {
        /** `baseUrl` is the API origin; `storagePath` the follow-state file inside the app sandbox. */
        fun create(baseUrl: String, storagePath: String): RustCoreGateway =
            RustCoreGateway(newCore(baseUrl, storagePath))
    }
}

private inline fun <T> mapErrors(block: () -> T): T =
    try {
        block()
    } catch (e: CoreException) {
        throw e.toApp()
    }

internal fun CoreException.toApp(): CoreError = when (this) {
    is CoreException.Network -> CoreError.Network(reason)
    is CoreException.Http -> CoreError.Http(code.toInt())
    is CoreException.Decoding -> CoreError.Decoding(reason)
    is CoreException.Storage -> CoreError.Storage(reason)
}

internal fun FfiUser.toApp() = User(
    id = id.toLong(),
    displayName = displayName,
    reputation = reputation.toLong(),
    avatarUrl = avatarUrl,
    location = location,
    websiteUrl = websiteUrl,
    creationDate = creationDate,
    lastModifiedDate = lastModifiedDate,
)

internal fun User.toFfi() = FfiUser(
    id = id.toULong(),
    displayName = displayName,
    reputation = reputation.toULong(),
    avatarUrl = avatarUrl,
    location = location,
    websiteUrl = websiteUrl,
    creationDate = creationDate,
    lastModifiedDate = lastModifiedDate,
)

internal fun SortField.toFfi() = when (this) {
    SortField.REPUTATION -> FfiSortField.REPUTATION
    SortField.NAME -> FfiSortField.NAME
    SortField.CREATION_DATE -> FfiSortField.CREATION_DATE
    SortField.MODIFIED_DATE -> FfiSortField.MODIFIED_DATE
}

internal fun SortDirection.toFfi() = when (this) {
    SortDirection.ASCENDING -> FfiSortDirection.ASC
    SortDirection.DESCENDING -> FfiSortDirection.DESC
}
