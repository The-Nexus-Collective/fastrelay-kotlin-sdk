package io.fastrelay.sdk.realtime

import kotlin.random.Random

internal class ReconnectionStrategy(
    private val initialDelayMillis: Long = 500,
    private val maxDelayMillis: Long = 30_000,
    val maxRetries: Int = 10,
    private val random: Random = Random.Default,
) {
    fun delayFor(attempt: Int): Long {
        val exponential = initialDelayMillis * (1L shl (attempt - 1).coerceIn(0, 20))
        val capped = exponential.coerceAtMost(maxDelayMillis)
        val jitter = (capped * 0.25 * random.nextDouble()).toLong()
        return capped + jitter
    }
}
