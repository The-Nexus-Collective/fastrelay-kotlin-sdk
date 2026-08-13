package io.fastrelay.sdk.realtime

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class KtorRealtimeTransport(private val http: HttpClient) : RealtimeTransport {
    override suspend fun connect(url: String): RealtimeConnection =
        KtorRealtimeConnection(http.webSocketSession(url))
}

internal class KtorRealtimeConnection(
    private val session: DefaultClientWebSocketSession,
) : RealtimeConnection {

    override val events: Flow<TransportEvent> = flow {
        try {
            for (frame in session.incoming) {
                if (frame is Frame.Text) {
                    emit(TransportEvent.Message(frame.readText()))
                }
            }
            val reason = session.closeReason.await()
            emit(TransportEvent.Closed(reason?.code?.toInt(), reason?.message))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            emit(TransportEvent.Failure(error))
        }
    }

    override suspend fun send(text: String) {
        session.send(Frame.Text(text))
    }

    override suspend fun close() {
        session.close(CloseReason(CloseReason.Codes.NORMAL, "client disconnect"))
    }
}
