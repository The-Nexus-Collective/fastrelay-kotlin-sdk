package io.fastrelay.sdk.realtime

import io.fastrelay.sdk.FastrelayApiError
import io.fastrelay.sdk.model.FastrelayChatEvent
import io.fastrelay.sdk.model.FastrelayConnectionInfo
import io.fastrelay.sdk.model.FastrelayRealtimeEvent
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive

private const val MESSAGE_DATA =
    """{"message":{"id":"msg_1","conversationId":"cnv_1","seq":7,"type":"regular","silent":false,"userId":"bo","text":"hi","custom":{},"createdAt":"2026-08-05T10:00:00Z","updatedAt":"2026-08-05T10:00:00Z"}}"""

private fun TestScope.chatRealtime(transport: FakeTransport): FastrelayRealtime = FastrelayRealtime(
    scope = backgroundScope,
    transport = transport,
    baseUrl = "http://localhost:8080",
    tokens = FakeTokens(),
    config = RealtimeConfig(subscribeDebounceMillis = 0),
    random = Random(42),
    nowMillis = { 1_754_300_000_000L },
    logger = {},
)

private fun TestScope.connected(transport: FakeTransport): Pair<FastrelayRealtime, FakeConnection> {
    val rt = chatRealtime(transport)
    rt.connect()
    runCurrent()
    val conn = transport.latest()
    conn.established()
    runCurrent()
    return rt to conn
}

class FastrelayRealtimeChatEventsTest {

    @Test
    fun chatEventWithoutFeedIdReachesChatEventsOnly() = runTest {
        // given
        val transport = FakeTransport()
        val (rt, conn) = connected(transport)
        val chat = mutableListOf<FastrelayChatEvent>()
        val feed = mutableListOf<FastrelayRealtimeEvent>()
        rt.chatEvents.onEach { chat += it }.launchIn(backgroundScope)
        rt.events.onEach { feed += it }.launchIn(backgroundScope)
        runCurrent()

        // when
        conn.chatEvent("message.new", eventId = "evt_1", dataJson = MESSAGE_DATA)
        conn.event("user:alex", eventId = "evt_2")
        runCurrent()

        // then
        val event = chat.single()
        assertEquals(FastrelayChatEvent.MESSAGE_NEW, event.type)
        assertEquals("cnv_1", event.conversationId)
        assertEquals("evt_1", event.eventId)
        assertEquals("hi", event.message?.text)
        assertEquals(7L, event.message?.seq)
        assertEquals(listOf("evt_2"), feed.map { it.eventId })
    }

    @Test
    fun duplicateChatEventIdIsDropped() = runTest {
        val transport = FakeTransport()
        val (rt, conn) = connected(transport)
        val chat = mutableListOf<String>()
        rt.chatEvents.onEach { chat += it.eventId }.launchIn(backgroundScope)
        runCurrent()

        conn.chatEvent("typing.start", eventId = "evt_1", dataJson = """{"userId":"bo"}""")
        conn.chatEvent("typing.start", eventId = "evt_1", dataJson = """{"userId":"bo"}""")
        conn.chatEvent("typing.stop", eventId = "evt_2", dataJson = """{"userId":"bo"}""")
        runCurrent()

        assertEquals(listOf("evt_1", "evt_2"), chat)
    }

