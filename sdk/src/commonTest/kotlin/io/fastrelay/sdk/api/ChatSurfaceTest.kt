package io.fastrelay.sdk.api

import io.fastrelay.sdk.FastrelayClient
import io.fastrelay.sdk.FastrelayRequestOptions
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private const val CONVERSATION_JSON =
    """{"id":"cnv_1","kind":"group","type":"messaging","custom":{},"presenceEnabled":false,"frozen":false,"lastMessageSeq":0,"memberCount":2,"createdAt":"2026-11-04T10:00:00Z","updatedAt":"2026-11-04T10:00:00Z"}"""
private const val MESSAGE_JSON =
    """{"id":"msg_1","conversationId":"cnv_1","seq":7,"type":"regular","silent":false,"userId":"alex","text":"hi","custom":{},"createdAt":"2026-11-04T10:00:00Z","updatedAt":"2026-11-04T10:00:00Z"}"""
private const val MEMBER_JSON =
    """{"userId":"bo","role":"moderator","joinedAt":"2026-11-04T10:00:00Z"}"""
private const val TOTALS_JSON = """{"totalUnread":3,"unreadConversations":1}"""

private class ChatRecorder(val engine: MockEngine, val sdk: FastrelayClient) {
    val last: HttpRequestData get() = engine.requestHistory.last()
}

private fun chatClient(body: String, status: HttpStatusCode = HttpStatusCode.OK): ChatRecorder {
    val engine = MockEngine {
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }
    val sdk = FastrelayClient(
        baseUrl = "http://localhost:8080",
        requestTimeoutMillis = 5_000,
        logger = {},
        engine = engine,
    ).apply { updateToken("user-token") }
    return ChatRecorder(engine, sdk)
}

private suspend fun HttpRequestData.bodyText(): String = body.toByteArray().decodeToString()

class ChatSurfaceTest {

    @Test
    fun createConversationPostsMembersAndOptionalFields() = runTest {
        // given
        val rec = chatClient(CONVERSATION_JSON, HttpStatusCode.Created)

        // when
        val conversation = rec.sdk.createConversation(
            members = listOf("alex", "bo"),
            kind = "direct",
            custom = buildJsonObject { put("topic", JsonPrimitive("x")) },
        )

        // then
        assertEquals(HttpMethod.Post, rec.last.method)
        assertEquals("/v1/conversations", rec.last.url.encodedPath)
        assertEquals("""{"kind":"direct","members":["alex","bo"],"custom":{"topic":"x"}}""", rec.last.bodyText())
        assertEquals("cnv_1", conversation.id)
    }

    @Test
    fun queryConversationsSendsFilterSortAndPaging() = runTest {
        // given
        val rec = chatClient("""{"data":[$CONVERSATION_JSON],"nextCursor":"c2","hasMore":true}""")

        // when
        val page = rec.sdk.queryConversations(
            kind = "group",
            members = listOf("bo"),
            hasUnread = true,
            sort = "unreadCount",
            direction = "asc",
            limit = 10,
            cursor = "c1",
        )

        // then
        assertEquals(HttpMethod.Post, rec.last.method)
        assertEquals("/v1/conversations/query", rec.last.url.encodedPath)
        assertEquals(
            """{"filter":{"kind":"group","members":["bo"],"hasUnread":true},"sort":"unreadCount","direction":"asc","limit":10,"cursor":"c1"}""",
            rec.last.bodyText(),
        )
        assertEquals("c2", page.nextCursor)
        assertEquals("cnv_1", page.data.single().id)
    }

    @Test
    fun queryConversationsWithoutArgumentsSendsEmptyBody() = runTest {
        val rec = chatClient("""{"data":[],"hasMore":false}""")

        rec.sdk.queryConversations()

        assertEquals("{}", rec.last.bodyText())
    }

