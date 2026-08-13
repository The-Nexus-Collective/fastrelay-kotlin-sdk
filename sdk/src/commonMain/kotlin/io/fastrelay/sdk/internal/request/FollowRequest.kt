package io.fastrelay.sdk.internal.request

import kotlinx.serialization.Serializable

@Serializable
internal data class FollowRequest(
    val target: String,
    val activityCopyLimit: Int = 100,
)
