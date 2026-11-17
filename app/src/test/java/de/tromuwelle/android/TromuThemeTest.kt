package de.tromuwelle.android

import org.junit.Assert.*
import org.junit.Test

class TromuThemeTest {
    @Test fun unknownSavedThemeFallsBackSafely() {
        assertEquals(TromuPalette.AURORA, TromuPalette.fromStored("invalid"))
        assertEquals(TromuPalette.AURORA, TromuPalette.fromStored(null))
        assertEquals(TromuAppearance.SYSTEM, TromuAppearance.fromStored("invalid"))
        assertEquals(TromuAppearance.SYSTEM, TromuAppearance.fromStored(null))
    }

    @Test fun paletteAndModeValuesRemainStableForStoredPreferences() {
        for (palette in TromuPalette.entries) {
            assertEquals(palette, TromuPalette.fromStored(palette.name))
            assertTrue(palette.title.isNotBlank())
        }
        for (appearance in TromuAppearance.entries) {
            assertEquals(appearance, TromuAppearance.fromStored(appearance.name))
        }
    }
}
