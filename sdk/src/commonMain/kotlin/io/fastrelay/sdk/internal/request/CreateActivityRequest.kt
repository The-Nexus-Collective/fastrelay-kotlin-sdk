package io.fastrelay.sdk.internal.request

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class CreateActivityRequest(
    val id: String? = null,
    val type: String,
    val text: String? = null,
    val userId: String? = null,
    val feeds: List<String>,
    val custom: JsonObject? = null,
    val visibility: String = "public",
    val expiresAt: String? = null,
)
