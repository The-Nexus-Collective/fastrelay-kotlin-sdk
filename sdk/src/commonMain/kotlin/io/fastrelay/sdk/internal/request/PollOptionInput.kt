package io.fastrelay.sdk.internal.request

import kotlinx.serialization.Serializable

@Serializable
internal data class PollOptionInput(
    val id: String,
    val text: String,
)
