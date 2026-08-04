package com.cleartune.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cleartune.core.designsystem.theme.ClearTuneDimensions
import com.cleartune.core.designsystem.theme.ClearTuneTheme
import com.cleartune.feature.downloads.DownloadsFeatureEntry
import com.cleartune.feature.library.LibraryFeatureEntry
import com.cleartune.feature.player.PlayerFeatureEntry
import com.cleartune.feature.playlists.PlaylistsFeatureEntry
import com.cleartune.feature.settings.SettingsFeatureEntry
import com.cleartune.feature.sources.SourcesFeatureEntry

private val baselineRoutes = listOf(
    LibraryFeatureEntry.route,
    SourcesFeatureEntry.route,
    DownloadsFeatureEntry.route,
    PlayerFeatureEntry.route,
    PlaylistsFeatureEntry.route,
    SettingsFeatureEntry.route,
)

@Composable
fun BaselineApp() {
    ClearTuneTheme {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(ClearTuneDimensions.spacingSm),
                modifier = Modifier.padding(ClearTuneDimensions.spacingMd),
            ) {
                Text(text = "ClearTune", style = MaterialTheme.typography.headlineLarge)
                Text(text = "Shared baseline")
                baselineRoutes.forEach { route -> Text(text = route) }
            }
        }
    }
}
