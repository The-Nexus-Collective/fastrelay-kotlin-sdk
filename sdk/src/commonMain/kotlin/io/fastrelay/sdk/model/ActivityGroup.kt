package io.fastrelay.sdk.model

import kotlinx.serialization.Serializable

@Serializable
data class ActivityGroup(
    val groupKey: String = "",
    val activities: List<FastrelayActivity> = emptyList(),
    val activityCount: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = "",
)
