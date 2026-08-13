package io.fastrelay.sdk.model

import kotlinx.serialization.Serializable

@Serializable
data class FastrelayVideo(
    val id: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = 0,
    val status: String = "",
    val provider: String = "",
    val createdAt: String = "",
    val hlsUrl: String? = null,
    val thumbnailUrl: String? = null,
    val durationSeconds: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)
