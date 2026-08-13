package io.fastrelay.sdk.internal.request

import kotlinx.serialization.Serializable

@Serializable
internal data class FeedSettingsRequest(
    val followApproval: String? = null,
)
