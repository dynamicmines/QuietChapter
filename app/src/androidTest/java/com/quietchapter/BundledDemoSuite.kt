package com.quietchapter

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.quietchapter.media.DemoCatalog
import java.io.File
import java.security.MessageDigest

/** Real bundled-media path; host holds the prior demo file before this fresh-preparation check. */
class BundledDemoSuite(private val test: Instrumentation) {
    fun run() {
        var activity: Activity? = null
        var code = Activity.RESULT_OK
        var message = "Bundled demo PASS: exact packaged bytes, paused preparation, real decode/progress/seek/resume/chapter selection; physical sound UNVERIFIED\n"
        try {
            activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ServiceTestClient(test).use { c ->
                try {
                    c.pause(); c.command(QuietCommands.STOP_SIMULATION); c.prepare()
                    await { c.snapshot().getLong("durationMs") > 40_000 }
                    val source = test.targetContext.assets.open("demo-original.wav").use { it.readBytes() }
                    val prepared = File(test.targetContext.noBackupFilesDir, "quietchapter-original-v1.wav").readBytes()
                    check(MessageDigest.getInstance("SHA-256").digest(source).contentEquals(MessageDigest.getInstance("SHA-256").digest(prepared)))
                    check(!c.snapshot().getBoolean("actualPlayWhenReady"))
                    c.seek(0); c.play()
                    await { c.snapshot().getBoolean("actualPlaying") }
                    SystemClock.sleep(1_500)
                    check(c.snapshot().getLong("positionMs") > 700)
                    c.pause(); c.seek(12_000)
                    await { c.snapshot().getLong("positionMs") in 11_900..12_100 }
                    val starts = c.snapshot().getInt("actualStarts")
                    SystemClock.sleep(700)
                    check(!c.snapshot().getBoolean("actualPlayWhenReady"))
                    check(c.snapshot().getInt("actualStarts") == starts)
                    c.play(); await { c.snapshot().getBoolean("actualPlaying") }
                    c.pause(); c.select(DemoCatalog.CHAPTER_TWO_ID)
                    check(!c.snapshot().getBoolean("actualPlayWhenReady"))
                } finally { c.pause() }
            }
        } catch (failure: Throwable) {
            code = Activity.RESULT_CANCELED
            message = "Bundled demo FAIL: ${failure.stackTraceToString()}\n"
        } finally {
            test.runOnMainSync { activity?.finish() }
        }
        test.finish(code, Bundle().apply { putString("stream", message) })
    }
    private fun await(test: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < until) { if (test()) return; SystemClock.sleep(50) }
        error("Bundled demo condition timed out")
    }
}
