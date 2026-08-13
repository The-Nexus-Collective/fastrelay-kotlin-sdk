package io.fastrelay.sdk.realtime

import io.fastrelay.sdk.FastrelayApiError
import io.fastrelay.sdk.internal.JwtClaims
import io.fastrelay.sdk.internal.Redaction
import io.fastrelay.sdk.model.FastrelayRealtimeEvent
import io.ktor.http.URLBuilder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class FastrelayRealtime internal constructor(
    private val scope: CoroutineScope,
    private val transport: RealtimeTransport,
    baseUrl: String,
    private val tokens: RealtimeTokenAccess,
    private val config: RealtimeConfig = RealtimeConfig(),
    random: Random = Random.Default,
    private val nowMillis: () -> Long,
    private val logger: (String) -> Unit = {},
) {
    private val wsBase: String = deriveWsBase(baseUrl)
    private val strategy = ReconnectionStrategy(
        initialDelayMillis = config.initialBackoffMillis,
        maxDelayMillis = config.maxBackoffMillis,
        maxRetries = config.maxRetries,
        random = random,
    )
    private val subscriptions = SubscriptionManager(config.maxFeeds)
    private val dedup = EventDeduplicator(config.dedupCapacity)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _events = MutableSharedFlow<FastrelayRealtimeEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<FastrelayRealtimeEvent> = _events

    private val _reconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val reconnected: SharedFlow<Unit> = _reconnected

    private val denials = MutableSharedFlow<String>(extraBufferCapacity = 32)

    private var loopJob: Job? = null
    private var activeConnection: RealtimeConnection? = null
    private var isSuspended = false
    private val pendingSubscribes = mutableSetOf<String>()
    private var flushJob: Job? = null

    fun connect() {
        if (loopJob?.isActive == true) return
        if (tokens.currentToken() == null && !tokens.canRefresh) {
            val error = FastrelayApiError.local(
                code = FastrelayApiError.CODE_REALTIME_REQUIRES_USER_TOKEN,
                message = "Realtime requires a client user token; server basic auth cannot open a WebSocket.",
                hint = "Call connectUser (or configure a tokenProvider) before connecting realtime.",
            )
            _connectionState.value = ConnectionState.Failed(error)
            throw error
        }
        isSuspended = false
        loopJob = scope.launch { runLoop() }
    }

    fun disconnect() {
        isSuspended = false
        stopLoop(ConnectionState.Disconnected)
    }

    fun suspend() {
        val state = _connectionState.value
        if (state == ConnectionState.Disconnected || state is ConnectionState.Failed) return
        isSuspended = true
        stopLoop(ConnectionState.Suspended)
    }

    fun resume() {
        if (!isSuspended) return
        isSuspended = false
        loopJob = scope.launch { runLoop() }
    }

    fun eventsForFeed(feedId: String, type: String? = null): Flow<FastrelayRealtimeEvent> = flow {
        val isFirst = subscriptions.acquire(feedId)
        try {
            if (isFirst) scheduleSubscribe(feedId)
            emitAll(
                merge(
                    events.filter { it.feedId == feedId && (type == null || it.type == type) },
                    denials.filter { it == feedId }.map<String, FastrelayRealtimeEvent> {
                        throw FastrelayApiError.local(
                            code = "FEED_SUBSCRIPTION_DENIED",
                            message = "Subscription to feed '$feedId' was denied.",
                            hint = "The user is not allowed to read this feed.",
                        )
                    },
                ),
            )
        } finally {
            if (subscriptions.release(feedId)) {
                // Still pending means the debounced subscribe never went out — cancel it
                // instead of sending an unsubscribe, or the flush would recreate the
                // subscription server-side with no collector behind it.
                if (!pendingSubscribes.remove(feedId)) {
                    sendFrame(RealtimeMessages.unsubscribeFrame(listOf(feedId)))
                }
            }
        }
    }

    private fun scheduleSubscribe(feedId: String) {
        pendingSubscribes += feedId
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            if (config.subscribeDebounceMillis > 0) delay(config.subscribeDebounceMillis)
            val batch = pendingSubscribes.toSet()
            pendingSubscribes.clear()
            if (batch.isNotEmpty() && _connectionState.value is ConnectionState.Connected) {
                sendFrame(RealtimeMessages.subscribeFrame(batch))
            }
        }
    }

    private fun sendFrame(frame: String) {
        val connection = activeConnection ?: return
        if (_connectionState.value !is ConnectionState.Connected) return
        scope.launch { runCatching { connection.send(frame) } }
    }

    private fun stopLoop(finalState: ConnectionState) {
        loopJob?.cancel()
        loopJob = null
        val connection = activeConnection
        activeConnection = null
        if (connection != null) {
            scope.launch { runCatching { connection.close() } }
        }
        _connectionState.value = finalState
    }

    private sealed interface Decision {
        data class Terminal(val error: FastrelayApiError) : Decision
        data class Refresh(val failedToken: String) : Decision
        data class Reconnect(val extended: Boolean) : Decision
    }

    private class ConnectionOutcome(val decision: Decision, val accepted: Boolean)

    private suspend fun runLoop() {
        var attempt = 0
        var freshToken = false
        while (true) {
            var token = tokens.currentToken()
            if (token == null || JwtClaims.isExpired(token, nowMillis())) {
                if (!tokens.canRefresh) {
                    fail(
                        FastrelayApiError.local(
                            code = FastrelayApiError.CODE_TOKEN_EXPIRED,
                            message = "The user token is expired and no tokenProvider is configured.",
                        ),
                    )
                    return
                }
                _connectionState.value = ConnectionState.RefreshingToken
                token = try {
                    tokens.refresh(token)
                } catch (error: FastrelayApiError) {
                    fail(error)
                    return
                }
                freshToken = true
            }
            _connectionState.value = ConnectionState.Connecting
            val connection = try {
                transport.connect("$wsBase?token=$token")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                attempt++
                if (attempt > config.maxRetries) {
                    fail(networkError(error, token))
                    return
                }
                _connectionState.value = ConnectionState.Reconnecting(attempt)
                delay(strategy.delayFor(attempt))
                continue
            }
            activeConnection = connection
            val outcome = try {
                runConnection(connection, freshToken, token)
            } finally {
                activeConnection = null
            }
            if (outcome.accepted) {
                freshToken = false
                attempt = 0
            }
            when (val decision = outcome.decision) {
                is Decision.Terminal -> {
                    fail(decision.error)
                    return
                }
                is Decision.Refresh -> {
                    _connectionState.value = ConnectionState.RefreshingToken
                    try {
                        tokens.refresh(decision.failedToken)
                    } catch (error: FastrelayApiError) {
                        fail(error)
                        return
                    }
                    freshToken = true
                }
                is Decision.Reconnect -> {
                    attempt++
                    if (attempt > config.maxRetries) {
                        fail(
                            FastrelayApiError.local(
                                code = FastrelayApiError.CODE_NETWORK_ERROR,
                                message = "Realtime reconnection gave up after ${config.maxRetries} attempts.",
                            ),
                        )
                        return
                    }
                    _connectionState.value = ConnectionState.Reconnecting(attempt)
                    val backoff = strategy.delayFor(attempt)
                    delay(if (decision.extended) maxOf(backoff, config.extendedRetryDelayMillis) else backoff)
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun runConnection(
        connection: RealtimeConnection,
        freshToken: Boolean,
        token: String,
    ): ConnectionOutcome {
        var accepted = false
        var decision: Decision = Decision.Reconnect(extended = false)
        coroutineScope {
            val frames = connection.events.produceIn(this)
            var keepaliveJob: Job? = null
            try {
                loop@ while (true) {
                    val event = withTimeoutOrNull(config.watchdogToleranceMillis) {
                        frames.receiveCatching().getOrNull()
                    }
                    if (event == null) {
                        runCatching { connection.close() }
                        decision = Decision.Reconnect(extended = false)
                        break@loop
                    }
                    when (event) {
                        is TransportEvent.Message -> when (val frame = RealtimeMessages.parse(event.text)) {
                            is ServerFrame.Established -> {
                                accepted = true
                                _connectionState.value = ConnectionState.Connected(frame.connectionId)
                                val active = subscriptions.activeFeeds()
                                if (active.isNotEmpty()) {
                                    runCatching { connection.send(RealtimeMessages.subscribeFrame(active)) }
                                }
                                _reconnected.tryEmit(Unit)
                                keepaliveJob = launch { keepaliveLoop(connection) }
                            }
                            is ServerFrame.Event ->
                                // Blank id (frames outside the standard event shape) must not dedup:
                                // every such event would collapse onto the first one seen.
                                if (frame.event.eventId.isBlank() || dedup.isNew(frame.event.eventId)) {
                                    _events.tryEmit(frame.event)
                                }
                            is ServerFrame.SubscribeError ->
                                frame.deniedFeeds.forEach { denials.tryEmit(it) }
                            is ServerFrame.GoingAway -> {
                                decision = Decision.Reconnect(extended = false)
                                runCatching { connection.close() }
                                break@loop
                            }
                            else -> Unit
                        }
                        is TransportEvent.Closed -> {
                                    decision = classify(event.code, event.reason, freshToken && !accepted, token)
                            break@loop
                        }
                        is TransportEvent.Failure -> {
                            decision = Decision.Reconnect(extended = false)
                            break@loop
                        }
                    }
                }
            } finally {
                keepaliveJob?.cancel()
                frames.cancel()
            }
        }
        return ConnectionOutcome(decision, accepted)
    }

    private suspend fun keepaliveLoop(connection: RealtimeConnection) {
        while (true) {
            delay(config.keepaliveIntervalMillis)
            val active = subscriptions.activeFeeds()
            if (active.isNotEmpty()) {
                runCatching { connection.send(RealtimeMessages.subscribeFrame(active)) }
            }
        }
    }

    private fun classify(code: Int?, reason: String?, freshUnacceptedToken: Boolean, token: String): Decision =
        when (code) {
            4003 -> Decision.Refresh(token)
            4002 ->
                if (freshUnacceptedToken) {
                    Decision.Terminal(
                        FastrelayApiError.local(
                            code = FastrelayApiError.CODE_AUTH_EXPIRED,
                            message = "The realtime endpoint rejected a freshly refreshed token.",
                            hint = "Check the app credentials backing the tokenProvider.",
                        ),
                    )
                } else {
                    Decision.Refresh(token)
                }
            4001, 4008, 4010 -> Decision.Terminal(
                FastrelayApiError(
                    status = 0,
                    code = "REALTIME_CLOSED_$code",
                    message = Redaction.redact(reason ?: "Realtime connection closed ($code).", token),
                ),
            )
            4029, 4009 -> Decision.Reconnect(extended = true)
            else -> Decision.Reconnect(extended = false)
        }

    private fun fail(error: FastrelayApiError) {
        logger("fastrelay realtime failed: ${error.code} ${error.message}")
        _connectionState.value = ConnectionState.Failed(error)
    }

    private fun networkError(cause: Throwable, token: String): FastrelayApiError =
        FastrelayApiError.local(
            code = FastrelayApiError.CODE_NETWORK_ERROR,
            message = Redaction.redact(cause.message ?: "Realtime connection failed", token),
        )

    private fun deriveWsBase(baseUrl: String): String {
        val url = URLBuilder(baseUrl).build()
        val scheme = if (url.protocol.name == "https") "wss" else "ws"
        val port = if (url.port != url.protocol.defaultPort) ":${url.port}" else ""
        return "$scheme://${url.host}$port/v1/realtime"
    }
}
