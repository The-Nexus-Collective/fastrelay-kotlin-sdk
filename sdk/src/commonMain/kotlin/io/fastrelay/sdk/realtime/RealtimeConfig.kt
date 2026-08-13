package io.fastrelay.sdk.realtime

data class RealtimeConfig(
    val initialBackoffMillis: Long = 500,
    val maxBackoffMillis: Long = 30_000,
    val maxRetries: Int = 10,
    val extendedRetryDelayMillis: Long = 5_000,
    val watchdogToleranceMillis: Long = 60_000,
    val keepaliveIntervalMillis: Long = 60_000,
    val subscribeDebounceMillis: Long = 50,
    val dedupCapacity: Int = 1000,
    val maxFeeds: Int = 20,
)
