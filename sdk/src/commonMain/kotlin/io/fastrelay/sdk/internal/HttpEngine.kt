package io.fastrelay.sdk.internal

import io.ktor.client.engine.HttpClientEngine

internal expect fun defaultHttpEngine(): HttpClientEngine
