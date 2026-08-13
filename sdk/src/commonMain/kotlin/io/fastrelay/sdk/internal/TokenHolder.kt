package io.fastrelay.sdk.internal

import io.fastrelay.sdk.FastrelayApiError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class TokenHolder(
    private val scope: CoroutineScope,
    private val tokenProvider: (suspend () -> String)?,
) {
    private val lock = Mutex()
    private var token: String? = null
    private var inFlight: Deferred<String>? = null
    private var inFlightFrom: String? = null

    // Bumped on every external write so an in-flight refresh can't overwrite a newer
    // token (updateToken) or resurrect a cleared one (disconnectUser).
    private var generation = 0

    fun current(): String? = token

    fun updateToken(newToken: String) {
        token = newToken
        generation++
    }

    fun clear() {
        token = null
        generation++
    }

    suspend fun refresh(failedToken: String?): String {
        val provider = tokenProvider ?: throw FastrelayApiError.local(
            code = FastrelayApiError.CODE_TOKEN_EXPIRED,
            message = "The user token has expired and no tokenProvider is configured.",
            hint = "Pass a tokenProvider when constructing FastrelayClient so expired tokens can be refreshed.",
        )
        val toAwait: Deferred<String> = lock.withLock {
            val currentToken = token
            if (currentToken != null && currentToken != failedToken) return currentToken
            inFlight?.takeIf { it.isActive && inFlightFrom == failedToken }
                ?: startRefresh(provider, generation).also {
                    inFlight = it
                    inFlightFrom = failedToken
                }
        }
        return toAwait.await()
    }

    private fun startRefresh(provider: suspend () -> String, startGeneration: Int): Deferred<String> {
        val result = CompletableDeferred<String>()
        scope.launch {
            try {
                val newToken = provider()
                lock.withLock {
                    if (generation == startGeneration) token = newToken
                    inFlight = null
                    inFlightFrom = null
                }
                result.complete(newToken)
            } catch (error: Throwable) {
                lock.withLock {
                    inFlight = null
                    inFlightFrom = null
                }
                result.completeExceptionally(
                    FastrelayApiError(
                        status = 401,
                        code = FastrelayApiError.CODE_AUTH_EXPIRED,
                        message = "Token refresh failed: ${error.message ?: error::class.simpleName}",
                        hint = "The tokenProvider threw while refreshing an expired token.",
                    ),
                )
            }
        }
        return result
    }
}
