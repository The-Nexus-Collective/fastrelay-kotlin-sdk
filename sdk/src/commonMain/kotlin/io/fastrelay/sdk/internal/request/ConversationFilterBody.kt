package io.fastrelay.sdk.internal.request

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class ConversationFilterBody(
    val kind: String? = null,
    val type: String? = null,
    val members: List<String>? = null,
    val custom: JsonObject? = null,
    val frozen: Boolean? = null,
    val hasUnread: Boolean? = null,
)
