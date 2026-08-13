package io.fastrelay.sdk.realtime

import io.fastrelay.sdk.FastrelayApiError
import io.fastrelay.sdk.model.FastrelayRealtimeEvent
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val FAR_FUTURE_EXP = 4_000_000_000L

internal fun jwtWithExp(expEpochSeconds: Long): String {
    val header = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode("""{"alg":"HS256"}""".encodeToByteArray())
    val payload = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode("""{"exp":$expEpochSeconds}""".encodeToByteArray())
    return "$header.$payload.sig"
}

internal class FakeTokens(
    var token: String? = jwtWithExp(FAR_FUTURE_EXP),
    override val canRefresh: Boolean = true,
    var refreshResult: () -> String = { jwtWithExp(FAR_FUTURE_EXP) },
) : RealtimeTokenAccess {
    var refreshCalls = 0

    override fun currentToken(): String? = token

    override suspend fun refresh(failedToken: String?): String {
        refreshCalls++
        val newToken = refreshResult()
        token = newToken
        return newToken
    }
}

private fun TestScope.realtime(
    transport: FakeTransport,
    tokens: FakeTokens = FakeTokens(),
    config: RealtimeConfig = RealtimeConfig(subscribeDebounceMillis = 0),
    nowEpochMillis: Long = 1_754_300_000_000L,
): FastrelayRealtime = FastrelayRealtime(
    scope = backgroundScope,
    transport = transport,
    baseUrl = "http://localhost:8080",
    tokens = tokens,
    config = config,
    random = Random(42),
    nowMillis = { nowEpochMillis },
    logger = {},
)

class FastrelayRealtimeConnectTest {

    @Test
    fun connectReachesConnectedAfterEstablishedFrame() = runTest {
        val transport = FakeTransport()
        val rt = realtime(transport)

        rt.connect()
        runCurrent()
        assertEquals(ConnectionState.Connecting, rt.connectionState.value)

        transport.latest().established("conn_9")
        runCurrent()
        assertEquals(ConnectionState.Connected("conn_9"), rt.connectionState.value)
        assertTrue(transport.latest().url.contains("/v1/realtime?token="))
        assertTrue(transport.latest().url.startsWith("ws://"))
    }

    @Test
    fun serverBasicClientGetsTypedErrorWithoutTransportActivity() = runTest {
        val transport = FakeTransport()
        val rt = realtime(transport, tokens = FakeTokens(token = null, canRefresh = false))

        val error = assertFailsWith<FastrelayApiError> { rt.connect() }

        assertEquals(FastrelayApiError.CODE_REALTIME_REQUIRES_USER_TOKEN, error.code)
        runCurrent()
        assertEquals(0, transport.connections.size)
    }

    @Test
    fun collectorTriggersSubscribeAndRefCountsAndUnsubscribes() = runTest {
        val transport = FakeTransport()
        val rt = realtime(transport)
        rt.connect()
        runCurrent()
        val conn = transport.latest()
        conn.established()
        runCurrent()

        val job1 = rt.eventsForFeed("user:alex").launchIn(backgroundScope)
        runCurrent()
        val job2 = rt.eventsForFeed("user:alex").launchIn(backgroundScope)
        runCurrent()

        assertEquals(1, conn.sent.count { it.contains("subscribe") && it.contains("user:alex") })

        job1.cancel()
        runCurrent()
        assertEquals(0, conn.sent.count { it.contains("unsubscribe") })

        job2.cancel()
        runCurrent()
        assertEquals(1, conn.sent.count { it.contains("unsubscribe") && it.contains("user:alex") })
    }

    @Test
    fun eventsAreDeliveredAndDeduplicatedByEventId() = runTest {
        val transport = FakeTransport()
        val rt = realtime(transport)
        rt.connect()
        runCurrent()
        val conn = transport.latest()
        conn.established()
        runCurrent()

        val received = mutableListOf<FastrelayRealtimeEvent>()
        rt.events.onEach { received += it }.launchIn(backgroundScope)
        runCurrent()

        conn.event("user:alex", eventId = "evt_1")
        conn.event("user:alex", eventId = "evt_1")
        conn.event("user:alex", eventId = "evt_2")
        runCurrent()

        assertEquals(listOf("evt_1", "evt_2"), received.map { it.eventId })
    }

