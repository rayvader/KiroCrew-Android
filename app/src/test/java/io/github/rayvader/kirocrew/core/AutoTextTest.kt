package io.github.rayvader.kirocrew.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTextTest {

    @Test
    fun phoneBaselineIsOneHundredPercent() {
        assertEquals(100, AutoText.percent(360))
    }

    @Test
    fun wideScreensGetLargerText() {
        assertEquals(133, AutoText.percent(600)) // unfolded phone
        assertEquals(150, AutoText.percent(800)) // tablet, capped
        assertEquals(150, AutoText.percent(2000))
    }

    @Test
    fun narrowScreensShrinkALittle() {
        assertEquals(94, AutoText.percent(320))
        assertEquals(90, AutoText.percent(100)) // floor
    }

    @Test
    fun growsWithWidth() {
        var last = 0
        for (w in 300..1000 step 20) {
            val p = AutoText.percent(w)
            assertTrue("$w dp: $p < $last", p >= last)
            last = p
        }
    }

    @Test
    fun unknownWidthChangesNothing() {
        assertEquals(100, AutoText.percent(0))
        assertEquals(100, AutoText.percent(-5))
    }

    @Test
    fun sliderSitsOnTopOfTheAutomaticSize() {
        assertEquals(100, AutoText.zoom(360, 100))
        assertEquals(133, AutoText.zoom(600, 100))
        assertEquals(200, AutoText.zoom(600, 150)) // 133 % x 150 %
        assertEquals(67, AutoText.zoom(600, 50))
    }

    @Test
    fun zoomStaysInRange() {
        assertEquals(300, AutoText.zoom(2000, 200)) // 150 % x 200 %
        assertTrue(AutoText.zoom(100, 50) >= AutoText.MIN_ZOOM)
    }

    @Test
    fun sliderIsClampedToItsOwnRange() {
        assertEquals(AutoText.zoom(600, 200), AutoText.zoom(600, 999))
        assertEquals(AutoText.zoom(600, 50), AutoText.zoom(600, 1))
    }
}
