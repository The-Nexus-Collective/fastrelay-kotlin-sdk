package io.fastrelay.sdk.internal.request

import kotlinx.serialization.Serializable

@Serializable
internal data class CreateFlagRequest(
    val targetType: String,
    val targetId: String,
    val reason: String,
    val description: String? = null,
)
