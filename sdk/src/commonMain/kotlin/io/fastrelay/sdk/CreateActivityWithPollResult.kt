package io.fastrelay.sdk

import io.fastrelay.sdk.model.FastrelayActivity
import io.fastrelay.sdk.model.FastrelayPoll

data class CreateActivityWithPollResult(
    val activity: FastrelayActivity,
    val poll: FastrelayPoll? = null,
    val pollError: FastrelayApiError? = null,
    val stampError: FastrelayApiError? = null,
) {
    val isFullSuccess: Boolean get() = poll != null && pollError == null && stampError == null
}
