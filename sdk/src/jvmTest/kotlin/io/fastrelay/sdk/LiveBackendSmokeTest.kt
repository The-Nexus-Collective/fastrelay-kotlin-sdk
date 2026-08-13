package io.fastrelay.sdk

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LiveBackendSmokeTest {

    // The demo api_secret lives only in this test: the SDK is client-only, so token
    // issuance happens over raw HTTP the way an app backend would do it.
    private fun issueDemoToken(userId: String): String {
        val connection = java.net.URL("http://localhost:8080/v1/tokens").openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        val basic = java.util.Base64.getEncoder().encodeToString("relay_demo_key:relay_demo_secret".toByteArray())
        connection.setRequestProperty("Authorization", "Basic $basic")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write("""{"userId":"$userId"}""".toByteArray()) }
        val body = connection.inputStream.use { it.readBytes().decodeToString() }
        return kotlinx.serialization.json.Json.parseToJsonElement(body)
            .let { it as kotlinx.serialization.json.JsonObject }
            .getValue("token")
            .let { it as kotlinx.serialization.json.JsonPrimitive }
            .content
    }

    private fun liveClient(): FastrelayClient? {
        if (System.getenv("FASTRELAY_LIVE") != "1") return null
        return FastrelayClient(
            baseUrl = "http://localhost:8080",
            tokenProvider = { issueDemoToken("alex") },
        )
    }

    @Test
    fun coreLoopAgainstLocalBackend() = runBlocking {
        val sdk = liveClient() ?: return@runBlocking
        sdk.connectUser("alex", displayName = "Alex")

        val capabilities = sdk.getCapabilities()
        assertTrue(capabilities.canAddActivity)

        val user = sdk.createUser(id = "alex", displayName = "Alex")
        assertEquals("alex", user.id)

        val feed = sdk.getOrCreateFeed("user", "alex", userId = "alex")
        assertEquals("user:alex", feed.feedId)

        val activity = sdk.addActivity(type = "post", feeds = listOf("user:alex"), text = "sdk smoke", userId = "alex")
        assertNotNull(activity.id)

        val page = feed.getActivities(limit = 5)
        assertTrue(page.data.orEmpty().any { it.id == activity.id })

        val fetched = sdk.getActivity(activity.id)
        assertEquals("sdk smoke", fetched.text)

        val batch = sdk.batchGetActivities(listOf(activity.id))
        assertEquals(listOf(activity.id), batch.map { it.id })

        sdk.deleteActivity(activity.id)
    }

    @Test
    fun engagementLoopAgainstLocalBackend() = runBlocking {
        val sdk = liveClient() ?: return@runBlocking
        sdk.connectUser("alex", displayName = "Alex")

        val activity = sdk.addActivity(type = "post", feeds = listOf("user:alex"), text = "engagement smoke")

        val reaction = sdk.addReaction(activity.id, "like", userId = "alex")
        assertEquals("like", reaction.type)
        sdk.removeReaction(activity.id, reaction.id)

        val comment = sdk.addComment(activity.id, "first!", userId = "alex")
        val comments = sdk.listComments(activity.id)
        assertTrue(comments.data.any { it.id == comment.id })

        val pollResult = sdk.attachPoll(activity, "Best option?", listOf("opt_a" to "A", "opt_b" to "B"))
        assertTrue(pollResult.isFullSuccess, "poll attach failed: ${pollResult.pollError} ${pollResult.stampError}")

        val pngBytes = java.util.Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
        val file = sdk.uploadFile(pngBytes, "pixel.png", "image/png", type = "image")
        assertTrue(file.url.endsWith(".png"))

        sdk.deleteActivity(activity.id)
    }

    @Test
    fun realtimeEventsFlowEndToEnd() = runBlocking {
        val listener = liveClient() ?: return@runBlocking
        listener.connectUser("alex", displayName = "Alex")
        val publisher = liveClient()!!
        publisher.connectUser("alex", upsertUser = false)

        val received = kotlinx.coroutines.CompletableDeferred<io.fastrelay.sdk.model.FastrelayRealtimeEvent>()
        listener.realtime.connect()
        val collector = launch {
            listener.realtime.eventsForFeed("user:alex").collect { event ->
                if (!received.isCompleted) received.complete(event)
            }
        }
        withTimeout(10_000) {
            listener.realtime.connectionState.first { it is io.fastrelay.sdk.realtime.ConnectionState.Connected }
        }

        val activity = publisher.addActivity(type = "post", feeds = listOf("user:alex"), text = "realtime smoke")
        val event = withTimeout(10_000) { received.await() }
        assertTrue(event.feedId == "user:alex")

        collector.cancel()
        listener.disconnectUser()
        publisher.deleteActivity(activity.id)
    }

    @Test
    fun reconnectAfterBackendRestart() = runBlocking {
        if (System.getenv("FASTRELAY_RECONNECT_SMOKE") != "1") return@runBlocking
        val sdk = liveClient()!!
        sdk.connectUser("alex")
        sdk.realtime.connect()
        val collector = launch { sdk.realtime.eventsForFeed("user:alex").collect {} }

        withTimeout(15_000) {
            sdk.realtime.connectionState.first { it is io.fastrelay.sdk.realtime.ConnectionState.Connected }
        }
        println("SMOKE: connected — kill the backend now")

        withTimeout(180_000) {
            sdk.realtime.connectionState.first { it is io.fastrelay.sdk.realtime.ConnectionState.Reconnecting }
        }
        println("SMOKE: reconnecting observed")

        withTimeout(180_000) {
            sdk.realtime.connectionState.first { it is io.fastrelay.sdk.realtime.ConnectionState.Connected }
        }
        println("SMOKE: reconnected after restart")

        collector.cancel()
        sdk.disconnectUser()
    }
}
