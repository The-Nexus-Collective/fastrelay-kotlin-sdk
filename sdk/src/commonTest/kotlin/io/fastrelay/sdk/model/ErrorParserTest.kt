package io.fastrelay.sdk.model

import io.fastrelay.sdk.internal.ErrorParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ErrorParserTest {

    @Test
    fun parsesFullErrorEnvelope() {
        // given
        val body = """
            {
              "error": {
                "code": "ACTIVITY_NOT_FOUND",
                "message": "activity with ID 'act_x' was not found.",
                "details": {"activity_id": "act_x"},
                "hint": "Verify the activity ID is correct and belongs to your app.",
                "docUrl": "https://docs.fastrelay.io/errors/ACTIVITY_NOT_FOUND"
              },
              "requestId": "req_123"
            }
        """.trimIndent()

        // when
        val error = ErrorParser.parse(404, body) { null }

        // then
        assertEquals(404, error.status)
        assertEquals("ACTIVITY_NOT_FOUND", error.code)
        assertEquals("req_123", error.requestId)
        assertNotNull(error.details)
        assertNotNull(error.hint)
        assertEquals("https://docs.fastrelay.io/errors/ACTIVITY_NOT_FOUND", error.docUrl)
        assertNull(error.rateLimit)
    }

    @Test
    fun synthesizes429FromHeadersWithEmptyBody() {
        // given
        val headers = mapOf(
            "X-RateLimit-Limit" to "100",
            "X-RateLimit-Remaining" to "0",
            "X-RateLimit-Reset" to "1754300000",
            "Retry-After" to "60",
        )

        // when
        val error = ErrorParser.parse(429, "") { headers[it] }

        // then
        assertEquals(429, error.status)
        assertEquals("RATE_LIMIT_EXCEEDED", error.code)
        assertEquals(100L, error.rateLimit?.limit)
        assertEquals(0L, error.rateLimit?.remaining)
        assertEquals(1754300000L, error.rateLimit?.reset)
        assertEquals(60L, error.rateLimit?.retryAfterSeconds)
    }

    @Test
    fun malformedBodyStillProducesTypedError() {
        // when
        val error = ErrorParser.parse(502, "<html>Bad Gateway</html>") { null }

        // then
        assertEquals(502, error.status)
        assertEquals("HTTP_502", error.code)
    }

    @Test
    fun missingEnvelopeFieldsFallBackToStatusDefaults() {
        // when
        val error = ErrorParser.parse(500, "{}") { null }

        // then
        assertEquals("HTTP_500", error.code)
        assertEquals("Request failed with status 500.", error.message)
    }
}
