package io.fastrelay.sdk.model

import kotlinx.serialization.Serializable

@Serializable
data class CursorPage<T>(
    val data: List<T> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)
