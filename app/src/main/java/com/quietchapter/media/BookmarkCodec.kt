package com.quietchapter.media

/** Position only: playback, navigation and automatic-resume intent are never persisted. */
data class PlaybackBookmark(val mediaId: String, val positionMs: Long, val contentIdentity: String? = null) {
    /** A replacement document must never inherit another registration's position. */
    fun positionFor(id: String, identity: String? = null): Long =
        if (mediaId == id && contentIdentity == identity) positionMs else 0
}

/** Small strict format, deliberately independent of Android and Media3 runtime state. */
object BookmarkCodec {
    private const val VERSION = "1"
    private const val MAX_CHARACTERS = 512
    private val playableIds = setOf("local:book", DemoCatalog.BOOK_ID, DemoCatalog.CHAPTER_ONE_ID, DemoCatalog.CHAPTER_TWO_ID)

    fun encode(bookmark: PlaybackBookmark): String {
        require(bookmark.mediaId in playableIds) { "Unknown playable media ID" }
        require(bookmark.positionMs >= 0) { "Negative playback position" }
        bookmark.contentIdentity?.let { identity ->
            require(bookmark.mediaId == "local:book" && validIdentity(identity)) { "Invalid content identity" }
            return "2|${bookmark.mediaId}|${bookmark.positionMs}|$identity"
        }
        return "$VERSION|${bookmark.mediaId}|${bookmark.positionMs}"
    }

    fun decode(value: String): PlaybackBookmark? {
        if (value.length > MAX_CHARACTERS) return null
        val parts = value.split('|')
        val identified = parts.size == 4 && parts[0] == "2" && parts[1] == "local:book" && validIdentity(parts[3])
        if (!identified && (parts.size != 3 || parts[0] != VERSION || parts[1] !in playableIds)) return null
        val position = parts[2].toLongOrNull() ?: return null
        if (position < 0 || position.toString() != parts[2]) return null
        return PlaybackBookmark(parts[1], position, if (identified) parts[3] else null)
    }

    private fun validIdentity(value: String) = value.matches(Regex("[0-9a-f]{32}"))
}
