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
import android.view.MotionEvent
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView

/** Dependency-free, stationary emulator smoke test using real Media3 and platform focus. */
class SmokeInstrumentation : Instrumentation() {
    private lateinit var activity: MainActivity
    private var reliabilityMode: String? = null
    private var serviceMode: String? = null
    private var phase3Mode: String? = null
    private var recoveryMode: String? = null
    private var localMode: String? = null
    private var phase6Mode: String? = null
    private var bundledDemo = false
    private var phase10: String? = null
    private var phase11: String? = null
    private var narrative: String? = null
    private var designV2: String? = null
    private var silver: String? = null
    private var acceptance: String? = null
    private var engineering: String? = null
    private var phase12: String? = null
    private var audioQaBundled = false
    private var audioQa = false
    private var oldServiceInstance: String? = null
    private var expectedBookmarkId: String? = null
    private var expectedBookmarkPosition: Long? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        reliabilityMode = arguments?.getString("reliability")
        serviceMode = arguments?.getString("service")
        phase3Mode = arguments?.getString("phase3")
        recoveryMode = arguments?.getString("recovery")
        localMode = arguments?.getString("localAudio")
        phase6Mode = arguments?.getString("phase6")
        audioQa = arguments?.getString("audioQa") == "trace"
        audioQaBundled = arguments?.getString("audioQaMedia") == "demo"
        narrative = arguments?.getString("narrative")
        acceptance = arguments?.getString("acceptance")
        designV2 = arguments?.getString("designV2")
        silver = arguments?.getString("silver")
        engineering = arguments?.getString("engineering")
        phase12 = arguments?.getString("phase12")
        phase11 = arguments?.getString("phase11")
        phase10 = arguments?.getString("phase10")
        bundledDemo = arguments?.getString("bundledDemo") == "verify"
        oldServiceInstance = arguments?.getString("oldInstance")
        expectedBookmarkId = arguments?.getString("expectedId")
        expectedBookmarkPosition = arguments?.getString("expectedPosition")?.toLongOrNull()
        start()
    }

    override fun onStart() {
        silver?.let { SilverVisualSuite(this).run(it); return }
        designV2?.let { DesignV2Suite(this).run(it); return }
        acceptance?.let { NarrativeAcceptanceSuite(this).run(it); return }
        narrative?.let { NarrativeSuite(this).run(it); return }
        engineering?.let { EngineeringRecoverySuite(this).run(it); return }
        phase12?.let { if (it.startsWith("layout")) { Phase12Suite(this).run(it); return } }
        if (phase11=="simulation") { Phase11DemoSuite(this).run(); return }
        phase10?.let { Phase10Suite(this).run(it, oldServiceInstance); return }
        if (bundledDemo) { BundledDemoSuite(this).run(); return }
        if (audioQa) { AudioQaSuite(this).run(audioQaBundled); return }
        recoveryMode?.let { RecoverySuite(this).run(it); return }
        localMode?.let { LocalAudioSuite(this).run(it); return }
        phase6Mode?.let { Phase6Suite(this).run(it); return }
        phase3Mode?.let { Phase3Suite(this).run(it); return }
        serviceMode?.let { ServiceFoundationSuite(this).run(it, oldServiceInstance, expectedBookmarkId, expectedBookmarkPosition); return }
        reliabilityMode?.let { ReliabilitySuite(this).run(it); return }
        var focus: AudioFocusRequest? = null
        val manager = targetContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            activity = launchParkedActivity()
            onMain { activity.findViewById<Button>(R.id.nav_player).performClick() }
            click(R.id.prepare)
            await(30_000, "offline TTS demo ready") { onMainValue { activity.findViewById<Button>(R.id.play).isEnabled } }
            click(R.id.pause)
            seekToFraction(0f)
            click(R.id.play)
            await(5_000, "real Media3 playback starts") { status().contains("playing=true") }
            val before = positionSeconds()
            SystemClock.sleep(2_200)
            check(positionSeconds() > before) { "Media3 position did not advance" }
            report("PASS local spoken file decoded; Media3 playing=true and position advanced")

            seekToFraction(0.5f)
            await(3_000, "seek to middle") { positionSeconds() > 10 }
            val middle = positionSeconds()
            click(R.id.rewind)
            await(2_000, "bounded rewind") { positionSeconds() in (middle - 4)..(middle - 2) }
            seekToFraction(0f)
            click(R.id.rewind)
            await(2_000, "rewind clamps at zero") { positionSeconds() <= 1 }
            report("PASS seek changes real player position; three-second rewind clamps at zero")

            click(R.id.simulate)
            await(5_000, "simulated automatic pause") { status().startsWith("AUTO_PAUSED") }
            check(status().contains("playing=false")) { "Auto-pause did not stop renderer" }
            await(10_000, "simulated controlled resume") { status().startsWith("NORMAL") && status().contains("playing=true") }
            report("PASS simulated cluster paused real player once and resumed after clear hysteresis")

            click(R.id.simulate)
            await(5_000, "automatic pause before user override") { status().startsWith("AUTO_PAUSED") }
            click(R.id.pause)
            SystemClock.sleep(9_000)
            check(status().startsWith("USER_PAUSED") && status().contains("playing=false")) { "Route overrode user pause: ${status()}" }
            report("PASS explicit Pause during automatic pause survives route clear")

            click(R.id.play)
            await(5_000, "play before focus test") { status().contains("playing=true") }
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setOnAudioFocusChangeListener { }
                .build()
            check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Test focus request denied" }
            await(3_000, "actual platform focus interruption") { status().startsWith("INTERRUPTED") && status().contains("playing=false") }
            manager.abandonAudioFocusRequest(focus)
            focus = null
            SystemClock.sleep(1_000)
            check(status().contains("playing=false")) { "Focus gain resumed without user intent" }
            report("PASS actual transient audio focus loss pauses; focus gain does not resume")

            click(R.id.play)
            await(5_000, "explicit replay after focus") { status().contains("playing=true") }
            click(R.id.simulate)
            await(5_000, "auto pause before second focus test") { status().startsWith("AUTO_PAUSED") }
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setOnAudioFocusChangeListener { }
                .build()
            check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            await(3_000, "focus loss while automatically paused") { status().startsWith("INTERRUPTED") }
            manager.abandonAudioFocusRequest(focus)
            focus = null
            SystemClock.sleep(9_000)
            check(status().startsWith("INTERRUPTED") && status().contains("playing=false")) { "Route/focus gain restarted player: ${status()}" }
            report("PASS focus loss during automatic pause cancels pending route resume")

            click(R.id.play)
            await(5_000, "explicit playback before completion") { status().contains("playing=true") }
            seekToFraction(1f)
            await(5_000, "actual media completion") { status().startsWith("ENDED") }
            click(R.id.simulate)
            SystemClock.sleep(12_000)
            check(status().startsWith("ENDED") && status().contains("playing=false")) { "Navigation restarted completed media" }
            report("PASS actual media end survives simulated route clear without restarting")

            click(R.id.play)
            await(5_000, "explicit playback before lifecycle test") { status().contains("playing=true") }
            onMain { activity.finish() }
            // The automotive host may animate/rearrange its own tasks indefinitely.
            // Observe our Activity's real lifecycle instead of an unbounded global idle barrier.
            await(5_000, "closed Activity destroyed") { onMainValue { activity.isDestroyed } }
            val automotive = packageManagerAutomotive()
            if (!automotive) await(3_000, "phone service retains audio after Activity close") { manager.isMusicActive }
            activity = launchParkedActivity()
            onMain { activity.findViewById<Button>(R.id.nav_player).performClick() }
            if (automotive) {
                await(5_000,"Automotive reopened paused") { status().contains("playing=false") }
                click(R.id.play)
            }
            await(5_000, "reopened Activity observes authorized service playback") { status().contains("playing=true") }
            click(R.id.pause)
            report("PASS Activity close/reopen preserves service media; Automotive task removal requires explicit Play; phone background retains playback")
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "QuietChapter smoke PASS\n") })
        } catch (error: Throwable) {
            report("FAIL ${error.javaClass.simpleName}: ${error.message}")
            finish(Activity.RESULT_CANCELED, Bundle().apply {
                putString("stream", "QuietChapter smoke FAIL: ${error.stackTraceToString()}\n")
            })
        } finally {
            focus?.let { manager.abandonAudioFocusRequest(it) }
        }
    }

    private fun packageManagerAutomotive() = targetContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)
    private fun click(id: Int) {
        // A real user cannot click before the browser and parked-UX gate enable the control.
        await(5_000, "enabled parked control $id") { onMainValue {
            activity.findViewById<Button>(id).let { it.isEnabled }
        } }
        onMain { activity.findViewById<Button>(id).performClick() }
    }
    private fun seekToFraction(fraction: Float) = onMain {
        val bar = activity.findViewById<SeekBar>(R.id.seek)
        val x = bar.paddingLeft + (bar.width - bar.paddingLeft - bar.paddingRight) * fraction
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(now, now, action, x, bar.height / 2f, 0)
            bar.dispatchTouchEvent(event)
            event.recycle()
        }
    }
    private fun status(): String = onMainValue { activity.findViewById<TextView>(R.id.player_status).text.toString() }
    private fun positionSeconds(): Long = status().substringAfter('\n').substringBefore(" / ").toLong()
    private fun onMain(block: () -> Unit) = runOnMainSync(block)
    private fun <T> onMainValue(block: () -> T): T {
        var result: T? = null
        runOnMainSync { result = block() }
        return checkNotNull(result)
    }
    private fun await(timeoutMs: Long, description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        error("Timed out: $description; ${status()}")
    }
    private fun report(message: String) = sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
}
