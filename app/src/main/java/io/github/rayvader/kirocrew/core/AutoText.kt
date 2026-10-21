package io.github.rayvader.kirocrew.core

import kotlin.math.roundToInt

/**
 * "Auto text size": a base page text size taken from the screen width, so a
 * wide screen (unfolded phone, tablet, landscape) gets larger text.
 *
 * A 360 dp phone is the baseline (100 %). Half of any extra width turns into
 * extra size, up to 150 %. A narrower screen shrinks the text a little, down to 90 %.
 *
 *   360 dp -> 100 %   600 dp -> 133 %   800 dp -> 150 %
 */
object AutoText {
    const val BASELINE_DP = 360
    private const val SHARE_OF_EXTRA_WIDTH = 0.5
    private const val MIN_PERCENT = 90
    private const val MAX_PERCENT = 150

    /** The automatic size in percent for a screen [widthDp] wide. */
    fun percent(widthDp: Int): Int {
        if (widthDp <= 0) return 100
        val scale = 1.0 + SHARE_OF_EXTRA_WIDTH * (widthDp.toDouble() / BASELINE_DP - 1.0)
        return (scale * 100).roundToInt().coerceIn(MIN_PERCENT, MAX_PERCENT)
    }

    /** The WebView text zoom: the automatic size with the user's slider on top (50-200 %). */
    fun zoom(widthDp: Int, sliderPercent: Int): Int =
        (percent(widthDp) * sliderPercent.coerceIn(50, 200) / 100.0).roundToInt().coerceIn(MIN_ZOOM, MAX_ZOOM)

    const val MIN_ZOOM = 50
    const val MAX_ZOOM = 300
}
