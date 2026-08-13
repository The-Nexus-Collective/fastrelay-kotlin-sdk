package io.fastrelay.sdk.internal

import io.fastrelay.sdk.FastrelayApiError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TokenHolderTest {

    @Test
    fun concurrentRefreshesShareOneProviderCall() = runTest {
        // given
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val holder = TokenHolder(backgroundScope) {
            calls++
            gate.await()
            "token-2"
        }
        holder.updateToken("token-1")

        // when
        val joiners = (1..5).map { async { holder.refresh(failedToken = "token-1") } }
        gate.complete(Unit)
        val results = joiners.awaitAll()

        // then
        assertEquals(1, calls)
        assertEquals(List(5) { "token-2" }, results)
    }

    @Test
    fun cancellingInitiatorDoesNotCancelRefreshForJoiners() = runTest {
        // given
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val holder = TokenHolder(backgroundScope) {
            calls++
            gate.await()
            "token-2"
        }
        holder.updateToken("token-1")

        // when
        val initiator = launch { holder.refresh("token-1") }
        val joiner = async { holder.refresh("token-1") }
        initiator.cancelAndJoin()
        gate.complete(Unit)

        // then
        assertEquals("token-2", joiner.await())
        assertEquals(1, calls)
    }

    @Test
    fun stragglerWithReplacedTokenJoinsNothingAndTriggersNothing() = runTest {
        // given
        var calls = 0
        val holder = TokenHolder(backgroundScope) {
            calls++
            "token-2"
        }
        holder.updateToken("token-1")
        holder.refresh("token-1")
        assertEquals(1, calls)

        // when a straggler still carrying token-1 asks after the refresh completed
        val result = holder.refresh("token-1")

        // then it gets the current token without a second provider call
        assertEquals("token-2", result)
        assertEquals(1, calls)
    }

    @Test
    fun noProviderSurfacesTypedTokenExpiredError() = runTest {
        // given
        val holder = TokenHolder(backgroundScope, tokenProvider = null)
        holder.updateToken("token-1")

        // when / then
        val error = assertFailsWith<FastrelayApiError> { holder.refresh("token-1") }
        assertEquals(FastrelayApiError.CODE_TOKEN_EXPIRED, error.code)
    }

    @Test
    fun providerFailureSurfacesTypedAuthExpiredErrorToAllJoiners() = runTest {
        // given
        val gate = CompletableDeferred<Unit>()
        val holder = TokenHolder(backgroundScope) {
            gate.await()
            throw IllegalStateException("secret rotated")
        }
        holder.updateToken("token-1")

        // when
        val joiners = (1..3).map { async { runCatching { holder.refresh("token-1") } } }
        gate.complete(Unit)
        val results = joiners.awaitAll()

        // then
        results.forEach { result ->
            val error = result.exceptionOrNull()
            assertEquals(FastrelayApiError.CODE_AUTH_EXPIRED, (error as FastrelayApiError).code)
        }
    }

    @Test
    fun failedRefreshDoesNotPoisonTheNextAttempt() = runTest {
        // given
        var calls = 0
        val holder = TokenHolder(backgroundScope) {
            calls++
            if (calls == 1) throw IllegalStateException("transient") else "token-2"
        }
        holder.updateToken("token-1")

        // when
        assertFailsWith<FastrelayApiError> { holder.refresh("token-1") }
        val second = holder.refresh("token-1")

        // then
        assertEquals("token-2", second)
        assertEquals(2, calls)
    }

    @Test
    fun staleRefreshDoesNotOverwriteNewerToken() = runTest {
        // given a refresh suspended in the provider
        val gate = CompletableDeferred<Unit>()
        val holder = TokenHolder(backgroundScope) {
            gate.await()
            "token-stale"
        }
        holder.updateToken("token-1")
        val refresh = async { holder.refresh("token-1") }
        testScheduler.runCurrent()

        // when a newer token arrives before the refresh completes
        holder.updateToken("token-new")
        gate.complete(Unit)
        refresh.await()

        // then the stale result does not clobber the newer token
        assertEquals("token-new", holder.current())
    }

    @Test
    fun staleRefreshDoesNotResurrectClearedToken() = runTest {
        // given a refresh suspended in the provider
        val gate = CompletableDeferred<Unit>()
        val holder = TokenHolder(backgroundScope) {
            gate.await()
            "token-stale"
        }
        holder.updateToken("token-1")
        val refresh = async { holder.refresh("token-1") }
        testScheduler.runCurrent()

        // when the session is disconnected before the refresh completes
        holder.clear()
        gate.complete(Unit)
        refresh.await()

        // then the cleared token stays cleared
        assertEquals(null, holder.current())
    }

    @Test
    fun clearForgetsTokenAndCurrentReflectsUpdates() = runTest {
        // given
        val holder = TokenHolder(backgroundScope, tokenProvider = null)

        // when / then
        assertEquals(null, holder.current())
        holder.updateToken("token-1")
        assertEquals("token-1", holder.current())
        holder.clear()
        assertEquals(null, holder.current())
    }
}
