package com.quietchapter

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.quietchapter.media.LocalAudioStore
import org.json.JSONObject

/** TEST_ONLY deterministic real-service trace. No claim of sound without independent capture. */
class AudioQaSuite(private val test: Instrumentation) {
    fun run(bundled: Boolean = false) {
        var foreground: Activity? = null
        var resultCode = Activity.RESULT_OK
        var resultMessage = "Audio QA framework PASS; signal verdict is external\n"
        try {
            if (bundled) foreground = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ServiceTestClient(test).use { c ->
                try {
                    val deadline = SystemClock.elapsedRealtime() + 10_000
                    while (c.snapshot().getBoolean("loadingLocal")) {
                        check(SystemClock.elapsedRealtime() < deadline); SystemClock.sleep(50)
                    }
                    c.pause(); c.command(QuietCommands.STOP_SIMULATION)
                    if (bundled) c.prepare()
                    c.select(if (bundled) com.quietchapter.media.DemoCatalog.BOOK_ID else LocalAudioStore.LOCAL_ID); c.seek(0)
                    fun emit(action: String, command: (() -> Unit)? = null) {
                        val s = c.snapshot()
                        var now = SystemClock.elapsedRealtimeNanos()
                        if (command != null) c.main {
                            now = SystemClock.elapsedRealtimeNanos()
                            command()
                        }
                        val row = JSONObject().put("action", action).put("deviceNs", now)
                            .put("commandTimestamped", command != null)
                            .put("positionMs", s.getLong("positionMs"))
                            .put("playing", s.getBoolean("actualPlaying"))
                            .put("playWhenReady", s.getBoolean("actualPlayWhenReady"))
                            .put("starts", s.getInt("actualStarts"))
                        test.sendStatus(0, Bundle().apply { putString("stream", "QA_EVENT $row\n") })
                    }
                    fun sample(ms: Long) {
                        val end = SystemClock.elapsedRealtime() + ms
                        while (SystemClock.elapsedRealtime() < end) { emit("sample"); SystemClock.sleep(100) }
                    }
                    emit("ready"); SystemClock.sleep(1_000)
                    emit("play") { c.browser.play() }; sample(6_000)
                    emit("pause") { c.browser.pause() }; sample(2_000)
                    check(!c.snapshot().getBoolean("actualPlayWhenReady"))
                    emit("resume") { c.browser.play() }; sample(6_000)
                    emit("stop") { c.browser.pause() }; sample(2_000)
                    check(c.snapshot().getLong("positionMs") > 10_000) { "Framework progress failed; inspect captured signal separately" }
                    check(!c.snapshot().getBoolean("actualPlayWhenReady"))
                    emit("finished")
                } finally { c.pause() }
            }
        } catch (error: Throwable) {
            resultCode = Activity.RESULT_CANCELED
            resultMessage = "Audio QA framework FAIL: ${error.stackTraceToString()}\n"
        } finally {
            test.runOnMainSync { foreground?.finish() }
        }
        test.finish(resultCode, Bundle().apply { putString("stream", resultMessage) })
    }
}
