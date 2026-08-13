package io.fastrelay.sdk.model

import kotlinx.serialization.Serializable

@Serializable
data class FastrelayFeedMember(
    val feedId: String = "",
    val userId: String = "",
    val role: String = "member",
    val createdAt: String = "",
)
