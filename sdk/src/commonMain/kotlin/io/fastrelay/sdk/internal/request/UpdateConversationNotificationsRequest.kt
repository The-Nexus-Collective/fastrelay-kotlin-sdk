package io.fastrelay.sdk.internal.request

import kotlinx.serialization.Serializable

@Serializable
internal data class UpdateConversationNotificationsRequest(
    val muted: Boolean,
)
