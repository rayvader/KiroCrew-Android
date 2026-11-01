package io.github.rayvader.kirocrew.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTextTest {

    @Test
    fun foldedPhonesGetSlightlyLargerText() {
        assertEquals(110, AutoText.percent(344)) // Fold cover screen
        assertEquals(110, AutoText.percent(360))
        assertEquals(110, AutoText.percent(411)) // Pixel 6
        assertEquals(110, AutoText.percent(519))
    }

    @Test
    fun unfoldedPhonesAndTabletsGetNormalText() {
        assertEquals(100, AutoText.percent(600))
        assertEquals(100, AutoText.percent(673)) // unfolded Fold inner screen
        assertEquals(100, AutoText.percent(880))
        assertEquals(100, AutoText.percent(2000))
    }

    @Test
    fun betweenItBlendsInAStraightLine() {
        assertEquals(110, AutoText.percent(520))
        assertEquals(105, AutoText.percent(560))
        assertEquals(108, AutoText.percent(540))
        assertEquals(103, AutoText.percent(580))
        var last = 111
        for (w in 500..620) {
            val p = AutoText.percent(w)
            assertTrue("$w dp: $p > $last", p <= last)
            assertTrue("$w dp: $p jumps from $last", last - p <= 1)
            last = p
        }
    }

    @Test
    fun turningThePhoneDoesNotChangeTheSize() {
        // The smallest width is the same upright and sideways, so the size is too: a folded
        // phone 411x915 dp reads 411 either way; unfolded 673x841 reads 673 either way.
        for (smallest in listOf(344, 411, 560, 673)) {
            assertEquals(AutoText.zoom(smallest, 100), AutoText.zoom(smallest, 100))
        }
        assertEquals(AutoText.percent(411), AutoText.percent(minOf(411, 915)))
        assertEquals(AutoText.percent(411), AutoText.percent(minOf(915, 411)))
    }

    @Test
    fun unknownWidthChangesNothing() {
        assertEquals(100, AutoText.percent(0))
        assertEquals(100, AutoText.percent(-5))
    }

    @Test
    fun sliderSitsOnTopOfTheAutomaticSize() {
        assertEquals(110, AutoText.zoom(411, 100))
        assertEquals(88, AutoText.zoom(411, 80)) // 110 % x 80 %
        assertEquals(132, AutoText.zoom(411, 120)) // 110 % x 120 %
        assertEquals(100, AutoText.zoom(673, 100))
        assertEquals(80, AutoText.zoom(673, 80)) // 100 % x 80 %
        assertEquals(120, AutoText.zoom(673, 120)) // 100 % x 120 %
        assertEquals(105, AutoText.zoom(560, 100))
    }

    @Test
    fun zoomStaysInRange() {
        assertEquals(220, AutoText.zoom(360, 200)) // 110 % x 200 %
        assertTrue(AutoText.zoom(2000, 50) >= AutoText.MIN_ZOOM)
        assertTrue(AutoText.zoom(360, 200) <= AutoText.MAX_ZOOM)
    }

    @Test
    fun sliderIsClampedToItsOwnRange() {
        assertEquals(AutoText.zoom(673, 200), AutoText.zoom(673, 999))
        assertEquals(AutoText.zoom(673, 50), AutoText.zoom(673, 1))
    }
}
