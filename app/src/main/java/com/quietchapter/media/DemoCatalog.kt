package com.quietchapter.media

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.io.File

/** Stable IDs resolve only to our original, locally synthesized spoken demo. */
object DemoCatalog {
    const val ROOT_ID = "quietchapter:root"
    const val AUDIOBOOKS_ID = "quietchapter:audiobooks"
    const val BOOK_ID = "quietchapter:book:river"
    const val CHAPTER_ONE_ID = "quietchapter:chapter:1"
    const val CHAPTER_TWO_ID = "quietchapter:chapter:2"

    @Volatile private var cover: ByteArray? = null

    /** Small original cover, loaded once by the service for modern and legacy controllers. */
    fun setArtwork(bytes: ByteArray) { require(bytes.size <= 256_000); cover = bytes.copyOf() }

    fun item(id: String, audioFile: File? = null): MediaItem? {
        val metadata = when (id) {
            ROOT_ID -> metadata("QuietChapter library", true, false, MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
            AUDIOBOOKS_ID -> metadata("Audiobooks", true, false, MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
            BOOK_ID -> metadata("A story that waits", true, true, MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            CHAPTER_ONE_ID -> metadata("Chapter 1 — The library", false, true, MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
            CHAPTER_TWO_ID -> metadata("Chapter 2 — The river", false, true, MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
            else -> return null
        }
        val builder = MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata)
        if (id !in setOf(ROOT_ID, AUDIOBOOKS_ID) && audioFile != null && playableFile(audioFile)) {
            builder.setUri(Uri.fromFile(audioFile))
            when (id) {
                CHAPTER_ONE_ID -> builder.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setEndPositionMs(24_000).build())
                CHAPTER_TWO_ID -> builder.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(24_000).build())
            }
        }
        return builder.build()
    }

    fun children(parentId: String): List<MediaItem> = when (parentId) {
        ROOT_ID -> listOfNotNull(item(AUDIOBOOKS_ID))
        AUDIOBOOKS_ID -> listOfNotNull(item(BOOK_ID))
        BOOK_ID -> listOfNotNull(item(CHAPTER_ONE_ID), item(CHAPTER_TWO_ID))
        else -> emptyList()
    }

    /** Small local catalog only; unknown or empty searches never select arbitrary media. */
    fun search(query: String): List<MediaItem> {
        val text = query.trim().lowercase(java.util.Locale.ROOT)
        if (text.isEmpty()) return emptyList()
        return listOfNotNull(item(BOOK_ID), item(CHAPTER_ONE_ID), item(CHAPTER_TWO_ID))
            .filter { it.mediaMetadata.title.toString().lowercase(java.util.Locale.ROOT).contains(text) }
    }

    /** Root/unknown IDs and missing or incomplete audio are never playable. */
    fun resolve(id: String, audioFile: File): MediaItem? =
        if (id in setOf(ROOT_ID, AUDIOBOOKS_ID) || !playableFile(audioFile)) null else item(id, audioFile)

    private fun playableFile(file: File) = file.isFile && file.canRead() && file.length() > 44

    private fun metadata(title: String, browsable: Boolean, playable: Boolean, type: Int) =
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist("QuietChapter Originals")
            .setAlbumTitle("A story that waits")
            .apply { if (type == MediaMetadata.MEDIA_TYPE_AUDIO_BOOK || type == MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER) {
                cover?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
                setArtworkUri(Uri.parse("android.resource://com.quietchapter/" + com.quietchapter.R.drawable.cover_story))
            } }
            .setIsBrowsable(browsable)
            .setIsPlayable(playable)
            .setMediaType(type)
            .build()
}
