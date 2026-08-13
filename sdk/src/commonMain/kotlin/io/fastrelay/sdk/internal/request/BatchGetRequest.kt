package io.fastrelay.sdk.internal.request

import kotlinx.serialization.Serializable

@Serializable
internal data class BatchGetRequest(
    val ids: List<String>,
)
