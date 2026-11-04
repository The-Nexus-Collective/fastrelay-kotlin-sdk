package io.fastrelay.sdk.internal.request

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class SendMessageRequest(
    val text: String? = null,
    val custom: JsonObject? = null,
    val replyToId: String? = null,
    val silent: Boolean? = null,
)
