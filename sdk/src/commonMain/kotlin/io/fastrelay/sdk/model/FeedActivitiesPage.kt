package io.fastrelay.sdk.model

import kotlinx.serialization.Serializable

@Serializable
data class FeedActivitiesPage(
    val data: List<FastrelayActivity>? = null,
    val groups: List<ActivityGroup>? = null,
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val unseenCount: Int? = null,
    val unreadCount: Int? = null,
    val pinned: List<FastrelayActivity>? = null,
)
