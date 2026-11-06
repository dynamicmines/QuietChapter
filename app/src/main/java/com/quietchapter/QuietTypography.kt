package com.quietchapter

import android.content.Context
import android.graphics.Typeface
import android.widget.Button
import android.widget.TextView

/** Bundled OFL typefaces. Cyrillic uses one family for the whole label, avoiding mixed-run metrics. */
object QuietTypography {
    private val cache = mutableMapOf<String, Typeface>()
    fun family(text: CharSequence?): String =
        if (text?.any { it.code in 0x0400..0x052f } == true) "Noto Sans" else "Asta Sans"
    @Synchronized fun face(context: Context, weight: Int, text: CharSequence?): Typeface {
        val w = if (weight >= 600) 600 else if (weight >= 500) 500 else 400
        val noto = family(text) == "Noto Sans"
        return cache.getOrPut("${if (noto) "noto" else "asta"}-$w") {
            if (android.os.Build.VERSION.SDK_INT >= 29) fallback(context, w, noto)
            else if (noto) Typeface.Builder(context.assets, "fonts/NotoSans.ttf")
                .setFontVariationSettings("'wght' $w, 'wdth' 100").build()
            else Typeface.createFromAsset(context.assets,
                "fonts/AstaSans-${if (w == 600) "SemiBold" else if (w == 500) "Medium" else "Regular"}.ttf")
        }
    }
    @androidx.annotation.RequiresApi(29)
    private fun fallback(context: Context, weight: Int, notoFirst: Boolean): Typeface {
        val asta = android.graphics.fonts.Font.Builder(context.assets,
            "fonts/AstaSans-${if (weight == 600) "SemiBold" else if (weight == 500) "Medium" else "Regular"}.ttf")
            .setWeight(weight).build()
        val noto = android.graphics.fonts.Font.Builder(context.assets, "fonts/NotoSans.ttf")
            .setFontVariationSettings("'wght' $weight, 'wdth' 100").setWeight(weight).build()
        val primary = android.graphics.fonts.FontFamily.Builder(if (notoFirst) noto else asta).build()
        val secondary = android.graphics.fonts.FontFamily.Builder(if (notoFirst) asta else noto).build()
        return Typeface.CustomFallbackBuilder(primary).addCustomFallback(secondary)
            .setSystemFallback("sans-serif")
            .setStyle(android.graphics.fonts.FontStyle(weight, android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT))
            .build()
    }
}

internal class QuietText(context: Context, private val weight: Int) : TextView(context) {
    init { typeface = QuietTypography.face(context, weight, text) }
    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        typeface = QuietTypography.face(context, weight, text)
    }
}

internal class QuietButton(context: Context, private val weight: Int = 500) : Button(context) {
    init { typeface = QuietTypography.face(context, weight, text) }
    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        typeface = QuietTypography.face(context, weight, text)
    }
    override fun drawableStateChanged() {
        super.drawableStateChanged()
        (text as? android.text.Spanned)?.let { s ->
            s.getSpans(0, s.length, android.text.style.ImageSpan::class.java).forEach {
                it.drawable.state = drawableState
            }
        }
    }
}
