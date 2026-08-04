package com.cleartune.feature.playlists

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.cleartune.core.contracts.PlaybackGateway
import com.cleartune.core.contracts.PlaylistRepository

data class PlaylistsFeatureDependencies(
    val playlistRepository: PlaylistRepository,
    val playbackGateway: PlaybackGateway,
)

object PlaylistsFeatureEntry {
    const val route = "playlists"

    @Composable
    @Suppress("UNUSED_PARAMETER")
    fun Content(
        dependencies: PlaylistsFeatureDependencies,
        onNavigate: (String) -> Unit,
    ) {
        Text(
            text = "Playlists module",
            modifier = Modifier.semantics { stateDescription = "baseline stub" },
        )
    }
}
