package com.quietchapter.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookmarkCodecTest {
    @Test fun `identified bookmark round trips and matches only the same registration`() {
        val identity = "a".repeat(32)
        val value = PlaybackBookmark("local:book", 42_000, identity)
        assertEquals(value, BookmarkCodec.decode(BookmarkCodec.encode(value)))
        assertEquals(42_000L, value.positionFor("local:book", identity))
        assertEquals(0L, value.positionFor("local:book", "b".repeat(32)))
        assertEquals(0L, value.positionFor("local:book"))
    }
    @Test fun `old bookmark cannot seek a newly registered file even after reset write fails`() {
        val old = BookmarkCodec.decode("1|local:book|42000")!!
        assertEquals(42_000L, old.positionFor("local:book"))
        assertEquals(0L, old.positionFor("local:book", "c".repeat(32)))
        assertEquals(0L, old.positionFor(DemoCatalog.CHAPTER_ONE_ID))
    }
    @Test fun `identity metadata format rejects malformed or foreign IDs`() {
        listOf("", "a".repeat(31), "A".repeat(32), "../book", "f".repeat(33)).forEach {
            assertNull(BookmarkCodec.decode("2|local:book|12|$it"))
        }
        assertNull(BookmarkCodec.decode("2|${DemoCatalog.BOOK_ID}|12|${"a".repeat(32)}"))
    }

    @Test fun `imported book restores position without URI or play intent`() {
        val value = PlaybackBookmark("local:book", 12_345)
        assertEquals(value, BookmarkCodec.decode(BookmarkCodec.encode(value)))
        assertNull(BookmarkCodec.decode("1|content://untrusted/file|0"))
    }

    @Test fun `every playable ID round trips without playback intent`() {
        listOf(DemoCatalog.BOOK_ID, DemoCatalog.CHAPTER_ONE_ID, DemoCatalog.CHAPTER_TWO_ID).forEach { id ->
            listOf(0L, 3_000L, Long.MAX_VALUE).forEach { position ->
                val bookmark = PlaybackBookmark(id, position)
                assertEquals(bookmark, BookmarkCodec.decode(BookmarkCodec.encode(bookmark)))
            }
        }
    }

    @Test fun `unknown version media ID and root cannot restore`() {
        listOf("2|${DemoCatalog.BOOK_ID}|0", "1|unknown|0", "1|${DemoCatalog.ROOT_ID}|0").forEach {
            assertNull(it, BookmarkCodec.decode(it))
        }
    }

    @Test fun `malformed or out of range positions cannot restore`() {
        listOf("-1", "+1", "01", "1.0", "", " 1", "1 ", "9223372036854775808").forEach {
            assertNull(it, BookmarkCodec.decode("1|${DemoCatalog.BOOK_ID}|$it"))
        }
    }

    @Test fun `extra fields truncation and oversized input cannot restore`() {
        listOf("", "1", "1|${DemoCatalog.BOOK_ID}", "1|${DemoCatalog.BOOK_ID}|0|true", "x".repeat(513)).forEach {
            assertNull(it, BookmarkCodec.decode(it))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative position cannot be persisted`() {
        BookmarkCodec.encode(PlaybackBookmark(DemoCatalog.BOOK_ID, -1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown media cannot be persisted`() {
        BookmarkCodec.encode(PlaybackBookmark("file:///untrusted", 0))
    }
}
