# fastrelay Kotlin Multiplatform SDK

Kotlin client for the [fastrelay](https://fastrelay.io) activity feeds platform. One codebase for Android, iOS, and the JVM: coroutine/`Flow` API, typed models, typed errors, and realtime WebSocket subscriptions.

## Add the SDK

Until the SDK is published to Maven Central, consume it via an included build. In your project's `settings.gradle.kts`:

```kotlin
includeBuild("path/to/fastrelay-kotlin-sdk") {
    dependencySubstitution {
        substitute(module("io.fastrelay:sdk")).using(project(":sdk"))
    }
}
```

Then in your module:

```kotlin
dependencies {
    implementation("io.fastrelay:sdk:0.1.0")
}
```

## Quickstart

```kotlin
import io.fastrelay.sdk.FastrelayClient

val client = FastrelayClient(
    baseUrl = "http://localhost:8080",
    // Called whenever a fresh user token is needed (first connect, 401, WS close 4003).
    // This must call YOUR backend, which holds the api_secret and mints user tokens
    // via POST /v1/tokens. The SDK itself is client-only and never sees the secret.
    tokenProvider = { myBackend.fetchFastrelayToken("alex") },
)

// Fetch a token via tokenProvider, store it for bearer requests, and upsert the user:
client.connectUser("alex", displayName = "Alex")

// One feed read:
val page = client.getOrCreateFeed("user", "alex").getActivities(limit = 25)
page.data.orEmpty().forEach { println("${it.userId}: ${it.text}") }

// One realtime subscription:
client.realtime.connect()
client.realtime.eventsForFeed("user:alex").collect { event ->
    println("live: ${event.type} on ${event.feedId}")
}
```

Everything is a `suspend` function; run inside a coroutine scope. `client.disconnectUser()` stops realtime and clears the token.

## Auth

The SDK is **client-only**: every request sends `Authorization: Bearer <user JWT>`. There is no server-auth mode and no `api_secret` anywhere in the SDK — the secret grants admin access to your whole fastrelay app and must stay on your backend, which mints user tokens via `POST /v1/tokens` (HTTP Basic `api_key:api_secret`) and hands them to the app through your `tokenProvider`. Server-side surfaces (token issuance, user administration, bans, blocklists, regex filters, flag review) are backend-API-only and deliberately absent here.

Token refresh: on a 401 (or WS close `4003`) the SDK calls your `tokenProvider` once — concurrent requests share a single refresh — and retries the request once. Without a `tokenProvider`, an expired token surfaces as a typed `TOKEN_EXPIRED` error.

## Errors

Failed requests throw `FastrelayApiError` with `status`, `code`, `message`, `details`, `hint`, `docUrl`, `requestId`, and — for 429s — `rateLimit` (limit/remaining/reset/retry-after, parsed from headers because rate-limited responses have an empty body).

## Realtime

`client.realtime` exposes:

- `connectionState: StateFlow<ConnectionState>` — `Disconnected / Connecting / Connected / Reconnecting / RefreshingToken / Suspended / Failed`
- `events: SharedFlow<FastrelayRealtimeEvent>` — every event after transport-level dedup
- `eventsForFeed(feedId, type?)` — subscribes on the first collector, unsubscribes on the last (20 feeds max per connection)
- `reconnected: SharedFlow<Unit>` — fires on every (re)connect; there is **no event replay**, so refresh your visible data over REST when it fires
- `suspend()` / `resume()` — call from your app's background/foreground lifecycle
- `connect()` / `disconnect()`

Realtime requires a **user token**; connecting without one yields a typed error instead of a connect attempt.

Close-code semantics (handled for you, documented for debugging):

| Code | Meaning | SDK behavior |
|------|---------|--------------|
| `4001` | missing token | terminal failure |
| `4002` | invalid token | one refresh + retry; terminal if the token was freshly refreshed |
| `4003` | token expired | refresh via `tokenProvider`, reconnect |
| `4004` | dead connection sweep | normal reconnect |
| `4008` | origin not allowed | terminal failure |
| `4009` | event buffer overflow | delayed reconnect |
| `4010` | user banned | terminal failure |
| `4029` | too many connections | delayed reconnect |
| `1001`/`1012` | server restart | normal reconnect |

On Darwin (iOS), Apple's WebSocket stack occasionally reports `1006` instead of the real close code; the SDK's watchdog-driven reconnect does not depend on close codes alone.

**Security note — token in the URL:** the realtime handshake authenticates via `wss://…/v1/realtime?token=<jwt>`. Query strings routinely land in server access logs, proxy logs, and crash pipelines. The SDK redacts the token from its own errors and states, but for production deployments terminate TLS end-to-end, keep access logs away from third parties, and use short-lived tokens.

Non-loopback plaintext `http` base URLs log a warning at client construction — the derived `ws://` URL carries the token where the exposure is worst.

## Video upload

`uploadVideoBytes` mints a tus upload URL via the backend, then PATCHes the bytes directly to Cloudflare Stream (tus 1.0.0, 50MB chunks by default). The final `ready` state arrives via the realtime `video.ready` event, or by polling `getVideo(videoId)`:

```kotlin
val result = client.uploadVideoBytes(
    bytes = videoBytes,
    filename = "clip.mp4",
    mimeType = "video/mp4",
    onProgress = { println("${(it.fraction * 100).toInt()}%") },
)
val video = client.getVideo(result.videoId)
```

Bytes are held in memory — fine for clips, not for multi-GB files. A streaming/resumable variant is deliberately deferred.

## Targets

`:sdk` compiles for `androidTarget`, `iosArm64`, `iosSimulatorArm64`, and `jvm` (desktop declared, not verified). The iOS binary is a static `FastrelaySDK` framework; consumers can re-export it through an umbrella framework via `api`/`export` so Swift sees unmangled SDK types. Maven publishing and the binary-compat guard are next.

Certificate pinning is intentionally out of scope — inject your own preconfigured engine via the `engine` constructor parameter if you need it.
