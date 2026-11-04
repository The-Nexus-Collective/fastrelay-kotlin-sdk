package io.fastrelay.sdk.model

/**
 * Payload of the realtime `connection.established` frame, emitted on every successful (re)connect.
 * [serverTime] is the anchor to pass as `since` to `syncChat` after the next reconnect.
 */
data class FastrelayConnectionInfo(
    val connectionId: String?,
    val serverTime: String?,
)
