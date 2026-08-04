package com.cleartune.feature.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.cleartune.core.contracts.DownloadRepository
import com.cleartune.core.contracts.SettingsRepository
import com.cleartune.core.contracts.SourceRepository
import com.cleartune.core.designsystem.theme.ClearTuneDimensions
import com.cleartune.core.model.ReducedMotionMode
import com.cleartune.core.model.SettingsCommand
import com.cleartune.core.model.ThemeMode
import kotlinx.coroutines.launch

data class SettingsFeatureDependencies(
    val settingsRepository: SettingsRepository,
    val sourceRepository: SourceRepository,
    val downloadRepository: DownloadRepository,
)

object SettingsFeatureEntry {
    const val route = "settings"

    @Composable
    fun Content(
        dependencies: SettingsFeatureDependencies,
        onNavigate: (String) -> Unit,
    ) {
        val settings by dependencies.settingsRepository.settings.collectAsState(
            initial = com.cleartune.core.model.AppSettings(),
        )
        val sources by dependencies.sourceRepository.observeSources().collectAsState(initial = emptyList())
        val downloads by dependencies.downloadRepository.observeDownloads().collectAsState(initial = emptyList())
        val scope = rememberCoroutineScope()
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = ClearTuneDimensions.spacingMd),
            verticalArrangement = Arrangement.spacedBy(ClearTuneDimensions.spacingSm),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onNavigate("back") }) { Text("返回") }
                    Text("设置", style = MaterialTheme.typography.headlineMedium)
                }
            }
            item { SectionTitle("外观") }
            item {
                SettingsCard("主题") {
                    ThemeMode.entries.forEach { mode ->
                        ChoiceRow(
                            label = mode.label,
                            selected = settings.themeMode == mode,
                            onClick = { scope.launch { dependencies.settingsRepository.update(SettingsCommand.SetTheme(mode)) } },
                        )
                    }
                }
            }
            item { SectionTitle("动效") }
            item {
                SettingsCard("减少动态效果") {
                    ReducedMotionMode.entries.forEach { mode ->
                        ChoiceRow(
                            label = mode.label,
                            selected = settings.reducedMotionMode == mode,
                            onClick = {
                                scope.launch {
                                    dependencies.settingsRepository.update(SettingsCommand.SetReducedMotion(mode))
                                }
                            },
                        )
                    }
                }
            }
            item { SectionTitle("音乐与存储") }
            item {
                SettingsCard("数据概览") {
                    NavigationRow("音乐来源", "${sources.size} 个", onClick = { onNavigate("sources") })
                    HorizontalDivider()
                    NavigationRow("下载管理", "${downloads.size} 项", onClick = { onNavigate("downloads") })
                }
            }
            item { SectionTitle("关于") }
            item {
                SettingsCard("ClearTune") {
                    Text("本地与 WebDAV 纯音乐播放器", modifier = Modifier.padding(ClearTuneDimensions.spacingMd))
                    Text("版本 1.0.0", modifier = Modifier.padding(horizontal = ClearTuneDimensions.spacingMd))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(ClearTuneDimensions.spacingMd))
            content()
        }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = ClearTuneDimensions.spacingSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun NavigationRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(ClearTuneDimensions.spacingMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("  ›")
    }
}

private val ThemeMode.label: String
    get() = when (this) {
        ThemeMode.SYSTEM -> "跟随系统"
        ThemeMode.LIGHT -> "浅色"
        ThemeMode.DARK -> "深色"
    }

private val ReducedMotionMode.label: String
    get() = when (this) {
        ReducedMotionMode.SYSTEM -> "跟随系统"
        ReducedMotionMode.ON -> "开启"
        ReducedMotionMode.OFF -> "关闭"
    }
