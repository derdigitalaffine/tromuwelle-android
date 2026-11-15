package de.tromuwelle.android

import androidx.media3.common.PlaybackException
import org.junit.Assert.*
import org.junit.Test

class PlaybackRecoveryTest {
    @Test fun retriesOnlyTransientRadioFailuresWithBoundedBackoff() {
        val offline = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        assertEquals(1_200L, radioRetryDelayMs(offline, null, 0))
        assertEquals(3_500L, radioRetryDelayMs(offline, null, 1))
        assertEquals(9_000L, radioRetryDelayMs(offline, null, 2))
        assertNull(radioRetryDelayMs(offline, null, 3))
        assertNull(radioRetryDelayMs(offline, null, -1))
        assertEquals(1_200L,
            radioRetryDelayMs(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 503, 0))
        assertEquals(3_500L,
            radioRetryDelayMs(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 429, 1))
    }

    @Test fun neverRetriesAuthOrPermanentMediaErrors() {
        for (status in listOf(400, 401, 403, 404)) {
            assertNull(radioRetryDelayMs(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, status, 0))
        }
        assertNull(radioRetryDelayMs(PlaybackException.ERROR_CODE_DECODING_FAILED, null, 0))
        assertNull(radioRetryDelayMs(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, null, 0))
    }

    @Test fun lateResponsesCannotTakeOverOtherMediaOrSignedOutSession() {
        assertTrue(playbackRequestIsCurrent(5, 5, "RADIO:s1", "RADIO:s1", true))
        assertFalse(playbackRequestIsCurrent(5, 6, "RADIO:s1", "RADIO:s1", true))
        assertFalse(playbackRequestIsCurrent(5, 5, "RADIO:s1", "RADIO:s2", true))
        assertFalse(playbackRequestIsCurrent(5, 5, "RADIO:s1", "RADIO:s1", false))
        assertFalse(playbackRequestIsCurrent(5, 5, "", "", true))
    }

    @Test fun progressWritesOnlyOnNewTrackOrMeaningfulAdvance() {
        assertFalse(shouldPersistPlaybackPosition("song", "", 0L, -1L))
        assertTrue(shouldPersistPlaybackPosition("song", "", 1_500L, -1L))
        assertFalse(shouldPersistPlaybackPosition("song", "song", 4_000L, 1_500L))
        assertTrue(shouldPersistPlaybackPosition("song", "song", 12_000L, 1_500L))
        assertTrue(shouldPersistPlaybackPosition("song", "song", 1_500L, 12_000L))
        assertTrue(shouldPersistPlaybackPosition("song-2", "song", 2_000L, 1_500L))
    }
}
