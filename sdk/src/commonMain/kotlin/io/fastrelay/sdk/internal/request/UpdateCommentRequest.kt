package io.fastrelay.sdk.internal.request

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class UpdateCommentRequest(
    val text: String = "",
    val custom: JsonObject? = null,
)
