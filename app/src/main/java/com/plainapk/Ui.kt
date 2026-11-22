package com.plainapk

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.TextView

/** Material You colors from the wallpaper (Android 12+ system palettes), light or dark with the system. */
class Palette(private val c: Context) {
    val dark = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private fun col(light: Int, darkId: Int) = c.getColor(if (dark) darkId else light)
    val background = col(android.R.color.system_neutral1_10, android.R.color.system_neutral1_900)
    val surface = col(android.R.color.system_neutral2_50, android.R.color.system_neutral2_800)
    val field = col(android.R.color.system_neutral2_100, android.R.color.system_neutral2_700)
    val onSurface = col(android.R.color.system_neutral1_900, android.R.color.system_neutral1_50)
    val muted = col(android.R.color.system_neutral2_700, android.R.color.system_neutral2_200)
    val primary = col(android.R.color.system_accent1_600, android.R.color.system_accent1_200)
    val onPrimary = col(android.R.color.system_accent1_0, android.R.color.system_accent1_800)
    val primaryContainer = col(android.R.color.system_accent1_100, android.R.color.system_accent1_700)
    val onPrimaryContainer = col(android.R.color.system_accent1_900, android.R.color.system_accent1_100)
    val tonal = col(android.R.color.system_accent2_100, android.R.color.system_accent2_700)
    val onTonal = col(android.R.color.system_accent2_900, android.R.color.system_accent2_100)
    val error = if (dark) 0xFFFFB4AB.toInt() else 0xFFBA1A1A.toInt()
    val ripple = (if (dark) 0x33FFFFFF else 0x22000000)

    /** Letter avatar colors, picked by name so an app always gets the same one. */
    fun avatar(seed: String): Pair<Int, Int> {
        val pairs = listOf(
            col(android.R.color.system_accent1_100, android.R.color.system_accent1_700) to col(android.R.color.system_accent1_900, android.R.color.system_accent1_100),
            col(android.R.color.system_accent2_100, android.R.color.system_accent2_700) to col(android.R.color.system_accent2_900, android.R.color.system_accent2_100),
            col(android.R.color.system_accent3_100, android.R.color.system_accent3_700) to col(android.R.color.system_accent3_900, android.R.color.system_accent3_100),
        )
        return pairs[Math.floorMod(seed.hashCode(), pairs.size)]
    }
}

fun Context.dp(v: Float) = v * resources.displayMetrics.density
fun Context.dp(v: Int) = dp(v.toFloat()).toInt()

fun rounded(color: Int, tl: Float, tr: Float = tl, br: Float = tl, bl: Float = br) = GradientDrawable().apply {
    setColor(color)
    cornerRadii = floatArrayOf(tl, tl, tr, tr, br, br, bl, bl)
}

fun ripple(p: Palette, content: Drawable) = RippleDrawable(ColorStateList.valueOf(p.ripple), content, content)

/** A pill button: filled for the main action, tonal for the rest. */
fun Context.pill(p: Palette, filled: Boolean) = TextView(this).apply {
    gravity = Gravity.CENTER
    textSize = 14f
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    minHeight = dp(40)
    setPadding(dp(18), 0, dp(18), 0)
    style(p, filled)
}

fun TextView.style(p: Palette, filled: Boolean) {
    setTextColor(if (filled) p.onPrimary else p.onTonal)
    background = ripple(p, rounded(if (filled) p.primary else p.tonal, context.dp(20f)))
}

/** A round letter for apps without an icon of their own (not installed yet). */
class LetterDrawable(private val letter: String, private val bg: Int, fg: Int) : Drawable() {
    private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fg; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    override fun draw(c: Canvas) {
        val b = bounds
        val r = minOf(b.width(), b.height()) / 2f
        c.drawCircle(b.exactCenterX(), b.exactCenterY(), r, circle)
        text.textSize = r * 0.95f
        c.drawText(letter, b.exactCenterX(), b.exactCenterY() - (text.descent() + text.ascent()) / 2, text)
    }

    override fun setAlpha(a: Int) { circle.alpha = a; text.alpha = a }
    override fun setColorFilter(f: ColorFilter?) { circle.colorFilter = f; text.colorFilter = f }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

fun ago(ms: Long): String {
    val m = (System.currentTimeMillis() - ms) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 48 * 60 -> "${m / 60} h ago"
        else -> "${m / 1440} days ago"
    }
}
