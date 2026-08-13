package io.fastrelay.sdk.internal

import kotlin.io.encoding.Base64
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object JwtClaims {

    fun expiresAtEpochSeconds(token: String): Long? {
        val payload = token.split(".").getOrNull(1) ?: return null
        val decoded = runCatching {
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(payload).decodeToString()
        }.getOrNull() ?: return null
        val json = runCatching { FastrelayJson.parseToJsonElement(decoded).jsonObject }.getOrNull() ?: return null
        return runCatching { json["exp"]?.jsonPrimitive?.content?.toLong() }.getOrNull()
    }

    fun isExpired(token: String, nowEpochMillis: Long, skewSeconds: Long = 30): Boolean {
        val exp = expiresAtEpochSeconds(token) ?: return false
        return nowEpochMillis / 1000 >= exp - skewSeconds
    }
}
