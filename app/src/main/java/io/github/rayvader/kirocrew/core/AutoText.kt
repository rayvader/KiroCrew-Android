package io.github.rayvader.kirocrew.core

import kotlin.math.roundToInt

/**
 * "Auto text size": a base page text size taken from the screen's smallest width
 * (`Configuration.smallestScreenWidthDp`), so a folded phone gets slightly larger text
 * and an unfolded phone or a tablet normal-size text. The smallest width does not
 * change when the phone is turned, so rotating never changes the size.
 *
 *   smallest width   size
 *   < 520 dp         110 %   folded phone, cover screen (~344-411 dp)
 *   520-599 dp       110 -> 100 %, straight line   (560 dp -> 105 %)
 *   >= 600 dp        100 %   unfolded phone, tablet
 */
object AutoText {
    const val PHONE_MAX_DP = 520
    const val TABLET_MIN_DP = 600
    const val PHONE_PERCENT = 110
    const val TABLET_PERCENT = 100

    /** The automatic size in percent for a screen whose smallest width is [smallestWidthDp]. */
    fun percent(smallestWidthDp: Int): Int = when {
        smallestWidthDp <= 0 -> 100
        smallestWidthDp < PHONE_MAX_DP -> PHONE_PERCENT
        smallestWidthDp >= TABLET_MIN_DP -> TABLET_PERCENT
        else -> {
            val t = (smallestWidthDp - PHONE_MAX_DP).toDouble() / (TABLET_MIN_DP - PHONE_MAX_DP)
            (PHONE_PERCENT + t * (TABLET_PERCENT - PHONE_PERCENT)).roundToInt()
        }
    }

    /** The WebView text zoom: the automatic size with the user's slider on top (slider 50-200 %). */
    fun zoom(smallestWidthDp: Int, sliderPercent: Int): Int =
        (percent(smallestWidthDp) * sliderPercent.coerceIn(50, 200) / 100.0).roundToInt().coerceIn(MIN_ZOOM, MAX_ZOOM)

    const val MIN_ZOOM = 50
    const val MAX_ZOOM = 300
}
