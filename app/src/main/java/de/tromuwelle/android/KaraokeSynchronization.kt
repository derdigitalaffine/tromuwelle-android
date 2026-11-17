package de.tromuwelle.android

import kotlin.math.abs

/** A small, testable clock policy shared by all independently decoded karaoke stems.
 * Positive [offsetMs] delays a stem relative to the instrumental timeline.
 * Small errors are corrected gradually; large errors get a single hard alignment.
 */
internal data class StemSyncAdjustment(val seekToMs: Long? = null, val rate: Float = 1f)

internal fun stemSyncAdjustment(
    referenceMs: Long, followerMs: Long, offsetMs: Long = 0
): StemSyncAdjustment {
    val target = (referenceMs - offsetMs).coerceAtLeast(0L)
    val drift = followerMs - target
    return when {
        abs(drift) >= 110L -> StemSyncAdjustment(seekToMs = target)
        abs(drift) < 24L -> StemSyncAdjustment()
        else -> StemSyncAdjustment(
            rate = (1f - drift.toFloat() * 0.00045f).coerceIn(0.96f, 1.04f)
        )
    }
}
