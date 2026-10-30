package io.fastrelay.sdk.model

import kotlinx.serialization.Serializable

/**
 * A presigned direct upload reserved by `POST /v1/files/uploads`. PUT the file body to
 * [uploadUrl] with every entry of [requiredHeaders] before [expiresAt], then call
 * `confirmFileUpload(fileId)`.
 */
@Serializable
data class FastrelayFileUpload(
    val fileId: String = "",
    val uploadUrl: String = "",
    val requiredHeaders: Map<String, String> = emptyMap(),
    val expiresAt: String = "",
)
