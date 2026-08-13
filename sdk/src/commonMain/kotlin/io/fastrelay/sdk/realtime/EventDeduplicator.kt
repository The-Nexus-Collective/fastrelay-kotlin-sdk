package io.fastrelay.sdk.realtime

internal class EventDeduplicator(private val capacity: Int = 1000) {
    private val seen = LinkedHashSet<String>()

    fun isNew(eventId: String): Boolean {
        if (eventId.isBlank()) return true
        if (!seen.add(eventId)) return false
        if (seen.size > capacity) {
            val iterator = seen.iterator()
            iterator.next()
            iterator.remove()
        }
        return true
    }
}