    @Test
    fun conversationCrudUsesVerifiedPaths() = runTest {
        val rec = chatClient(CONVERSATION_JSON)

        rec.sdk.getConversation("cnv_1")
        assertEquals(HttpMethod.Get to "/v1/conversations/cnv_1", rec.last.method to rec.last.url.encodedPath)

        rec.sdk.updateConversation("cnv_1", name = "Team", frozen = true)
        assertEquals(HttpMethod.Patch to "/v1/conversations/cnv_1", rec.last.method to rec.last.url.encodedPath)
        assertEquals("""{"name":"Team","frozen":true}""", rec.last.bodyText())

        rec.sdk.truncateConversation("cnv_1", systemMessage = false)
        assertEquals(HttpMethod.Post to "/v1/conversations/cnv_1/truncate", rec.last.method to rec.last.url.encodedPath)
        assertEquals("""{"systemMessage":false}""", rec.last.bodyText())

        rec.sdk.addConversationMembers("cnv_1", listOf("cy"))
        assertEquals(HttpMethod.Post to "/v1/conversations/cnv_1/members", rec.last.method to rec.last.url.encodedPath)
        assertEquals("""{"userIds":["cy"]}""", rec.last.bodyText())

        rec.sdk.deleteConversation("cnv_1")
        assertEquals(HttpMethod.Delete to "/v1/conversations/cnv_1", rec.last.method to rec.last.url.encodedPath)
    }

    @Test
    fun memberListUpdateAndRemove() = runTest {
        // list
        val list = chatClient("""{"data":[$MEMBER_JSON],"nextCursor":null,"hasMore":false}""")
        list.sdk.listConversationMembers("cnv_1", limit = 50, cursor = "m1")
        assertEquals("/v1/conversations/cnv_1/members", list.last.url.encodedPath)
        assertEquals("50", list.last.url.parameters["limit"])
        assertEquals("m1", list.last.url.parameters["cursor"])

        // update
        val update = chatClient(MEMBER_JSON)
        val member = update.sdk.updateConversationMember(
            "cnv_1",
            "bo",
            restriction = "muted",
            restrictionExpiresAt = "2026-12-01T00:00:00Z",
        )
        assertEquals(HttpMethod.Patch, update.last.method)
        assertEquals("/v1/conversations/cnv_1/members/bo", update.last.url.encodedPath)
        assertEquals("""{"restriction":"muted","restrictionExpiresAt":"2026-12-01T00:00:00Z"}""", update.last.bodyText())
        assertEquals("moderator", member.role)

        // remove (leave)
        val remove = chatClient("")
        remove.sdk.removeConversationMember("cnv_1", "alex")
        assertEquals(HttpMethod.Delete, remove.last.method)
        assertEquals("/v1/conversations/cnv_1/members/alex", remove.last.url.encodedPath)
    }

    @Test
    fun sendMessageCarriesIdempotencyKey() = runTest {
        // given
        val rec = chatClient(MESSAGE_JSON, HttpStatusCode.Created)

        // when
        val message = rec.sdk.sendMessage(
            "cnv_1",
            text = "hi",
            replyToId = "msg_0",
            silent = true,
            options = FastrelayRequestOptions(idempotencyKey = "idem-1"),
        )

        // then
        assertEquals(HttpMethod.Post, rec.last.method)
        assertEquals("/v1/conversations/cnv_1/messages", rec.last.url.encodedPath)
        assertEquals("idem-1", rec.last.headers["Idempotency-Key"])
        assertEquals("""{"text":"hi","replyToId":"msg_0","silent":true}""", rec.last.bodyText())
        assertEquals(7L, message.seq)
    }

    @Test
    fun listMessagesEncodesDirectionAndAround() = runTest {
        val rec = chatClient("""{"data":[$MESSAGE_JSON],"nextCursor":"7","hasMore":true}""")

        rec.sdk.listMessages("cnv_1", cursor = "20", direction = "newer", limit = 10)
        assertEquals("/v1/conversations/cnv_1/messages", rec.last.url.encodedPath)
        assertEquals("20", rec.last.url.parameters["cursor"])
        assertEquals("newer", rec.last.url.parameters["direction"])
        assertEquals("10", rec.last.url.parameters["limit"])
        assertNull(rec.last.url.parameters["around"])

        val page = rec.sdk.listMessages("cnv_1", around = 42)
        assertEquals("42", rec.last.url.parameters["around"])
        assertNull(rec.last.url.parameters["direction"])
        assertNull(rec.last.url.parameters["limit"])
        assertEquals("7", page.nextCursor)
    }