    @Test
    fun subscribingBeyondFeedCapFailsLocallyBeforeAnyFrame() = runTest {
        val transport = FakeTransport()
        val rt = realtime(transport, config = RealtimeConfig(subscribeDebounceMillis = 0, maxFeeds = 2))
        rt.connect()
        runCurrent()
        val conn = transport.latest()
        conn.established()
        runCurrent()

        rt.eventsForFeed("f:1").launchIn(backgroundScope)
        rt.eventsForFeed("f:2").launchIn(backgroundScope)
        runCurrent()
        val framesBefore = conn.sent.size

        var failure: Throwable? = null
        rt.eventsForFeed("f:3").catch { failure = it }.launchIn(backgroundScope)
        runCurrent()

        val error = failure as? FastrelayApiError
        assertEquals(FastrelayApiError.CODE_SUBSCRIPTION_LIMIT, error?.code)
        assertEquals(framesBefore, conn.sent.size)
    }

    @Test
    fun subscribeErrorSurfacesAsTypedDenialWhileConnectionStaysConnected() = runTest {
        val transport = FakeTransport()
        val rt = realtime(transport)
        rt.connect()
        runCurrent()
        val conn = transport.latest()
        conn.established()
        runCurrent()

        var denied: Throwable? = null
        rt.eventsForFeed("secret:feed").catch { denied = it }.launchIn(backgroundScope)
        val okEvents = mutableListOf<String>()
        rt.eventsForFeed("user:alex").onEach { okEvents += it.eventId }.launchIn(backgroundScope)
        runCurrent()

        conn.serverSend(
            """{"type":"subscribe.error","error":{"code":"FEED_SUBSCRIPTION_DENIED","message":"Subscription denied for one or more feeds.","hint":"Denied feeds: secret:feed"}}""",
        )
        runCurrent()
        conn.event("user:alex", eventId = "evt_9")
        runCurrent()

        val error = denied as? FastrelayApiError
        assertEquals("FEED_SUBSCRIPTION_DENIED", error?.code)
        assertIs<ConnectionState.Connected>(rt.connectionState.value)
        assertEquals(listOf("evt_9"), okEvents)
    }
}

class FastrelayRealtimeReconnectTest {

