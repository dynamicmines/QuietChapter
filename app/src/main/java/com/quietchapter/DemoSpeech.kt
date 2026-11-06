package com.quietchapter

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.UUID

/** Prepares the bundled original synthetic story; offline platform TTS is a fallback. */
class DemoSpeech(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var closed = false
    private var pendingFile: File? = null

    fun prepare(result: (File?, String) -> Unit) {
        val file = File(context.noBackupFilesDir, "quietchapter-original-v1.wav")
        val previous = File(context.cacheDir, file.name)
        if (file.length() <= 44 && previous.length() > 44) previous.copyTo(file, overwrite = true)
        if (file.length() > 44) {
            result(file, "Local spoken demo ready")
            return
        }
        // A late callback from a stopped engine must never delete a newer attempt.
        val temporary = File(context.noBackupFilesDir, "quietchapter-${UUID.randomUUID()}.partial.wav")
        pendingFile = temporary
        try {
            context.assets.open("demo-original.wav").use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            check(temporary.length() > 44 && temporary.renameTo(file))
            result(file, "Bundled original synthetic story ready")
            return
        } catch (_: java.io.FileNotFoundException) {
            temporary.delete() // Older/custom builds can still use their offline platform voice.
        } catch (_: Exception) {
            temporary.delete()
            result(null, "Bundled demo could not be prepared; retry or import your audio.")
            return
        }
        engine = TextToSpeech(context) { status ->
            handler.post {
                if (closed) return@post
                val tts = engine ?: return@post
                val voice = tts.voices?.filter {
                    it.locale.language == Locale.ENGLISH.language && !it.isNetworkConnectionRequired
                }?.maxByOrNull { it.quality }
                if (status != TextToSpeech.SUCCESS || voice == null) {
                    result(null, "Offline English TTS unavailable. Install a local voice in Android settings, then retry.")
                    return@post
                }
                tts.voice = voice
                tts.setSpeechRate(0.9f)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) {
                        handler.post {
                            if (closed) {
                                temporary.delete()
                            } else {
                                val valid = temporary.length() > 44 && temporary.renameTo(file)
                                result(file.takeIf { valid }, if (valid) "Local spoken demo synthesized" else "Speech file incomplete; retry")
                            }
                        }
                    }
                    @Deprecated("Platform callback")
                    override fun onError(id: String?) = failed()
                    override fun onError(id: String?, errorCode: Int) = failed()
                    private fun failed() {
                        temporary.delete()
                        handler.post { if (!closed) result(null, "Speech synthesis failed; retry preparation") }
                    }
                })
                val text = context.assets.open("demo.txt").bufferedReader().use { it.readText() }
                if (tts.synthesizeToFile(text, null, temporary, "original-demo") != TextToSpeech.SUCCESS) {
                    temporary.delete()
                    result(null, "Speech synthesis could not start")
                }
            }
        }
    }

    companion object {
        /** Runs before the service creates a player. Never rewrites unknown audio or bookmarks. */
        fun refreshKnownBundled(context: Context): Boolean {
            val file = File(context.noBackupFilesDir, "quietchapter-original-v1.wav")
            val previous = File(context.cacheDir, file.name)
            val existing = if (file.length() > 44) file else previous
            val metadata = runCatching {
                context.assets.open("demo-asset.json").bufferedReader().use { org.json.JSONObject(it.readText()) }
            }.getOrNull() ?: return false
            if (existing.length() != metadata.getLong("legacyAssetBytes") ||
                sha256(existing) != metadata.getString("legacyAssetSha256")) return false
            val staged = File(context.noBackupFilesDir, "quietchapter-upgrade-${UUID.randomUUID()}.partial.wav")
            return try {
                context.assets.open("demo-original.wav").use { input -> staged.outputStream().use { input.copyTo(it) } }
                check(sha256(staged) == metadata.getString("assetSha256"))
                check(staged.renameTo(file))
                true
            } catch (failure: Exception) {
                android.util.Log.w("QuietChapter", "Bundled asset upgrade failed; previous audio retained", failure)
                false
            } finally { staged.delete() }
        }
        private fun sha256(file: File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val bytes = ByteArray(16_384)
                while (true) {
                    val count = input.read(bytes)
                    if (count < 0) break
                    digest.update(bytes, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }

    fun close() {
        closed = true
        engine?.stop()
        engine?.shutdown()
        engine = null
        pendingFile?.delete()
        pendingFile = null
        handler.removeCallbacksAndMessages(null)
    }
}