    @Test
    fun updateAndDeleteMessageUseMessagePaths() = runTest {
        val rec = chatClient(MESSAGE_JSON)

        rec.sdk.updateMessage("msg_1", text = "edited")
        assertEquals(HttpMethod.Patch to "/v1/messages/msg_1", rec.last.method to rec.last.url.encodedPath)
        assertEquals("""{"text":"edited"}""", rec.last.bodyText())

        rec.sdk.deleteMessage("msg_1")
        assertEquals(HttpMethod.Delete to "/v1/messages/msg_1", rec.last.method to rec.last.url.encodedPath)
    }

    @Test
    fun markConversationReadWithAndWithoutSeq() = runTest {
        val rec = chatClient(TOTALS_JSON)

        val totals = rec.sdk.markConversationRead("cnv_1", seq = 7)
        assertEquals(HttpMethod.Post to "/v1/conversations/cnv_1/read", rec.last.method to rec.last.url.encodedPath)
        assertEquals("""{"seq":7}""", rec.last.bodyText())
        assertEquals(3L, totals.totalUnread)

        rec.sdk.markConversationRead("cnv_1")
        assertEquals("{}", rec.last.bodyText())
    }

    @Test
    fun unreadTotalsAndSync() = runTest {
        val unread = chatClient(TOTALS_JSON)
        assertEquals(1L, unread.sdk.getUnreadTotals().unreadConversations)
        assertEquals(HttpMethod.Get to "/v1/chat/unread", unread.last.method to unread.last.url.encodedPath)

        val sync = chatClient(
            """{"serverTime":"2026-11-04T10:05:00Z","conversations":[$CONVERSATION_JSON],"messages":[$MESSAGE_JSON],""" +
                """"removedConversationIds":["cnv_9"],"reloadConversationIds":[],"reloadAll":false,"unread":$TOTALS_JSON}""",
        )
        val result = sync.sdk.syncChat(since = "2026-11-04T10:00:00Z", conversationIds = listOf("cnv_1", "cnv_9"))
        assertEquals(HttpMethod.Post to "/v1/chat/sync", sync.last.method to sync.last.url.encodedPath)
        assertEquals(
            """{"since":"2026-11-04T10:00:00Z","conversationIds":["cnv_1","cnv_9"]}""",
            sync.last.bodyText(),
        )
        assertEquals("2026-11-04T10:05:00Z", result.serverTime)
        assertEquals(listOf("cnv_9"), result.removedConversationIds)
        assertEquals(3L, result.unread.totalUnread)

        sync.sdk.syncChat(since = "2026-11-04T10:05:00Z")
        assertEquals("""{"since":"2026-11-04T10:05:00Z","conversationIds":[]}""", sync.last.bodyText())
    }

    @Test
    fun blockingAndMessageFlagsReuseModerationEndpoints() = runTest {
        val mute = chatClient("""{"id":"mute_1","mutedUserId":"bo","type":"block","createdAt":"2026-11-04T10:00:00Z"}""")
        val block = mute.sdk.createMute("bo", type = "block")
        assertEquals("/v1/moderation/mutes", mute.last.url.encodedPath)
        assertEquals("""{"userId":"bo","type":"block"}""", mute.last.bodyText())
        assertEquals("block", block.type)

        mute.sdk.removeMute("bo", type = "block")
        assertEquals("block", mute.last.url.parameters["type"])

        val flag = chatClient(
            """{"id":"flag_1","reporterId":"alex","targetType":"message","targetId":"msg_1","reason":"spam","createdAt":"2026-11-04T10:00:00Z"}""",
        )
        flag.sdk.createFlag(targetType = "message", targetId = "msg_1", reason = "spam")
        assertEquals("""{"targetType":"message","targetId":"msg_1","reason":"spam"}""", flag.last.bodyText())
    }
}