    @Test
    fun transportDropReconnectsWithBackoffAndResubscribes() = runTest {
        val transport = FakeTransport()
        val rt = realtime(transport)
        rt.connect()
        runCurrent()
        transport.latest().established()
        runCurrent()
        rt.eventsForFeed("user:alex").launchIn(backgroundScope)
        runCurrent()

        transport.latest().serverClose(null, "transport drop")
        runCurrent()
        assertIs<ConnectionState.Reconnecting>(rt.connectionState.value)
        assertEquals(1, transport.connections.size)

        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, transport.connections.size)
        transport.latest().established()
        runCurrent()
        assertIs<ConnectionState.Connected>(rt.connectionState.value)
        assertTrue(transport.latest().sent.any { it.contains("subscribe") && it.contains("user:alex") })
    }

    @Test
    fun reconnectSignalsConsumersToRefresh() = runTest {
        val transport = FakeTransport()
        val rt = realtime(transport)
        var refreshSignals = 0
        rt.reconnected.onEach { refreshSignals++ }.launchIn(backgroundScope)

        rt.connect()
        runCurrent()
        transport.latest().established()
        runCurrent()
        assertEquals(1, refreshSignals)

        transport.latest().serverClose(1012, "restart")
        advanceTimeBy(60_000); runCurrent()
        transport.latest().established()
        runCurrent()
        assertEquals(2, refreshSignals)
    }

    @Test
    fun close4003RefreshesTokenViaProviderAndReconnects() = runTest {
        val transport = FakeTransport()
        val tokens = FakeTokens()
        val rt = realtime(transport, tokens = tokens)
        rt.connect()
        runCurrent()
        transport.latest().established()
        runCurrent()

        tokens.refreshResult = { jwtWithExp(FAR_FUTURE_EXP + 1) }
        transport.latest().serverClose(4003, "Token expired")
        advanceTimeBy(60_000); runCurrent()

        assertEquals(1, tokens.refreshCalls)
        assertEquals(2, transport.connections.size)
        assertTrue(transport.latest().url.contains("token="))
    }

    @Test
    fun close4003WithoutProviderFailsTerminally() = runTest {
        val transport = FakeTransport()
        val tokens = object : RealtimeTokenAccess {
            override fun currentToken(): String? = jwtWithExp(FAR_FUTURE_EXP)
            override val canRefresh: Boolean = false
            override suspend fun refresh(failedToken: String?): String =
                throw FastrelayApiError.local(FastrelayApiError.CODE_TOKEN_EXPIRED, "no provider")
        }
        val rt = FastrelayRealtime(
            scope = backgroundScope,
            transport = transport,
            baseUrl = "http://localhost:8080",
            tokens = tokens,
            config = RealtimeConfig(subscribeDebounceMillis = 0),
            random = Random(42),
            nowMillis = { 1_754_300_000_000L },
            logger = {},
        )
        rt.connect()
        runCurrent()
        transport.latest().established()
        runCurrent()

        transport.latest().serverClose(4003, "Token expired")
        advanceTimeBy(60_000); runCurrent()

        val state = rt.connectionState.value
        assertIs<ConnectionState.Failed>(state)
        assertEquals(1, transport.connections.size)
    }

    @Test
    fun expiredTokenIsRefreshedBeforeReconnectAttempt() = runTest {
        val transport = FakeTransport()
        val expiredSoon = jwtWithExp(1_754_300_000L + 10)
        val tokens = FakeTokens(token = expiredSoon)
        val rt = realtime(transport, tokens = tokens, nowEpochMillis = 1_754_300_000_000L)

        rt.connect()
        runCurrent()

        assertEquals(1, tokens.refreshCalls)
        assertEquals(1, transport.connections.size)
    }

    @Test
    fun close4002WithPreviouslyAcceptedTokenRetriesOnceThenTerminal() = runTest {
        val transport = FakeTransport()
        val tokens = FakeTokens()
        val rt = realtime(transport, tokens = tokens)
        rt.connect()
        runCurrent()
        transport.latest().established()
        runCurrent()

        transport.latest().serverClose(4002, "Invalid token")
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, tokens.refreshCalls)
        assertEquals(2, transport.connections.size)

        transport.latest().serverClose(4002, "Invalid token")
        advanceTimeBy(1_000); runCurrent()
        assertIs<ConnectionState.Failed>(rt.connectionState.value)
        assertEquals(2, transport.connections.size)
    }

    @Test
    fun banned4010IsTerminalWithZeroReconnects() = runTest {
        val transport = FakeTransport()
        val rt = realtime(transport)
        rt.connect()
        runCurrent()
        transport.latest().established()
        runCurrent()

        transport.latest().serverClose(4010, "Banned")
        advanceTimeBy(60_000); runCurrent()

        assertIs<ConnectionState.Failed>(rt.connectionState.value)
        assertEquals(1, transport.connections.size)
    }

    @Test
    fun tooManyConnections4029RetriesAfterExtendedDelayNotImmediately() = runTest {
        val transport = FakeTransport()
        val rt = realtime(
            transport,
            config = RealtimeConfig(subscribeDebounceMillis = 0, extendedRetryDelayMillis = 5_000),
        )
        rt.connect()
        runCurrent()
        transport.latest().serverClose(4029, "Too many connections")
        runCurrent()

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, transport.connections.size)

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, transport.connections.size)
    }

    @Test
    fun deadConnection4004ReconnectsNormally() = runTest {
        val transport = FakeTransport()
        val rt = realtime(transport)
        rt.connect()
        runCurrent()
        transport.latest().established()
        runCurrent()

        transport.latest().serverClose(4004, "Dead connection timeout")
        advanceTimeBy(60_000); runCurrent()

        assertEquals(2, transport.connections.size)
    }

    @Test
    fun watchdogForcesReconnectWhenServerGoesSilent() = runTest {
        val transport = FakeTransport()
        val rt = realtime(
            transport,
            config = RealtimeConfig(subscribeDebounceMillis = 0, watchdogToleranceMillis = 60_000, keepaliveIntervalMillis = 600_000),
        )
        rt.connect()
        runCurrent()
        transport.latest().established()
        runCurrent()

        advanceTimeBy(61_000)
        runCurrent()
        advanceTimeBy(60_000); runCurrent()

        assertTrue(transport.connections.size >= 2)
        assertTrue(transport.connections[0].closedByClient)
    }

    @Test
    fun idleConnectionSendsKeepaliveSubscribeForActiveFeeds() = runTest {
        val transport = FakeTransport()
        val rt = realtime(
            transport,
            config = RealtimeConfig(subscribeDebounceMillis = 0, keepaliveIntervalMillis = 60_000, watchdogToleranceMillis = 600_000),
        )
        rt.connect()
        runCurrent()
        val conn = transport.latest()
        conn.established()
        runCurrent()
        rt.eventsForFeed("user:alex").launchIn(backgroundScope)
        runCurrent()
        val subscribesBefore = conn.sent.count { it.contains("\"subscribe\"") }

        advanceTimeBy(61_000)
        runCurrent()

        assertTrue(conn.sent.count { it.contains("\"subscribe\"") } > subscribesBefore)
    }

    @Test
    fun retryCapExhaustionFailsTerminally() = runTest {
        val transport = FakeTransport()
        transport.connectFailures = Int.MAX_VALUE
        val rt = realtime(
            transport,
            config = RealtimeConfig(subscribeDebounceMillis = 0, maxRetries = 3),
        )
        rt.connect()
        advanceTimeBy(60_000); runCurrent()

        assertIs<ConnectionState.Failed>(rt.connectionState.value)
    }
}

