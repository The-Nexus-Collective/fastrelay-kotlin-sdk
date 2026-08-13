package io.fastrelay.sdk

import io.fastrelay.sdk.model.CursorPage
import io.fastrelay.sdk.model.FastrelayFollow
import io.fastrelay.sdk.model.FeedActivitiesPage
import kotlin.coroutines.cancellation.CancellationException

class FastrelayFeed internal constructor(
    private val client: FastrelayClient,
    val group: String,
    val id: String,
) {
    val feedId: String get() = "$group:$id"

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun getActivities(
        limit: Int = 25,
        cursor: String? = null,
        view: String? = null,
        markSeen: Boolean? = null,
        markRead: String? = null,
        filters: Map<String, String> = emptyMap(),
    ): FeedActivitiesPage =
        client.getFeedActivities(group, id, limit, cursor, view, markSeen, markRead, filters)

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun follow(targetFeedId: String, activityCopyLimit: Int = 100): FastrelayFollow =
        client.followFeed(group, id, targetFeedId, activityCopyLimit)

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun unfollow(targetFeedId: String, keepHistory: Boolean = false) =
        client.unfollowFeed(group, id, targetFeedId, keepHistory)

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun followers(limit: Int = 25, cursor: String? = null): CursorPage<FastrelayFollow> =
        client.listFollowers(group, id, limit, cursor)

    @Throws(FastrelayApiError::class, CancellationException::class)
    suspend fun following(limit: Int = 25, cursor: String? = null): CursorPage<FastrelayFollow> =
        client.listFollowing(group, id, limit, cursor)

    override fun toString(): String = "FastrelayFeed($feedId)"
}
