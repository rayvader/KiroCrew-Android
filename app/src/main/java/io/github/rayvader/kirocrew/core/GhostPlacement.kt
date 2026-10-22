package io.github.rayvader.kirocrew.core

/**
 * Where the menu ghost rests. It sits on the left or right edge of the page, half hidden,
 * at a height saved as a share of the free space so it survives rotation.
 * All sizes are in pixels; no Android types, so it can be unit tested.
 */
object GhostPlacement {
    /** Saved height meaning "never moved": use the default distance from the top. */
    const val UNSET = -1f

    /** The edge a ghost released at [centerX] snaps to. */
    fun leftSide(centerX: Float, parentWidth: Float): Boolean = centerX < parentWidth / 2f

    /** Left edge of the ghost at rest: half hidden past the edge, or [inset] px in from it while the menu is open. */
    fun restX(left: Boolean, parentWidth: Float, size: Float, hidden: Float, inset: Float, open: Boolean): Float =
        if (left) {
            if (open) inset else -hidden
        } else {
            val flush = parentWidth - size
            if (open) flush - inset else flush + hidden
        }

    /** Top of the ghost for a saved [fraction] (0 = top, 1 = bottom), or [defaultTop] if never moved. */
    fun top(fraction: Float, parentHeight: Float, size: Float, defaultTop: Float): Float {
        val free = (parentHeight - size).coerceAtLeast(0f)
        return if (fraction < 0f) defaultTop.coerceIn(0f, free) else (fraction.coerceIn(0f, 1f) * free)
    }

    /** The share of free height to save for a ghost whose top is at [top]. */
    fun fraction(top: Float, parentHeight: Float, size: Float): Float {
        val free = parentHeight - size
        return if (free <= 0f) 0f else (top / free).coerceIn(0f, 1f)
    }
}
