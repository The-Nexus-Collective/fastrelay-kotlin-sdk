package io.fastrelay.sdk.model

import kotlinx.serialization.Serializable

@Serializable
data class FastrelayModerationFlag(
    val id: String = "",
    val reporterId: String = "",
    val targetType: String = "",
    val targetId: String = "",
    val reason: String = "",
    val description: String? = null,
    val status: String = "pending",
    val resolvedBy: String? = null,
    val resolvedAt: String? = null,
    val createdAt: String = "",
)
