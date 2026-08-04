package com.cleartune.playback

import com.cleartune.core.model.LocationType
import com.cleartune.core.model.PlayableTrack
import com.cleartune.core.model.TrackLocation

sealed interface PlaybackFailure {
    data object NoAvailableLocation : PlaybackFailure
    data object NetworkUnavailable : PlaybackFailure
}

sealed interface LocationResolution {
    data class Ready(val attempts: List<TrackLocation>) : LocationResolution
    data class Unavailable(val failure: PlaybackFailure) : LocationResolution
}

object LocationResolver {
    fun resolve(
        playableTrack: PlayableTrack,
        fileExists: (String) -> Boolean,
        uriReadable: (String) -> Boolean,
        networkAvailable: Boolean,
    ): LocationResolution {
        val available = playableTrack.locations
            .asSequence()
            .filter(TrackLocation::available)
            .filter { location ->
                when (location.type) {
                    LocationType.DOWNLOADED_FILE -> fileExists(location.uri)
                    LocationType.LOCAL_URI -> uriReadable(location.uri)
                    LocationType.REMOTE_URL -> networkAvailable
                }
            }
            .sortedBy { it.type.priority }
            .toList()

        if (available.isNotEmpty()) return LocationResolution.Ready(available)

        val hasAvailableRemote = playableTrack.locations.any {
            it.available && it.type == LocationType.REMOTE_URL
        }
        return LocationResolution.Unavailable(
            if (hasAvailableRemote && !networkAvailable) {
                PlaybackFailure.NetworkUnavailable
            } else {
                PlaybackFailure.NoAvailableLocation
            },
        )
    }

    private val LocationType.priority: Int
        get() = when (this) {
            LocationType.DOWNLOADED_FILE -> 0
            LocationType.LOCAL_URI -> 1
            LocationType.REMOTE_URL -> 2
        }
}
