package io.fastrelay.sdk.model

import kotlinx.serialization.Serializable

@Serializable
data class FastrelayConversationMember(
    val userId: String = "",
    val user: FastrelayUser? = null,
    /** `owner`, `moderator` or `member`. */
    val role: String = "member",
    val joinedAt: String = "",
    /** `banned` or `muted` while restricted. */
    val restriction: String? = null,
    val restrictionExpiresAt: String? = null,
    val lastDeliveredSeq: Long? = null,
    val lastDeliveredAt: String? = null,
    val lastReadSeq: Long? = null,
    val lastReadAt: String? = null,
    val online: Boolean? = null,
    val lastActiveAt: String? = null,
)
