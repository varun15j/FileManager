package com.varun.filemanager.media

import org.junit.Assert.*
import org.junit.Test

class OrientationTest {
    @Test fun upright_and_unknown_values_need_no_turn() {
        assertTrue(Orientation.turnFor(1).isIdentity)
        assertTrue(Orientation.turnFor(0).isIdentity)
        assertTrue(Orientation.turnFor(99).isIdentity)
    }

    @Test fun phoneHeldUprightIsTurnedNinetyDegrees() {
        assertEquals(Orientation.Turn(90, false), Orientation.turnFor(6))   // most portrait camera photos
        assertEquals(Orientation.Turn(-90, false), Orientation.turnFor(8))
        assertEquals(Orientation.Turn(180, false), Orientation.turnFor(3))
    }

    @Test fun mirroredOrientationsFlip() {
        assertEquals(Orientation.Turn(0, true), Orientation.turnFor(2))
        assertEquals(Orientation.Turn(180, true), Orientation.turnFor(4))
        assertEquals(Orientation.Turn(90, true), Orientation.turnFor(5))
        assertEquals(Orientation.Turn(-90, true), Orientation.turnFor(7))
    }

    @Test fun sizeSwapsOnlyForQuarterTurns() {
        assertEquals(3000 to 4000, Orientation.sizeAfter(Orientation.turnFor(6), 4000, 3000))
        assertEquals(3000 to 4000, Orientation.sizeAfter(Orientation.turnFor(8), 4000, 3000))
        assertEquals(4000 to 3000, Orientation.sizeAfter(Orientation.turnFor(3), 4000, 3000))
        assertEquals(4000 to 3000, Orientation.sizeAfter(Orientation.turnFor(1), 4000, 3000))
    }
}