class FastrelayRealtimeLifecycleTest {

    @Test
    fun suspendClosesTransportAndResumeReconnectsAndResubscribes() = runTest {
        val transport = FakeTransport()
        val rt = realtime(transport)
        rt.connect()
        runCurrent()
        transport.latest().established()
        runCurrent()
        rt.eventsForFeed("user:alex").launchIn(backgroundScope)
        runCurrent()

        rt.suspend()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(ConnectionState.Suspended, rt.connectionState.value)
        assertTrue(transport.connections[0].closedByClient)

        rt.resume()
        runCurrent()
        assertEquals(2, transport.connections.size)
        transport.latest().established()
        runCurrent()
        assertIs<ConnectionState.Connected>(rt.connectionState.value)
        assertTrue(transport.latest().sent.any { it.contains("subscribe") && it.contains("user:alex") })
    }

    @Test
    fun backgroundingDuringReconnectingParksTheBackoff() = runTest {
        val transport = FakeTransport()
        val rt = realtime(transport)
        rt.connect()
        runCurrent()
        transport.latest().established()
        runCurrent()
        transport.latest().serverClose(null, "drop")
        runCurrent()
        assertIs<ConnectionState.Reconnecting>(rt.connectionState.value)

        rt.suspend()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(ConnectionState.Suspended, rt.connectionState.value)
        assertEquals(1, transport.connections.size)

        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, transport.connections.size)

        rt.resume()
        runCurrent()
        assertEquals(2, transport.connections.size)
    }

    @Test
    fun disconnectDuringReconnectingCancelsPendingBackoff() = runTest {
        val transport = FakeTransport()
        val rt = realtime(transport)
        rt.connect()
        runCurrent()
        transport.latest().established()
        runCurrent()
        transport.latest().serverClose(null, "drop")
        runCurrent()

        rt.disconnect()
        advanceTimeBy(60_000); runCurrent()

        assertEquals(ConnectionState.Disconnected, rt.connectionState.value)
        assertEquals(1, transport.connections.size)
    }

    @Test
    fun redactionKeepsTokenOutOfFailedState() = runTest {
        val transport = FakeTransport()
        transport.connectFailures = Int.MAX_VALUE
        val token = jwtWithExp(FAR_FUTURE_EXP)
        val rt = realtime(
            transport,
            tokens = FakeTokens(token = token),
            config = RealtimeConfig(subscribeDebounceMillis = 0, maxRetries = 1),
        )
        rt.connect()
        advanceTimeBy(60_000); runCurrent()

        val state = rt.connectionState.value
        assertIs<ConnectionState.Failed>(state)
        assertTrue(!state.error.message.contains(token))
    }
}
