package io.fastrelay.sdk.realtime

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RealtimeMessagesTest {

    @Test
    fun unknownFrameTypeKeepsFullPayloadInData() {
        // given a frame shape the event model doesn't declare
        val frame = RealtimeMessages.parse(
            """{"type":"video.ready","videoId":"vid_1","video":{"status":"ready"}}""",
        )

        // then the payload survives in data instead of being silently dropped
        val event = assertIs<ServerFrame.Event>(frame).event
        assertEquals("video.ready", event.type)
        assertEquals("vid_1", event.data?.get("videoId")?.jsonPrimitive?.content)
    }

    @Test
    fun framesWithoutFeedIdAreChatAndFramesWithFeedIdAreFeedEvents() {
        val chat = RealtimeMessages.parse(
            """{"type":"message.new","conversationId":"cnv_1","eventId":"evt_1","createdAt":"2026-11-04T10:00:00Z","data":{}}""",
        )
        val feed = RealtimeMessages.parse(
            """{"type":"activity.created","feedId":"user:alex","eventId":"evt_2","createdAt":"2026-11-04T10:00:00Z","data":{}}""",
        )
        val video = RealtimeMessages.parse("""{"type":"video.failed","videoId":"vid_1","errorCode":"X"}""")

        assertEquals("cnv_1", assertIs<ServerFrame.Chat>(chat).event.conversationId)
        assertEquals("user:alex", assertIs<ServerFrame.Event>(feed).event.feedId)
        assertIs<ServerFrame.Event>(video)
    }

    @Test
    fun connectionEstablishedCarriesServerTime() {
        val frame = RealtimeMessages.parse(
            """{"type":"connection.established","connectionId":"conn_1","serverTime":"2026-11-04T10:00:00Z"}""",
        )

        assertEquals(ServerFrame.Established("conn_1", "2026-11-04T10:00:00Z"), frame)
    }

    @Test
    fun nonStringTypeIsUnknownInsteadOfThrowing() {
        assertIs<ServerFrame.Unknown>(RealtimeMessages.parse("""{"type":{"nested":true}}"""))
    }
}
