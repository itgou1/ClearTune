package com.cleartune.feature.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.cleartune.core.contracts.DownloadRepository
import com.cleartune.core.contracts.SettingsRepository
import com.cleartune.core.contracts.SourceRepository

data class SettingsFeatureDependencies(
    val settingsRepository: SettingsRepository,
    val sourceRepository: SourceRepository,
    val downloadRepository: DownloadRepository,
)

object SettingsFeatureEntry {
    const val route = "settings"

    @Composable
    @Suppress("UNUSED_PARAMETER")
    fun Content(
        dependencies: SettingsFeatureDependencies,
        onNavigate: (String) -> Unit,
    ) {
        Text(
            text = "Settings module",
            modifier = Modifier.semantics { stateDescription = "baseline stub" },
        )
    }
}
