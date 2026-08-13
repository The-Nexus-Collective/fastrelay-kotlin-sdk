package io.fastrelay.sdk.model

import kotlinx.serialization.Serializable

@Serializable
data class FastrelayFeedActivityPin(
    val feedId: String = "",
    val activityId: String = "",
    val pinnedAt: String = "",
    val pinnedBy: String = "",
)
