package io.fastrelay.sdk.realtime

import io.fastrelay.sdk.internal.FastrelayJson
import io.fastrelay.sdk.model.FastrelayChatEvent
import io.fastrelay.sdk.model.FastrelayRealtimeEvent
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal sealed interface ServerFrame {
    data class Established(val connectionId: String?, val serverTime: String? = null) : ServerFrame
    data object Heartbeat : ServerFrame
    data class SubscribeSuccess(val feeds: List<String>) : ServerFrame
    data class SubscribeError(val code: String?, val message: String?, val deniedFeeds: List<String>) : ServerFrame
    data class UnsubscribeSuccess(val feeds: List<String>) : ServerFrame
    data class ServerError(val code: String?, val message: String?, val hint: String? = null) : ServerFrame
    data object GoingAway : ServerFrame
    data class Event(val event: FastrelayRealtimeEvent) : ServerFrame
    data class Chat(val event: FastrelayChatEvent) : ServerFrame
    data class Unknown(val type: String?) : ServerFrame
}

internal object RealtimeMessages {

    fun parse(text: String): ServerFrame {
        val root = runCatching { FastrelayJson.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return ServerFrame.Unknown(null)
        val type = root.stringOrNull("type") ?: return ServerFrame.Unknown(null)
        return when (type) {
            "connection.established" ->
                ServerFrame.Established(root.stringOrNull("connectionId"), root.stringOrNull("serverTime"))
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
                ServerFrame.ServerError(error?.stringOrNull("code"), error?.stringOrNull("message"), error?.stringOrNull("hint"))
            }
            "server.going_away" -> ServerFrame.GoingAway
            // Feed events carry a feedId; video.* status frames are user-scoped but predate chat and
            // keep flowing through the feed event stream. Everything else without a feedId is chat.
            else -> if (type.startsWith("video.") || root.hasValue("feedId")) {
                parseFeedEvent(text, root, type)
            } else if (type.isNotBlank()) {
                ServerFrame.Chat(
                    FastrelayChatEvent(
                        type = type,
                        conversationId = root.stringOrNull("conversationId"),
                        eventId = root.stringOrNull("eventId").orEmpty(),
                        createdAt = root.stringOrNull("createdAt").orEmpty(),
                        data = root["data"] as? JsonObject ?: JsonObject(emptyMap()),
                    ),
                )
            } else {
                ServerFrame.Unknown(type)
            }
        }
    }

    private fun parseFeedEvent(text: String, root: JsonObject, type: String): ServerFrame =
        runCatching {
            val event = FastrelayJson.decodeFromString<FastrelayRealtimeEvent>(text)
            // Frames like video.ready carry payload fields the event model doesn't declare;
            // keep the whole frame in `data` so they aren't silently dropped.
            ServerFrame.Event(if (event.data == null) event.copy(data = root) else event)
        }.getOrElse { ServerFrame.Unknown(type) }

    fun subscribeFrame(feeds: Collection<String>): String = clientFrame("subscribe", feeds)

    fun unsubscribeFrame(feeds: Collection<String>): String = clientFrame("unsubscribe", feeds)

    fun typingFrame(conversationId: String, started: Boolean): String =
        buildJsonObject {
            put("type", if (started) "typing.start" else "typing.stop")
            put("conversationId", conversationId)
        }.toString()

    fun deliveredFrame(conversationId: String, seq: Long): String =
        buildJsonObject {
            put("type", "message.delivered")
            put("conversationId", conversationId)
            put("seq", seq)
        }.toString()

    fun readFrame(conversationId: String, seq: Long?): String =
        buildJsonObject {
            put("type", "conversation.read")
            put("conversationId", conversationId)
            if (seq != null) put("seq", seq)
        }.toString()

    fun presenceStartFrame(): String = buildJsonObject { put("type", "presence.start") }.toString()

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
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.hasValue(key: String): Boolean = this[key].let { it != null && it !is JsonNull }

    private fun JsonObject.feedList(): List<String> =
        (this["feeds"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?: emptyList()
}
