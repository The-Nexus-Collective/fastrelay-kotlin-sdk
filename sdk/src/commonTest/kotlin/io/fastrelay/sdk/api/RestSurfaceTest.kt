package io.fastrelay.sdk.api

import io.fastrelay.sdk.FastrelayApiError
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
import io.ktor.http.encodeURLPathPart
import io.ktor.http.fullPath
import io.ktor.http.headersOf
import kotlin.coroutines.cancellation.CancellationException
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

private val activityJson = """
    {"id": "act_1", "type": "post", "text": "hi", "userId": "alex", "feeds": ["user:alex"], "visibility": "public", "custom": {}, "popularity": 0.0, "reactionCounts": {}, "commentCount": 0, "bookmarkCount": 0, "createdAt": "2026-08-04T10:00:00Z", "updatedAt": "2026-08-04T10:00:00Z"}
""".trimIndent()

class RestSurfaceTest {

    @Test
    fun dynamicPathComponentsAreEncodedAsSingleSegments() = runTest {
        // given
        val (sdk, engine) = testClient { json(activityJson) }

        // when an id carries reserved path characters
        sdk.getActivity("act/../evil?x=1")

        // then it stays one encoded segment instead of rerouting the request
        val expected = "/v1/activities/" + "act/../evil?x=1".encodeURLPathPart()
        assertEquals(expected, engine.requestHistory.single().url.encodedPath)
    }

    @Test
    fun cancellationIsNotWrappedAsApiError() = runTest {
        // given an engine whose request is cancelled mid-flight
        val (sdk, _) = testClient { throw CancellationException("cancelled") }

        // when / then the cancellation propagates instead of becoming NETWORK_ERROR
        assertFailsWith<CancellationException> { sdk.getActivity("act_1") }
    }

    @Test
    fun getOrCreateFeedPostsToFeedPath() = runTest {
        // given
        val (sdk, engine) = testClient { json("""{"group":"user","id":"alex"}""") }

        // when
        val feed = sdk.getOrCreateFeed("user", "alex")

        // then
        val request = engine.requestHistory.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/v1/feeds/user/alex", request.url.encodedPath)
        assertEquals("user:alex", feed.feedId)
    }

    @Test
    fun getFeedActivitiesEncodesQueryParams() = runTest {
        // given
        val (sdk, engine) = testClient {
            json("""{"data":[$activityJson],"groups":null,"nextCursor":null,"hasMore":false,"unseenCount":null,"unreadCount":null}""")
        }

        // when
        val page = sdk.getFeedActivities(
            "user",
            "alex",
            limit = 10,
            markSeen = true,
            filters = mapOf("type" to "post"),
        )

        // then
        val url = engine.requestHistory.single().url
        assertEquals("/v1/feeds/user/alex/activities", url.encodedPath)
        assertEquals("10", url.parameters["limit"])
        assertEquals("true", url.parameters["markSeen"])
        assertEquals("post", url.parameters["filter[type]"])
        assertEquals("act_1", page.data?.single()?.id)
    }

    @Test
    fun getFeedActivitiesDecodesAggregatedPage() = runTest {
        // given
        val (sdk, _) = testClient {
            json(
                """{"data":null,"groups":[{"groupKey":"g1","activities":[$activityJson],"activityCount":2,"createdAt":"2026-08-04T10:00:00Z","updatedAt":"2026-08-04T10:00:00Z"}],"nextCursor":null,"hasMore":false,"unseenCount":1,"unreadCount":3}""",
            )
        }

        // when
        val page = sdk.getFeedActivities("notification", "alex")

        // then
        assertEquals("g1", page.groups?.single()?.groupKey)
        assertEquals(1, page.unseenCount)
        assertEquals(3, page.unreadCount)
    }

    @Test
    fun batchGetActivitiesPostsIdsAndUnwrapsThePageEnvelope() = runTest {
        // given the endpoint returns CursorPage<ActivityResponse>, not a bare array
        val (sdk, engine) = testClient {
            json("""{"data":[$activityJson],"nextCursor":null,"hasMore":false}""")
        }

        // when
        val result = sdk.batchGetActivities(listOf("act_1", "act_2"))

        // then
        val request = engine.requestHistory.single()
        assertEquals("/v1/activities/batch", request.url.encodedPath)
        assertTrue(request.body.toByteArray().decodeToString().contains(""""ids":["act_1","act_2"]"""))
        assertEquals(listOf("act_1"), result.map { it.id })
    }

    @Test
    fun batchGetActivitiesDecodesEmptyPage() = runTest {
        // given
        val (sdk, _) = testClient { json("""{"data":[],"nextCursor":null,"hasMore":false}""") }

        // when
        val result = sdk.batchGetActivities(listOf("act_missing"))

        // then
        assertTrue(result.isEmpty())
    }

    @Test
    fun batchGetWithNoIdsMakesNoRequest() = runTest {
        // given
        val (sdk, engine) = testClient { json("[]") }

        // when
        val result = sdk.batchGetActivities(emptyList())

        // then
        assertTrue(result.isEmpty())
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun connectUserUpsertsThenStoresToken() = runTest {
        // given
        val (sdk, engine) = testClient(tokenProvider = { "jwt-connected" }) { request ->
            when (request.url.encodedPath) {
                "/v1/users" -> json("""{"id":"alex","displayName":"Alex","profileData":{},"role":"user","createdAt":"2026-08-04T10:00:00Z","updatedAt":"2026-08-04T10:00:00Z"}""")
                else -> json("{}")
            }
        }

        // when
        sdk.connectUser("alex", displayName = "Alex")

        // then
        val request = engine.requestHistory.single()
        assertEquals("/v1/users", request.url.encodedPath)
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("Bearer jwt-connected", request.headers[HttpHeaders.Authorization])
        assertEquals("jwt-connected", sdk.currentToken)
    }

    @Test
    fun connectUserWithoutUpsertSkipsRestEntirely() = runTest {
        // given
        val (sdk, engine) = testClient(tokenProvider = { "jwt-connected" }) { json("{}") }

        // when
        sdk.connectUser("alex", upsertUser = false)

        // then
        assertTrue(engine.requestHistory.isEmpty())
        assertEquals("jwt-connected", sdk.currentToken)
    }

    @Test
    fun disconnectUserClearsToken() = runTest {
        // given
        val (sdk, _) = testClient(tokenProvider = { "jwt" }) { json("{}") }
        sdk.connectUser("alex", upsertUser = false)

        // when
        sdk.disconnectUser()

        // then
        assertEquals(null, sdk.currentToken)
    }

    @Test
    fun notFoundOnGetActivitySurfacesTypedError() = runTest {
        // given
        val (sdk, _) = testClient {
            respond(
                """{"error":{"code":"ACTIVITY_NOT_FOUND","message":"activity with ID 'act_x' was not found."},"requestId":"req_1"}""",
                HttpStatusCode.NotFound,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        // when
        val error = assertFailsWith<FastrelayApiError> { sdk.getActivity("act_x") }

        // then
        assertEquals("ACTIVITY_NOT_FOUND", error.code)
        assertEquals(404, error.status)
    }

    @Test
    fun addActivityPostsBodyAndDecodesActivity() = runTest {
        // given
        val (sdk, engine) = testClient { json(activityJson) }

        // when
        val activity = sdk.addActivity(type = "post", feeds = listOf("user:alex"), text = "hi", userId = "alex")

        // then
        val body = engine.requestHistory.single().body.toByteArray().decodeToString()
        assertTrue(body.contains(""""type":"post""""))
        assertTrue(body.contains(""""feeds":["user:alex"]"""))
        assertEquals("act_1", activity.id)
    }
}
