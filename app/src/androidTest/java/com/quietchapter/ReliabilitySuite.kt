package com.quietchapter

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.media3.common.Player
import com.quietchapter.core.NavigationEvent
import com.quietchapter.core.PlaybackAction
import com.quietchapter.core.PlaybackCoordinator
import java.io.File

/** A retained test-owned player isolates focus from the foreground Activity's onStop release.
 * This is a diagnostic harness, not a production background playback service.
 * Navigation timestamps below use an explicitly simulated clock to keep fixtures bounded.
 */
class ReliabilitySuite(private val instrumentation: Instrumentation) {
    private lateinit var activity: MainActivity
    private lateinit var media: File
    private var probe: Probe? = null
    private var focusRequest: AudioFocusRequest? = null
    private val manager get() = instrumentation.targetContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun run(mode: String) {
        try {
            activity = launch()
            var prepared = false
            var error = ""
            var speech: DemoSpeech? = null
            main {
                speech = DemoSpeech(activity).also { demo ->
                    demo.prepare { file, message ->
                        if (file != null) media = file else error = message
                        prepared = true
                    }
                }
            }
            await("local speech preparation", 30_000) { mainValue { prepared } }
            check(error.isEmpty()) { error }
            main { speech?.close() }
            when (mode) {
                "focus" -> focusOrders()
                "calls" -> phoneOrders()
                else -> error("Unknown reliability suite: $mode")
            }
            report("QuietChapter reliability $mode PASS")
            instrumentation.finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "Reliability $mode PASS\n") })
        } catch (failure: Throwable) {
            report("FAIL ${failure.message}")
            instrumentation.finish(Activity.RESULT_CANCELED, Bundle().apply {
                putString("stream", "Reliability $mode FAIL: ${failure.stackTraceToString()}\n")
            })
        } finally {
            abandonFocus()
            main { probe?.audio?.close() }
        }
    }

    private fun focusOrders() {
        for (navigationFirst in listOf(true, false)) {
            for (manualPause in listOf(false, true)) {
                newProbe()
                if (navigationFirst) pauseForNavigation()
                takeFocus()
                await("focus interruption") { mainValue { probe!!.coordinator.state == "INTERRUPTED" } }
                if (!navigationFirst) main { probe!!.navigation(5) }
                if (manualPause) main { probe!!.command(probe!!.coordinator.userPause(), "manual Pause") }
                forbidAndClear()
                assertStill(2_000)
                if (!manualPause) {
                    main { probe!!.command(probe!!.coordinator.userPlay(), "Play while Media3 suppressed") }
                    assertStill(500)
                }
                abandonFocus()
                assertStill(1_000)
                check(mainValue { probe!!.coordinator.state == if (manualPause) "USER_PAUSED" else "INTERRUPTED" })
                report("PASS retained focus ${if (navigationFirst) "navigation-first" else "focus-first"} manual=$manualPause; zero forbidden starts/position advance")
            }
        }
        newProbe()
        pauseForNavigation()
        main {
            probe!!.navigation(60, 3_000)
            probe!!.navigation(60, 3_000)
            repeat(50) { probe!!.navigation(60, 0) }
        }
        await("single resume") { mainValue { probe!!.audio.player.isPlaying } }
        check(mainValue { probe!!.actions.count { it == PlaybackAction.PLAY } == 2 }) // explicit + one automatic
        main { repeat(50) { probe!!.navigation(5, 1) } }
        check(mainValue { probe!!.coordinator.state == "NORMAL" })
        report("PASS duplicate clears emit one automatic resume; immediate ETA oscillation emits no pause/play loop")
    }

    private fun phoneOrders() {
        for (navigationFirst in listOf(true, false)) {
          for (manualPause in listOf(false, true)) {
            newProbe()
            if (navigationFirst) pauseForNavigation()
            val name = (if (navigationFirst) "navigation_first" else "focus_first") + "_manual_$manualPause"
            report("HOST_RING $name")
            await("emulated incoming call ringing", 20_000) { callState() == 1 }
            report("OBSERVED_RINGING $name ${mainValue { probe!!.snapshot() }}")
            await("Media3 ringing focus interruption", 10_000) {
                mainValue { probe!!.coordinator.state == "INTERRUPTED" && !probe!!.audio.player.isPlaying }
            }
            if (!navigationFirst) main { probe!!.navigation(5) }
            forbidAndClear()
            assertStill(500)
            report("HOST_ACCEPT $name")
            await("emulated call offhook", 20_000) { callState() == 2 }
            await("Telecom in-call audio mode", 10_000) { manager.mode == AudioManager.MODE_IN_CALL }
            report("HOST_FOCUS_BLOCKED $name")
            assertStill(1_500)
            // Keep the Activity top to distinguish call blocking from background eligibility.
            foregroundActivity()
            main { probe!!.command(probe!!.coordinator.userPlay(), "explicit Play while call active") }
            assertStill(1_000)
            check(mainValue { probe!!.coordinator.state == "INTERRUPTED" }) { "Suppressed request left coordinator optimistic" }
            main {
                if (manualPause) probe!!.command(probe!!.coordinator.userPause(), "manual Pause during call")
                repeat(10) { probe!!.navigation(60) }
            }
            assertStill(1_000)
            report("HOST_CANCEL $name")
            await("emulated call idle", 20_000) { callState() == 0 }
            assertStill(1_500)
            check(mainValue { probe!!.coordinator.state == if (manualPause) "USER_PAUSED" else "INTERRUPTED" })
            foregroundActivity()
            await("Media3 focus restored") { mainValue { probe!!.audio.player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE } }
            main {
                probe!!.forbidden = false
                probe!!.command(probe!!.coordinator.userPlay(), "explicit Play after call")
            }
            await("explicit playback after call") { mainValue { probe!!.audio.player.isPlaying } }
            report("PASS emulated call $name: no forbidden starts; call end never resumes; explicit post-call Play works")
          }
        }
        activityPhoneScenario()
    }

    private fun activityPhoneScenario() {
        main { probe?.audio?.close(); probe = null }
        foregroundActivity()
        ServiceTestClient(instrumentation).use { service ->
        service.prepare()
        service.pause()
        service.command(QuietCommands.STOP_SIMULATION)
        service.seek(0)
        main { activity.findViewById<Button>(R.id.prepare).performClick() }
        await("Activity demo ready", 30_000) { mainValue { activity.findViewById<Button>(R.id.play).isEnabled } }
        main { activity.findViewById<Button>(R.id.play).performClick() }
        await("Activity Media3 playback") { uiStatus().contains("playing=true") }
        main { activity.findViewById<Button>(R.id.simulate).performClick() }
        await("Activity automatic pause") { uiStatus().startsWith("AUTO_PAUSED") }
        report("HOST_RING activity_navigation_first")
        await("Activity incoming call ringing", 20_000) { callState() == 1 }
        await("Service focus interruption", 10_000) {
            service.snapshot().getString("state") == "INTERRUPTED"
        }
        report("HOST_ACCEPT activity_navigation_first")
        await("Activity call offhook", 20_000) { callState() == 2 && manager.mode == AudioManager.MODE_IN_CALL }
        val deadline = SystemClock.elapsedRealtime() + 2_000
        while (SystemClock.elapsedRealtime() < deadline) {
            check(!service.snapshot().getBoolean("actualPlaying")) { "Service resumed during call" }
            SystemClock.sleep(25)
        }
        report("HOST_CANCEL activity_navigation_first")
        await("Activity call idle", 20_000) { callState() == 0 }
        foregroundActivity()
        SystemClock.sleep(1_000)
        check(!service.snapshot().getBoolean("actualPlaying")) { "Service resumed after call" }
        report("PASS actual Activity UI: service stays paused through call during navigation pause and Activity return")
        }
    }

    private fun uiStatus(): String = mainValue { activity.findViewById<TextView>(R.id.player_status).text.toString() }

    private fun newProbe() {
        main {
            probe?.audio?.close()
            probe = Probe()
            probe!!.audio.load(media, 0)
            probe!!.command(probe!!.coordinator.userPlay(), "explicit initial Play")
        }
        await("real player starts") { mainValue { probe!!.audio.player.isPlaying } }
    }

    private fun pauseForNavigation() {
        main { probe!!.navigation(5) }
        await("automatic navigation pause") { mainValue { probe!!.coordinator.state == "AUTO_PAUSED" && !probe!!.audio.player.isPlaying } }
    }

    private fun forbidAndClear() = main {
        probe!!.forbidden = true
        probe!!.baselinePosition = probe!!.audio.player.currentPosition
        probe!!.navigation(60, 3_000)
        probe!!.navigation(60, 3_000)
        repeat(50) { probe!!.navigation(60, 0) }
        check(probe!!.actions.count { it == PlaybackAction.PLAY } == 1) { "Unexpected automatic resume command" }
    }

    private fun assertStill(durationMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (SystemClock.elapsedRealtime() < deadline) {
            main {
                val p = probe!!
                check(!p.audio.player.isPlaying && p.forbiddenStarts == 0) { "Forbidden playback: ${p.snapshot()}" }
                check(p.audio.player.currentPosition <= p.baselinePosition + 150) { "Position advanced while blocked: ${p.snapshot()}" }
            }
            SystemClock.sleep(25)
        }
    }

    private fun takeFocus() {
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener { }.build()
        check(manager.requestAudioFocus(focusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    private fun abandonFocus() {
        focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private inner class Probe {
        val coordinator = PlaybackCoordinator()
        val actions = mutableListOf<PlaybackAction>()
        var forbidden = false
        var forbiddenStarts = 0
        var baselinePosition = 0L
        private var clock = SystemClock.elapsedRealtime()
        val audio = AudioPlaybackController(activity,
            interrupted = { reason -> command(coordinator.interrupt(reason), reason) },
            finished = { command(coordinator.ended(), "ended") }, changed = { }).also { controller ->
            controller.player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying && forbidden) forbiddenStarts++
                    report("PLAYER playing=$isPlaying state=${coordinator.state}")
                }
                override fun onPlaybackSuppressionReasonChanged(reason: Int) { report("PLAYER suppression=$reason") }
                override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) { report("PLAYER ready=$ready reason=$reason") }
            })
        }
        fun command(action: PlaybackAction, reason: String) {
            if (action != PlaybackAction.NONE) {
                actions.add(action)
                report("COMMAND $action state=${coordinator.state} reason=$reason")
                audio.apply(action)
            }
        }
        fun navigation(eta: Int, advanceMs: Long = 1_000) {
            clock += advanceMs
            command(coordinator.onNavigation(NavigationEvent(clock, true, eta), clock), "SIMULATED navigation")
        }
        fun snapshot() = "${coordinator.state} playing=${audio.player.isPlaying} ready=${audio.player.playWhenReady} suppression=${audio.player.playbackSuppressionReason} position=${audio.player.currentPosition}"
    }

    private fun callState(): Int {
        val descriptor = instrumentation.uiAutomation.executeShellCommand("dumpsys telephony.registry")
        val dump = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        return Regex("mCallState=(\\d+)").find(dump)?.groupValues?.get(1)?.toInt() ?: -1
    }
    private fun launch(): MainActivity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }) as MainActivity
    private fun foregroundActivity() {
        // Reusing an existing Activity must not wait for a new onCreate via startActivitySync.
        main {
            activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            })
        }
        await("Activity foreground", 5_000) { mainValue { activity.hasWindowFocus() } }
    }
    private fun report(message: String) {
        Log.i("QuietChapterReliability", message)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
    }
    private fun main(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun <T> mainValue(block: () -> T): T {
        var result: T? = null
        main { result = block() }
        return checkNotNull(result)
    }
    private fun await(description: String, timeoutMs: Long = 5_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(50)
        }
        error("Timed out: $description")
    }
}
