package com.quietchapter

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.session.SessionCommand
import com.quietchapter.media.LocalAudioStore

/** Real SAF-selected document/service checks. No synthetic SDK navigation or audible claims. */
class LocalAudioSuite(private val test: Instrumentation) {
    fun run(mode: String) {
        try {
            val activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            ServiceTestClient(test).use { c ->
                await("restore validation") { !c.snapshot().getBoolean("loadingLocal") }
                try { when (mode) {
                    "picker" -> {
                        await("parked import enabled") { var ready = false; c.main { ready = activity.findViewById<android.widget.Button>(R.id.import_audio).isEnabled }; ready }
                        val priorRevision = c.snapshot().getInt("mediaRevision")
                        c.main { activity.findViewById<android.widget.Button>(R.id.import_audio).performClick() }
                        report("PICKER_OPEN; actual document selection is performed through system picker")
                        // Keep the result-receiving Activity alive for the external UI operator.
                        await("SAF import completed", 90_000) { val s = c.snapshot(); s.getInt("mediaRevision") > priorRevision && s.getBoolean("localSelected") && s.getLong("durationMs") > 0 }
                        check(!c.snapshot().getBoolean("actualPlayWhenReady"))
                        check(c.snapshot().getString("state") == "USER_PAUSED")
                        check(c.snapshot().getBoolean("localPersistent")) { "Expected persisted read on this provider" }
                        report("PASS actual ACTION_OPEN_DOCUMENT selection, persisted read grant, paused local media preparation")
                    }
                    "provider_fixture" -> {
                        check(android.os.Build.VERSION.SDK_INT >= 29)
                        val resolver = test.targetContext.contentResolver
                        val source = java.io.File(test.targetContext.cacheDir, "phase6b-fixture.wav")
                        check(source.length() > 44) { "Install only the original known test WAV in debug cache first" }
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, "QuietChapter-original-test.wav")
                            put(android.provider.MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                            put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, "Music/QuietChapterPhase6B")
                            put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
                        }
                        val uri = resolver.insert(android.provider.MediaStore.Audio.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: error("MediaStore unavailable")
                        try {
                            resolver.openOutputStream(uri)!!.use { output -> source.inputStream().use { it.copyTo(output) } }
                            resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
                            c.command(QuietCommands.IMPORT_AUDIO, Bundle().apply { putString("uri", uri.toString()) })
                            await("provider fixture ready") { c.snapshot().getLong("durationMs") > 40_000 }
                            check(!c.snapshot().getBoolean("actualPlayWhenReady"))
                            check(c.snapshot().getString("state") == "USER_PAUSED")
                            val digest = java.security.MessageDigest.getInstance("SHA-256")
                            resolver.openInputStream(uri)!!.use { input -> val bytes = ByteArray(8192); while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) } }
                            report("PASS TEST_ONLY MediaStore-owned content fixture; sha256=" + digest.digest().joinToString("") { "%02x".format(it) } + "; SAF UI BLOCKED, persistedRead=${c.snapshot().getBoolean("localPersistent")}")
                        } catch (failure: Throwable) { resolver.delete(uri, null, null); throw failure }
                    }
                    "legacy" -> legacy(c)
                    "local" -> local(c)
                    "unavailable" -> {
                        val s = c.snapshot()
                        check(!s.getBoolean("actualPlayWhenReady") && !s.getBoolean("actualPlaying"))
                        check(!s.getBoolean("localAvailable"))
                        check(s.getString("mediaError").orEmpty().isNotEmpty())
                        val starts = s.getInt("actualStarts")
                        c.play(); SystemClock.sleep(800)
                        check(!c.snapshot().getBoolean("actualPlayWhenReady") && c.snapshot().getInt("actualStarts") == starts)
                        report("PASS inaccessible/corrupted persisted document restores silent with recovery error; no demo substitution")
                    }
                    "restore" -> {
                        val s = c.snapshot()
                        check(s.getString("mediaId") == LocalAudioStore.LOCAL_ID)
                        check(s.getLong("positionMs") in 11_900..12_100)
                        check(!s.getBoolean("actualPlayWhenReady"))
                        report("PASS local provider access, title, bookmark and paused restoration after real process restart; persistedRead=${s.getBoolean("localPersistent")}")
                    }
                    "listen" -> {
                        c.pause(); c.command(QuietCommands.STOP_SIMULATION)
                        c.select(LocalAudioStore.LOCAL_ID); c.seek(0)
                        val audio = test.targetContext.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                        report("OUTPUT " + audio.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).joinToString { "${it.type}:${it.productName}" })
                        report("VOLUME ${audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)}")
                        val start = SystemClock.elapsedRealtime()
                        c.play(); await("framework start") { c.snapshot().getBoolean("actualPlaying") }
                        report("FRAMEWORK_START elapsedMs=${SystemClock.elapsedRealtime()} delayMs=${SystemClock.elapsedRealtime()-start}; audibility UNVERIFIED")
                        repeat(10) { SystemClock.sleep(1_000); val s = c.snapshot(); report("SAMPLE elapsedMs=${SystemClock.elapsedRealtime()} positionMs=${s.getLong("positionMs")} playing=${s.getBoolean("actualPlaying")}") }
                        val advanced = c.snapshot().getLong("positionMs")
                        c.pause()
                        val immediate = c.snapshot()
                        report("PAUSE_IMMEDIATE elapsedMs=${SystemClock.elapsedRealtime()} positionMs=${immediate.getLong("positionMs")} ready=${immediate.getBoolean("actualPlayWhenReady")} playing=${immediate.getBoolean("actualPlaying")}")
                        await("pause applied") { !c.snapshot().getBoolean("actualPlayWhenReady") }
                        val stopped = c.snapshot().getLong("positionMs")
                        val starts = c.snapshot().getInt("actualStarts")
                        repeat(6) {
                            SystemClock.sleep(100)
                            val paused = c.snapshot()
                            report("PAUSE_SAMPLE elapsedMs=${SystemClock.elapsedRealtime()} positionMs=${paused.getLong("positionMs")} ready=${paused.getBoolean("actualPlayWhenReady")} playing=${paused.getBoolean("actualPlaying")} starts=${paused.getInt("actualStarts")}")
                            check(!paused.getBoolean("actualPlayWhenReady") && paused.getInt("actualStarts") == starts)
                        }
                        val drift = c.snapshot().getLong("positionMs") - stopped
                        check(kotlin.math.abs(drift) < 150) { "Paused timeline corrected by ${drift}ms after command barrier; no Play inferred" }
                        c.seek(20_000); c.play(); SystemClock.sleep(2_000); c.pause()
                        check(advanced > 8_000) { "Decoder/output progression stalled: positionMs=$advanced after 10s" }
                        report("PASS framework Pause/resume/seek completed; sound/latency/stutter require separate listener evidence")
                    }
                    else -> error("Unknown local-audio mode")
                }
                } finally { c.pause() }
            }
            test.finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "LocalAudio $mode PASS\n") })
        } catch (failure: Throwable) {
            test.finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "LocalAudio $mode FAIL: ${failure.stackTraceToString()}\n") })
        }
    }
    private fun legacy(c: ServiceTestClient) {
        c.pause(); c.command(QuietCommands.STOP_SIMULATION)
        val starts = c.snapshot().getInt("actualStarts")
        val hasLocal = c.snapshot().getBoolean("localAvailable")
        var browser: android.media.browse.MediaBrowser? = null
        val connected = java.util.concurrent.CountDownLatch(1)
        val children = java.util.concurrent.CountDownLatch(1)
        val chapters = java.util.concurrent.CountDownLatch(1)
        val books = java.util.concurrent.CountDownLatch(1)
        var failure: String? = null
        c.main {
            browser = android.media.browse.MediaBrowser(test.targetContext,
                android.content.ComponentName(test.targetContext, PlaybackService::class.java),
                object : android.media.browse.MediaBrowser.ConnectionCallback() {
                    override fun onConnected() { connected.countDown() }
                    override fun onConnectionFailed() { failure = "Legacy connection rejected"; connected.countDown() }
                }, null)
            browser!!.connect()
        }
        try {
            check(connected.await(10, java.util.concurrent.TimeUnit.SECONDS)); check(failure == null) { failure!! }
            c.main {
                browser!!.subscribe(browser!!.root, object : android.media.browse.MediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, items: MutableList<android.media.browse.MediaBrowser.MediaItem>) {
                        if (items.size != 1 || items.single().mediaId != com.quietchapter.media.DemoCatalog.AUDIOBOOKS_ID || items.any { !it.isBrowsable || it.isPlayable }) failure = "Legacy root must contain only a browsable tab"
                        children.countDown()
                    }
                    override fun onError(parentId: String) { failure = "Legacy root browse failed"; children.countDown() }
                })
                browser!!.subscribe(com.quietchapter.media.DemoCatalog.AUDIOBOOKS_ID, object : android.media.browse.MediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, items: MutableList<android.media.browse.MediaBrowser.MediaItem>) {
                        if (items.none { it.mediaId == com.quietchapter.media.DemoCatalog.BOOK_ID && it.isBrowsable && it.isPlayable }) failure = "Legacy audiobook missing"
                        if (hasLocal && items.none { it.mediaId == LocalAudioStore.LOCAL_ID }) failure = "Imported book missing from Audiobooks"
                        books.countDown()
                    }
                    override fun onError(parentId: String) { failure = "Legacy audiobook folder failed"; books.countDown() }
                })
                browser!!.subscribe(com.quietchapter.media.DemoCatalog.BOOK_ID, object : android.media.browse.MediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, items: MutableList<android.media.browse.MediaBrowser.MediaItem>) {
                        if (items.size != 2 || items.any { !it.isPlayable }) failure = "Legacy chapter browse invalid"
                        chapters.countDown()
                    }
                    override fun onError(parentId: String) { failure = "Legacy chapter browse failed"; chapters.countDown() }
                })
            }
            check(children.await(10, java.util.concurrent.TimeUnit.SECONDS) && books.await(10, java.util.concurrent.TimeUnit.SECONDS) && chapters.await(10, java.util.concurrent.TimeUnit.SECONDS))
            check(failure == null) { failure!! }
            check(c.snapshot().getInt("actualStarts") == starts && !c.snapshot().getBoolean("actualPlayWhenReady"))
            if (c.snapshot().getBoolean("localAvailable")) c.select(LocalAudioStore.LOCAL_ID)
            else { c.prepare(); c.select(com.quietchapter.media.DemoCatalog.BOOK_ID) }
            c.play(); await("explicit playback before legacy Pause") { c.snapshot().getBoolean("actualPlaying") }
            c.main { android.media.session.MediaController(test.targetContext, browser!!.sessionToken).transportControls.pause() }
            await("legacy Pause stops actual playback") {
                val paused = c.snapshot()
                paused.getString("state") == "USER_PAUSED" && !paused.getBoolean("actualPlayWhenReady") && !paused.getBoolean("actualPlaying")
            }
            report("PASS public platform MediaBrowser legacy bridge: audiobook root/2chapters and platform MediaController actively stops playing audio, no connection autoplay; native host remains separate")
        } finally { c.main { browser?.disconnect() } }
    }

    private fun local(c: ServiceTestClient) {
        c.pause(); c.command(QuietCommands.STOP_SIMULATION)
        c.select(LocalAudioStore.LOCAL_ID)
        val initial = c.snapshot()
        check(initial.getString("localTitle").orEmpty().isNotBlank())
        check(initial.getLong("durationMs") > 40_000)
        c.play(); await("local decoding") { c.snapshot().getBoolean("actualPlaying") }
        SystemClock.sleep(1_200)
        check(c.snapshot().getLong("positionMs") > 500)
        c.pause(); c.seek(12_000)
        await("seek barrier") { c.snapshot().getLong("positionMs") in 11_900..12_100 }
        val starts = c.snapshot().getInt("actualStarts")
        // Real second browser, ticker polling, Activity background/reopen and simulated clear.
        c.navigation(5)
        c.main { activityTasks().forEach { it.finishAndRemoveTask() } }
        test.startActivitySync(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ServiceTestClient(test).use { other -> other.request { other.browser.getLibraryRoot(null) } }
        repeat(8) { c.navigation(40); c.snapshot(); SystemClock.sleep(400) }
        check(!c.snapshot().getBoolean("actualPlayWhenReady"))
        check(c.snapshot().getInt("actualStarts") == starts)
        check(c.snapshot().getString("state") == "USER_PAUSED")
        report("PASS imported audio preparation/metadata/seek; manual Pause survives navigation clear, browser reconnect, ticker and Activity reopen without extra Play")
        c.play(); await("one explicit restart") { c.snapshot().getBoolean("actualPlaying") }
        check(c.snapshot().getInt("actualStarts") == starts + 1)
        c.pause()
        c.main { c.browser.seekToNextMediaItem() }
        check(!c.snapshot().getBoolean("actualPlaying"))
        report("PASS one explicit Play creates one start; local whole-file skip invents no chapters or autoplay")
        val title = LocalAudioStore(test.targetContext).read()!!.title
        for (uri in listOf("file:///not-permitted.wav", "content://missing.quietchapter.provider/unavailable")) {
            val result = c.request { c.browser.sendCustomCommand(SessionCommand(QuietCommands.IMPORT_AUDIO, Bundle.EMPTY), Bundle().apply { putString("uri", uri) }) }
            check(result.resultCode != androidx.media3.session.SessionResult.RESULT_SUCCESS)
            check(!c.snapshot().getBoolean("actualPlayWhenReady"))
            check(LocalAudioStore(test.targetContext).read()!!.title == title)
        }
        report("PASS non-document and inaccessible imports fail visibly/silent without replacing saved metadata")
        c.select(LocalAudioStore.LOCAL_ID); c.seek(12_000)
        await("bookmark seek") { c.snapshot().getLong("positionMs") in 11_900..12_100 }
        c.pause(); SystemClock.sleep(1_200)
    }
    private fun activityTasks() = (test.targetContext.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).appTasks
    private fun await(name: String, timeout: Long = 10_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) { if (condition()) return; SystemClock.sleep(50) }
        error(name)
    }
    private fun report(message: String) = test.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
}
