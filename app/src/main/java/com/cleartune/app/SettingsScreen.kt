package com.cleartune.app

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleartune.app.settings.SettingsViewModel
import com.cleartune.app.settings.UpdateRelease
import com.cleartune.core.datastore.EqualizerPreset
import com.cleartune.core.datastore.MobileAudioQuality
import com.cleartune.core.datastore.PLAYBACK_CACHE_SIZE_OPTIONS_MB
import com.cleartune.core.datastore.ThemeMode
import com.cleartune.core.model.ServerProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FullSettingsScreen(
    profile: ServerProfile,
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onEqualizer: () -> Unit,
    onLogout: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val update by viewModel.updateState.collectAsStateWithLifecycle()
    val cache by viewModel.cacheState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showThemePicker by remember { mutableStateOf(false) }
    var showQualityPicker by remember { mutableStateOf(false) }
    var showPlaybackCachePicker by remember { mutableStateOf(false) }
    var showUpdateDetails by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refreshCacheSize() }
    val diagnosticTitle = stringResource(R.string.diagnostic_title)
    val diagnosticVersion = stringResource(R.string.diagnostic_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
    val diagnosticDevice = stringResource(R.string.diagnostic_device, Build.MANUFACTURER, Build.MODEL)
    val diagnosticAndroid = stringResource(R.string.diagnostic_android, Build.VERSION.RELEASE, Build.VERSION.SDK_INT)
    val diagnosticServer = stringResource(R.string.diagnostic_server, profile.serverType, profile.serverVersion)
    val diagnosticPrivacy = stringResource(R.string.diagnostic_privacy)
    val diagnosticChooser = stringResource(R.string.diagnostic_chooser)
    val exportDiagnostics = {
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
    }

    Scaffold(topBar = { ClearTuneTopAppBar(title = stringResource(R.string.settings), onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ClearTuneAppMark(Modifier.size(52.dp))
                Column(modifier = Modifier.padding(start = 14.dp)) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.current_version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SettingsGroup(stringResource(R.string.settings_server_account)) {
                SettingsRow(
                    icon = Icons.Rounded.CloudDone,
                    title = stringResource(R.string.server_status_connected, profile.serverType),
                    subtitle = "${profile.username} · ${profile.serverType} ${profile.serverVersion}",
                    iconTint = MaterialTheme.colorScheme.primary,
                )
            }

            SettingsGroup(stringResource(R.string.settings_appearance)) {
                SettingsRow(
                    icon = Icons.Rounded.Palette,
                    title = stringResource(R.string.settings_theme),
                    value = settings.themeMode.label(),
                    onClick = { showThemePicker = true },
                )
            }

            SettingsGroup(stringResource(R.string.settings_play_quality)) {
                SettingsRow(
                    icon = Icons.AutoMirrored.Rounded.VolumeUp,
                    title = stringResource(R.string.volume_normalization),
                    subtitle = stringResource(R.string.volume_normalization_auto_mode),
                    trailing = {
                        Switch(
                            checked = settings.volumeNormalizationEnabled,
                            onCheckedChange = viewModel::setVolumeNormalization,
                        )
                    },
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Rounded.Tune,
                    title = stringResource(R.string.mobile_audio_quality),
                    subtitle = stringResource(R.string.network_quality_explanation),
                    value = settings.mobileAudioQuality.label(),
                    onClick = { showQualityPicker = true },
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Rounded.GraphicEq,
                    title = stringResource(R.string.equalizer),
                    value = if (settings.equalizer.enabled) settings.equalizer.preset.label() else stringResource(R.string.eq_is_off),
                    onClick = onEqualizer,
                )
            }

            SettingsGroup(stringResource(R.string.settings_download_cache)) {
                SettingsRow(
                    icon = Icons.Rounded.Download,
                    title = stringResource(R.string.wifi_only_downloads),
                    trailing = {
                        Switch(checked = settings.wifiOnlyDownloads, onCheckedChange = viewModel::setWifiOnly)
                    },
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Rounded.Storage,
                    title = stringResource(R.string.playback_cache),
                    subtitle = stringResource(
                        R.string.playback_cache_description,
                        formatBytes(cache.playbackSizeBytes),
                    ),
                    value = formatPlaybackCacheSize(settings.playbackCacheSizeMb),
                    onClick = { showPlaybackCachePicker = true },
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Rounded.DeleteSweep,
                    title = stringResource(R.string.clear_cache),
                    subtitle = cache.message,
                    value = if (cache.clearing) stringResource(R.string.clearing_cache) else formatBytes(cache.sizeBytes),
                    onClick = if (cache.clearing) null else viewModel::clearCache,
                )
            }

            SettingsGroup(stringResource(R.string.settings_about_updates)) {
                SettingsRow(
                    icon = Icons.Rounded.SystemUpdate,
                    title = stringResource(R.string.auto_check_updates),
                    trailing = {
                        Switch(checked = settings.checkUpdates, onCheckedChange = viewModel::setCheckUpdates)
                    },
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Rounded.Info,
                    title = stringResource(if (update.checking) R.string.checking_updates else R.string.check_updates),
                    subtitle = update.message,
                    value = BuildConfig.VERSION_NAME,
                    onClick = if (update.checking) null else viewModel::checkUpdate,
                )
                update.release?.takeIf { it.newer }?.let { release ->
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Rounded.Download,
                        title = stringResource(R.string.go_to_download),
                        value = release.version,
                        onClick = { showUpdateDetails = true },
                    )
                }
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Rounded.Security,
                    title = stringResource(R.string.privacy_notice),
                    onClick = { showPrivacy = true },
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Rounded.Code,
                    title = stringResource(R.string.open_source_licenses),
                    onClick = { showLicenses = true },
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Rounded.BugReport,
                    title = stringResource(R.string.diagnostic_export),
                    onClick = exportDiagnostics,
                )
            }

            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.logout)) }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showThemePicker) {
        ChoiceDialog(
            title = stringResource(R.string.settings_theme),
            options = ThemeMode.entries.map { it.label() },
            selectedIndex = ThemeMode.entries.indexOf(settings.themeMode),
            onSelected = { index ->
                viewModel.setTheme(ThemeMode.entries[index])
                showThemePicker = false
            },
            onClose = { showThemePicker = false },
        )
    }
    if (showQualityPicker) {
        ChoiceDialog(
            title = stringResource(R.string.mobile_audio_quality),
            options = MobileAudioQuality.entries.map { it.label() },
            selectedIndex = MobileAudioQuality.entries.indexOf(settings.mobileAudioQuality),
            onSelected = { index ->
                viewModel.setMobileAudioQuality(MobileAudioQuality.entries[index])
                showQualityPicker = false
            },
            onClose = { showQualityPicker = false },
        )
    }
    if (showPlaybackCachePicker) {
        ChoiceDialog(
            title = stringResource(R.string.playback_cache),
            options = PLAYBACK_CACHE_SIZE_OPTIONS_MB.map(::formatPlaybackCacheSize),
            selectedIndex = PLAYBACK_CACHE_SIZE_OPTIONS_MB.indexOf(settings.playbackCacheSizeMb),
            onSelected = { index ->
                viewModel.setPlaybackCacheSizeMb(PLAYBACK_CACHE_SIZE_OPTIONS_MB[index])
                showPlaybackCachePicker = false
            },
            onClose = { showPlaybackCachePicker = false },
        )
    }
    if (showPrivacy) InfoDialog(
        title = stringResource(R.string.privacy_notice),
        body = stringResource(R.string.privacy_body),
        onClose = { showPrivacy = false },
    )
    if (showLicenses) InfoDialog(
        title = stringResource(R.string.open_source_licenses),
        body = stringResource(R.string.licenses_body),
        onClose = { showLicenses = false },
    )
    if (showUpdateDetails) {
        update.release?.takeIf { it.newer }?.let { release ->
            UpdateDetailsDialog(
                release = release,
                ignored = update.ignored,
                onOpenRelease = {
                    showUpdateDetails = false
                    context.startActivity(Intent(Intent.ACTION_VIEW, release.pageUrl.toUri()))
                },
                onIgnore = {
                    viewModel.ignoreUpdate(release)
                    showUpdateDetails = false
                },
                onClose = { showUpdateDetails = false },
            )
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            modifier = Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) { Column { content() } }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val rowModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier = rowModifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(38.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            }
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            subtitle?.let {
                Text(
                    it,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke() ?: Row(verticalAlignment = Alignment.CenterVertically) {
            value?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onClick != null) {
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(modifier = Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEachIndexed { index, label ->
                    FilterChip(
                        selected = index == selectedIndex,
                        onClick = { onSelected(index) },
                        label = { Text(label) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close_action)) } },
    )
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

@Composable
private fun UpdateDetailsDialog(
    release: UpdateRelease,
    ignored: Boolean,
    onOpenRelease: () -> Unit,
    onIgnore: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.update_details_title, release.version)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                release.apkSizeBytes?.let {
                    Text(
                        stringResource(R.string.update_file_size, formatBytes(it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.update_release_notes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    release.notes.ifBlank { stringResource(R.string.update_no_release_notes) },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.update_download_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenRelease) { Text(stringResource(R.string.open_release_page)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onIgnore, enabled = !ignored) {
                    Text(
                        stringResource(
                            if (ignored) R.string.update_version_ignored else R.string.ignore_this_version,
                        ),
                    )
                }
                TextButton(onClick = onClose) { Text(stringResource(R.string.later_action)) }
            }
        },
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatPlaybackCacheSize(sizeMb: Int): String =
    if (sizeMb >= 1_024) "${sizeMb / 1_024} GB" else "$sizeMb MB"

@Composable
private fun ThemeMode.label(): String = stringResource(when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
})

@Composable
private fun MobileAudioQuality.label(): String = maxBitRate?.let { "$it kbps" }
    ?: stringResource(R.string.original_audio_quality)

@Composable
private fun EqualizerPreset.label(): String = stringResource(when (this) {
    EqualizerPreset.BALANCED -> R.string.eq_balanced
    EqualizerPreset.CLEAR_VOCAL -> R.string.eq_clear_vocal
    EqualizerPreset.WARM_BASS -> R.string.eq_warm_bass
    EqualizerPreset.AIRY_TREBLE -> R.string.eq_airy_treble
    EqualizerPreset.NIGHT_SOFT -> R.string.eq_night_soft
    EqualizerPreset.CUSTOM -> R.string.eq_custom
})
