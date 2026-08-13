package io.fastrelay.sdk.internal.request

import kotlinx.serialization.Serializable

@Serializable
internal data class CreateMuteRequest(
    val userId: String,
    val type: String = "personal",
    val expiresAt: String? = null,
)
