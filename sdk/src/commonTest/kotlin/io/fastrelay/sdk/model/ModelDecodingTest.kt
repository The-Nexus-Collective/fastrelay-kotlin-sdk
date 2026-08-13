package io.fastrelay.sdk.model

import io.fastrelay.sdk.internal.FastrelayJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelDecodingTest {

    @Test
    fun decodesActivityFromBackendShape() {
        // given
        val json = """
            {
              "id": "act_01HZX3V9K3W7",
              "type": "post",
              "text": "hello world",
              "userId": "alex",
              "feeds": ["user:alex"],
              "visibility": "public",
              "custom": {"imageUrl": "https://cdn.example/x.png"},
              "popularity": 0.5,
              "reactionCounts": {"like": 3, "heart": 1},
              "commentCount": 2,
              "bookmarkCount": 1,
              "expiresAt": null,
              "createdAt": "2026-08-04T10:00:00Z",
              "updatedAt": "2026-08-04T10:00:00Z",
              "ownReactions": [{"id": "rxn_1", "appId": "app_x", "activityId": "act_01HZX3V9K3W7", "userId": "alex", "type": "like", "createdAt": "2026-08-04T10:01:00Z"}],
              "pinned": false,
              "user": {"id": "alex", "displayName": "Alex", "profileData": {}, "role": "user", "createdAt": "2026-08-01T00:00:00Z", "updatedAt": "2026-08-01T00:00:00Z"},
              "someFutureField": "ignored"
            }
        """.trimIndent()

        // when
        val activity = FastrelayJson.decodeFromString<FastrelayActivity>(json)

        // then
        assertEquals("act_01HZX3V9K3W7", activity.id)
        assertEquals(mapOf("like" to 3, "heart" to 1), activity.reactionCounts)
        assertEquals("like", activity.ownReactions?.single()?.type)
        assertEquals("Alex", activity.user?.displayName)
        assertNull(activity.expiresAt)
    }

    @Test
    fun decodesActivityWithAbsentOptionalFields() {
        // given
        val json = """
            {
              "id": "act_1", "type": "post", "userId": "alex", "feeds": [],
              "visibility": "public", "custom": {}, "popularity": 0.0,
              "reactionCounts": {}, "commentCount": 0, "bookmarkCount": 0,
              "createdAt": "2026-08-04T10:00:00Z", "updatedAt": "2026-08-04T10:00:00Z"
            }
        """.trimIndent()

        // when
        val activity = FastrelayJson.decodeFromString<FastrelayActivity>(json)

        // then
        assertNull(activity.text)
        assertNull(activity.ownReactions)
        assertNull(activity.user)
        assertEquals(false, activity.pinned)
    }

    @Test
    fun nestedCustomDataSurvivesAsJsonObject() {
        // given
        val json = """
            {
              "id": "act_2", "type": "post", "userId": "alex", "feeds": [],
              "custom": {"poll": {"id": "poll_9"}, "tags": ["a", "b"], "depth": {"one": {"two": 2}}},
              "createdAt": "2026-08-04T10:00:00Z", "updatedAt": "2026-08-04T10:00:00Z",
              "reactionCounts": {}, "commentCount": 0, "bookmarkCount": 0, "popularity": 0.0, "visibility": "public"
            }
        """.trimIndent()

        // when
        val activity = FastrelayJson.decodeFromString<FastrelayActivity>(json)

        // then
        val custom = activity.custom!!
        assertEquals("poll_9", (custom["poll"] as JsonObject)["id"]!!.toString().trim('"'))
        assertTrue(custom["tags"] is JsonArray)
        assertEquals(2, ((custom["depth"] as JsonObject)["one"] as JsonObject)["two"]!!.toString().toInt())
    }

    @Test
    fun decodesCursorPageOfReactions() {
        // given
        val json = """
            {
              "data": [
                {"id": "rxn_1", "appId": "app_x", "activityId": "act_1", "userId": "alex", "type": "like", "createdAt": "2026-08-04T10:00:00Z"},
                {"id": "rxn_2", "appId": "app_x", "activityId": "act_1", "userId": "bo", "type": "heart", "createdAt": "2026-08-04T10:01:00Z", "user": {"id": "bo", "displayName": "Bo", "profileData": {}, "role": "user", "createdAt": "2026-08-01T00:00:00Z", "updatedAt": "2026-08-01T00:00:00Z"}}
              ],
              "nextCursor": "Y3Vyc29y",
              "hasMore": true
            }
        """.trimIndent()

        // when
        val page = FastrelayJson.decodeFromString<CursorPage<FastrelayReaction>>(json)

        // then
        assertEquals(2, page.data.size)
        assertEquals("Y3Vyc29y", page.nextCursor)
        assertTrue(page.hasMore)
        assertEquals("Bo", page.data[1].user?.displayName)
    }

    @Test
    fun decodesPollWithUserVote() {
        // given
        val json = """
            {
              "id": "poll_1",
              "question": "Best feed type?",
              "options": [{"id": "opt_1", "text": "flat", "voteCount": 4}, {"id": "opt_2", "text": "aggregated", "voteCount": 2}],
              "totalVotes": 6,
              "userVote": {"optionId": "opt_1"},
              "expiresAt": "2026-09-01T00:00:00Z",
              "isClosed": false
            }
        """.trimIndent()

        // when
        val poll = FastrelayJson.decodeFromString<FastrelayPoll>(json)

        // then
        assertEquals(2, poll.options.size)
        assertEquals(4, poll.options[0].voteCount)
        assertEquals("opt_1", poll.userVote?.get("optionId"))
    }

    @Test
    fun decodesCommentFollowBookmarkFileCapabilitiesToken() {
        val comment = FastrelayJson.decodeFromString<FastrelayComment>(
            """{"id": "cmt_1", "activityId": "act_1", "userId": "alex", "text": "nice", "parentId": null, "mentionedUsers": [], "reactionCounts": {}, "custom": {}, "score": 0.0, "createdAt": "2026-08-04T10:00:00Z", "updatedAt": "2026-08-04T10:00:00Z"}""",
        )
        assertEquals("nice", comment.text)
        assertNull(comment.parentId)

        val follow = FastrelayJson.decodeFromString<FastrelayFollow>(
            """{"id": "fol_1", "appId": "app_x", "sourceFeed": "timeline:alex", "targetFeed": "user:bo", "status": "active", "createdAt": "2026-08-04T10:00:00Z"}""",
        )
        assertEquals("user:bo", follow.targetFeed)

        val bookmark = FastrelayJson.decodeFromString<FastrelayBookmark>(
            """{"id": "bmk_1", "appId": "app_x", "activityId": "act_1", "userId": "alex", "createdAt": "2026-08-04T10:00:00Z"}""",
        )
        assertEquals("act_1", bookmark.activityId)

        val file = FastrelayJson.decodeFromString<FastrelayFile>(
            """{"id": "file_1", "url": "https://cdn.example/f.png", "type": "image", "mimeType": "image/png", "size": 1024, "metadata": {}, "createdAt": "2026-08-04T10:00:00Z"}""",
        )
        assertEquals(1024L, file.size)

        val capabilities = FastrelayJson.decodeFromString<FastrelayCapabilities>(
            """{"canAddActivity": true, "canDeleteOwnActivity": true, "canDeleteAnyActivity": false, "canAddReaction": true, "canAddComment": true, "canFollow": true, "canAddBookmark": true, "canCreatePoll": true, "canUploadFile": true, "canFlagContent": true, "canBanUser": false, "canMuteUser": false, "canReviewFlags": false}""",
        )
        assertTrue(capabilities.canAddActivity)
    }

    @Test
    fun decodesRealtimeEvent() {
        // given
        val json = """
            {
              "type": "activity.created",
              "feedId": "user:alex",
              "eventId": "evt_01HZX",
              "createdAt": "2026-08-04T10:00:00Z",
              "data": {"id": "act_1", "type": "post"}
            }
        """.trimIndent()

        // when
        val event = FastrelayJson.decodeFromString<FastrelayRealtimeEvent>(json)

        // then
        assertEquals("activity.created", event.type)
        assertEquals("user:alex", event.feedId)
        assertEquals("act_1", event.data!!.jsonObject["id"]!!.toString().trim('"'))
    }
}
