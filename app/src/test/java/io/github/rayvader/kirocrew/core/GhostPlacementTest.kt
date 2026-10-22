package io.github.rayvader.kirocrew.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostPlacementTest {

    @Test
    fun snapsToTheNearerEdge() {
        assertTrue(GhostPlacement.leftSide(100f, 1000f))
        assertFalse(GhostPlacement.leftSide(900f, 1000f))
        assertFalse(GhostPlacement.leftSide(500f, 1000f))
    }

    @Test
    fun restsHalfHiddenPastTheEdge() {
        assertEquals(-22f, GhostPlacement.restX(true, 1000f, 48f, 22f, 6f, false), 0f)
        assertEquals(974f, GhostPlacement.restX(false, 1000f, 48f, 22f, 6f, false), 0f)
    }

    @Test
    fun slidesInWhileTheMenuIsOpen() {
        assertEquals(6f, GhostPlacement.restX(true, 1000f, 48f, 22f, 6f, true), 0f)
        assertEquals(946f, GhostPlacement.restX(false, 1000f, 48f, 22f, 6f, true), 0f)
    }

    @Test
    fun usesTheDefaultTopUntilMoved() {
        assertEquals(48f, GhostPlacement.top(GhostPlacement.UNSET, 2000f, 48f, 48f), 0f)
    }

    @Test
    fun keepsTheSavedShareAcrossSizes() {
        val f = GhostPlacement.fraction(976f, 2000f, 48f)
        assertEquals(0.5f, f, 0.001f)
        assertEquals(576f, GhostPlacement.top(f, 1200f, 48f, 48f), 0.5f)
    }

    @Test
    fun staysOnScreen() {
        assertEquals(0f, GhostPlacement.top(-0.5f - 1f, 1000f, 48f, -10f), 0f)
        assertEquals(952f, GhostPlacement.top(5f, 1000f, 48f, 48f), 0f)
        assertEquals(1f, GhostPlacement.fraction(5000f, 1000f, 48f), 0f)
        assertEquals(0f, GhostPlacement.fraction(10f, 40f, 48f), 0f)
    }
}
