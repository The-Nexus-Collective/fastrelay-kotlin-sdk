package io.fastrelay.sdk.internal.request

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class UpdateConversationRequest(
    val name: String? = null,
    val image: String? = null,
    val custom: JsonObject? = null,
    val presenceEnabled: Boolean? = null,
    val frozen: Boolean? = null,
)
