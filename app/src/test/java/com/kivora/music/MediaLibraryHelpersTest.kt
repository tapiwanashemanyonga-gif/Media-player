package com.kivora.music

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaLibraryHelpersTest {

    @Test
    fun firstItemByKeyPreservesFirstDuplicate() {
        val items = listOf(
            "first" to "content://media/1",
            "second" to "content://media/1",
            "third" to "content://media/2"
        )

        val indexed = firstItemByKey(items) { it.second }

        assertEquals("first", indexed["content://media/1"]?.first)
        assertEquals("third", indexed["content://media/2"]?.first)
        assertEquals(2, indexed.size)
    }

    @Test
    fun titleFilterTrimsAndIgnoresCase() {
        val titles = listOf("Summer Song", "Night Drive", "Summer Rain")

        assertEquals(
            listOf("Summer Song", "Summer Rain"),
            filterItemsByTitle(titles, "  SUMMER  ") { it }
        )
    }

    @Test
    fun emptyTitleQueryReturnsEveryItem() {
        val titles = listOf("One", "Two")

        assertEquals(titles, filterItemsByTitle(titles, "   ") { it })
    }
}
