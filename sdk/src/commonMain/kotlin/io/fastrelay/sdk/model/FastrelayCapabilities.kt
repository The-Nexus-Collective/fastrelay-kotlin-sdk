package io.fastrelay.sdk.model

import kotlinx.serialization.Serializable

@Serializable
data class FastrelayCapabilities(
    val canAddActivity: Boolean = false,
    val canDeleteOwnActivity: Boolean = false,
    val canDeleteAnyActivity: Boolean = false,
    val canAddReaction: Boolean = false,
    val canAddComment: Boolean = false,
    val canFollow: Boolean = false,
    val canAddBookmark: Boolean = false,
    val canCreatePoll: Boolean = false,
    val canUploadFile: Boolean = false,
    val canFlagContent: Boolean = false,
    val canBanUser: Boolean = false,
    val canMuteUser: Boolean = false,
    val canReviewFlags: Boolean = false,
)
