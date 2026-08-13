package io.fastrelay.sdk.realtime

internal interface RealtimeTokenAccess {
    fun currentToken(): String?
    val canRefresh: Boolean
    suspend fun refresh(failedToken: String?): String
}
