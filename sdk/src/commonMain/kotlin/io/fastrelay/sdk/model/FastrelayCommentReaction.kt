package io.fastrelay.sdk.model

import kotlinx.serialization.Serializable

@Serializable
data class FastrelayCommentReaction(
    val id: String = "",
    val commentId: String = "",
    val userId: String = "",
    val type: String = "",
    val createdAt: String = "",
)
