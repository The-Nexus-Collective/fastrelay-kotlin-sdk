package io.fastrelay.sdk.realtime

import kotlinx.coroutines.flow.Flow

internal sealed interface TransportEvent {
    data class Message(val text: String) : TransportEvent
    data class Closed(val code: Int?, val reason: String?) : TransportEvent
    data class Failure(val cause: Throwable) : TransportEvent
}

internal interface RealtimeConnection {
    val events: Flow<TransportEvent>
    suspend fun send(text: String)
    suspend fun close()
}

internal interface RealtimeTransport {
    suspend fun connect(url: String): RealtimeConnection
}
