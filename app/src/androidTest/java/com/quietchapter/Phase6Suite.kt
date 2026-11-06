package com.quietchapter

import android.app.Activity
import android.app.ActivityManager
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.SeekBar
import com.quietchapter.media.DemoCatalog
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/** Real media/parked UI checks. Navigation remains synthetic; listening requires a human. */
class Phase6Suite(private val test: Instrumentation) {
    private lateinit var activity: MainActivity
    fun run(mode: String) {
        try {
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            ServiceTestClient(test).use { c ->
                c.pause(); c.command(QuietCommands.STOP_SIMULATION)
                try {
                    if (mode == "prepare_error") preparationError(c)
                    else {
                        c.prepare(); c.select(DemoCatalog.BOOK_ID); c.seek(0)
                        when (mode) {
                            "media" -> media(c)
                            "listen" -> listen(c)
                            else -> error("Unknown Phase6 mode")
                        }
                    }
                } finally { c.pause() }
            }
            test.finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "Phase6 $mode PASS\n") })
        } catch (failure: Throwable) {
            test.finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "Phase6 $mode FAIL: ${failure.stackTraceToString()}\n") })
        }
    }
    private fun preparationError(c: ServiceTestClient) {
        check(android.os.Build.FINGERPRINT.startsWith("Pleos/")) { "Official Pleos-only negative case" }
        check(!c.snapshot().getBoolean("demoReady")) { "Temporarily hold the debug test WAV before this case" }
        await("Prepare enabled") {
            var enabled = false
            c.main { enabled = activity.findViewById<Button>(R.id.prepare).isEnabled }
            enabled
        }
        c.main { activity.findViewById<Button>(R.id.prepare).performClick() }
        val message = test.targetContext.getString(R.string.audio_preparation_failed)
        await("visible preparation failure") {
            var shown = false
            c.main { shown = containsText(activity.window.decorView, message) }
            shown
        }
        check(!c.snapshot().getBoolean("actualPlaying"))
        SystemClock.sleep(300)
        val screenshot = test.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        java.io.File(test.targetContext.cacheDir, "phase6-preparation-error.png").outputStream().use {
            check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        screenshot.recycle()
        report("PASS genuine unavailable Pleos English voice shows persistent visible retry guidance; no playback")
    }

    private fun media(c: ServiceTestClient) {
        val before = c.snapshot()
        ServiceTestClient(test).use { other ->
            other.request { other.browser.getLibraryRoot(null) }
            SystemClock.sleep(400)
            check(!c.snapshot().getBoolean("actualPlaying") && c.snapshot().getInt("actualStarts") == before.getInt("actualStarts"))
        }
        val book = c.request { c.browser.getItem(DemoCatalog.BOOK_ID) }.value!!
        val chapters = c.request { c.browser.getChildren(DemoCatalog.BOOK_ID, 0, 10, null) }.value!!
        check(book.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
        check(chapters.size == 2 && chapters.all { it.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER })
        c.request { c.browser.search("river", null) }
        val results = c.request { c.browser.getSearchResult("river", 0, 10, null) }.value!!
        check(results.single().mediaId == DemoCatalog.CHAPTER_TWO_ID)
        c.request { c.browser.search("missing-result", null) }
        check(c.request { c.browser.getSearchResult("missing-result", 0, 10, null) }.value!!.isEmpty())
        report("PASS real browser connection/browse/search retains audiobook/chapter types and cannot autoplay")

        c.main {
            c.browser.setMediaItem(MediaItem.Builder().setRequestMetadata(
                MediaItem.RequestMetadata.Builder().setSearchQuery("river").build()).build())
            c.browser.prepare()
        }
        await("search resolves chapter") { c.snapshot().getString("mediaId") == DemoCatalog.CHAPTER_TWO_ID && c.snapshot().getLong("durationMs") > 0 }
        check(!c.snapshot().getBoolean("actualPlaying"))
        report("PASS search-based media request resolves known local chapter while paused")

        c.select(DemoCatalog.BOOK_ID); c.seek(35_000); c.pause()
        c.main { c.browser.seekToNextMediaItem() }
        SystemClock.sleep(200)
        check(c.snapshot().getLong("positionMs") >= 34_900) { "Next rewound the final chapter" }
        c.main { c.browser.seekToPreviousMediaItem() }
        await("previous chapter") { c.snapshot().getLong("positionMs") < 200 }
        c.main { c.browser.seekToNextMediaItem() }
        await("next chapter boundary") { c.snapshot().getLong("positionMs") in 23_900..24_100 }
        check(!c.snapshot().getBoolean("actualPlaying"))
        report("PASS standard next/previous honors chapter boundaries; final Next does not rewind or grant playback")

        c.select(DemoCatalog.CHAPTER_ONE_ID); c.play()
        await("chapter playing") { c.snapshot().getBoolean("actualPlaying") }
        c.main { c.browser.seekToNextMediaItem() }
        await("next chapter preserves explicit playback") { c.snapshot().getString("mediaId") == DemoCatalog.CHAPTER_TWO_ID && c.snapshot().getBoolean("actualPlaying") }
        c.pause()
        val starts = c.snapshot().getInt("actualStarts")
        c.main { c.browser.seekToPreviousMediaItem() }
        await("previous chapter paused") { c.snapshot().getString("mediaId") == DemoCatalog.CHAPTER_ONE_ID }
        check(!c.snapshot().getBoolean("actualPlaying") && c.snapshot().getInt("actualStarts") == starts)
        check(c.snapshot().getString("state") == "USER_PAUSED")
        report("PASS chapter-item skip keeps existing intent; paused skip never issues Play")

        c.select(DemoCatalog.BOOK_ID); c.pause()
        await("parked controls available") { var enabled = false; c.main { enabled = activity.findViewById<Button>(R.id.play).isEnabled }; enabled }
        val duration = c.snapshot().getLong("durationMs")
        val remaining = test.targetContext.getString(R.string.remaining_time,
            String.format(java.util.Locale.ROOT, "%d:%02d", duration / 60_000, (duration / 1_000) % 60))
        await("UI timeline reflects selected book") {
            var reflected = false
            c.main { reflected = containsText(activity.window.decorView, remaining) }
            reflected
        }
        c.main {
            activity.findViewById<SeekBar>(R.id.seek).performAccessibilityAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 500f) })
        }
        val half = c.snapshot().getLong("durationMs") / 2
        await("accessible actual seek") { kotlin.math.abs(c.snapshot().getLong("positionMs") - half) < 300 }
        c.main { activity.findViewById<Button>(R.id.chapter_two).performClick() }
        await("UI chapter selection") { c.snapshot().getString("mediaId") == DemoCatalog.CHAPTER_TWO_ID }
        check(!c.snapshot().getBoolean("actualPlaying") && c.snapshot().getString("state") == "USER_PAUSED")
        report("PASS accessible SeekBar changes actual position; parked chapter selection stays manually paused")
        c.main { c.browser.stop() }
        await("standard Stop") {
            val stopped = c.snapshot()
            !stopped.getBoolean("actualPlayWhenReady") &&
                stopped.getInt("actualPlaybackState") == androidx.media3.common.Player.STATE_IDLE &&
                stopped.getString("state") in listOf("USER_PAUSED", "INTERRUPTED")
        }
        report("PASS standard Stop reaches real player; reconnect/search/selection never grants automatic Play")
    }

    private fun listen(c: ServiceTestClient) {
        val audio = test.targetContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        report("OUTPUT " + audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString { "${it.type}:${it.productName}" })
        report("VOLUME ${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}")
        c.seek(22_000)
        await("seek before listening") { c.snapshot().getLong("positionMs") in 21_900..22_100 }
        val requested = SystemClock.elapsedRealtime()
        c.play(); await("audible renderer start") { c.snapshot().getBoolean("actualPlaying") }
        report("LISTEN_START frameworkStartLatencyMs=${SystemClock.elapsedRealtime() - requested}; acoustic latency UNVERIFIED")
        SystemClock.sleep(3_000)
        val crossed = c.snapshot()
        check(crossed.getLong("positionMs") > 24_000) { "Chapter crossing failed: $crossed" }
        c.pause(); SystemClock.sleep(700)
        check(!c.snapshot().getBoolean("actualPlaying"))
        c.play(); await("resume") { c.snapshot().getBoolean("actualPlaying") }
        SystemClock.sleep(2_000)
        c.seek(8_000); SystemClock.sleep(2_000)
        shell("input keyevent KEYCODE_SLEEP"); SystemClock.sleep(2_000)
        check(c.snapshot().getBoolean("actualPlaying"))
        shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
        c.main { (test.targetContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).appTasks.forEach { it.finishAndRemoveTask() } }
        SystemClock.sleep(2_000)
        check(c.snapshot().getBoolean("actualPlaying"))
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener({ }).build()
        try {
            c.main { check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) }
            await("actual focus interruption") { !c.snapshot().getBoolean("actualPlaying") }
            c.main { audio.abandonAudioFocusRequest(focus) }
            SystemClock.sleep(800)
            check(!c.snapshot().getBoolean("actualPlaying"))
            c.play(); await("explicit focus recovery") { c.snapshot().getBoolean("actualPlaying") }
            SystemClock.sleep(2_000); c.pause()
        } finally {
            c.main { audio.abandonAudioFocusRequest(focus) }
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
        }
        report("LISTEN_END real chapter boundary/Pause/Play/seek/screen-off/task removal/focus recovery completed; human listening result separate")
    }
    private fun await(name: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < end) { if (condition()) return; SystemClock.sleep(40) }
        error(name)
    }
    private fun containsText(view: android.view.View, text: String): Boolean =
        (view is android.widget.TextView && view.text.toString() == text) ||
            (view is android.view.ViewGroup && (0 until view.childCount).any { containsText(view.getChildAt(it), text) })
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        test.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun report(message: String) = test.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
}
