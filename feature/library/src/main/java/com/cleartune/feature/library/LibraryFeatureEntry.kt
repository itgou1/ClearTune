package com.cleartune.feature.library

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.cleartune.core.contracts.LibraryRepository
import com.cleartune.core.contracts.PlaybackGateway
import com.cleartune.core.contracts.PlaylistRepository

data class LibraryFeatureDependencies(
    val libraryRepository: LibraryRepository,
    val playbackGateway: PlaybackGateway,
    val playlistRepository: PlaylistRepository,
)

object LibraryFeatureEntry {
    const val route = "library"

    @Composable
    @Suppress("UNUSED_PARAMETER")
    fun Content(
        dependencies: LibraryFeatureDependencies,
        onNavigate: (String) -> Unit,
    ) {
        Text(
            text = "Library module",
            modifier = Modifier.semantics { stateDescription = "baseline stub" },
        )
    }
}