    @Test
    fun conversationlessEventsAndTypedAccessors() = runTest {
        val transport = FakeTransport()
        val (rt, conn) = connected(transport)
        val chat = mutableListOf<FastrelayChatEvent>()
        rt.chatEvents.onEach { chat += it }.launchIn(backgroundScope)
        runCurrent()

        conn.chatEvent(
            "presence.changed",
            eventId = "evt_1",
            conversationId = null,
            dataJson = """{"userId":"bo","online":false,"lastActiveAt":"2026-08-05T09:59:00Z"}""",
        )
        conn.chatEvent(
            "unread.updated",
            eventId = "evt_2",
            conversationId = null,
            dataJson = """{"totals":{"totalUnread":5,"unreadConversations":2}}""",
        )
        conn.chatEvent(
            "receipt.read",
            eventId = "evt_3",
            dataJson = """{"userId":"bo","lastDeliveredSeq":null,"lastDeliveredAt":null,"lastReadSeq":7,"lastReadAt":"2026-08-05T10:00:00Z"}""",
        )
        conn.chatEvent("member.added", eventId = "evt_4", dataJson = """{"conversation":{"id":"cnv_1","kind":"group","type":"messaging","custom":{},"memberCount":3},"userIds":["cy"]}""")
        runCurrent()

        val (presence, unread, receipt, added) = chat
        assertNull(presence.conversationId)
        assertEquals("bo", presence.userId)
        assertEquals(false, presence.online)
        assertEquals("2026-08-05T09:59:00Z", presence.lastActiveAt)
        assertEquals(5L, unread.totals?.totalUnread)
        assertNull(receipt.lastDeliveredSeq)
        assertEquals(7L, receipt.lastReadSeq)
        assertEquals(3, added.conversation?.memberCount)
        assertNull(added.conversation?.members)
        assertEquals(listOf("cy"), added.userIds)
    }

    @Test
    fun unknownChatTypeIsDeliveredWithRawData() = runTest {
        val transport = FakeTransport()
        val (rt, conn) = connected(transport)
        val chat = mutableListOf<FastrelayChatEvent>()
        rt.chatEvents.onEach { chat += it }.launchIn(backgroundScope)
        runCurrent()

        conn.chatEvent("reaction.added", eventId = "evt_1", dataJson = """{"emoji":"wave"}""")
        runCurrent()

        val event = chat.single()
        assertEquals("reaction.added", event.type)
        assertEquals("wave", event.data["emoji"]?.jsonPrimitive?.content)
        assertNull(event.message)
    }

    @Test
    fun malformedTypedPayloadDoesNotBreakTheSocketLoop() = runTest {
        val transport = FakeTransport()
        val (rt, conn) = connected(transport)
        val chat = mutableListOf<FastrelayChatEvent>()
        rt.chatEvents.onEach { chat += it }.launchIn(backgroundScope)
        runCurrent()

        conn.chatEvent("message.new", eventId = "evt_1", dataJson = """{"message":"not an object"}""")
        conn.serverSend("""{"type":"message.new","eventId":"evt_2","data":"oops"}""")
        conn.chatEvent("message.new", eventId = "evt_3", dataJson = MESSAGE_DATA)
        runCurrent()

        assertEquals(listOf("evt_1", "evt_2", "evt_3"), chat.map { it.eventId })
        assertNull(chat[0].message)
        assertTrue(chat[1].data.isEmpty())
        assertEquals("msg_1", chat[2].message?.id)
        assertTrue(rt.connectionState.value is ConnectionState.Connected)
    }

    @Test
    fun connectionEstablishedExposesServerTimeOnEveryConnect() = runTest {
        val transport = FakeTransport()
        val rt = chatRealtime(transport)
        val infos = mutableListOf<FastrelayConnectionInfo>()
        rt.connectionEstablished.onEach { infos += it }.launchIn(backgroundScope)

        rt.connect()
        runCurrent()
        transport.latest().established("conn_1", serverTime = "2026-08-05T10:00:00Z")
        runCurrent()
        transport.latest().serverClose(1012, "restart")
        advanceTimeBy(60_000); runCurrent()
        transport.latest().established("conn_2", serverTime = "2026-08-05T10:02:00Z")
        runCurrent()

        assertEquals(
            listOf(
                FastrelayConnectionInfo("conn_1", "2026-08-05T10:00:00Z"),
                FastrelayConnectionInfo("conn_2", "2026-08-05T10:02:00Z"),
            ),
            infos,
        )
    }

    @Test
    fun serverErrorFramesSurfaceOnErrors() = runTest {
        val transport = FakeTransport()
        val (rt, conn) = connected(transport)
        val errors = mutableListOf<FastrelayApiError>()
        rt.errors.onEach { errors += it }.launchIn(backgroundScope)
        runCurrent()

        conn.serverSend("""{"type":"error","error":{"code":"NOT_A_MEMBER","message":"Not a member.","hint":"Join first."}}""")
        runCurrent()

        assertEquals("NOT_A_MEMBER", errors.single().code)
        assertEquals("Join first.", errors.single().hint)
        assertTrue(rt.connectionState.value is ConnectionState.Connected)
    }
}

