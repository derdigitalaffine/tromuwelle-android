package de.tromuwelle.android

import androidx.media3.common.PlaybackException
import kotlin.math.abs

/**
 * Retry only transient streaming failures, never bad credentials, missing media,
 * invalid codecs or broken certificates. One radio has three bounded retries.
 */
internal fun radioRetryDelayMs(errorCode: Int, httpStatus: Int?, attempt: Int): Long? {
    if (attempt !in 0..2) return null
    val transient = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> true
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            httpStatus in setOf(429, 500, 502, 503, 504)
        else -> false
    }
    if (!transient) return null
    return longArrayOf(1_200L, 3_500L, 9_000L)[attempt]
}

/** A delayed reconnect or metadata load may only operate on its original media. */
internal fun playbackRequestIsCurrent(
    requestGeneration: Int,
    currentGeneration: Int,
    requestedMediaId: String,
    currentMediaId: String?,
    connected: Boolean
): Boolean = connected && requestGeneration == currentGeneration &&
    requestedMediaId.isNotBlank() && requestedMediaId == currentMediaId

/** Suppress repeated progress writes while the player is stopped or paused. */
internal fun shouldPersistPlaybackPosition(
    mediaId: String,
    lastMediaId: String,
    positionMs: Long,
    lastPositionMs: Long,
    minDeltaMs: Long = 10_000L
): Boolean = mediaId.isNotBlank() && positionMs > 1_000L &&
    (mediaId != lastMediaId || lastPositionMs < 0L ||
        abs(positionMs - lastPositionMs) >= minDeltaMs)
