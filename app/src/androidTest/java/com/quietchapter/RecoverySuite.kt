package com.quietchapter

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.quietchapter.media.LocalAudioStore
import java.io.File

/** Executes recovery against the real service/provider. No navigation SDK events are generated. */
class RecoverySuite(private val test: Instrumentation) {
    fun run(mode: String) {
        try {
            ServiceTestClient(test).use { c ->
                try {
                    when (mode) {
                        "clock_seek" -> {
                            await("local validation") { !c.snapshot().getBoolean("loadingLocal") }
                            c.select(LocalAudioStore.LOCAL_ID); c.seek(0); c.play()
                            await("real playback") { c.snapshot().getBoolean("actualPlaying") }
                            c.command(QuietCommands.DEBUG_FREEZE_CLOCK)
                            SystemClock.sleep(7_000)
                            c.seek(0)
                            SystemClock.sleep(1_800)
                            check(c.snapshot().getBoolean("actualPlayWhenReady")) { "Late explicit seek inherited old guard timeout" }
                            SystemClock.sleep(2_000)
                            check(c.snapshot().getBoolean("actualPlayWhenReady"))
                            check(!c.snapshot().getString("mediaError").orEmpty().contains("progress stalled"))
                            report("PASS TEST_ONLY clock fault plus genuine late seek resets the guard deadline; playback is not falsely paused")
                        }
                        "clock_fault" -> {
                            await("local validation") { !c.snapshot().getBoolean("loadingLocal") }
                            c.select(LocalAudioStore.LOCAL_ID); c.seek(0); c.play()
                            await("real playback") { c.snapshot().getBoolean("actualPlaying") }
                            val starts = c.snapshot().getInt("actualStarts")
                            c.command(QuietCommands.DEBUG_FREEZE_CLOCK)
                            await("guard pauses actual player", 12_000) {
                                val s = c.snapshot()
                                s.getString("mediaError").orEmpty().contains("progress stalled") &&
                                    !s.getBoolean("actualPlayWhenReady") && !s.getBoolean("actualPlaying")
                            }
                            check(c.snapshot().getString("state") == "INTERRUPTED")
                            c.navigation(40); SystemClock.sleep(2_100); c.navigation(40)
                            check(!c.snapshot().getBoolean("actualPlayWhenReady"))
                            check(c.snapshot().getInt("actualStarts") == starts)
                            check(c.snapshot().getString("log").orEmpty().lines().count { it.contains("Playback clock stalled →") } == 1)
                            report("PASS TEST_ONLY frozen-clock injection pauses actual player once, shows recovery error and never automatically retries")
                        }
                        "import_retention" -> {
                            await("local validation") { !c.snapshot().getBoolean("loadingLocal") }
                            c.select(LocalAudioStore.LOCAL_ID); c.seek(12_000)
                            await("initial bookmark") { c.snapshot().getLong("positionMs") in 11_900..12_100 }
                            c.pause()
                            val before = c.snapshot()
                            val failed = c.request { c.browser.sendCustomCommand(SessionCommand(QuietCommands.IMPORT_AUDIO, Bundle.EMPTY), Bundle().apply { putString("uri", "content://quietchapter.missing/audio") }) }
                            check(failed.resultCode != SessionResult.RESULT_SUCCESS)
                            val after = c.snapshot()
                            check(after.getString("mediaId") == before.getString("mediaId")) { "Invalid import discarded the selected book" }
                            check(after.getLong("positionMs") in 11_900..12_100) { "Invalid import lost position" }
                            check(!after.getBoolean("actualPlayWhenReady") && after.getInt("actualStarts") == before.getInt("actualStarts"))
                            c.play(); await("explicit recovery of retained book") { c.snapshot().getBoolean("actualPlaying") }
                            c.pause(); c.seek(12_000); c.pause()
                            report("PASS invalid import preserves the actual book/12s position paused; explicit Play recovers the same book")
                        }
                        "prepare_restore_pause", "prepare_restore_selection" -> {
                            await("local validation") { !c.snapshot().getBoolean("loadingLocal") }
                            c.select(LocalAudioStore.LOCAL_ID); c.seek(12_000)
                            await("bookmark position") { c.snapshot().getLong("positionMs") in 11_900..12_100 }
                            c.pause(); SystemClock.sleep(1_200)
                            File(test.targetContext.noBackupFilesDir, "test-restore-delay-ms").writeText("5000")
                            report("READY delayed real local restore; finish/kill the instrumentation process before verification")
                        }
                        "restore_pause" -> {
                            check(c.snapshot().getBoolean("loadingLocal")) { "Restore delay did not overlap test" }
                            c.pause()
                            await("paused local restore", 8_000) {
                                val s = c.snapshot()
                                !s.getBoolean("loadingLocal") && s.getString("mediaId") == LocalAudioStore.LOCAL_ID && s.getLong("durationMs") > 0
                            }
                            val s = c.snapshot()
                            check(s.getLong("positionMs") in 11_900..12_100)
                            check(s.getString("state") == "USER_PAUSED" && !s.getBoolean("actualPlayWhenReady"))
                            check(s.getInt("actualStarts") == 0)
                            report("PASS manual Pause during delayed provider validation preserves paused selection/12s bookmark; no starts")
                        }
                        "restore_selection" -> {
                            check(c.snapshot().getBoolean("loadingLocal"))
                            c.select(com.quietchapter.media.DemoCatalog.CHAPTER_ONE_ID)
                            await("old restore completion", 8_000) { !c.snapshot().getBoolean("loadingLocal") }
                            val s = c.snapshot()
                            check(s.getString("mediaId") == com.quietchapter.media.DemoCatalog.CHAPTER_ONE_ID)
                            check(s.getLong("positionMs") < 150 && !s.getBoolean("actualPlayWhenReady"))
                            report("PASS explicit chapter selection invalidates pending old restoration and seek target")
                        }
                        "replacement" -> {
                            await("local validation") { !c.snapshot().getBoolean("loadingLocal") }
                            c.select(LocalAudioStore.LOCAL_ID); c.seek(12_000); c.pause()
                            val oldBookmark = File(test.targetContext.noBackupFilesDir, "playback-bookmark-v1")
                            SystemClock.sleep(1_200)
                            val old = oldBookmark.readBytes()
                            val registered = LocalAudioStore(test.targetContext).read()!!
                            c.command(QuietCommands.IMPORT_AUDIO, Bundle().apply { putString("uri", registered.uri.toString()) })
                            await("replacement ready") { c.snapshot().getLong("durationMs") > 0 }
                            val current = LocalAudioStore(test.targetContext).read()!!
                            check(current.contentIdentity != null && current.contentIdentity != registered.contentIdentity)
                            check(c.snapshot().getLong("positionMs") < 150 && !c.snapshot().getBoolean("actualPlayWhenReady"))
                            // Simulate stale durable bookmark bytes, as after a crash/write failure.
                            c.pause(); SystemClock.sleep(1_200)
                            oldBookmark.writeBytes(old)
                            File(test.targetContext.noBackupFilesDir, "test-stale-bookmark").writeBytes(old)
                            report("PASS replacement gets new registration identity and starts at0 paused; stale bookmark captured for host restore test")
                        }
                        "replacement_restore" -> {
                            await("replacement restore") { !c.snapshot().getBoolean("loadingLocal") && c.snapshot().getLong("durationMs") > 0 }
                            val s = c.snapshot()
                            check(s.getString("mediaId") == LocalAudioStore.LOCAL_ID && s.getLong("positionMs") < 150)
                            check(!s.getBoolean("actualPlayWhenReady") && s.getInt("actualStarts") == 0)
                            report("PASS stale previous-registration bookmark cannot seek or autoplay a replacement book")
                        }
                        else -> error("Unknown recovery mode")
                    }
                } finally { c.pause() }
            }
            test.finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "Recovery $mode PASS\n") })
        } catch (failure: Throwable) {
            test.finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "Recovery $mode FAIL: ${failure.stackTraceToString()}\n") })
        }
    }
    private fun await(name: String, ms: Long = 8_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < deadline) { if (condition()) return; SystemClock.sleep(40) }
        error(name)
    }
    private fun report(message: String) = test.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
}
