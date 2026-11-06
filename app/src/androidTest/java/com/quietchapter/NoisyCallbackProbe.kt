package com.quietchapter

import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.Player
import com.quietchapter.core.NavigationEvent
import com.quietchapter.core.PlaybackAction
import com.quietchapter.core.PlaybackCoordinator
import java.io.File

/** White-box delivery to Media3's registered receiver. Not a physical/system noisy event. */
class NoisyCallbackProbe(private val test: Instrumentation, context: Context) {
    private val recordingContext = ReceiverContext(context)
    fun run() {
        for (initial in listOf("NORMAL", "AUTO_PAUSED", "USER_PAUSED")) {
            val coordinator = PlaybackCoordinator()
            lateinit var audio: AudioPlaybackController
            val actions = mutableListOf<PlaybackAction>()
            var starts = 0
            fun apply(action: PlaybackAction) { if (action != PlaybackAction.NONE) { actions.add(action); audio.apply(action) } }
            main {
                audio = AudioPlaybackController(recordingContext,
                    interrupted = { apply(coordinator.interrupt(it)) },
                    finished = { apply(coordinator.ended()) }, changed = { })
                audio.player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) starts++ }
                })
                audio.load(File(test.targetContext.noBackupFilesDir, "quietchapter-original-v1.wav"), 0)
                apply(coordinator.userPlay())
            }
            try {
                await { mainValue { audio.player.isPlaying && recordingContext.noisyReceiver != null } }
                main {
                    if (initial == "AUTO_PAUSED") apply(coordinator.onNavigation(NavigationEvent(1_000, true, 5), 1_000))
                    if (initial == "USER_PAUSED") apply(coordinator.userPause())
                }
                val pauses = mainValue { actions.count { it == PlaybackAction.PAUSE } }
                main { recordingContext.deliver() }
                await { mainValue { !audio.player.playWhenReady && coordinator.state == if (initial == "USER_PAUSED") "USER_PAUSED" else "INTERRUPTED" } }
                main { recordingContext.deliver() }
                SystemClock.sleep(200)
                main {
                    apply(coordinator.onNavigation(NavigationEvent(4_000, true, 60), 4_000))
                    apply(coordinator.onNavigation(NavigationEvent(7_000, true, 60), 7_000))
                    check(!audio.player.isPlaying && !audio.player.playWhenReady && starts == 1)
                    check(actions.count { it == PlaybackAction.PAUSE } - pauses <= 1)
                }
                report("PASS WHITEBOX noisy callback $initial: Media3 receiver latches pause, duplicate delivery adds no resume/pause loop; physical disconnection NOT TESTED")
            } finally { main { audio.close() } }
        }
    }

    private class ReceiverContext(base: Context) : ContextWrapper(base) {
        var noisyReceiver: BroadcastReceiver? = null
        override fun getApplicationContext(): Context = this
        private fun capture(receiver: BroadcastReceiver?, filter: IntentFilter) {
            if (receiver != null && filter.hasAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) noisyReceiver = receiver
        }
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? {
            capture(receiver, filter); return super.registerReceiver(receiver, filter)
        }
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, flags: Int): Intent? {
            capture(receiver, filter); return super.registerReceiver(receiver, filter, flags)
        }
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, permission: String?, scheduler: Handler?): Intent? {
            capture(receiver, filter); return super.registerReceiver(receiver, filter, permission, scheduler)
        }
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, permission: String?, scheduler: Handler?, flags: Int): Intent? {
            capture(receiver, filter); return super.registerReceiver(receiver, filter, permission, scheduler, flags)
        }
        fun deliver() = checkNotNull(noisyReceiver).onReceive(this, Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }
    private fun main(block: () -> Unit) {
        var failure: Throwable? = null
        test.runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun <T> mainValue(block: () -> T): T { var value: T? = null; main { value = block() }; return checkNotNull(value) }
    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) { if (predicate()) return; SystemClock.sleep(25) }
        error("Noisy callback did not reach expected player/policy state")
    }
    private fun report(message: String) = test.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
}
