package io.fastrelay.sdk.model

import kotlinx.serialization.Serializable

@Serializable
data class FastrelayVideoUploadUrl(
    val videoId: String = "",
    val uploadUrl: String = "",
    val protocol: String = "tus",
)
