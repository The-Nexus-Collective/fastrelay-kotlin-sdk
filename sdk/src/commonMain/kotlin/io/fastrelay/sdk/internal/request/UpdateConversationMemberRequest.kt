package io.fastrelay.sdk.internal.request

import kotlinx.serialization.Serializable

@Serializable
internal data class UpdateConversationMemberRequest(
    val role: String? = null,
    val restriction: String? = null,
    val restrictionExpiresAt: String? = null,
)
