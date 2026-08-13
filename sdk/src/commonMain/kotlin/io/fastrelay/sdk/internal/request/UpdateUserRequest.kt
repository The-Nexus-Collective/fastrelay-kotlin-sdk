package io.fastrelay.sdk.internal.request

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class UpdateUserRequest(
    val displayName: String? = null,
    val profileData: JsonObject? = null,
    val role: String? = null,
)
