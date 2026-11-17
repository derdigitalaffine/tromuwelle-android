package de.tromuwelle.android

import org.junit.Assert.*
import org.junit.Test

class KaraokeSynchronizationTest {
    @Test fun alignedStemRunsAtUnmodifiedSpeed() {
        assertEquals(StemSyncAdjustment(), stemSyncAdjustment(3000, 3000))
        assertEquals(StemSyncAdjustment(), stemSyncAdjustment(3000, 3020))
    }

    @Test fun moderateDriftUsesSlowCatchUpWithoutSeeking() {
        val lagging = stemSyncAdjustment(4000, 3935)
        val ahead = stemSyncAdjustment(4000, 4060)
        assertNull(lagging.seekToMs)
        assertNull(ahead.seekToMs)
        assertTrue(lagging.rate > 1.0f)
        assertTrue(ahead.rate < 1.0f)
        assertTrue(lagging.rate <= 1.04f)
        assertTrue(ahead.rate >= .96f)
    }

    @Test fun audibleDriftSeeksToMasterClock() {
        assertEquals(2000L, stemSyncAdjustment(2000, 1770).seekToMs)
        assertEquals(2000L, stemSyncAdjustment(2000, 2150).seekToMs)
    }

    @Test fun calibrationOffsetDelaysVocalTimelineWithoutAffectingMaster() {
        assertEquals(4750L, stemSyncAdjustment(5000, 5200, 250).seekToMs)
        assertEquals(5250L, stemSyncAdjustment(5000, 4900, -250).seekToMs)
        assertEquals(0L, stemSyncAdjustment(120, 250, 250).seekToMs)
    }
}
