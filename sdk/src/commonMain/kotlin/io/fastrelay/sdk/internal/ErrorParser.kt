package io.fastrelay.sdk.internal

import io.fastrelay.sdk.FastrelayApiError
import io.fastrelay.sdk.FastrelayRateLimit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class ErrorEnvelope(
    val error: ErrorBody? = null,
    val requestId: String? = null,
)

@Serializable
internal data class ErrorBody(
    val code: String? = null,
    val message: String? = null,
    val details: JsonObject? = null,
    val hint: String? = null,
    val docUrl: String? = null,
)

internal object ErrorParser {

    fun parse(status: Int, body: String, header: (String) -> String?): FastrelayApiError {
        val rateLimit = rateLimitFrom(header)
        if (status == 429 && body.isBlank()) {
            return FastrelayApiError(
                status = status,
                code = "RATE_LIMIT_EXCEEDED",
                message = "Rate limit exceeded.",
                hint = "Wait and retry after the Retry-After period.",
                rateLimit = rateLimit,
            )
        }
        val envelope = runCatching { FastrelayJson.decodeFromString<ErrorEnvelope>(body) }.getOrNull()
        return FastrelayApiError(
            status = status,
            code = envelope?.error?.code ?: "HTTP_$status",
            message = envelope?.error?.message ?: "Request failed with status $status.",
            details = envelope?.error?.details,
            hint = envelope?.error?.hint,
            docUrl = envelope?.error?.docUrl,
            requestId = envelope?.requestId,
            rateLimit = rateLimit,
        )
    }

    private fun rateLimitFrom(header: (String) -> String?): FastrelayRateLimit? {
        val limit = header("X-RateLimit-Limit")?.toLongOrNull()
        val remaining = header("X-RateLimit-Remaining")?.toLongOrNull()
        val reset = header("X-RateLimit-Reset")?.toLongOrNull()
        val retryAfter = header("Retry-After")?.toLongOrNull()
        if (limit == null && remaining == null && reset == null && retryAfter == null) return null
        return FastrelayRateLimit(limit, remaining, reset, retryAfter)
    }
}
