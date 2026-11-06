package com.quietchapter

import android.app.Activity
import android.app.ActivityManager
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import com.quietchapter.media.DemoCatalog
import java.io.File

/** Stationary emulator checks. Framework state never substitutes for physical listening. */
class Phase3Suite(private val test: Instrumentation) {
    private lateinit var activity: MainActivity
    fun run(mode: String) {
        var failure: Throwable? = null
        try {
            activity = launch()
            ServiceTestClient(test).use { client ->
                client.prepare()
                client.pause()
                client.command(QuietCommands.STOP_SIMULATION)
                client.select(DemoCatalog.BOOK_ID)
                client.seek(0)
                when (mode) {
                    "audible" -> audible(client)
                    "lifecycle" -> lifecycle(client)
                    "external" -> external(client)
                    "noisy_whitebox" -> NoisyCallbackProbe(test, activity).run()
                    else -> error("Unknown phase3 mode")
                }
                client.pause()
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
        }
        if (failure == null) {
            report("QuietChapter phase3 $mode PASS")
            test.finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "Phase3 $mode PASS\n") })
        } else {
            report("FAIL phase3 $mode: ${failure.message}")
            test.finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", failure.stackTraceToString()) })
        }
    }

    private fun audible(c: ServiceTestClient) {
        val audio = test.targetContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        report("OUTPUT_DEVICES " + audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString { "${it.type}:${it.productName}" })
        report("MEDIA_VOLUME ${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)} muted=${audio.isStreamMute(AudioManager.STREAM_MUSIC)}")
        c.play()
        await("actual player starts") { c.snapshot().getBoolean("actualPlaying") }
        val before = c.snapshot().getLong("positionMs")
        report("LISTEN_START original spoken demo, eight seconds")
        SystemClock.sleep(8_000)
        val after = c.snapshot()
        check(after.getBoolean("actualPlaying") && after.getLong("positionMs") > before + 6_000)
        c.pause()
        report("LISTEN_END framework playback advanced; physical audibility requires listener confirmation")
    }

    private fun lifecycle(c: ServiceTestClient) {
        c.play()
        await("playback before sleep") { c.snapshot().getBoolean("actualPlaying") }
        val before = c.snapshot()
        shell("input keyevent KEYCODE_SLEEP")
        val power = test.targetContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        await("display asleep") { !power.isInteractive }
        SystemClock.sleep(2_000)
        val asleep = c.snapshot()
        check(asleep.getBoolean("actualPlaying") && asleep.getLong("positionMs") > before.getLong("positionMs") + 1_000)
        check(asleep.getString("serviceInstance") == before.getString("serviceInstance"))
        shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
        report("PASS screen sleep/wake retains actual playback, advancing position and same service/player; no secure-PIN lock claim")
        c.command(QuietCommands.STOP_SIMULATION)
        c.play()
        c.navigation(5)
        await("automatic pause") { c.snapshot().getString("state") == "AUTO_PAUSED" }
        c.pause()
        await("manual pause") { c.snapshot().getString("state") == "USER_PAUSED" }
        val starts = c.snapshot().getInt("actualStarts")
        shell("input keyevent KEYCODE_SLEEP")
        c.navigation(60); SystemClock.sleep(2_200); c.navigation(60)
        check(c.snapshot().getString("state") == "USER_PAUSED" && c.snapshot().getInt("actualStarts") == starts)
        shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
        report("PASS manual Pause survives route clear during screen sleep without another actual start")
        // Treat task removal as a separate scenario: Pleos may retire the parked task over sleep.
        // Relaunch only our Activity; never remove or restart a proprietary host task.
        activity = launch()
        await("fresh own task before removal") {
            var exists = false
            c.main { exists = (test.targetContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).appTasks.isNotEmpty() }
            exists
        }
        c.command(QuietCommands.STOP_SIMULATION)
        c.play()
        await("playing before task removal") { c.snapshot().getBoolean("actualPlaying") }
        val pos = c.snapshot().getLong("positionMs")
        removeTasks(c)
        await("onTaskRemoved playing") { c.snapshot().getString("log")!!.contains("Task removed; actualPlaying=true") }
        SystemClock.sleep(1_500)
        if (test.targetContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)) {
            check(!c.snapshot().getBoolean("actualPlayWhenReady") && c.snapshot().getString("state") in listOf("INTERRUPTED","USER_PAUSED"))
            check(c.snapshot().getInt("actualPlaybackState")==androidx.media3.common.Player.STATE_IDLE)
            report("PASS Automotive AppTask removal stops player and revokes resume intent; bound observer does not grant playback")
        } else {
            check(c.snapshot().getBoolean("actualPlaying") && c.snapshot().getLong("positionMs") > pos + 500)
            report("PASS phone AppTask removal while playing retains foreground service and advancing playback")
        }
        activity = launch()
        c.command(QuietCommands.STOP_SIMULATION)
        c.play()
        c.command(QuietCommands.SIMULATE)
        await("automatic pause before task removal") { c.snapshot().getString("state") == "AUTO_PAUSED" }
        val blockedStarts = c.snapshot().getInt("actualStarts")
        removeTasks(c)
        await("task removal cancels pending resume") {
            val state = c.snapshot()
            state.getString("log")!!.contains("Task removed; actualPlaying=false") &&
                state.getString("state") in listOf("INTERRUPTED", "USER_PAUSED")
        }
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = c.snapshot()
            check(!state.getBoolean("actualPlaying") && !state.getBoolean("actualPlayWhenReady") && state.getInt("actualStarts") == blockedStarts)
            SystemClock.sleep(100)
        }
        report("PASS task removal while automatically paused cancels simulation/resume; test client keeps binding only for observation")
    }

    private fun external(c: ServiceTestClient) {
        runProbe("untrusted")
        report("PASS host different-UID untrusted probe completed; actual result retained in probe log")
        val starts = c.snapshot().getInt("actualStarts")
        runProbe("trusted")
        val controlled = c.snapshot()
        check(controlled.getInt("actualStarts") > starts && !controlled.getBoolean("actualPlaying") && controlled.getString("state") == "USER_PAUSED")
        report("PASS different-UID trusted select/Play/Pause affected the real service, not only client masking")
        c.command(QuietCommands.STOP_SIMULATION)
        c.play()
        await("play before external pause") { c.snapshot().getBoolean("actualPlaying") }
        c.navigation(5)
        await("automatic pause before external controller") { c.snapshot().getString("state") == "AUTO_PAUSED" }
        val autoStarts = c.snapshot().getInt("actualStarts")
        runProbe("pause_only")
        await("trusted external manual priority") { c.snapshot().getString("state") == "USER_PAUSED" }
        c.navigation(60); SystemClock.sleep(2_200); c.navigation(60)
        check(!c.snapshot().getBoolean("actualPlaying") && c.snapshot().getInt("actualStarts") == autoStarts)
        report("PASS different-UID trusted Pause during automatic pause prevents navigation resume")
    }

    private fun runProbe(mode: String) {
        val done = File(test.targetContext.cacheDir, "phase3-probe-$mode.done")
        done.delete()
        report("HOST_PROBE $mode")
        await("external probe $mode", 40_000) { done.exists() }
        done.delete()
    }
    private fun removeTasks(c: ServiceTestClient) = c.main {
        val tasks = (test.targetContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).appTasks
        check(tasks.isNotEmpty())
        tasks.forEach { it.finishAndRemoveTask() }
    }
    private fun launch() = test.launchParkedActivity()
    private fun await(description: String, timeoutMs: Long = 10_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) { if (predicate()) return; SystemClock.sleep(50) }
        error("Timed out: $description")
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun report(message: String) = test.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
}
