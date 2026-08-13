package io.fastrelay.sdk

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun client(
    engine: MockEngine,
    tokenProvider: (suspend () -> String)? = null,
    baseUrl: String = "http://localhost:8080",
    logger: (String) -> Unit = {},
) = FastrelayClient(
    baseUrl = baseUrl,
    tokenProvider = tokenProvider,
    requestTimeoutMillis = 5_000,
    logger = logger,
    engine = engine,
)

private fun okEngine() = MockEngine { respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }

class FastrelayClientAuthTest {

    @Test
    fun attachesBearerToken() = runTest {
        // given
        val engine = okEngine()
        val sdk = client(engine)
        sdk.updateToken("jwt-1")

        // when
        sdk.requestRaw(HttpMethod.Get, "/v1/me/capabilities")

        // then
        assertEquals("Bearer jwt-1", engine.requestHistory.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun missingTokenFailsBeforeAnyRequest() = runTest {
        // given
        val engine = okEngine()
        val sdk = client(engine)

        // when
        val error = assertFailsWith<FastrelayApiError> { sdk.requestRaw(HttpMethod.Get, "/v1/me/capabilities") }

        // then
        assertEquals(FastrelayApiError.CODE_NO_TOKEN, error.code)
        assertTrue(engine.requestHistory.isEmpty())
    }
}

class FastrelayClientRefreshTest {

    @Test
    fun refreshesOnceAndRetriesOnceOn401() = runTest {
        // given
        var providerCalls = 0
        val engine = MockEngine { request ->
            if (request.headers[HttpHeaders.Authorization] == "Bearer fresh") {
                respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respondError(HttpStatusCode.Unauthorized)
            }
        }
        val sdk = client(engine, tokenProvider = {
            providerCalls++
            "fresh"
        })
        sdk.updateToken("stale")

        // when
        sdk.requestRaw(HttpMethod.Get, "/v1/me/capabilities")

        // then
        assertEquals(1, providerCalls)
        assertEquals(2, engine.requestHistory.size)
        assertEquals("Bearer fresh", engine.requestHistory.last().headers[HttpHeaders.Authorization])
    }

    @Test
    fun secondConsecutive401AfterRefreshSurfacesAuthExpired() = runTest {
        // given
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized) }
        val sdk = client(engine, tokenProvider = { "fresh" })
        sdk.updateToken("stale")

        // when
        val error = assertFailsWith<FastrelayApiError> { sdk.requestRaw(HttpMethod.Get, "/v1/me/capabilities") }

        // then
        assertEquals(FastrelayApiError.CODE_AUTH_EXPIRED, error.code)
        assertEquals(2, engine.requestHistory.size)
    }

    @Test
    fun expiredTokenWithoutProviderSurfacesTokenExpired() = runTest {
        // given
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized) }
        val sdk = client(engine, tokenProvider = null)
        sdk.updateToken("stale")

        // when
        val error = assertFailsWith<FastrelayApiError> { sdk.requestRaw(HttpMethod.Get, "/v1/me/capabilities") }

        // then
        assertEquals(401, error.status)
    }
}

class FastrelayClientErrorTest {

    @Test
    fun errorEnvelopeMapsToTypedError() = runTest {
        // given
        val engine = MockEngine {
            respond(
                """{"error":{"code":"ACTIVITY_NOT_FOUND","message":"activity with ID 'act_x' was not found.","hint":"Check the id."},"requestId":"req_9"}""",
                HttpStatusCode.NotFound,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val sdk = client(engine)
        sdk.updateToken("jwt-1")

        // when
        val error = assertFailsWith<FastrelayApiError> { sdk.requestRaw(HttpMethod.Get, "/v1/activities/act_x") }

        // then
        assertEquals("ACTIVITY_NOT_FOUND", error.code)
        assertEquals("req_9", error.requestId)
    }

    @Test
    fun rateLimit429WithEmptyBodyCarriesHeaderMetadata() = runTest {
        // given
        val engine = MockEngine {
            respond(
                "",
                HttpStatusCode.TooManyRequests,
                headersOf(
                    "X-RateLimit-Limit" to listOf("100"),
                    "X-RateLimit-Remaining" to listOf("0"),
                    "X-RateLimit-Reset" to listOf("1754300000"),
                    "Retry-After" to listOf("60"),
                ),
            )
        }
        val sdk = client(engine)
        sdk.updateToken("jwt-1")

        // when
        val error = assertFailsWith<FastrelayApiError> { sdk.requestRaw(HttpMethod.Get, "/v1/me/capabilities") }

        // then
        assertEquals(60L, error.rateLimit?.retryAfterSeconds)
        assertEquals(100L, error.rateLimit?.limit)
    }

    @Test
    fun transportFailureIsWrappedAndRedacted() = runTest {
        // given
        val engine = MockEngine { throw RuntimeException("connect failed: http://host/ws?token=jwt-secret-value with Authorization: Bearer jwt-secret-value") }
        val sdk = client(engine)
        sdk.updateToken("jwt-secret-value")

        // when
        val error = assertFailsWith<FastrelayApiError> { sdk.requestRaw(HttpMethod.Get, "/v1/me/capabilities") }

        // then
        assertEquals(FastrelayApiError.CODE_NETWORK_ERROR, error.code)
        assertFalse(error.message.contains("jwt-secret-value"))
    }

    @Test
    fun idempotencyKeyHeaderIsSentWhenSupplied() = runTest {
        // given
        val engine = okEngine()
        val sdk = client(engine)
        sdk.updateToken("jwt-1")

        // when
        sdk.requestRaw(
            HttpMethod.Post,
            "/v1/activities",
            body = mapOf("type" to "post"),
            options = FastrelayRequestOptions(idempotencyKey = "idem-123"),
        )

        // then
        assertEquals("idem-123", engine.requestHistory.single().headers["Idempotency-Key"])
    }

    @Test
    fun plaintextNonLoopbackHostWarnsAndLocalhostDoesNot() {
        // given / when
        val warnings = mutableListOf<String>()
        client(okEngine(), baseUrl = "http://10.0.2.2:8080", logger = { warnings += it })
        val emulatorWarned = warnings.isNotEmpty()
        warnings.clear()
        client(okEngine(), baseUrl = "http://localhost:8080", logger = { warnings += it })
        val localhostWarned = warnings.isNotEmpty()

        // then
        assertTrue(emulatorWarned)
        assertFalse(localhostWarned)
    }
}
