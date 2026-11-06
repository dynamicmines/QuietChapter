package com.quietchapter

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import com.quietchapter.media.DemoCatalog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Real parked-window and artwork checks. No new player or synthetic SDK callback. */
class Phase12Suite(private val test: Instrumentation) {
    fun run(mode: String) {
        try {
            check(mode == "layout") { "Use a genuine window configuration; root-only compact fixtures are historical" }
            val activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            ServiceTestClient(test).use { client ->
                client.pause(); client.command(QuietCommands.STOP_SIMULATION)
                val book = client.request { client.browser.getItem(DemoCatalog.BOOK_ID) }.value!!
                val chapters = client.request { client.browser.getChildren(DemoCatalog.BOOK_ID, 0, 10, null) }.value!!
                check(chapters.map { it.mediaId } == listOf(DemoCatalog.CHAPTER_ONE_ID, DemoCatalog.CHAPTER_TWO_ID))
                check(book.mediaMetadata.artist.toString() == "QuietChapter Originals")
                val cover = book.mediaMetadata.artworkData ?: error("Missing native cover data")
                check(cover.size < 32_000)
                android.graphics.BitmapFactory.decodeByteArray(cover, 0, cover.size).let {
                    check(it.width == 256 && it.height == 256); it.recycle()
                }
                client.main { activity.findViewById<android.widget.Button>(R.id.nav_player).performClick() }
                val deadline = SystemClock.elapsedRealtime() + 5_000
                var enabled = false
                while (SystemClock.elapsedRealtime() < deadline) {
                    client.main { enabled = activity.findViewById<View>(R.id.play).isEnabled }
                    if (enabled) break
                    SystemClock.sleep(50)
                }
                check(enabled) { "Parked Play never became enabled" }
                SystemClock.sleep(500)
                val views = JSONArray()
                client.main {
                    val root = activity.findViewById<android.widget.FrameLayout>(android.R.id.content).getChildAt(0)
                    val viewport = Rect(); check(root.getGlobalVisibleRect(viewport))
                    for (id in listOf((if(activity.findViewById<View>(R.id.pause).isShown)R.id.pause else R.id.play), R.id.rewind, R.id.seek)) {
                        val view = activity.findViewById<View>(id); val visible = Rect()
                        check(view.isShown && view.getGlobalVisibleRect(visible))
                        check(visible.height() == view.height && visible.width() == view.width) { "Clipped transport $id: $visible / ${view.width}x${view.height}" }
                        check(viewport.contains(visible)) { "Transport outside available window" }
                        check(view.height >= 48 * activity.resources.displayMetrics.density) { "Small touch target $id: ${view.height}" }
                        if (view is TextView) {
                            val layout = view.layout ?: error("Text has no layout")
                            check(layout.getLineBottom(layout.lineCount - 1) <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom) { "Clipped button text ${view.text}" }
                        }
                        views.put(JSONObject().put("id",activity.resources.getResourceEntryName(id)).put("bounds",visible.toShortString()).put("enabled",view.isEnabled))
                    }
                    File(activity.cacheDir,"phase12-layout.json").writeText(JSONObject()
                        .put("layoutKind","ACTUAL_PLEOS_WINDOW").put("rootBounds",viewport.toShortString()).put("fontScale",activity.resources.configuration.fontScale)
                        .put("systemNightMode",activity.resources.configuration.uiMode and 48).put("appearance",if (activity.getSharedPreferences("presentation",0).getBoolean("day",false)) "DAY" else "NIGHT").put("controls",views)
                        .put("artworkBytes",cover.size).put("artworkPixels",256).put("nativeRoute","BLOCKED; disconnected")
                        .put("result","PASS").toString(2))
                }
                val screenshot = test.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
                File(test.targetContext.cacheDir,"phase12-layout.png").outputStream().use {
                    check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))
                }
                screenshot.recycle()

            }
            test.finish(Activity.RESULT_OK,Bundle().apply { putString("stream","Phase12 actual window transport/artwork PASS\n") })
        } catch (failure:Throwable) {
            test.finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream","Phase12 layout FAIL: ${failure.stackTraceToString()}\n") })
        }
    }
}
