package com.quietchapter

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.quietchapter.media.DemoCatalog

/** Real service/browser call tests. GSM events come from the host runner, not production code. */
class ServicePhoneSuite(private val test: Instrumentation, private var activity: MainActivity,
    private var client: ServiceTestClient) {
    private val audioMode get() = (test.targetContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode

    fun run(): ServiceTestClient {
        client.prepare()
        client.command(QuietCommands.STOP_SIMULATION)
        client.select(DemoCatalog.BOOK_ID)
        client.seek(0)
        client.play()
        await("service playback") { client.snapshot().getBoolean("actualPlaying") }
        SystemClock.sleep(10)
        client.navigation(5)
        await("service automatic pause") { client.snapshot().getString("state") == "AUTO_PAUSED" }
        val starts = client.snapshot().getInt("actualStarts")
        report("HOST_RING service_navigation_pause")
        await("ringing") { callState() == 1 }
        await("service interruption") { client.snapshot().getString("state") == "INTERRUPTED" }
        report("HOST_ACCEPT service_navigation_pause")
        await("active call") { callState() == 2 && audioMode == AudioManager.MODE_IN_CALL }
        report("HOST_FOCUS_BLOCKED service_navigation_pause")
        clear()
        client.play()
        assertStill(starts)
        client.pause()
        await("manual priority") { client.snapshot().getString("state") == "USER_PAUSED" }
        report("HOST_CANCEL service_navigation_pause")
        await("call ended") { callState() == 0 && audioMode == AudioManager.MODE_NORMAL }
        assertStill(starts)
        check(client.snapshot().getString("state") == "USER_PAUSED")
        report("PASS service-owned player: call during automatic pause, route clear and Play blocked; controller Pause survives call end")

        val old = client.snapshot().getString("serviceInstance")
        client.close()
        test.runOnMainSync { activity.finish() }
        test.waitForIdleSync()
        SystemClock.sleep(500)
        shell("am stopservice -n com.quietchapter/.PlaybackService")
        await("old service destroyed") { !shell("dumpsys activity services com.quietchapter").contains("com.quietchapter/.PlaybackService") }
        report("HOST_RING fresh_service")
        await("fresh-service incoming call") { callState() == 1 && audioMode != AudioManager.MODE_NORMAL }
        report("HOST_ACCEPT fresh_service")
        await("fresh-service active call") { callState() == 2 && audioMode == AudioManager.MODE_IN_CALL }
        activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        client = ServiceTestClient(test)
        client.prepare()
        check(client.snapshot().getString("serviceInstance") != old)
        client.play()
        await("fresh Play veto") { client.snapshot().getString("state") == "INTERRUPTED" }
        check(client.snapshot().getInt("actualStarts") == 0)
        report("HOST_FOCUS_BLOCKED fresh_service")
        client.navigation(5)
        clear()
        assertStill(0)
        report("HOST_CANCEL fresh_service")
        await("fresh-service call ended") { callState() == 0 && audioMode == AudioManager.MODE_NORMAL }
        assertStill(0)
        client.play()
        await("explicit recovery") { client.snapshot().getBoolean("actualPlaying") }
        report("PASS newly created service/player while Telecom owns focus: initial Play and route clear never start audio; explicit post-call Play works")
        client.pause()
        client.command(QuietCommands.DELAY_NEXT_PREPARATION, Bundle().apply { putLong("delayMs", 15_000) })
        val requestedAt = SystemClock.elapsedRealtime()
        client.play()
        await("pending preparation Play") { client.snapshot().getBoolean("awaitingPlay") }
        val pendingStarts = client.snapshot().getInt("actualStarts")
        report("HOST_RING pending_preparation")
        await("pending preparation incoming call") { callState() == 1 && audioMode != AudioManager.MODE_NORMAL }
        await("pending intent cancelled") { !client.snapshot().getBoolean("awaitingPlay") }
        report("HOST_ACCEPT pending_preparation")
        await("pending preparation active call") { callState() == 2 && audioMode == AudioManager.MODE_IN_CALL }
        check(SystemClock.elapsedRealtime() - requestedAt < 15_000) { "Call did not precede delayed completion" }
        report("HOST_CANCEL pending_preparation")
        await("pending preparation call ended") { callState() == 0 && audioMode == AudioManager.MODE_NORMAL }
        while (SystemClock.elapsedRealtime() - requestedAt < 16_000) assertStill(pendingStarts)
        report("PASS call entirely during controlled pending readiness cancels old Play even after call ends and completion arrives")
        return client
    }

    private fun clear() { client.navigation(60); SystemClock.sleep(2_200); client.navigation(60) }
    private fun assertStill(starts: Int) {
        val baseline = client.snapshot().getLong("positionMs")
        repeat(20) {
            val state = client.snapshot()
            check(!state.getBoolean("actualPlaying") && !state.getBoolean("actualPlayWhenReady") && state.getInt("actualStarts") == starts)
            check(state.getLong("positionMs") <= baseline + 150)
            SystemClock.sleep(50)
        }
    }
    private fun callState(): Int = Regex("mCallState=(\\d+)").find(shell("dumpsys telephony.registry"))?.groupValues?.get(1)?.toInt() ?: -1
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun await(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) { if (predicate()) return; SystemClock.sleep(50) }
        error("Timed out: $description")
    }
    private fun report(message: String) = test.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
}
