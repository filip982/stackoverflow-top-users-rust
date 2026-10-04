package dev.filip.stackoverflowusers.testing

import dev.filip.stackoverflowusers.core.User

object TestUsers {
    val jon = user(22656, "Jon Skeet", 1_520_345, created = 1_222_430_705, modified = 1_727_187_919)
    val gordon = user(1144035, "Gordon Linoff", 1_350_123, created = 1_325_878_478, modified = 1_726_441_261)
    val vonc = user(6309, "VonC", 1_300_000, created = 1_221_000_000, modified = null)
    val all = listOf(jon, gordon, vonc)

    fun user(id: Long, name: String, reputation: Long, created: Long = 0, modified: Long? = null) = User(
        id = id,
        displayName = name,
        reputation = reputation,
        avatarUrl = null,
        location = "Somewhere",
        websiteUrl = null,
        creationDate = created,
        lastModifiedDate = modified,
    )
}
