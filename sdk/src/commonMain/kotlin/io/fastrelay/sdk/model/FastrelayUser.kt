package io.fastrelay.sdk.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class FastrelayUser(
    val id: String = "",
    val displayName: String? = null,
    val profileData: JsonObject? = null,
    val role: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
)
