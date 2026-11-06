package com.quietchapter

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock

/** Real service/player, explicitly synthetic navigation. No audible or SDK assertion. */
class NavigationResilienceSuite(private val test: Instrumentation, private val c: ServiceTestClient) {
    private var generation = 0L
    private var pauseTime = 0L
    private fun send(eta: Int? = 60, time: Long = SystemClock.elapsedRealtime(),
        signal: String = "UPDATE", route: Long = generation) = c.command(QuietCommands.SIMULATED_EVENT,
        Bundle().apply {
            eta?.let { putInt("eta", it) }
            putLong("sourceElapsedMs", time)
            putLong("generation", route)
            putString("signal", signal)
        })
    private fun reset() {
        c.pause()
        c.command(QuietCommands.STOP_SIMULATION)
        generation = c.snapshot().getLong("simulationGeneration") + 1
        SystemClock.sleep(5)
        send()
        c.seek(10_000)
        c.play()
        await("initial playback") { c.snapshot().getBoolean("actualPlaying") }
        SystemClock.sleep(5)
        pauseTime = SystemClock.elapsedRealtime()
        send(5, time = pauseTime)
        await("navigation pause") { c.snapshot().getString("state") == "AUTO_PAUSED" && !c.snapshot().getBoolean("actualPlaying") }
    }
    private fun still(starts: Int, state: String, duration: Long = 350) {
        val before = c.snapshot().getLong("positionMs")
        val until = SystemClock.elapsedRealtime() + duration
        while (SystemClock.elapsedRealtime() < until) {
            val s = c.snapshot()
            check(!s.getBoolean("actualPlaying") && !s.getBoolean("actualPlayWhenReady")) { "Unexpected playback: $s" }
            check(s.getInt("actualStarts") == starts && s.getString("state") == state)
            check(kotlin.math.abs(s.getLong("positionMs") - before) <= 80) { "Unexpected seek/progress while paused" }
            SystemClock.sleep(30)
        }
    }
    fun run() {
        // This legacy suite counts raw isPlaying transitions. Acoustic seek-buffering is a
        // separately qualified opt-in behavior, not an extra automatic Play command.
        c.command(QuietCommands.RECOVERY_MODE, Bundle().apply { putString("mode", "EXACT") })
        c.prepare()
        reset()
        var starts = c.snapshot().getInt("actualStarts")
        SystemClock.sleep(2_200)
        val batch = SystemClock.elapsedRealtime()
        send(time = batch - 2_100)
        send(time = batch - 1)
        still(starts, "AUTO_PAUSED", 700)
        SystemClock.sleep(1_400)
        send()
        await("one delayed-batch resume") { c.snapshot().getBoolean("actualPlaying") }
        check(c.snapshot().getInt("actualStarts") == starts + 1)
        report("PASS SIMULATED delayed batch cannot compress receipt-time hysteresis; one actual resume")

        reset()
        starts = c.snapshot().getInt("actualStarts")
        val clear = SystemClock.elapsedRealtime()
        send(time = clear)
        send(3, time = clear)
        SystemClock.sleep(2_100)
        send()
        still(starts, "AUTO_PAUSED")
        c.pause()
        SystemClock.sleep(2_100)
        send()
        still(starts, "USER_PAUSED")
        report("PASS SIMULATED conflicting duplicate resets clear span; manual Pause overrides later clear; position stays bounded")

        for (signal in listOf("REROUTING", "CANCELLED", "DISCONNECTED")) {
            reset()
            starts = c.snapshot().getInt("actualStarts")
            SystemClock.sleep(5)
            send(signal = signal, time = pauseTime)
            await("source loss latch") { c.snapshot().getString("state") == "INTERRUPTED" }
            SystemClock.sleep(5)
            send(route = ++generation)
            SystemClock.sleep(2_100)
            send()
            still(starts, "INTERRUPTED")
            report("PASS SIMULATED $signal invalidates automatic intent; fresh new route cannot restart the player")
        }

        reset()
        starts = c.snapshot().getInt("actualStarts")
        still(starts, "AUTO_PAUSED", 4_500)
        await("silent source expiry") { c.snapshot().getString("state") == "INTERRUPTED" }
        check(c.snapshot().getString("navigationHealth") == "EXPIRED")
        send()
        SystemClock.sleep(2_100)
        send()
        still(starts, "INTERRUPTED")
        report("PASS SIMULATED missing stream expires; recovered events cannot revive playback or seek")
    }
    private fun await(description: String, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < end) {
            if (predicate()) return
            SystemClock.sleep(40)
        }
        error(description)
    }
    private fun report(message: String) = test.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
}
