package io.fastrelay.sdk.internal.request

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class UpdateActivityRequest(
    val text: String? = null,
    val custom: JsonObject? = null,
)
