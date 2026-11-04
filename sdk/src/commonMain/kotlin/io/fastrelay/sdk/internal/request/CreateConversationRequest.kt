package io.fastrelay.sdk.internal.request

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class CreateConversationRequest(
    val kind: String,
    val type: String? = null,
    val members: List<String>,
    val name: String? = null,
    val image: String? = null,
    val custom: JsonObject? = null,
    val presenceEnabled: Boolean? = null,
)
