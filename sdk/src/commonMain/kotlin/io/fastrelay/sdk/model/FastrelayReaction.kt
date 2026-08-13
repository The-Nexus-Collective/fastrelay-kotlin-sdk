package io.fastrelay.sdk.model

import kotlinx.serialization.Serializable

@Serializable
data class FastrelayReaction(
    val id: String = "",
    val activityId: String = "",
    val userId: String = "",
    val type: String = "",
    val createdAt: String = "",
    val user: FastrelayUser? = null,
)
