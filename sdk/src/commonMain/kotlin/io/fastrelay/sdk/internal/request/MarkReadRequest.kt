package io.fastrelay.sdk.internal.request

import kotlinx.serialization.Serializable

@Serializable
internal data class MarkReadRequest(
    val seq: Long? = null,
)
