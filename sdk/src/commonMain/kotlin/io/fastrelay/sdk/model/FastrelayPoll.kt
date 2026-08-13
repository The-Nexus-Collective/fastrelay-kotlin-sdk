package io.fastrelay.sdk.model

import kotlinx.serialization.Serializable

@Serializable
data class FastrelayPoll(
    val id: String = "",
    val question: String = "",
    val options: List<FastrelayPollOption> = emptyList(),
    val totalVotes: Int = 0,
    val userVote: Map<String, String>? = null,
    val expiresAt: String? = null,
    val isClosed: Boolean = false,
)
