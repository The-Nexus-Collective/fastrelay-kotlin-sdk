package io.fastrelay.sdk.model

import kotlinx.serialization.Serializable

@Serializable
data class FastrelayUserMute(
    val id: String = "",
    val muterId: String? = null,
    val mutedUserId: String = "",
    val type: String = "personal",
    val mutedBy: String? = null,
    val expiresAt: String? = null,
    val createdAt: String = "",
)
