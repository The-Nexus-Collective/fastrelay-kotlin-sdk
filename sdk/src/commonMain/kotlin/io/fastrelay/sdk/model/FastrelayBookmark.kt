package io.fastrelay.sdk.model

import kotlinx.serialization.Serializable

@Serializable
data class FastrelayBookmark(
    val id: String = "",
    val activityId: String = "",
    val userId: String = "",
    val createdAt: String = "",
)
