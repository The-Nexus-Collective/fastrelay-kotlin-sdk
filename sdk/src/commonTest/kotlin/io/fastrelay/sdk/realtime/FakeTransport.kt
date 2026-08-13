package io.fastrelay.sdk.realtime

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

internal class FakeTransport : RealtimeTransport {
    val connections = mutableListOf<FakeConnection>()
    var connectFailures = 0

    override suspend fun connect(url: String): RealtimeConnection {
        if (connectFailures > 0) {
            connectFailures--
            throw RuntimeException("simulated network failure")
        }
        return FakeConnection(url).also { connections += it }
    }

    fun latest(): FakeConnection = connections.last()
}

internal class FakeConnection(val url: String) : RealtimeConnection {
    private val channel = Channel<TransportEvent>(Channel.UNLIMITED)
    override val events: Flow<TransportEvent> = channel.receiveAsFlow()
    val sent = mutableListOf<String>()
    var closedByClient = false

    override suspend fun send(text: String) {
        sent += text
    }

    override suspend fun close() {
        closedByClient = true
        channel.trySend(TransportEvent.Closed(1000, "client close"))
        channel.close()
    }

    fun established(connectionId: String = "conn_1") {
        serverSend("""{"type":"connection.established","connectionId":"$connectionId"}""")
    }

    fun heartbeat() {
        serverSend("""{"type":"heartbeat","time":"2026-08-05T10:00:00Z"}""")
    }

    fun serverSend(text: String) {
        channel.trySend(TransportEvent.Message(text))
    }

    fun serverClose(code: Int?, reason: String? = null) {
        channel.trySend(TransportEvent.Closed(code, reason))
        channel.close()
    }

    fun event(feedId: String, type: String = "activity.created", eventId: String, dataJson: String = "{}") {
        serverSend(
            """{"type":"$type","feedId":"$feedId","eventId":"$eventId","createdAt":"2026-08-05T10:00:00Z","data":$dataJson}""",
        )
    }
}
