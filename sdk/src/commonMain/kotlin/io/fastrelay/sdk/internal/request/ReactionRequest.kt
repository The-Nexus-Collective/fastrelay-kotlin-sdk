package io.fastrelay.sdk.internal.request

import kotlinx.serialization.Serializable

@Serializable
internal data class ReactionRequest(
    val type: String,
    val userId: String? = null,
)
