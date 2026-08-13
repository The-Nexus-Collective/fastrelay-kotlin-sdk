package io.fastrelay.sdk.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class FastrelayActivity(
    val id: String = "",
    val type: String = "",
    val text: String? = null,
    val userId: String = "",
    val feeds: List<String> = emptyList(),
    val visibility: String? = null,
    val custom: JsonObject? = null,
    val popularity: Double = 0.0,
    val reactionCounts: Map<String, Int> = emptyMap(),
    val commentCount: Int = 0,
    val bookmarkCount: Int = 0,
    val expiresAt: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    val ownReactions: List<FastrelayReaction>? = null,
    val pinned: Boolean = false,
    val user: FastrelayUser? = null,
)
