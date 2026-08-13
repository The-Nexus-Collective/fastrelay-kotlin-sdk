package io.fastrelay.sdk.internal.request

import kotlinx.serialization.Serializable

@Serializable
internal data class CreatePollRequest(
    val question: String,
    val options: List<PollOptionInput>,
    val maxVotesPerUser: Int = 1,
    val expiresAt: String? = null,
    val anonymous: Boolean = false,
)
