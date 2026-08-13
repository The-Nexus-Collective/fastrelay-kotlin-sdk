package io.fastrelay.sdk.model

import io.fastrelay.sdk.internal.FastrelayJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeedActivitiesPageTest {

    @Test
    fun decodesFlatResponse() {
        // given
        val json = """
            {
              "data": [
                {"id": "act_1", "type": "post", "userId": "alex", "feeds": ["user:alex"], "visibility": "public", "custom": {}, "popularity": 0.0, "reactionCounts": {}, "commentCount": 0, "bookmarkCount": 0, "createdAt": "2026-08-04T10:00:00Z", "updatedAt": "2026-08-04T10:00:00Z"}
              ],
              "groups": null,
              "nextCursor": "abc",
              "hasMore": true,
              "unseenCount": null,
              "unreadCount": null,
              "pinned": [
                {"id": "act_pin", "type": "post", "userId": "alex", "feeds": ["user:alex"], "visibility": "public", "custom": {}, "popularity": 0.0, "reactionCounts": {}, "commentCount": 0, "bookmarkCount": 0, "createdAt": "2026-08-01T10:00:00Z", "updatedAt": "2026-08-01T10:00:00Z", "pinned": true}
              ]
            }
        """.trimIndent()

        // when
        val page = FastrelayJson.decodeFromString<FeedActivitiesPage>(json)

        // then
        assertEquals(1, page.data?.size)
        assertNull(page.groups)
        assertEquals("act_pin", page.pinned?.single()?.id)
        assertEquals(true, page.hasMore)
    }

    @Test
    fun decodesAggregatedNotificationResponse() {
        // given
        val json = """
            {
              "data": null,
              "groups": [
                {
                  "groupKey": "reaction:act_1",
                  "activities": [
                    {"id": "act_n1", "type": "reaction", "userId": "bo", "feeds": ["notification:alex"], "visibility": "public", "custom": {}, "popularity": 0.0, "reactionCounts": {}, "commentCount": 0, "bookmarkCount": 0, "createdAt": "2026-08-04T10:00:00Z", "updatedAt": "2026-08-04T10:00:00Z"}
                  ],
                  "activityCount": 3,
                  "createdAt": "2026-08-04T10:00:00Z",
                  "updatedAt": "2026-08-04T11:00:00Z"
                }
              ],
              "nextCursor": null,
              "hasMore": false,
              "unseenCount": 2,
              "unreadCount": 5
            }
        """.trimIndent()

        // when
        val page = FastrelayJson.decodeFromString<FeedActivitiesPage>(json)

        // then
        assertNull(page.data)
        assertEquals("reaction:act_1", page.groups?.single()?.groupKey)
        assertEquals(3, page.groups?.single()?.activityCount)
        assertEquals(2, page.unseenCount)
        assertEquals(5, page.unreadCount)
    }
}
