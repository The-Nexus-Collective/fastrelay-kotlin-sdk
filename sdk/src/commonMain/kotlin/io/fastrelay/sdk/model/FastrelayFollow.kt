package io.fastrelay.sdk.model

import kotlinx.serialization.Serializable

@Serializable
data class FastrelayFollow(
    val id: String = "",
    val sourceFeed: String = "",
    val targetFeed: String = "",
    val status: String = "active",
    val createdAt: String = "",
)
