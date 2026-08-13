package io.fastrelay.sdk.internal.request

import kotlinx.serialization.Serializable

@Serializable
internal data class BatchFollowRequest(
    val targets: List<String>,
    val activityCopyLimit: Int = 50,
)
