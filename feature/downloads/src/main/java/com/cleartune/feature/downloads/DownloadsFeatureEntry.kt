package com.cleartune.feature.downloads

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.cleartune.core.contracts.DownloadRepository
import com.cleartune.core.contracts.PlaybackGateway

data class DownloadsFeatureDependencies(
    val downloadRepository: DownloadRepository,
    val playbackGateway: PlaybackGateway,
)

object DownloadsFeatureEntry {
    const val route = "downloads"

    @Composable
    @Suppress("UNUSED_PARAMETER")
    fun Content(
        dependencies: DownloadsFeatureDependencies,
        onNavigate: (String) -> Unit,
    ) {
        Text(
            text = "Downloads module",
            modifier = Modifier.semantics { stateDescription = "baseline stub" },
        )
    }
}
