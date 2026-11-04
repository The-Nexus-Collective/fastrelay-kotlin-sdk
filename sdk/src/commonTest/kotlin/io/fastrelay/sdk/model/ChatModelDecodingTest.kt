package io.fastrelay.sdk.model

import io.fastrelay.sdk.internal.FastrelayJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatModelDecodingTest {

    @Test
    fun decodesConversationWithMembersAndLastMessage() {
        // given
        val json = """
            {
              "id": "cnv_1", "kind": "group", "type": "messaging", "name": "Team", "image": "https://cdn.example/t.png",
              "custom": {"topic": "launch"}, "presenceEnabled": true, "frozen": false, "createdBy": "alex",
              "lastMessageSeq": 12, "lastMessageAt": "2026-11-04T10:00:00Z", "memberCount": 2,
              "members": [
                {"userId": "alex", "user": {"id": "alex", "displayName": "Alex", "createdAt": "2026-11-01T00:00:00Z", "updatedAt": "2026-11-01T00:00:00Z"},
                 "role": "owner", "joinedAt": "2026-11-01T00:00:00Z", "lastReadSeq": 12, "lastReadAt": "2026-11-04T10:00:00Z", "online": true},
                {"userId": "bo", "role": "member", "joinedAt": "2026-11-01T00:00:00Z", "restriction": "muted"}
              ],
              "unreadCount": 3,
              "lastMessage": {"id": "msg_12", "conversationId": "cnv_1", "seq": 12, "type": "regular", "silent": false,
                              "userId": "alex", "text": "ship it", "custom": {}, "createdAt": "2026-11-04T10:00:00Z", "updatedAt": "2026-11-04T10:00:00Z"},
              "createdAt": "2026-11-01T00:00:00Z", "updatedAt": "2026-11-04T10:00:00Z",
              "someFutureField": 1
            }
        """.trimIndent()

        // when
        val conversation = FastrelayJson.decodeFromString<FastrelayConversation>(json)

        // then
        assertEquals("launch", conversation.custom["topic"]?.jsonPrimitive?.content)
        assertEquals(12L, conversation.lastMessageSeq)
        assertEquals(listOf("alex", "bo"), conversation.members?.map { it.userId })
        assertEquals("Alex", conversation.members?.first()?.user?.displayName)
        assertEquals(true, conversation.members?.first()?.online)
        assertEquals("muted", conversation.members?.last()?.restriction)
        assertNull(conversation.members?.last()?.lastReadSeq)
        assertEquals("ship it", conversation.lastMessage?.text)
        assertEquals(3, conversation.unreadCount)
    }

    @Test
    fun decodesMessageWithReplyPreview() {
        val json = """
            {"id": "msg_2", "conversationId": "cnv_1", "seq": 2, "type": "regular", "silent": true, "userId": "bo",
             "text": "agreed", "custom": {}, "replyToId": "msg_1",
             "replyTo": {"id": "msg_1", "seq": 1, "userId": "alex", "text": "ship it?", "deleted": false},
             "editedAt": "2026-11-04T10:01:00Z", "createdAt": "2026-11-04T10:00:00Z", "updatedAt": "2026-11-04T10:01:00Z"}
        """.trimIndent()

        val message = FastrelayJson.decodeFromString<FastrelayMessage>(json)

        assertEquals("msg_1", message.replyTo?.id)
        assertEquals(1L, message.replyTo?.seq)
        assertEquals(false, message.replyTo?.deleted)
        assertTrue(message.silent)
        assertEquals("2026-11-04T10:01:00Z", message.editedAt)
    }

    @Test
    fun decodesSystemMessageWithoutUserOrText() {
        val json = """
            {"id": "msg_3", "conversationId": "cnv_1", "seq": 3, "type": "system", "silent": false,
             "custom": {"event": "member.added", "actorId": "alex", "userIds": ["cy"]},
             "createdAt": "2026-11-04T10:00:00Z", "updatedAt": "2026-11-04T10:00:00Z"}
        """.trimIndent()

        val message = FastrelayJson.decodeFromString<FastrelayMessage>(json)

        assertEquals("system", message.type)
        assertNull(message.userId)
        assertNull(message.text)
        assertEquals("member.added", message.custom["event"]?.jsonPrimitive?.content)
        assertTrue(message.custom["userIds"] is JsonArray)
    }

    @Test
    fun decodesPayloadsWithNullFieldsOmitted() {
        // the server omits nulls entirely
        val conversation = FastrelayJson.decodeFromString<FastrelayConversation>(
            """{"id":"cnv_1","kind":"direct","type":"messaging","custom":{},"presenceEnabled":false,"frozen":false,"lastMessageSeq":0,"memberCount":2,"createdAt":"2026-11-04T10:00:00Z","updatedAt":"2026-11-04T10:00:00Z"}""",
        )
        assertNull(conversation.name)
        assertNull(conversation.members)
        assertNull(conversation.unreadCount)
        assertNull(conversation.lastMessage)
        assertNull(conversation.truncatedSeq)

        val member = FastrelayJson.decodeFromString<FastrelayConversationMember>(
            """{"userId":"bo","role":"member","joinedAt":"2026-11-04T10:00:00Z"}""",
        )
        assertNull(member.user)
        assertNull(member.lastDeliveredSeq)
        assertNull(member.online)

        val preview = FastrelayJson.decodeFromString<FastrelayReplyPreview>("""{"id":"msg_1","deleted":true}""")
        assertTrue(preview.deleted)
        assertNull(preview.text)
    }

    @Test
    fun decodesChatSync() {
        val sync = FastrelayJson.decodeFromString<FastrelayChatSync>(
            """{"serverTime":"2026-11-04T10:05:00Z","conversations":[],"messages":[],"removedConversationIds":[],"reloadConversationIds":["cnv_2"],"reloadAll":true,"unread":{"totalUnread":4,"unreadConversations":2}}""",
        )

        assertEquals(listOf("cnv_2"), sync.reloadConversationIds)
        assertTrue(sync.reloadAll)
        assertEquals(2L, sync.unread.unreadConversations)
    }
}
