package com.quietchapter

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.quietchapter.media.DemoCatalog
import java.io.File

/** Actual stock Pleos host UI. Pinned coordinates refer to visually inspected, unlabeled host
 * controls on image2.1.0 at2560x1440; fail closed for another window/layout. No host internals. */
class Phase10Suite(private val test: Instrumentation) {
    fun run(mode: String, oldInstance: String?) {
        try {
            check(android.os.Build.FINGERPRINT.startsWith("Pleos/"))
            ServiceTestClient(test).use { c ->
                try {
                    if (mode == "restore") {
                        check(!oldInstance.isNullOrBlank() && c.snapshot().getString("serviceInstance") != oldInstance) { "Expected a new service instance" }
                        await("paused saved chapter restoration") {
                            val s = c.snapshot()
                            s.getString("mediaId") == DemoCatalog.CHAPTER_TWO_ID && s.getLong("durationMs") > 0 &&
                                s.getLong("positionMs") in 6_900..7_100 && !s.getBoolean("actualPlayWhenReady")
                        }
                        SystemClock.sleep(700)
                        check(!c.snapshot().getBoolean("actualPlaying") && c.snapshot().getInt("actualStarts") == 0)
                        report("PASS fresh service restores chapter2 at7s paused without autoplay")
                    } else {
                        check(mode == "native")
                        c.pause()
                        if (test.targetContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                            c.command(QuietCommands.STOP_SIMULATION)
                        c.prepare(); c.select(DemoCatalog.BOOK_ID)
                        val starts = c.snapshot().getInt("actualStarts")
                        test.targetContext.startActivity(Intent("android.car.intent.action.MEDIA_TEMPLATE")
                            .putExtra("android.car.intent.extra.MEDIA_COMPONENT", "com.quietchapter/com.quietchapter.PlaybackService")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        SystemClock.sleep(700)
                        // Reopening the host retains its current Now Playing / chapter view.
                        // Collapse/back within that visible host (at most two levels).
                        repeat(2) {
                            if (findText("Audiobooks") == null) {
                                val root = test.uiAutomation.rootInActiveWindow ?: error("No native window")
                                check(root.packageName?.toString() == "ai.umos.mediaapp")
                                shell("input tap 903 140"); SystemClock.sleep(500)
                            }
                        }
                        await("native library") { findText("Audiobooks") != null }
                        check(c.snapshot().getInt("actualStarts") == starts && !c.snapshot().getBoolean("actualPlayWhenReady"))
                        capture("library")
                        clickText("A story that waits")
                        await("native chapter tree") { findText("Chapter 2 — The river") != null }
                        capture("chapters")
                        clickText("Chapter 1 — The library")
                        await("native chapter starts") { c.snapshot().getString("mediaId") == DemoCatalog.CHAPTER_ONE_ID && c.snapshot().getBoolean("actualPlaying") }
                        await("real chapter progression") { c.snapshot().getLong("positionMs") > 1_200 }
                        capture("playing")
                        tapPlayer(1534,1190)
                        await("native Pause") { !c.snapshot().getBoolean("actualPlayWhenReady") && c.snapshot().getString("state") == "USER_PAUSED" }
                        SystemClock.sleep(300)
                        val paused = c.snapshot().getLong("positionMs")
                        SystemClock.sleep(600)
                        check(c.snapshot().getLong("positionMs") <= paused + 150 && !c.snapshot().getBoolean("actualPlaying"))
                        // Actual visible native seek track [945,1047]..[2443,1047].
                        tapPlayer(1694,1047)
                        await("native paused seek") { c.snapshot().getLong("positionMs") in 10_500..13_500 && !c.snapshot().getBoolean("actualPlayWhenReady") }
                        capture("paused-seek")
                        tapPlayer(1534,1190)
                        await("native resume") { c.snapshot().getBoolean("actualPlaying") }
                        tapPlayer(1694,1190)
                        await("native Next") { c.snapshot().getString("mediaId") == DemoCatalog.CHAPTER_TWO_ID && c.snapshot().getBoolean("actualPlaying") }
                        tapPlayer(1534,1190)
                        await("native Pause chapter2") { !c.snapshot().getBoolean("actualPlayWhenReady") }
                        val pausedStarts = c.snapshot().getInt("actualStarts")
                        tapPlayer(1374,1190)
                        await("native paused Previous") { c.snapshot().getString("mediaId") == DemoCatalog.CHAPTER_ONE_ID }
                        check(!c.snapshot().getBoolean("actualPlayWhenReady") && c.snapshot().getInt("actualStarts") == pausedStarts)
                        // Public emulator media-key input exercises the system session dispatch path.
                        shell("input keyevent KEYCODE_MEDIA_PLAY")
                        await("system media Play") { c.snapshot().getBoolean("actualPlaying") }
                        shell("input keyevent KEYCODE_MEDIA_PAUSE")
                        await("system media Pause") { !c.snapshot().getBoolean("actualPlayWhenReady") }
                        report("PASS native library/book/chapter selection, progressing Play, Pause, paused seek, resume, Next, paused Previous and public media keys")
                        c.select(DemoCatalog.CHAPTER_TWO_ID); c.seek(7_000); c.pause()
                        await("durable bookmark") { c.snapshot().getLong("positionMs") in 6_900..7_100 }
                        SystemClock.sleep(400)
                        capture("final-paused")
                        report("PHASE10_INSTANCE ${c.snapshot().getString("serviceInstance")}")
                    }
                } finally { c.pause() }
            }
            test.finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "Phase10 $mode PASS; sound not asserted, separate capture required\n") })
        } catch (failure: Throwable) {
            test.finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "Phase10 $mode FAIL: ${failure.stackTraceToString()}\n") })
        }
    }
    private fun findText(text: String): AccessibilityNodeInfo? {
        val root = test.uiAutomation.rootInActiveWindow ?: return null
        if (root.packageName?.toString() != "ai.umos.mediaapp") return null
        // The stock Compose host exposes nodes but not the platform text-search index.
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == text) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return visit(root)
    }
    private fun clickText(text: String) {
        var node = findText(text) ?: error("Missing native item $text")
        while (!node.isClickable) node = node.parent ?: error("Native item is not clickable")
        check(node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun tapPlayer(x: Int, y: Int) {
        check(findText("Chapter 1 — The library") != null || findText("Chapter 2 — The river") != null)
        val root = test.uiAutomation.rootInActiveWindow ?: error("No host window")
        val bounds = Rect(); root.getBoundsInScreen(bounds)
        check(root.packageName?.toString() == "ai.umos.mediaapp" && bounds == Rect(853,90,2535,1300)) { "Unqualified native layout $bounds" }
        shell("input tap $x $y")
    }
    private fun capture(name: String) {
        val image = test.uiAutomation.takeScreenshot() ?: error("No screenshot")
        check(image.width == 2560 && image.height == 1440)
        val folder = File(test.targetContext.cacheDir,"phase10").apply { mkdirs() }
        File(folder,"$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG,100,it)) }
        image.recycle()
    }
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) { if (predicate()) return; SystemClock.sleep(50) }
        error("Timed out: $label")
    }
    private fun report(text: String) = test.sendStatus(0,Bundle().apply { putString("stream", "$text\n") })
}
