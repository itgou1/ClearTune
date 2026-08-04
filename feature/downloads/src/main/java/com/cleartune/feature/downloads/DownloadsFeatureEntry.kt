package com.cleartune.feature.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.cleartune.core.contracts.DownloadRepository
import com.cleartune.core.contracts.PlaybackGateway
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

data class DownloadsFeatureDependencies(
    val downloadRepository: DownloadRepository,
    val playbackGateway: PlaybackGateway,
    val titleResolver: DownloadTitleResolver,
)

object DownloadsFeatureEntry {
    const val route = "downloads"

    @Composable
    fun Content(
        dependencies: DownloadsFeatureDependencies,
        onNavigate: (String) -> Unit,
    ) {
        val downloads by dependencies.downloadRepository.observeDownloads()
            .collectAsStateWithLifecycle(initialValue = emptyList())
        val scope = rememberCoroutineScope()
        DownloadsScreen(downloads, dependencies.titleResolver) { command ->
            scope.launch { dependencies.downloadRepository.dispatch(command) }
        }
    }
}
