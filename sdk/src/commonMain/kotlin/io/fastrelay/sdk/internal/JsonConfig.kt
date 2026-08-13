package io.fastrelay.sdk.internal

import kotlinx.serialization.json.Json

internal val FastrelayJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
}
