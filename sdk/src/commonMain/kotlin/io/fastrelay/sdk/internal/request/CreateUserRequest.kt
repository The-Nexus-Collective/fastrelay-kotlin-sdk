package io.fastrelay.sdk.internal.request

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class CreateUserRequest(
    val id: String? = null,
    val displayName: String? = null,
    val profileData: JsonObject? = null,
    val role: String = "user",
)
