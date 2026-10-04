package dev.filip.stackoverflowusers.core

import kotlinx.coroutines.flow.Flow

/**
 * The app's only view of the Rust core. Stores depend on this interface; production uses
 * [RustCoreGateway] (generated UniFFI bindings), tests use fakes. Deliberately mirrors the
 * FFI contract one-to-one — no business logic lives on this side.
 */
interface CoreGateway {
    /** Top users in the core's default order. Throws [CoreError]. */
    suspend fun getTopUsers(): List<User>

    /** Flips the follow state; returns the new state (`true` = followed). Throws [CoreError]. */
    suspend fun toggleFollow(userId: Long): Boolean

    /** Snapshot of followed ids. Throws [CoreError.Storage] once after a corrupt store was reset. */
    fun followedIds(): Set<Long>

    /** Every committed follow change as a full snapshot. Collecting registers a core observer; cancelling disposes it. */
    fun followUpdates(): Flow<Set<Long>>

    /** Deterministic client-side sort, performed by the core. */
    fun sortUsers(users: List<User>, sort: SortSpec): List<User>
}

/** App-side immutable mirror of the core `User` record (dates are epoch seconds). */
data class User(
    val id: Long,
    val displayName: String,
    val reputation: Long,
    val avatarUrl: String?,
    val location: String?,
    val websiteUrl: String?,
    val creationDate: Long,
    val lastModifiedDate: Long?,
)

enum class SortField { REPUTATION, NAME, CREATION_DATE, MODIFIED_DATE }

enum class SortDirection { ASCENDING, DESCENDING }

data class SortSpec(
    val field: SortField = SortField.REPUTATION,
    val direction: SortDirection = SortDirection.DESCENDING,
)

/** Typed mirror of the core's `CoreError`. */
sealed class CoreError : Exception() {
    data class Network(val detail: String) : CoreError()
    data class Http(val code: Int) : CoreError()
    data class Decoding(val detail: String) : CoreError()
    data class Storage(val detail: String) : CoreError()
}
