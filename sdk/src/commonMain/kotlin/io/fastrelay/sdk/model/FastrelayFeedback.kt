package io.fastrelay.sdk.model

import kotlinx.serialization.Serializable

@Serializable
data class FastrelayFeedback(
    val id: String = "",
    val activityId: String = "",
    val userId: String = "",
    val type: String = "",
    val createdAt: String = "",
)
