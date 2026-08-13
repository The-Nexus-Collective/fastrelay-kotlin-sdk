package io.fastrelay.sdk.internal.request

import kotlinx.serialization.Serializable

@Serializable
internal data class AddMemberRequest(
    val userId: String,
    val role: String = "member",
)
