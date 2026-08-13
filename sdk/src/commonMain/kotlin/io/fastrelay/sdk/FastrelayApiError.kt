package io.fastrelay.sdk

import kotlinx.serialization.json.JsonObject

class FastrelayApiError(
    val status: Int,
    val code: String,
    override val message: String,
    val details: JsonObject? = null,
    val hint: String? = null,
    val docUrl: String? = null,
    val requestId: String? = null,
    val rateLimit: FastrelayRateLimit? = null,
) : Exception(message) {

    val isRateLimited: Boolean get() = status == 429
    val isAuthError: Boolean get() = status == 401
    val isNotFound: Boolean get() = status == 404

    override fun toString(): String =
        "FastrelayApiError(status=$status, code=$code, message=$message, requestId=$requestId)"

    companion object {
        const val CODE_TOKEN_EXPIRED = "TOKEN_EXPIRED"
        const val CODE_AUTH_EXPIRED = "AUTH_EXPIRED"
        const val CODE_NETWORK_ERROR = "NETWORK_ERROR"
        const val CODE_NO_TOKEN = "NO_TOKEN"
        const val CODE_REALTIME_REQUIRES_USER_TOKEN = "REALTIME_REQUIRES_USER_TOKEN"
        const val CODE_INVALID_FILE_NAME = "INVALID_FILE_NAME"
        const val CODE_VIDEO_UPLOAD_FAILED = "VIDEO_UPLOAD_FAILED"
        const val CODE_SUBSCRIPTION_LIMIT = "SUBSCRIPTION_LIMIT_EXCEEDED"

        fun local(code: String, message: String, hint: String? = null): FastrelayApiError =
            FastrelayApiError(status = 0, code = code, message = message, hint = hint)
    }
}

data class FastrelayRateLimit(
    val limit: Long? = null,
    val remaining: Long? = null,
    val reset: Long? = null,
    val retryAfterSeconds: Long? = null,
)
