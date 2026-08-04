package com.cleartune.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.cleartune.core.contracts.DownloadRepository
import com.cleartune.core.contracts.SettingsRepository
import com.cleartune.core.contracts.SourceRepository
import com.cleartune.core.designsystem.theme.ClearTuneDimensions
import com.cleartune.core.model.AppSettings
import com.cleartune.core.model.ReducedMotionMode
import com.cleartune.core.model.SettingsCommand
import com.cleartune.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

data class SettingsFeatureDependencies(
    val settingsRepository: SettingsRepository,
    val sourceRepository: SourceRepository,
    val downloadRepository: DownloadRepository,
    val productSettings: Flow<SettingsProductState> = flowOf(SettingsProductState()),
    val onProductCommand: suspend (SettingsProductCommand) -> Unit = {},
)

object SettingsFeatureEntry {
    const val route = "settings"

    @Composable
    fun Content(dependencies: SettingsFeatureDependencies, onNavigate: (String) -> Unit) {
        val settings by dependencies.settingsRepository.settings.collectAsState(initial = AppSettings())
        val product by dependencies.productSettings.collectAsState(initial = SettingsProductState())
        val sources by dependencies.sourceRepository.observeSources().collectAsState(initial = emptyList())
        val downloads by dependencies.downloadRepository.observeDownloads().collectAsState(initial = emptyList())
        val scope = rememberCoroutineScope()
        val dispatch: (SettingsProductCommand) -> Unit = { command ->
            scope.launch { dependencies.onProductCommand(command) }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = ClearTuneDimensions.spacingMd),
            verticalArrangement = Arrangement.spacedBy(ClearTuneDimensions.spacingSm),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onNavigate("back") }) { Text("Back") }
                    Text("Settings", style = MaterialTheme.typography.headlineMedium)
                }
            }
            item { SectionTitle("Appearance") }
            item { SettingsCard("Theme") {
                ThemeMode.entries.forEach { mode -> ChoiceRow(
                    label = mode.label,
                    selected = settings.themeMode == mode,
                    onClick = { scope.launch { dependencies.settingsRepository.update(SettingsCommand.SetTheme(mode)) } },
                ) }
            } }
            item { SettingsCard("Motion") {
                ReducedMotionMode.entries.forEach { mode -> ChoiceRow(
                    label = mode.label,
                    selected = settings.reducedMotionMode == mode,
                    onClick = { scope.launch {
                        dependencies.settingsRepository.update(SettingsCommand.SetReducedMotion(mode))
                    } },
                ) }
            } }
            item { ToggleCard("Dynamic background", product.dynamicBackground) {
                dispatch(SettingsProductCommand.SetDynamicBackground(it))
            } }
            item { SectionTitle("Playback") }
            item { SettingsCard("Playback behavior") {
                ToggleRow("Restore queue and position", product.restoreQueue) {
                    dispatch(SettingsProductCommand.SetRestoreQueue(it))
                }
                HorizontalDivider()
                ToggleRow("Pause when headphones disconnect", product.pauseOnHeadphoneDisconnect) {
                    dispatch(SettingsProductCommand.SetPauseOnHeadphoneDisconnect(it))
                }
                HorizontalDivider()
                ToggleRow("Background playback", product.backgroundPlayback) {
                    dispatch(SettingsProductCommand.SetBackgroundPlayback(it))
                }
            } }
            item { SectionTitle("Music and storage") }
            item { SettingsCard("Offline cache") {
                ToggleRow("Cache streamed music", product.offlineCacheEnabled) {
                    dispatch(SettingsProductCommand.SetOfflineCacheEnabled(it))
                }
                NavigationRow("Cache limit", "${product.cacheLimitMb} MB") {
                    val next = if (product.cacheLimitMb >= 2_048) 256 else product.cacheLimitMb * 2
                    dispatch(SettingsProductCommand.SetCacheLimitMb(next))
                }
                NavigationRow(
                    "Clean up cache",
                    product.cleanUpCache.label(formatBytes(product.cachedBytes)),
                    enabled = product.cleanUpCache.isActionable,
                ) {
                    dispatch(SettingsProductCommand.CleanUpCache)
                }
                NavigationRow("Offline downloads", "${downloads.size} tracks") { onNavigate("downloads") }
            } }
            item { SettingsCard("Library") {
                NavigationRow("Music sources", "${sources.size}") { onNavigate("sources") }
                NavigationRow(
                    "Scan library",
                    product.scanLibrary.label("Scan now"),
                    enabled = product.scanLibrary.isActionable,
                ) { dispatch(SettingsProductCommand.ScanLibrary) }
            } }
            item { SectionTitle("About") }
            item { SettingsCard("ClearTune") {
                Text("Local and WebDAV music player", modifier = Modifier.padding(ClearTuneDimensions.spacingMd))
                NavigationRow(
                    "Open-source licenses",
                    product.openLicenses.label("View"),
                    enabled = product.openLicenses.isActionable,
                ) { dispatch(SettingsProductCommand.OpenLicenses) }
            } }
        }
    }
}

@Composable private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(ClearTuneDimensions.spacingMd))
            content()
        }
    }
}

@Composable private fun ToggleCard(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) { ToggleRow(label, checked, onCheckedChange) }
}

@Composable private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(ClearTuneDimensions.spacingMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = ClearTuneDimensions.spacingSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable private fun NavigationRow(
    label: String,
    value: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(ClearTuneDimensions.spacingMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Text(
            value,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

private val SettingsOperationState.isActionable: Boolean
    get() = this !is SettingsOperationState.Unavailable && this !is SettingsOperationState.Running

private fun SettingsOperationState.label(ready: String): String = when (this) {
    SettingsOperationState.Ready -> ready
    SettingsOperationState.Running -> "Working…"
    is SettingsOperationState.Success -> message
    is SettingsOperationState.Error -> "Failed: $message"
    is SettingsOperationState.Unavailable -> reason
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "Empty"
    bytes < 1_048_576 -> "${bytes / 1_024} KB"
    else -> "${bytes / 1_048_576} MB"
}

private val ThemeMode.label: String get() = when (this) {
    ThemeMode.SYSTEM -> "Use system setting"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

private val ReducedMotionMode.label: String get() = when (this) {
    ReducedMotionMode.SYSTEM -> "Use system setting"
    ReducedMotionMode.ON -> "Reduce motion"
    ReducedMotionMode.OFF -> "Allow motion"
}
