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
}
