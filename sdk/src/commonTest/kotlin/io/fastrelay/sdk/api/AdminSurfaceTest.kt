package io.fastrelay.sdk.api

import io.fastrelay.sdk.FastrelayClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun MockRequestHandleScope.json(body: String): HttpResponseData =
    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

private fun testClient(
    tokenProvider: (suspend () -> String)? = null,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): Pair<FastrelayClient, MockEngine> {
    val engine = MockEngine { request -> handler(request) }
    val client = FastrelayClient(
        baseUrl = "http://localhost:8080",
        tokenProvider = tokenProvider,
        requestTimeoutMillis = 5_000,
        logger = {},
        engine = engine,
    ).apply { updateToken("test-token") }
    return client to engine
}

class AdminSurfaceTest {

    @Test
    fun deleteFeedHitsFeedPath() = runTest {
        // given
        val (sdk, engine) = testClient { json("{}") }

        // when
        sdk.deleteFeed("user", "alex")

        // then
        val request = engine.requestHistory.single()
        assertEquals(HttpMethod.Delete, request.method)
        assertEquals("/v1/feeds/user/alex", request.url.encodedPath)
    }

    @Test
    fun setFeedVisibilityPutsLevel() = runTest {
        // given
        val (sdk, engine) = testClient { json("{}") }

        // when
        sdk.setFeedVisibility("user", "alex", "private")

        // then
        val request = engine.requestHistory.single()
        assertEquals(HttpMethod.Put, request.method)
        assertEquals("/v1/feeds/user/alex/visibility", request.url.encodedPath)
        assertTrue(request.body.toByteArray().decodeToString().contains(""""level":"private""""))
    }

    @Test
    fun feedMembersRoundTrip() = runTest {
        // given
        val (sdk, engine) = testClient {
            if (it.method == HttpMethod.Post) {
                json("""{"feedId":"user:alex","userId":"kim","role":"moderator","createdAt":"2026-08-05T10:00:00Z"}""")
            } else {
                json("""{"data":[{"feedId":"user:alex","userId":"kim","role":"moderator","createdAt":"2026-08-05T10:00:00Z"}],"nextCursor":null,"hasMore":false}""")
            }
        }

        // when
        val member = sdk.addFeedMember("user", "alex", userId = "kim", role = "moderator")
        val members = sdk.listFeedMembers("user", "alex")

        // then
        assertEquals("/v1/feeds/user/alex/members", engine.requestHistory.first().url.encodedPath)
        assertEquals("moderator", member.role)
        assertEquals("kim", members.data.single().userId)
    }

    @Test
    fun pinAndUnpinActivityUsePinPath() = runTest {
        // given
        val (sdk, engine) = testClient {
            json("""{"feedId":"user:alex","activityId":"act_1","pinnedAt":"2026-08-05T10:00:00Z","pinnedBy":"alex"}""")
        }

        // when
        val pin = sdk.pinActivity("user", "alex", "act_1")
        sdk.unpinActivity("user", "alex", "act_1")

        // then
        assertEquals("act_1", pin.activityId)
        assertEquals(HttpMethod.Post, engine.requestHistory[0].method)
        assertEquals(HttpMethod.Delete, engine.requestHistory[1].method)
        assertEquals("/v1/feeds/user/alex/activities/act_1/pin", engine.requestHistory[1].url.encodedPath)
    }

    @Test
    fun batchFollowFeedPostsTargets() = runTest {
        // given
        val (sdk, engine) = testClient {
            json("""{"data":[{"id":"fol_1","sourceFeed":"timeline:alex","targetFeed":"user:kim","status":"active","createdAt":"2026-08-05T10:00:00Z"}],"nextCursor":null,"hasMore":false}""")
        }

        // when
        val page = sdk.batchFollowFeed("timeline", "alex", targets = listOf("user:kim", "user:lee"), activityCopyLimit = 10)

        // then
        val request = engine.requestHistory.single()
        assertEquals("/v1/feeds/timeline/alex/follows/batch", request.url.encodedPath)
        val body = request.body.toByteArray().decodeToString()
        assertTrue(body.contains(""""targets":["user:kim","user:lee"]"""))
        assertTrue(body.contains(""""activityCopyLimit":10"""))
        assertEquals("user:kim", page.data.single().targetFeed)
    }

    @Test
    fun followRequestLifecyclePaths() = runTest {
        // given
        val followJson =
            """{"id":"fol_1","sourceFeed":"timeline:kim","targetFeed":"user:alex","status":"pending","createdAt":"2026-08-05T10:00:00Z"}"""
        val (sdk, engine) = testClient {
            if (it.url.encodedPath.endsWith("/follow-requests")) {
                json("""{"data":[$followJson],"nextCursor":null,"hasMore":false}""")
            } else {
                json(followJson)
            }
        }

        // when
        val pending = sdk.listFollowRequests("user", "alex")
        sdk.approveFollowRequest("user", "alex", "fol_1")
        sdk.rejectFollowRequest("user", "alex", "fol_2")

        // then
        assertEquals("pending", pending.data.single().status)
        assertEquals("/v1/feeds/user/alex/follow-requests/fol_1/approve", engine.requestHistory[1].url.encodedPath)
        assertEquals("/v1/feeds/user/alex/follow-requests/fol_2/reject", engine.requestHistory[2].url.encodedPath)
    }

    @Test
    fun createVideoUploadUrlPostsMetadata() = runTest {
        // given
        val (sdk, engine) = testClient {
            json("""{"videoId":"vid_1","uploadUrl":"https://upload.example/tus/vid_1","protocol":"tus"}""")
        }

        // when
        val upload = sdk.createVideoUploadUrl("clip.mp4", sizeBytes = 1024, mimeType = "video/mp4")

        // then
        val request = engine.requestHistory.single()
        assertEquals("/v1/videos/upload-url", request.url.encodedPath)
        val body = request.body.toByteArray().decodeToString()
        assertTrue(body.contains(""""filename":"clip.mp4""""))
        assertTrue(body.contains(""""sizeBytes":1024"""))
        assertEquals("vid_1", upload.videoId)
        assertEquals("tus", upload.protocol)
    }

    @Test
    fun getVideoDecodesPlaybackFields() = runTest {
        // given
        val (sdk, _) = testClient {
            json(
                """{"id":"vid_1","mimeType":"video/mp4","sizeBytes":1024,"status":"ready","provider":"cloudflare","createdAt":"2026-08-05T10:00:00Z","hlsUrl":"https://cdn.example/vid_1.m3u8","durationSeconds":12,"width":1920,"height":1080}""",
            )
        }

        // when
        val video = sdk.getVideo("vid_1")

        // then
        assertEquals("ready", video.status)
        assertEquals("https://cdn.example/vid_1.m3u8", video.hlsUrl)
        assertEquals(12, video.durationSeconds)
    }

    @Test
    fun submitFeedbackValidatesType() = runTest {
        // given
        val (sdk, engine) = testClient {
            json("""{"id":"fbk_1","activityId":"act_1","userId":"alex","type":"show_less","createdAt":"2026-08-05T10:00:00Z"}""")
        }

        // when
        val feedback = sdk.submitFeedback("act_1", "show_less")

        // then
        assertEquals("/v1/activities/act_1/feedback", engine.requestHistory.single().url.encodedPath)
        assertEquals("show_less", feedback.type)
        assertFailsWith<IllegalArgumentException> { sdk.submitFeedback("act_1", "meh") }
    }

    @Test
    fun createFlagUsesCallerAuth() = runTest {
        // given
        val (sdk, engine) = testClient(tokenProvider = { "user-token" }) {
            json("""{"id":"flg_1","reporterId":"alex","targetType":"activity","targetId":"act_1","reason":"spam","status":"pending","createdAt":"2026-08-05T10:00:00Z"}""")
        }
        sdk.updateToken("user-token")

        // when
        val flag = sdk.createFlag("activity", "act_1", reason = "spam", description = "obvious bot")

        // then
        val request = engine.requestHistory.single()
        assertEquals("/v1/moderation/flags", request.url.encodedPath)
        assertEquals("Bearer user-token", request.headers[HttpHeaders.Authorization])
        assertTrue(request.body.toByteArray().decodeToString().contains(""""description":"obvious bot""""))
        assertEquals("pending", flag.status)
    }

    @Test
    fun muteLifecycleUsesCallerAuthAndTypeQuery() = runTest {
        // given
        val muteJson =
            """{"id":"mut_1","muterId":"alex","mutedUserId":"kim","type":"personal","createdAt":"2026-08-05T10:00:00Z"}"""
        val (sdk, engine) = testClient(tokenProvider = { "user-token" }) {
            if (it.method == HttpMethod.Get) {
                json("""{"data":[$muteJson],"nextCursor":null,"hasMore":false}""")
            } else {
                json(muteJson)
            }
        }
        sdk.updateToken("user-token")

        // when
        val mute = sdk.createMute("kim")
        val mutes = sdk.listMutes()
        sdk.removeMute("kim")

        // then
        assertEquals("kim", mute.mutedUserId)
        assertEquals("kim", mutes.data.single().mutedUserId)
        engine.requestHistory.forEach { request ->
            assertEquals("Bearer user-token", request.headers[HttpHeaders.Authorization])
        }
        assertEquals("personal", engine.requestHistory.last().url.parameters["type"])
    }
}
