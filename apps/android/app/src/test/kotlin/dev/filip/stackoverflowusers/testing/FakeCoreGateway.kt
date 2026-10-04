package dev.filip.stackoverflowusers.testing

import dev.filip.stackoverflowusers.core.CoreError
import dev.filip.stackoverflowusers.core.CoreGateway
import dev.filip.stackoverflowusers.core.SortDirection
import dev.filip.stackoverflowusers.core.SortField
import dev.filip.stackoverflowusers.core.SortSpec
import dev.filip.stackoverflowusers.core.User
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Controllable [CoreGateway]: every fetch/toggle suspends on a [CompletableDeferred] that the
 * test completes explicitly (deferred responses), unless an auto-response is configured.
 */
class FakeCoreGateway(
    var followed: Set<Long> = emptySet(),
) : CoreGateway {
    val loads = mutableListOf<CompletableDeferred<Result<List<User>>>>()
    val toggles = mutableListOf<Pair<Long, CompletableDeferred<Result<Boolean>>>>()
    val sortCalls = mutableListOf<SortSpec>()
    var followedIdsError: CoreError? = null
    var autoLoad: Result<List<User>>? = null
    var autoToggle: Boolean = false
    var observerCount = 0
        private set

    private val updates = MutableSharedFlow<Set<Long>>(extraBufferCapacity = 16)

    override suspend fun getTopUsers(): List<User> {
        autoLoad?.let { return it.getOrThrow() }
        val deferred = CompletableDeferred<Result<List<User>>>()
        loads += deferred
        return deferred.await().getOrThrow()
    }

    override suspend fun toggleFollow(userId: Long): Boolean {
        if (autoToggle) return applyToggle(userId)
        val deferred = CompletableDeferred<Result<Boolean>>()
        toggles += userId to deferred
        return deferred.await().getOrThrow()
    }

    override fun followedIds(): Set<Long> {
        followedIdsError?.let { followedIdsError = null; throw it }
        return followed
    }

    override fun followUpdates(): Flow<Set<Long>> {
        observerCount++
        return updates
    }

    override fun sortUsers(users: List<User>, sort: SortSpec): List<User> {
        sortCalls += sort
        val comparator: Comparator<User> = when (sort.field) {
            SortField.REPUTATION -> compareBy { it.reputation }
            SortField.NAME -> compareBy { it.displayName.lowercase() }
            SortField.CREATION_DATE -> compareBy { it.creationDate }
            SortField.MODIFIED_DATE -> compareBy { it.lastModifiedDate ?: Long.MAX_VALUE }
        }
        return users.sortedWith(if (sort.direction == SortDirection.ASCENDING) comparator else comparator.reversed())
    }

    // --- test controls -------------------------------------------------------------------------

    fun completeLoad(index: Int, users: List<User>) = loads[index].complete(Result.success(users))
    fun failLoad(index: Int, error: CoreError) = loads[index].complete(Result.failure(error))

    /** Completes a pending toggle the way the real core would (persist, then notify observers). */
    fun completeToggle(index: Int): Boolean {
        val (id, deferred) = toggles[index]
        deferred.complete(Result.success(applyToggle(id)))
        return id in followed
    }

    fun failToggle(index: Int, error: CoreError) = toggles[index].second.complete(Result.failure(error))

    /** Simulates a change committed elsewhere (e.g. from another screen). */
    fun emitFollows(ids: Set<Long>) {
        followed = ids
        check(updates.tryEmit(ids))
    }

    private fun applyToggle(id: Long): Boolean {
        followed = if (id in followed) followed - id else followed + id
        updates.tryEmit(followed)
        return id in followed
    }
}