class FastrelayRealtimeChatClientMessagesTest {

    @Test
    fun clientMessagesSerializeExactly() = runTest {
        val transport = FakeTransport()
        val (rt, conn) = connected(transport)
        val before = conn.sent.size

        assertTrue(rt.startTyping("cnv_1"))
        assertTrue(rt.stopTyping("cnv_1"))
        assertTrue(rt.markDelivered("cnv_1", 7))
        assertTrue(rt.markRead("cnv_1", 7))
        assertTrue(rt.markRead("cnv_1"))
        rt.startPresence()
        runCurrent()

        assertEquals(
            listOf(
                """{"type":"typing.start","conversationId":"cnv_1"}""",
                """{"type":"typing.stop","conversationId":"cnv_1"}""",
                """{"type":"message.delivered","conversationId":"cnv_1","seq":7}""",
                """{"type":"conversation.read","conversationId":"cnv_1","seq":7}""",
                """{"type":"conversation.read","conversationId":"cnv_1"}""",
                """{"type":"presence.start"}""",
            ),
            conn.sent.drop(before),
        )
    }

    @Test
    fun fireAndForgetMessagesReturnFalseWhileDisconnected() = runTest {
        val transport = FakeTransport()
        val rt = chatRealtime(transport)

        assertFalse(rt.startTyping("cnv_1"))
        assertFalse(rt.markRead("cnv_1"))

        rt.connect()
        runCurrent()
        // socket open but connection.established not yet received
        assertFalse(rt.stopTyping("cnv_1"))
        assertFalse(rt.markDelivered("cnv_1", 1))
        runCurrent()
        assertTrue(transport.latest().sent.isEmpty())
    }

    @Test
    fun invalidInputIsRejectedLocally() = runTest {
        val transport = FakeTransport()
        val (rt, conn) = connected(transport)
        val before = conn.sent.size

        assertFailsWith<IllegalArgumentException> { rt.startTyping(" ") }
        assertFailsWith<IllegalArgumentException> { rt.markDelivered("cnv_1", 0) }
        assertFailsWith<IllegalArgumentException> { rt.markRead("cnv_1", -1) }
        assertFailsWith<IllegalArgumentException> { rt.markRead("") }
        runCurrent()

        assertEquals(before, conn.sent.size)
    }

    @Test
    fun startPresenceSendsNowAndAfterEveryReconnect() = runTest {
        // given
        val transport = FakeTransport()
        val (rt, conn) = connected(transport)
        val presence = """{"type":"presence.start"}"""

        // when requested while connected
        rt.startPresence()
        runCurrent()

        // then sent immediately
        assertEquals(1, conn.sent.count { it == presence })

        // when the connection drops and comes back
        conn.serverClose(null, "drop")
        advanceTimeBy(60_000); runCurrent()
        transport.latest().established("conn_2")
        runCurrent()

        // then it is re-sent on the new connection
        assertEquals(1, transport.latest().sent.count { it == presence })
    }

    @Test
    fun presenceRequestedBeforeConnectIsSentOnEstablished() = runTest {
        val transport = FakeTransport()
        val rt = chatRealtime(transport)
        rt.startPresence()

        rt.connect()
        runCurrent()
        transport.latest().established()
        runCurrent()

        assertEquals(listOf("""{"type":"presence.start"}"""), transport.latest().sent)
    }

    @Test
    fun resetUserSessionStopsPresenceReplay() = runTest {
        val transport = FakeTransport()
        val (rt, _) = connected(transport)
        rt.startPresence()
        runCurrent()

        rt.disconnect()
        rt.resetUserSession()
        rt.connect()
        runCurrent()
        transport.latest().established("conn_2")
        runCurrent()

        assertTrue(transport.latest().sent.none { it.contains("presence.start") })
    }
}
