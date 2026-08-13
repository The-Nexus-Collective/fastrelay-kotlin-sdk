package io.fastrelay.sdk.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class FastrelayComment(
    val id: String = "",
    val activityId: String = "",
    val userId: String = "",
    val text: String = "",
    val parentId: String? = null,
    val mentionedUsers: List<String> = emptyList(),
    val reactionCounts: Map<String, Int> = emptyMap(),
    val custom: JsonObject? = null,
    val score: Double = 0.0,
    val createdAt: String = "",
    val updatedAt: String = "",
    val user: FastrelayUser? = null,
)
