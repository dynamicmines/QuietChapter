package com.quietchapter

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Actual Android typeface/render specimens, explicitly TEST_ONLY; no claimed SDK/vehicle event. */
class SilverVisualSuite(private val test: Instrumentation) {
    fun run(mode: String) {
        try {
            check(mode == "typography")
            val a = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val result = JSONObject().put("provenance", "ACTUAL_ANDROID_RENDER_TEST_ONLY").put("density", a.resources.displayMetrics.density).put("fontScale", a.resources.configuration.fontScale)
            val samples = linkedMapOf("English" to "A story that waits · QuietChapter", "Russian" to "Русский · Продолжить слушать", "Kazakh" to "Қазақша · Әә Ғғ Ққ Ңң Өө Ұұ Үү Һһ Іі", "Korean" to "한국어 · 오디오북 계속 듣기")
            ServiceTestClient(test).use { client ->
                client.pause()
                val starts = client.snapshot().getInt("actualStarts")
                client.main {
                    fun attr(id: Int): Int { val v = android.util.TypedValue(); check(a.theme.resolveAttribute(id, v, true)); return v.data }
                    fun dp(v: Int) = (v * a.resources.displayMetrics.density + .5f).toInt()
                    fun label(s: String, size: Int = 22, before: Boolean = false): TextView = TextView(a).apply {
                        text = s; textSize = size.toFloat(); setTextColor(attr(R.attr.qc_ink)); includeFontPadding = false
                        typeface = if (before) Typeface.create("sans-serif-medium", Typeface.NORMAL) else QuietTypography.face(a, 500, s)
                        fontFeatureSettings = "'tnum'"
                    }
                    val content = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(32), dp(24), dp(32), dp(24)); setBackgroundColor(attr(R.attr.qc_background)) }
                    content.addView(label("TEST ONLY · Typography & control states", 32))
                    val headings = LinearLayout(a)
                    headings.addView(label("BEFORE · platform sans-serif", 22), LinearLayout.LayoutParams(0, -2, 1f))
                    headings.addView(label("AFTER · Asta Sans / Noto fallback", 22), LinearLayout.LayoutParams(0, -2, 1f))
                    content.addView(headings, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
                    val rows = JSONArray()
                    for ((language, text) in samples) {
                        content.addView(label(language, 20), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
                        val row = LinearLayout(a)
                        row.addView(label(text, 24, true), LinearLayout.LayoutParams(0, -2, 1f))
                        val current = label(text, 24); row.addView(current, LinearLayout.LayoutParams(0, -2, 1f)); content.addView(row)
                        val missing = text.filter { !it.isWhitespace() && !current.paint.hasGlyph(it.toString()) }
                        check(missing.isEmpty()) { "Missing $language glyphs: $missing" }
                        val bounds = Rect(); current.paint.getTextBounds(text, 0, text.length, bounds)
                        rows.put(JSONObject().put("language", language).put("text", text).put("primaryFamily", QuietTypography.family(text)).put("weight", 500).put("sizeSp", 24).put("glyphBoundsPx", bounds.toShortString()).put("allGlyphsRendered", true))
                    }
                    val mixed = label("QuietChapter · Қазақша · 한국어 · 01:28", 24)
                    check(mixed.text.all { it.isWhitespace() || mixed.paint.hasGlyph(it.toString()) })
                    content.addView(mixed, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
                    val digits = (0..9).map { mixed.paint.measureText(it.toString()) }
                    check(digits.max() - digits.min() < .1f) { "Tabular numeral feature unavailable: $digits" }
                    result.put("samples", rows).put("mixedScriptGlyphs", true).put("tabularNumerals", true)
                    content.addView(label("Actual component styles · no playback commands", 24), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(32) })
                    val buttons = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
                    var focusedButton: View? = null
                    for (state in listOf("Active", "Inactive", "Pressed", "Focused", "Disabled")) {
                        val source = ParkedPlayerView(a); val b = source.play
                        (b.parent as ViewGroup).removeView(b)
                        b.isActivated = state in listOf("Active", "Pressed", "Disabled"); b.isEnabled = state != "Disabled"
                        b.isPressed = state == "Pressed"
                        if (state == "Focused") { b.isFocusableInTouchMode = true; focusedButton = b }
                        val column = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                        column.addView(label(state, 20)); column.addView(b, LinearLayout.LayoutParams(-1, dp(88)).apply { topMargin = dp(12) })
                        buttons.addView(column, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(16) })
                    }
                    content.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
                    val slider = ParkedPlayerView(a).seek; (slider.parent as ViewGroup).removeView(slider); slider.isEnabled = true; slider.progress = 430
                    content.addView(slider, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(24) })
                    content.addView(label("TEST ONLY · Glyph coverage is not full UI translation or physical readability.", 20))
                    a.setContentView(ScrollView(a).apply { addView(content) })
                    focusedButton?.requestFocus()
                    File(a.cacheDir, "silver-typography.json").writeText(result.toString(2))
                }
                SystemClock.sleep(500)
                val image = test.uiAutomation.takeScreenshot() ?: error("No real screenshot")
                File(a.cacheDir, "silver-typography.png").outputStream().use { check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }; image.recycle()
                check(client.snapshot().getInt("actualStarts") == starts && !client.snapshot().getBoolean("actualPlaying"))
            }
            test.finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "Silver PASS: four language glyphs, mixed-script fallback, tabular numerals, actual component specimens; no playback\n") })
        } catch (failure: Throwable) {
            test.finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "Silver FAIL ${failure.stackTraceToString()}\n") })
        }
    }
}
