package io.fastrelay.sdk.internal.request

import kotlinx.serialization.Serializable

@Serializable
internal data class QueryConversationsRequest(
    val filter: ConversationFilterBody? = null,
    val sort: String? = null,
    val direction: String? = null,
    val limit: Int? = null,
    val cursor: String? = null,
)
