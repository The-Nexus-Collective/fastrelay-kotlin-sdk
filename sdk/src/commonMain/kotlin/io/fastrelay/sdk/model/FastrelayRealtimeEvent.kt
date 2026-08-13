package io.fastrelay.sdk.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class FastrelayRealtimeEvent(
    val type: String = "",
    val feedId: String = "",
    val eventId: String = "",
    val createdAt: String = "",
    val data: JsonObject? = null,
)
