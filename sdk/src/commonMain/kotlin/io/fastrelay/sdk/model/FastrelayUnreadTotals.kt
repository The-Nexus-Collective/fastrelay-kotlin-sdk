package io.fastrelay.sdk.model

import kotlinx.serialization.Serializable

@Serializable
data class FastrelayUnreadTotals(
    val totalUnread: Long = 0,
    val unreadConversations: Long = 0,
)
