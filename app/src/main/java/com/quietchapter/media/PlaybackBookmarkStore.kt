package com.quietchapter.media

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException

/** Atomic private restoration data, excluded from Android cloud/device backup by location. */
class PlaybackBookmarkStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "playback-bookmark-v1"))

    @Synchronized
    fun read(): PlaybackBookmark? = try {
        file.openRead().use { input ->
            val bytes = ByteArray(513)
            var size = 0
            while (size < bytes.size) {
                val count = input.read(bytes, size, bytes.size - size)
                if (count < 0) break
                size += count
            }
            if (size > 512) null else BookmarkCodec.decode(String(bytes, 0, size, Charsets.UTF_8))
        }
    } catch (_: IOException) {
        null
    }

    /** Write errors propagate so the owner can record failed persistence rather than claim success. */
    @Synchronized
    fun write(bookmark: PlaybackBookmark) {
        val bytes = BookmarkCodec.encode(bookmark).toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (failure: IOException) {
            file.failWrite(stream)
            throw failure
        }
    }
}
