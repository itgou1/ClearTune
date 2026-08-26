package com.cleartune.core.player

import android.os.Bundle
import com.cleartune.core.model.ReplayGain
import com.cleartune.core.model.Song
import kotlin.math.min
import kotlin.math.pow

internal object ReplayGainNormalizer {
    fun requiredHeadroomDb(items: List<ReplayGain>, preferAlbum: Boolean): Double = items
        .mapNotNull { select(it, preferAlbum)?.gainDb }
        .filter(Double::isFinite)
        .maxOrNull()
        ?.coerceAtLeast(0.0)
        ?: 0.0

    fun volume(
        replayGain: ReplayGain?,
        enabled: Boolean,
        preferAlbum: Boolean,
        headroomDb: Double,
    ): Float {
        if (!enabled || replayGain == null) return 1f
        val selection = select(replayGain, preferAlbum) ?: return 1f
        if (!selection.gainDb.isFinite()) return 1f

        val safeHeadroom = headroomDb.takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0
        val requested = 10.0.pow((selection.gainDb - safeHeadroom) / 20.0)
        val peakLimit = selection.peak
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { 1.0 / it }
            ?: Double.POSITIVE_INFINITY
        return min(min(requested, peakLimit), 1.0).coerceIn(0.0, 1.0).toFloat()
    }

    private fun select(replayGain: ReplayGain, preferAlbum: Boolean): GainSelection? {
        val candidates: List<GainCandidate> = if (preferAlbum) {
            listOf(
                GainCandidate(replayGain.albumGainDb, replayGain.albumPeak),
                GainCandidate(replayGain.fallbackGainDb, replayGain.albumPeak ?: replayGain.trackPeak),
                GainCandidate(replayGain.trackGainDb, replayGain.trackPeak),
            )
        } else {
            listOf(
                GainCandidate(replayGain.trackGainDb, replayGain.trackPeak),
                GainCandidate(replayGain.fallbackGainDb, replayGain.trackPeak ?: replayGain.albumPeak),
                GainCandidate(replayGain.albumGainDb, replayGain.albumPeak),
            )
        }
        return candidates.firstOrNull { it.gainDb?.isFinite() == true }
            ?.let { GainSelection(requireNotNull(it.gainDb), it.peak) }
    }

    private data class GainCandidate(val gainDb: Double?, val peak: Double?)
    private data class GainSelection(val gainDb: Double, val peak: Double?)
}

internal object ReplayGainMetadata {
    private const val PREFIX = "com.cleartune.replaygain."
    private const val TRACK_GAIN = "${PREFIX}track_gain"
    private const val ALBUM_GAIN = "${PREFIX}album_gain"
    private const val TRACK_PEAK = "${PREFIX}track_peak"
    private const val ALBUM_PEAK = "${PREFIX}album_peak"
    private const val BASE_GAIN = "${PREFIX}base_gain"
    private const val FALLBACK_GAIN = "${PREFIX}fallback_gain"
    private const val ALBUM_SEQUENCE = "${PREFIX}album_sequence"
    private const val TRACK_HEADROOM = "${PREFIX}track_headroom"
    private const val ALBUM_HEADROOM = "${PREFIX}album_headroom"

    fun queueValues(songs: List<Song>): QueueValues {
        val albumId = songs.firstOrNull()?.albumId
        val albumSequence = songs.size > 1 && albumId != null && songs.all { it.albumId == albumId }
        val gains = songs.mapNotNull(Song::replayGain)
        return QueueValues(
            albumSequence = albumSequence,
            trackHeadroomDb = ReplayGainNormalizer.requiredHeadroomDb(gains, preferAlbum = false),
            albumHeadroomDb = ReplayGainNormalizer.requiredHeadroomDb(gains, preferAlbum = true),
        )
    }

    fun extras(song: Song, queue: QueueValues): Bundle = Bundle().apply {
        putBoolean(ALBUM_SEQUENCE, queue.albumSequence)
        putDouble(TRACK_HEADROOM, queue.trackHeadroomDb)
        putDouble(ALBUM_HEADROOM, queue.albumHeadroomDb)
        song.replayGain?.let { gain ->
            gain.trackGainDb?.let { putDouble(TRACK_GAIN, it) }
            gain.albumGainDb?.let { putDouble(ALBUM_GAIN, it) }
            gain.trackPeak?.let { putDouble(TRACK_PEAK, it) }
            gain.albumPeak?.let { putDouble(ALBUM_PEAK, it) }
            gain.baseGainDb?.let { putDouble(BASE_GAIN, it) }
            gain.fallbackGainDb?.let { putDouble(FALLBACK_GAIN, it) }
        }
    }

    fun values(extras: Bundle?): Values {
        if (extras == null) return Values()
        return Values(
            replayGain = ReplayGain(
                trackGainDb = extras.optionalDouble(TRACK_GAIN),
                albumGainDb = extras.optionalDouble(ALBUM_GAIN),
                trackPeak = extras.optionalDouble(TRACK_PEAK),
                albumPeak = extras.optionalDouble(ALBUM_PEAK),
                baseGainDb = extras.optionalDouble(BASE_GAIN),
                fallbackGainDb = extras.optionalDouble(FALLBACK_GAIN),
            ).takeIf { gain ->
                gain.trackGainDb != null || gain.albumGainDb != null ||
                    gain.fallbackGainDb != null || gain.baseGainDb != null
            },
            albumSequence = extras.getBoolean(ALBUM_SEQUENCE, false),
            trackHeadroomDb = extras.getDouble(TRACK_HEADROOM, 0.0),
            albumHeadroomDb = extras.getDouble(ALBUM_HEADROOM, 0.0),
        )
    }

    private fun Bundle.optionalDouble(key: String): Double? = if (containsKey(key)) getDouble(key) else null

    data class QueueValues(
        val albumSequence: Boolean,
        val trackHeadroomDb: Double,
        val albumHeadroomDb: Double,
    )

    data class Values(
        val replayGain: ReplayGain? = null,
        val albumSequence: Boolean = false,
        val trackHeadroomDb: Double = 0.0,
        val albumHeadroomDb: Double = 0.0,
    )
}
