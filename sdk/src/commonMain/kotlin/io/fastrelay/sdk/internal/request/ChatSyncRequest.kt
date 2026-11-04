package io.fastrelay.sdk.internal.request

import kotlinx.serialization.Serializable

@Serializable
internal data class ChatSyncRequest(
    val since: String,
    val conversationIds: List<String>,
)
