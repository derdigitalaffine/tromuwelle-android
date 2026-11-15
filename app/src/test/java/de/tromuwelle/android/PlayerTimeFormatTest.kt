package de.tromuwelle.android

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerTimeFormatTest {
    @Test fun formatsShortLongAndMissingPositions() {
        assertEquals("0:00", formatMediaTime(-2L))
        assertEquals("0:09", formatMediaTime(9_999L))
        assertEquals("1:02", formatMediaTime(62_000L))
        assertEquals("1:01:01", formatMediaTime(3_661_000L))
        assertEquals("12:00:00", formatMediaTime(43_200_000L))
    }
}
