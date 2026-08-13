package io.fastrelay.sdk.internal.request

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class CreateCommentRequest(
    val text: String = "",
    val parentId: String? = null,
    val mentionedUsers: List<String>? = null,
    val custom: JsonObject? = null,
    val userId: String? = null,
)
