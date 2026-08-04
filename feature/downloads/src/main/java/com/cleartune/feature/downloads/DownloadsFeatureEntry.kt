package com.cleartune.feature.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.cleartune.core.contracts.DownloadRepository
import com.cleartune.core.contracts.PlaybackGateway
import kotlinx.coroutines.launch

data class DownloadsFeatureDependencies(
    val downloadRepository: DownloadRepository,
    val playbackGateway: PlaybackGateway,
)

object DownloadsFeatureEntry {
    const val route = "downloads"

    @Composable
    fun Content(
        dependencies: DownloadsFeatureDependencies,
        onNavigate: (String) -> Unit,
    ) {
        val downloads by dependencies.downloadRepository.observeDownloads().collectAsState(initial = emptyList())
        val scope = rememberCoroutineScope()
        DownloadsScreen(downloads) { command ->
            scope.launch { dependencies.downloadRepository.dispatch(command) }
        }
    }
}
