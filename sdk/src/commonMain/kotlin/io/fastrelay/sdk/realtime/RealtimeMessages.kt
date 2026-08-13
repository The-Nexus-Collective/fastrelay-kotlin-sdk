package io.fastrelay.sdk.realtime

import io.fastrelay.sdk.internal.FastrelayJson
import io.fastrelay.sdk.model.FastrelayRealtimeEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal sealed interface ServerFrame {
    data class Established(val connectionId: String?) : ServerFrame
    data object Heartbeat : ServerFrame
    data class SubscribeSuccess(val feeds: List<String>) : ServerFrame
    data class SubscribeError(val code: String?, val message: String?, val deniedFeeds: List<String>) : ServerFrame
    data class UnsubscribeSuccess(val feeds: List<String>) : ServerFrame
    data class ServerError(val code: String?, val message: String?) : ServerFrame
    data object GoingAway : ServerFrame
    data class Event(val event: FastrelayRealtimeEvent) : ServerFrame
    data class Unknown(val type: String?) : ServerFrame
}

internal object RealtimeMessages {

    fun parse(text: String): ServerFrame {
        val root = runCatching { FastrelayJson.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return ServerFrame.Unknown(null)
        val type = root["type"]?.jsonPrimitive?.content ?: return ServerFrame.Unknown(null)
        return when (type) {
            "connection.established" -> ServerFrame.Established(root.stringOrNull("connectionId"))
            "heartbeat" -> ServerFrame.Heartbeat
            "subscribe.success" -> ServerFrame.SubscribeSuccess(root.feedList())
            "unsubscribe.success" -> ServerFrame.UnsubscribeSuccess(root.feedList())
            "subscribe.error" -> {
                val error = root["error"] as? JsonObject
                val hint = error?.stringOrNull("hint")
                ServerFrame.SubscribeError(
                    code = error?.stringOrNull("code"),
                    message = error?.stringOrNull("message"),
                    deniedFeeds = parseDeniedFeeds(hint),
                )
            }
            "error" -> {
                val error = root["error"] as? JsonObject
                ServerFrame.ServerError(error?.stringOrNull("code"), error?.stringOrNull("message"))
            }
            "server.going_away" -> ServerFrame.GoingAway
            else -> runCatching {
                val event = FastrelayJson.decodeFromString<FastrelayRealtimeEvent>(text)
                // Frames like video.ready carry payload fields the event model doesn't declare;
                // keep the whole frame in `data` so they aren't silently dropped.
                ServerFrame.Event(if (event.data == null) event.copy(data = root) else event)
            }.getOrElse { ServerFrame.Unknown(type) }
        }
    }

    fun subscribeFrame(feeds: Collection<String>): String = clientFrame("subscribe", feeds)

    fun unsubscribeFrame(feeds: Collection<String>): String = clientFrame("unsubscribe", feeds)

    private fun clientFrame(type: String, feeds: Collection<String>): String =
        buildJsonObject {
            put("type", type)
            put(
                "feeds",
                buildJsonArray { feeds.forEach { add(FastrelayJson.parseToJsonElement("\"$it\"")) } },
            )
        }.toString()

    private fun parseDeniedFeeds(hint: String?): List<String> {
        val marker = "Denied feeds:"
        if (hint == null || !hint.contains(marker)) return emptyList()
        return hint.substringAfter(marker).split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content

    private fun JsonObject.feedList(): List<String> =
        (this["feeds"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?: emptyList()
}
