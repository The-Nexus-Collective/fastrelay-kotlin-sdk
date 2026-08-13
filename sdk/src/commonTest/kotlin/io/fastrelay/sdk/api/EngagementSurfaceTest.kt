package io.fastrelay.sdk.api

import io.fastrelay.sdk.FastrelayApiError
import io.fastrelay.sdk.FastrelayClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val reactionJson = """{"id":"rxn_1","appId":"app_x","activityId":"act_1","userId":"alex","type":"like","createdAt":"2026-08-04T10:00:00Z"}"""
private val commentJson = """{"id":"cmt_1","activityId":"act_1","userId":"alex","text":"nice","createdAt":"2026-08-04T10:00:00Z","updatedAt":"2026-08-04T10:00:00Z"}"""
private val bookmarkJson = """{"id":"bmk_1","appId":"app_x","activityId":"act_1","userId":"alex","createdAt":"2026-08-04T10:00:00Z"}"""
private val pollJson = """{"id":"poll_1","question":"q","options":[{"id":"opt_1","text":"a","voteCount":0}],"totalVotes":0,"userVote":null,"expiresAt":null,"isClosed":false}"""
private val followJson = """{"id":"fol_1","appId":"app_x","sourceFeed":"timeline:alex","targetFeed":"user:bo","status":"active","createdAt":"2026-08-04T10:00:00Z"}"""
private val fileJson = """{"id":"file_1","url":"https://cdn.example/f.png","type":"image","mimeType":"image/png","size":3,"metadata":{},"createdAt":"2026-08-04T10:00:00Z"}"""
private val activityJson = """{"id":"act_1","type":"post","text":"hi","userId":"alex","feeds":["user:alex"],"visibility":"public","custom":{"existing":"kept"},"popularity":0.0,"reactionCounts":{},"commentCount":0,"bookmarkCount":0,"createdAt":"2026-08-04T10:00:00Z","updatedAt":"2026-08-04T10:00:00Z"}"""

private class Recorded(val engine: MockEngine, val sdk: FastrelayClient)

