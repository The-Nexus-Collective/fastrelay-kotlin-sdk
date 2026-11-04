package io.fastrelay.sdk.model

import io.fastrelay.sdk.internal.FastrelayJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * A realtime chat event. Unlike [FastrelayRealtimeEvent] it is not tied to a feed: it reaches every
 * connection of the recipient users without a subscription.
 *
 * [data] always holds the raw payload, so event types this SDK version doesn't know are still
 * delivered. The typed accessors decode the payloads of the known types and return null when the
 * field is absent or malformed.
 */
data class FastrelayChatEvent(
    val type: String,
    /** Absent for `presence.changed` and `unread.updated`. */
    val conversationId: String? = null,
    val eventId: String = "",
    val createdAt: String = "",
    val data: JsonObject = JsonObject(emptyMap()),
) {
    /** `message.new`, `message.updated`, `message.deleted`. */
    val message: FastrelayMessage? get() = decode("message", FastrelayMessage.serializer())

    /** `conversation.updated`, `conversation.truncated`, `member.added` (without `members`). */
    val conversation: FastrelayConversation? get() = decode("conversation", FastrelayConversation.serializer())

    /** `member.updated`. */
    val member: FastrelayConversationMember? get() = decode("member", FastrelayConversationMember.serializer())

    /** `member.removed`, `receipt.*`, `typing.*`, `presence.changed`. */
    val userId: String? get() = decode("userId", String.serializer())

    /** `member.added`. */
    val userIds: List<String>? get() = decode("userIds", ListSerializer(String.serializer()))

    /** `presence.changed`. */
    val online: Boolean? get() = decode("online", Boolean.serializer())

    /** `presence.changed`. */
    val lastActiveAt: String? get() = decode("lastActiveAt", String.serializer())

    /** `receipt.delivered`, `receipt.read`. */
    val lastDeliveredSeq: Long? get() = decode("lastDeliveredSeq", Long.serializer())

    /** `receipt.delivered`, `receipt.read`. */
    val lastDeliveredAt: String? get() = decode("lastDeliveredAt", String.serializer())

    /** `receipt.delivered`, `receipt.read`. */
    val lastReadSeq: Long? get() = decode("lastReadSeq", Long.serializer())

    /** `receipt.delivered`, `receipt.read`. */
    val lastReadAt: String? get() = decode("lastReadAt", String.serializer())

    /** `unread.updated`. */
    val totals: FastrelayUnreadTotals? get() = decode("totals", FastrelayUnreadTotals.serializer())

    private fun <T> decode(key: String, serializer: KSerializer<T>): T? {
        val element: JsonElement = data[key] ?: return null
        if (element is JsonNull) return null
        return runCatching { FastrelayJson.decodeFromJsonElement(serializer, element) }.getOrNull()
    }

    companion object {
        const val MESSAGE_NEW = "message.new"
        const val MESSAGE_UPDATED = "message.updated"
        const val MESSAGE_DELETED = "message.deleted"
        const val CONVERSATION_UPDATED = "conversation.updated"
        const val CONVERSATION_TRUNCATED = "conversation.truncated"
        const val CONVERSATION_DELETED = "conversation.deleted"
        const val MEMBER_ADDED = "member.added"
        const val MEMBER_REMOVED = "member.removed"
        const val MEMBER_UPDATED = "member.updated"
        const val RECEIPT_DELIVERED = "receipt.delivered"
        const val RECEIPT_READ = "receipt.read"
        const val TYPING_START = "typing.start"
        const val TYPING_STOP = "typing.stop"
        const val PRESENCE_CHANGED = "presence.changed"
        const val UNREAD_UPDATED = "unread.updated"
    }
}
