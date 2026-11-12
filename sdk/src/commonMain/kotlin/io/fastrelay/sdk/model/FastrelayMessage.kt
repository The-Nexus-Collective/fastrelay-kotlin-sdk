package io.fastrelay.sdk.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A chat message. System messages (`type == "system"`) have no [userId] or [text]; their
 * [custom] carries `{event, actorId, userIds}`.
 */
@Serializable
data class FastrelayMessage(
    val id: String = "",
    val conversationId: String = "",
    val seq: Long = 0,
    /** `regular` or `system`. */
    val type: String = "regular",
    val silent: Boolean = false,
    val userId: String? = null,
    val text: String? = null,
    val custom: JsonObject = JsonObject(emptyMap()),
    val replyToId: String? = null,
    val replyTo: FastrelayReplyPreview? = null,
    /**
     * Reaction type to the ids of the first 100 users who added it, in the order they reacted. Page
     * the rest with `listMessageReactionUsers` when [reactionCounts] is higher.
     */
    val reactions: Map<String, List<String>> = emptyMap(),
    /** Reaction type to the total number of users who added it. */
    val reactionCounts: Map<String, Int> = emptyMap(),
    val editedAt: String? = null,
    val deletedAt: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
)

@Serializable
data class FastrelayReplyPreview(
    val id: String = "",
    val seq: Long? = null,
    val userId: String? = null,
    val text: String? = null,
    val deleted: Boolean = false,
)
