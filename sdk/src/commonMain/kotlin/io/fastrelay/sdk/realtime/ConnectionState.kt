package io.fastrelay.sdk.realtime

import io.fastrelay.sdk.FastrelayApiError

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val connectionId: String?) : ConnectionState
    data class Reconnecting(val attempt: Int) : ConnectionState
    data object RefreshingToken : ConnectionState
    data object Suspended : ConnectionState
    data class Failed(val error: FastrelayApiError) : ConnectionState
}
