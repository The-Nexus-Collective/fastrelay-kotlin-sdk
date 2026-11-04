package io.fastrelay.sdk.model

import kotlinx.serialization.Serializable

@Serializable
data class FastrelayChatSync(
    /** Pass as `since` on the next `syncChat` call. */
    val serverTime: String = "",
    val conversations: List<FastrelayConversation> = emptyList(),
    /** Messages created, edited or deleted since `since`, outside [reloadConversationIds]. */
    val messages: List<FastrelayMessage> = emptyList(),
    /** Requested conversations the user can no longer access. */
    val removedConversationIds: List<String> = emptyList(),
    /** Conversations with too many changes; reload their history with `listMessages`. */
    val reloadConversationIds: List<String> = emptyList(),
    /** Too many conversations changed; reload everything with `queryConversations`. */
    val reloadAll: Boolean = false,
    val unread: FastrelayUnreadTotals = FastrelayUnreadTotals(),
)
