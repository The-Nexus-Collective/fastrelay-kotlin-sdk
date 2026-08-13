package io.fastrelay.sdk.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class FastrelayFile(
    val id: String = "",
    val url: String = "",
    val type: String = "",
    val mimeType: String = "",
    val size: Long = 0,
    val metadata: JsonObject? = null,
    val createdAt: String = "",
)
