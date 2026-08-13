package io.fastrelay.sdk.internal.request

import kotlinx.serialization.Serializable

@Serializable
internal data class CreateFeedRequest(
    val userId: String? = null,
)