private fun recorded(handler: (path: String, method: HttpMethod) -> String?): Recorded {
    val engine = MockEngine { request ->
        val body = handler(request.url.encodedPath, request.method)
        if (body == null) {
            respond("""{"error":{"code":"NOT_FOUND","message":"no"},"requestId":"r"}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
        } else {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }
    val sdk = FastrelayClient(
        baseUrl = "http://localhost:8080",
        requestTimeoutMillis = 5_000,
        logger = {},
        engine = engine,
    ).apply { updateToken("test-token") }
    return Recorded(engine, sdk)
}

class EngagementSurfaceTest {

    @Test
    fun reactionEndpointsUseVerifiedPaths() = runTest {
        // given
        val r = recorded { path, _ ->
            when {
                path.endsWith("/reactions") || path.contains("/reactions/") ->
                    if (path.contains("rxn_")) "" else reactionJson
                else -> null
            }
        }

        // when
        r.sdk.addReaction("act_1", "like")
        r.sdk.removeReaction("act_1", "rxn_1")

        // then
        assertEquals("/v1/activities/act_1/reactions", r.engine.requestHistory[0].url.encodedPath)
        assertEquals(HttpMethod.Post, r.engine.requestHistory[0].method)
        assertEquals("/v1/activities/act_1/reactions/rxn_1", r.engine.requestHistory[1].url.encodedPath)
        assertEquals(HttpMethod.Delete, r.engine.requestHistory[1].method)
    }

    @Test
    fun commentAndReplyEndpointsUseVerifiedPaths() = runTest {
        // given
        val r = recorded { path, _ ->
            when {
                path.contains("/comments") || path.contains("/replies") ->
                    if (path.contains("/replies") || path.endsWith("/comments") && false) commentPage() else commentJson
                else -> null
            }
        }

        // when
        r.sdk.addComment("act_1", "nice")
        r.sdk.listReplies("cmt_1")
        r.sdk.addCommentReaction("cmt_1", "like")

        // then
        assertEquals("/v1/activities/act_1/comments", r.engine.requestHistory[0].url.encodedPath)
        assertEquals("/v1/comments/cmt_1/replies", r.engine.requestHistory[1].url.encodedPath)
        assertEquals("/v1/comments/cmt_1/reactions", r.engine.requestHistory[2].url.encodedPath)
    }

    private fun commentPage() = """{"data":[$commentJson],"nextCursor":"next","hasMore":true}"""

    @Test
    fun listCommentsPassesCursorAndDecodesPage() = runTest {
        // given
        val r = recorded { path, _ -> if (path.endsWith("/comments")) commentPage() else null }

        // when
        val page = r.sdk.listComments("act_1", limit = 10, cursor = "abc")

        // then
        val url = r.engine.requestHistory.single().url
        assertEquals("10", url.parameters["limit"])
        assertEquals("abc", url.parameters["cursor"])
        assertEquals("next", page.nextCursor)
        assertTrue(page.hasMore)
        assertEquals("cmt_1", page.data.single().id)
    }

    @Test
    fun bookmarkEndpointsUseVerifiedPaths() = runTest {
        // given
        val r = recorded { path, _ ->
            when {
                path.endsWith("/bookmarks") && path.contains("activities") -> bookmarkJson
                path == "/v1/me/bookmarks" -> """{"data":[$bookmarkJson],"nextCursor":null,"hasMore":false}"""
                else -> ""
            }
        }

        // when
        r.sdk.addBookmark("act_1")
        r.sdk.removeBookmark("act_1")
        val page = r.sdk.listBookmarks()

        // then
        assertEquals("/v1/activities/act_1/bookmarks", r.engine.requestHistory[0].url.encodedPath)
        assertEquals(HttpMethod.Delete, r.engine.requestHistory[1].method)
        assertEquals("/v1/me/bookmarks", r.engine.requestHistory[2].url.encodedPath)
        assertEquals("act_1", page.data.single().activityId)
    }

    @Test
    fun pollEndpointsUseVerifiedPathsAndVoteSwitchIsTwoCalls() = runTest {
        // given
        val r = recorded { path, _ -> if (path.contains("poll")) pollJson else null }

        // when
        r.sdk.createPoll("act_1", "q", listOf("opt_1" to "a", "opt_2" to "b"))
        r.sdk.getPollForActivity("act_1")
        r.sdk.vote("poll_1", "opt_1")
        r.sdk.removeVote("poll_1")
        r.sdk.vote("poll_1", "opt_2")

        // then
        val paths = r.engine.requestHistory.map { "${it.method.value} ${it.url.encodedPath}" }
        assertEquals(
            listOf(
                "POST /v1/activities/act_1/polls",
                "GET /v1/activities/act_1/polls",
                "POST /v1/polls/poll_1/votes",
                "DELETE /v1/polls/poll_1/votes",
                "POST /v1/polls/poll_1/votes",
            ),
            paths,
        )
    }

    @Test
    fun followEndpointsUseVerifiedPaths() = runTest {
        // given
        val r = recorded { path, _ ->
            when {
                path.contains("/follows") -> followJson
                path.contains("/followers") || path.contains("/following") -> """{"data":[$followJson],"nextCursor":null,"hasMore":false}"""
                else -> null
            }
        }

        // when
        r.sdk.followFeed("timeline", "alex", "user:bo")
        r.sdk.unfollowFeed("timeline", "alex", "user:bo")
        r.sdk.listFollowers("user", "bo")
        r.sdk.listFollowing("timeline", "alex")

        // then
        val paths = r.engine.requestHistory.map { "${it.method.value} ${it.url.encodedPath}" }
        assertEquals(
            listOf(
                "POST /v1/feeds/timeline/alex/follows",
                "DELETE /v1/feeds/timeline/alex/follows/user:bo",
                "GET /v1/feeds/user/bo/followers",
                "GET /v1/feeds/timeline/alex/following",
            ),
            paths,
        )
        assertEquals("false", r.engine.requestHistory[1].url.parameters["keepHistory"])
    }

    @Test
    fun uploadBuildsMultipartWithFilePartAndCallerMime() = runTest {
        // given
        val r = recorded { path, _ -> if (path == "/v1/files") fileJson else null }

        // when
        r.sdk.uploadFile(byteArrayOf(1, 2, 3), filename = "photo.png", mimeType = "image/png", type = "image")

        // then
        val request = r.engine.requestHistory.single()
        val contentType = request.body.contentType.toString()
        assertTrue(contentType.startsWith("multipart/form-data"))
        val raw = request.body.toByteArray().decodeToString()
        assertTrue(raw.contains("name=file") || raw.contains("name=\"file\""))
        assertTrue(raw.contains("filename=\"photo.png\""))
        assertTrue(raw.contains("image/png"))
        assertTrue(raw.contains("name=type") || raw.contains("name=\"type\""))
    }

    @Test
    fun uploadWithoutExtensionFailsLocallyBeforeAnyRequest() = runTest {
        // given
        val r = recorded { _, _ -> fileJson }

        // when
        val error = assertFailsWith<FastrelayApiError> {
            r.sdk.uploadFile(byteArrayOf(1), filename = "noextension", mimeType = "image/png")
        }

        // then
        assertEquals(FastrelayApiError.CODE_INVALID_FILE_NAME, error.code)
        assertTrue(r.engine.requestHistory.isEmpty())
    }

    @Test
    fun createActivityWithPollStampsPollIdIntoCustomData() = runTest {
        // given
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            val key = "${request.method.value} ${request.url.encodedPath}"
            requests += key
            val body = when {
                key == "POST /v1/activities" -> activityJson
                key == "POST /v1/activities/act_1/polls" -> pollJson
                key == "PATCH /v1/activities/act_1" -> {
                    val sent = request.body.toByteArray().decodeToString()
                    assertTrue(sent.contains(""""pollId":"poll_1""""))
                    assertTrue(sent.contains(""""existing":"kept""""))
                    activityJson.replace(""""existing":"kept"""", """"existing":"kept","pollId":"poll_1"""")
                }
                else -> null
            }
            if (body == null) {
                respond("{}", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val sdk = FastrelayClient(
            baseUrl = "http://localhost:8080",
            requestTimeoutMillis = 5_000,
            logger = {},
            engine = engine,
        ).apply { updateToken("test-token") }

        // when
        val result = sdk.createActivityWithPoll(
            type = "post",
            feeds = listOf("user:alex"),
            question = "q",
            pollOptions = listOf("opt_1" to "a", "opt_2" to "b"),
        )

        // then
        assertTrue(result.isFullSuccess)
        assertEquals("poll_1", result.poll?.id)
        assertEquals(
            listOf("POST /v1/activities", "POST /v1/activities/act_1/polls", "PATCH /v1/activities/act_1"),
            requests,
        )
    }

    @Test
    fun pollCreateFailureYieldsPartialSuccessWithLiveActivity() = runTest {
        // given
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/v1/activities" ->
                    respond(activityJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else ->
                    respond(
                        """{"error":{"code":"BAD_REQUEST","message":"poll rejected"},"requestId":"r"}""",
                        HttpStatusCode.BadRequest,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
            }
        }
        val sdk = FastrelayClient(
            baseUrl = "http://localhost:8080",
            requestTimeoutMillis = 5_000,
            logger = {},
            engine = engine,
        ).apply { updateToken("test-token") }

        // when
        val result = sdk.createActivityWithPoll(
            type = "post",
            feeds = listOf("user:alex"),
            question = "q",
            pollOptions = listOf("opt_1" to "a", "opt_2" to "b"),
        )

        // then
        assertEquals("act_1", result.activity.id)
        assertNull(result.poll)
        assertNotNull(result.pollError)
        assertEquals("BAD_REQUEST", result.pollError?.code)
        assertTrue(!result.isFullSuccess)
    }
}
