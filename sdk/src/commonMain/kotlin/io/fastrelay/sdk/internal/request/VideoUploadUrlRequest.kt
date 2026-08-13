package io.fastrelay.sdk.internal.request

import kotlinx.serialization.Serializable

@Serializable
internal data class VideoUploadUrlRequest(
    val filename: String,
    val sizeBytes: Long,
    val mimeType: String,
)
