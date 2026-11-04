package io.fastrelay.sdk.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class FastrelayConversation(
    val id: String = "",
    /** `direct` or `group`. */
    val kind: String = "",
    val type: String = "",
    val name: String? = null,
    val image: String? = null,
    val custom: JsonObject = JsonObject(emptyMap()),
    val presenceEnabled: Boolean = false,
    val frozen: Boolean = false,
    val createdBy: String? = null,
    val lastMessageSeq: Long = 0,
    val lastMessageAt: String? = null,
    val truncatedSeq: Long? = null,
    val truncatedAt: String? = null,
    val memberCount: Int = 0,
    /** The first 100 members by join time; omitted in realtime events. Page the rest with `listConversationMembers`. */
    val members: List<FastrelayConversationMember>? = null,
    val unreadCount: Int? = null,
    /** Latest visible message; included in `queryConversations` results only. */
    val lastMessage: FastrelayMessage? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
)
