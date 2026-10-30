package io.fastrelay.sdk.internal.request

import kotlinx.serialization.Serializable

@Serializable
internal data class CreateFileUploadRequest(
    val contentType: String,
    val sizeBytes: Long,
    val type: String? = null,
)
