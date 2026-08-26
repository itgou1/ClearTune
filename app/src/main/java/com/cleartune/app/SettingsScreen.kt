package com.cleartune.app

import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleartune.app.settings.SettingsViewModel
import com.cleartune.core.datastore.ThemeMode
import com.cleartune.core.model.ServerProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FullSettingsScreen(
    profile: ServerProfile,
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onLogout: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val update by viewModel.updateState.collectAsStateWithLifecycle()
    val cache by viewModel.cacheState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showPrivacy by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    val diagnosticTitle = stringResource(R.string.diagnostic_title)
    val diagnosticVersion = stringResource(
        R.string.diagnostic_version,
        BuildConfig.VERSION_NAME,
        BuildConfig.VERSION_CODE,
    )
    val diagnosticDevice = stringResource(R.string.diagnostic_device, Build.MANUFACTURER, Build.MODEL)
    val diagnosticAndroid = stringResource(R.string.diagnostic_android, Build.VERSION.RELEASE, Build.VERSION.SDK_INT)
    val diagnosticServer = stringResource(
        R.string.diagnostic_server,
        profile.serverType,
        profile.serverVersion,
    )
    val diagnosticPrivacy = stringResource(R.string.diagnostic_privacy)
    val diagnosticChooser = stringResource(R.string.diagnostic_chooser)
    Scaffold(
        topBar = {
            ClearTuneTopAppBar(title = stringResource(R.string.settings), onBack = onBack)
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ClearTuneGradientHeader {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ClearTuneAppMark(Modifier.size(64.dp))
                    Column(modifier = Modifier.padding(start = 16.dp)) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                        Text(
                            stringResource(R.string.current_version, BuildConfig.VERSION_NAME),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            ClearTuneSectionCard(
                title = stringResource(R.string.settings_server_account),
                icon = Icons.Rounded.CloudDone,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(10.dp),
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = androidx.compose.ui.graphics.Color(0xFF2E7D32),
                    ) {}
                    Text(
                        stringResource(R.string.server_status_connected, profile.serverType),
                        modifier = Modifier.padding(start = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(profile.username, style = MaterialTheme.typography.titleMedium)
                Text("${profile.serverType} ${profile.serverVersion}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = onLogout,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.logout)) }
            }

            ClearTuneSectionCard(
                title = stringResource(R.string.settings_appearance),
                icon = Icons.Rounded.Palette,
            ) {
                Text(stringResource(R.string.settings_theme))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { viewModel.setTheme(mode) },
                            label = { Text(mode.label()) },
                        )
                    }
                }
            }

            ClearTuneSectionCard(
                title = stringResource(R.string.settings_play_quality),
                icon = Icons.Rounded.Tune,
            ) {
                SettingSwitch(
                    title = stringResource(R.string.volume_normalization),
                    description = stringResource(R.string.volume_normalization_description),
                    icon = Icons.AutoMirrored.Rounded.VolumeUp,
                    checked = settings.volumeNormalizationEnabled,
                    onCheckedChange = viewModel::setVolumeNormalization,
                )
                Text(
                    stringResource(R.string.volume_normalization_auto_mode),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (settings.volumeNormalizationEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(start = 56.dp),
                )
                Text(stringResource(R.string.mobile_audio_quality))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(128, 192, 320).forEach { rate ->
                        FilterChip(
                            selected = settings.mobileBitRate == rate,
                            onClick = { viewModel.setMobileBitRate(rate) },
                            label = { Text("$rate kbps") },
                        )
                    }
                }
                Text(
                    stringResource(R.string.network_quality_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ClearTuneSectionCard(
                title = stringResource(R.string.settings_download_cache),
                icon = Icons.Rounded.Download,
            ) {
                SettingSwitch(
                    title = stringResource(R.string.wifi_only_downloads),
                    checked = settings.wifiOnlyDownloads,
                    onCheckedChange = viewModel::setWifiOnly,
                )
                Text(stringResource(R.string.cache_usage, formatBytes(cache.sizeBytes)))
                OutlinedButton(onClick = viewModel::clearCache, enabled = !cache.clearing) {
                    Text(stringResource(if (cache.clearing) R.string.clearing_cache else R.string.clear_cache))
                }
                cache.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }

            ClearTuneSectionCard(
                title = stringResource(R.string.settings_about_updates),
                icon = Icons.Rounded.Info,
            ) {
                SettingSwitch(
                    title = stringResource(R.string.auto_check_updates),
                    checked = settings.checkUpdates,
                    onCheckedChange = viewModel::setCheckUpdates,
                )
                Text(stringResource(R.string.current_version, BuildConfig.VERSION_NAME))
                update.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::checkUpdate, enabled = !update.checking) {
                        Text(stringResource(if (update.checking) R.string.checking_updates else R.string.check_updates))
                    }
                    update.release?.takeIf { it.newer }?.let { release ->
                        OutlinedButton(
                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, release.pageUrl.toUri())) },
                        ) { Text(stringResource(R.string.go_to_download)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showPrivacy = true }) { Text(stringResource(R.string.privacy_notice)) }
                    TextButton(onClick = { showLicenses = true }) { Text(stringResource(R.string.open_source_licenses)) }
                }
                OutlinedButton(
                    onClick = {
                        val diagnostic = buildString {
                            appendLine(diagnosticTitle)
                            appendLine(diagnosticVersion)
                            appendLine(diagnosticAndroid)
                            appendLine(diagnosticDevice)
                            appendLine(diagnosticServer)
                            appendLine(diagnosticPrivacy)
                        }
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, diagnosticTitle)
                                    putExtra(Intent.EXTRA_TEXT, diagnostic)
                                },
                                diagnosticChooser,
                            ),
                        )
                    },
                ) { Text(stringResource(R.string.diagnostic_export)) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (showPrivacy) {
        InfoDialog(
            title = stringResource(R.string.privacy_notice),
            body = stringResource(R.string.privacy_body),
            onClose = { showPrivacy = false },
        )
    }
    if (showLicenses) {
        InfoDialog(
            title = stringResource(R.string.open_source_licenses),
            body = stringResource(R.string.licenses_body),
            onClose = { showLicenses = false },
        )
    }
}

@Composable
private fun InfoDialog(title: String, body: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close_action)) } },
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun SettingTitle(value: String) {
    Text(value, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
private fun SettingSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = if (icon == null) 0.dp else 16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ThemeMode.label(): String = stringResource(when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
})
