package io.fastrelay.sdk.realtime

import io.fastrelay.sdk.FastrelayApiError

internal class SubscriptionManager(private val maxFeeds: Int = 20) {
    private val refCounts = mutableMapOf<String, Int>()

    fun acquire(feedId: String): Boolean {
        val current = refCounts[feedId]
        if (current == null && refCounts.size >= maxFeeds) {
            throw FastrelayApiError.local(
                code = FastrelayApiError.CODE_SUBSCRIPTION_LIMIT,
                message = "At most $maxFeeds feed subscriptions are allowed per connection.",
                hint = "Unsubscribe from feeds you no longer display before subscribing to new ones.",
            )
        }
        refCounts[feedId] = (current ?: 0) + 1
        return current == null
    }

    fun release(feedId: String): Boolean {
        val current = refCounts[feedId] ?: return false
        return if (current <= 1) {
            refCounts.remove(feedId)
            true
        } else {
            refCounts[feedId] = current - 1
            false
        }
    }

    fun activeFeeds(): Set<String> = refCounts.keys.toSet()
}
