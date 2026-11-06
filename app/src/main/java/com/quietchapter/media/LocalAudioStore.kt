package com.quietchapter.media

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** One explicitly selected document. No playback or automatic-resume intent is stored here. */
data class LocalAudio(
    val uri: Uri,
    val title: String,
    val durationMs: Long,
    val mime: String?,
    val artist: String? = null,
    val album: String? = null,
    val persistentRead: Boolean = false,
    val contentIdentity: String? = null,
) {
    /** Metadata-only construction is safe on the session looper; document access is separate. */
    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(LocalAudioStore.LOCAL_ID)
        .setUri(uri)
        .setMediaMetadata(MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setDurationMs(durationMs)
            .setExtras(android.os.Bundle().apply { putString(LocalAudioStore.CONTENT_IDENTITY, contentIdentity) })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            .build())
        .build()
}

/** All methods that access documents or disk must be called on the owner's I/O executor. */
class LocalAudioStore(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val metadata = AtomicFile(File(app.noBackupFilesDir, "local-audio-v1.json"))

    @Synchronized
    fun read(): LocalAudio? = try {
        val bytes = metadata.openRead().use { input ->
            val buffer = ByteArray(MAX_RECORD_BYTES + 1)
            var size = 0
            while (size < buffer.size) {
                val count = input.read(buffer, size, buffer.size - size)
                if (count < 0) break
                size += count
            }
            buffer.copyOf(size)
        }
        if (bytes.size > MAX_RECORD_BYTES) null else {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val uri = Uri.parse(json.getString("uri"))
            val title = json.getString("title")
            val duration = json.getLong("durationMs")
            val version = json.getInt("version")
            val identity = json.text("contentIdentity")
            if (version !in 1..2 || (version == 2 && (identity == null || !identity.matches(Regex("[0-9a-f]{32}")))) || !validUri(uri) ||
                title.isBlank() || title.length > MAX_TEXT || duration <= 0) null
            else LocalAudio(uri, title, duration, json.text("mime"), json.text("artist"),
                json.text("album"), json.optBoolean("persistentRead"), identity)
        }
    } catch (_: Exception) {
        // Missing/corrupt private metadata must never start playback or crash the service.
        null
    }

    /**
     * Validates a real audio track, then atomically replaces the single imported-book record.
     * A provider may decline persistence; that import remains usable for its current grant,
     * and persistentRead=false lets the UI explain that the document may need reselection.
     * Parseable metadata is not proof the entire audio stream can be decoded by Media3.
     */
    @Synchronized
    @Throws(IOException::class)
    fun import(uri: Uri, grantFlags: Int): LocalAudio {
        require(validUri(uri)) { "Choose an audio document using the system file picker" }
        val previous = read()
        val inspected = inspect(uri)
        val alreadyPersisted = persistedRead(uri)
        val offered = grantFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0 &&
            grantFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        if (offered && !alreadyPersisted) {
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Some document providers advertise persistence but do not grant it.
            }
        }
        val imported = inspected.copy(persistentRead = persistedRead(uri),
            contentIdentity = java.util.UUID.randomUUID().toString().replace("-", ""))
        try {
            write(imported)
        } catch (failure: IOException) {
            if (!alreadyPersisted && imported.persistentRead) releaseRead(uri)
            throw failure
        }
        if (previous != null && previous.uri != uri && previous.persistentRead) releaseRead(previous.uri)
        return imported
    }

    /** Revalidates provider access and audio metadata; never falls back to a different book. */
    @Synchronized
    fun resolve(): MediaItem? = read()?.let { saved ->
        try {
            validate(saved).toMediaItem()
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Restore-time validation performs no persistence mutation and grants no new access. */
    @Throws(IOException::class)
    fun validate(audio: LocalAudio): LocalAudio {
        require(validUri(audio.uri)) { "Invalid audio document" }
        return inspect(audio.uri).copy(persistentRead = persistedRead(audio.uri), contentIdentity = audio.contentIdentity)
    }

    private fun inspect(uri: Uri): LocalAudio {
        val retriever = MediaMetadataRetriever()
        try {
            // ContentResolver access is used by MMR, and later by Media3's ContentDataSource.
            retriever.setDataSource(app, uri)
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0
            if (hasAudio != "yes" || hasVideo == "yes" || duration <= 0) {
                throw IOException("This document is not supported audio")
            }
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                .clean() ?: displayName(uri) ?: "Imported audiobook"
            return LocalAudio(uri, title, duration,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).clean()
                    ?: resolver.getType(uri).clean(),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).clean(),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).clean())
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Exception) {
            // The UI receives a non-sensitive message, never provider paths or book metadata.
            throw IOException("Cannot read this audio document; choose another file", failure)
        } finally {
            try { retriever.release() } catch (_: Exception) { /* Best-effort resource cleanup. */ }
        }
    }

    private fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0).clean() else null
        }
    } catch (_: Exception) { null }

    private fun persistedRead(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    private fun releaseRead(uri: Uri) {
        try { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: SecurityException) { /* Provider may already have revoked access. */ }
    }

    private fun write(audio: LocalAudio) {
        val json = JSONObject().put("version", 2).put("contentIdentity", audio.contentIdentity).put("uri", audio.uri.toString())
            .put("title", audio.title).put("durationMs", audio.durationMs)
            .put("mime", audio.mime).put("artist", audio.artist).put("album", audio.album)
            .put("persistentRead", audio.persistentRead)
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_RECORD_BYTES) throw IOException("Audio document metadata is too large")
        val stream = metadata.startWrite()
        try {
            stream.write(bytes)
            metadata.finishWrite(stream)
        } catch (failure: IOException) {
            metadata.failWrite(stream)
            throw failure
        }
    }

    private fun JSONObject.text(name: String): String? = optString(name).clean()
    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TEXT)

    companion object {
        const val LOCAL_ID = "local:book"
        const val CONTENT_IDENTITY = "quietchapter.contentIdentity"
        private const val MAX_TEXT = 240
        private const val MAX_RECORD_BYTES = 32_768
        private fun validUri(uri: Uri) = uri.scheme == "content" &&
            !uri.authority.isNullOrBlank() && uri.toString().length <= 8_192
    }
}
