package io.fastrelay.sdk.model

import kotlinx.serialization.Serializable

@Serializable
data class FastrelayPollOption(
    val id: String = "",
    val text: String = "",
    val voteCount: Int = 0,
)
