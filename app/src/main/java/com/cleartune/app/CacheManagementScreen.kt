package com.cleartune.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import com.cleartune.core.datastore.AppPreferences
import com.cleartune.core.datastore.PLAYBACK_CACHE_SIZE_OPTIONS_MB
import com.cleartune.core.player.PlaybackCacheStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

internal fun storageBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.2f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

internal fun parseCacheCapacity(text: String, gb: Boolean): Int? = runCatching {
    val value = text.trim().toBigDecimal().multiply(if (gb) 1024.toBigDecimal() else 1.toBigDecimal()).intValueExact()
    value.takeIf { it in 512..32768 }
}.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CacheManagementScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val preferences = remember { AppPreferences(context) }
    val settings by preferences.settings.collectAsState(initial = null)
    var draft by rememberSaveable { mutableStateOf<Int?>(null) }
    var playback by remember { mutableStateOf<Long?>(null) }
    var temporary by remember { mutableStateOf<Long?>(null) }
    var free by remember { mutableStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var capacityOpen by rememberSaveable { mutableStateOf(false) }
    var custom by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var gb by rememberSaveable { mutableStateOf(true) }
    var downloads by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            message = null
        }
    }
    suspend fun refresh() {
        val sizes = withContext(Dispatchers.IO) {
            Triple(PlaybackCacheStore.size(context), com.cleartune.app.artwork.ArtworkCache.temporarySize(context),
                android.os.StatFs(context.filesDir.path).availableBytes)
        }
        playback = sizes.first; temporary = sizes.second; free = sizes.third
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (true) { if (!busy) runCatching { refresh() }.onFailure { message = it.message }; delay(5000) }
    } }
    LaunchedEffect(settings) { if (draft == null) draft = settings?.playbackCacheSizeMb }
    if (downloads) { DeviceDownloadsScreen { downloads = false }; return }
    val changed = draft != null && draft != settings?.playbackCacheSizeMb
    val back = { if (!busy) onBack() }
    val closeCapacity = { if (!busy) { if (changed) confirmation = "leave" else capacityOpen = false } }
    BackHandler { back() }
    fun save(confirmed: Boolean = false) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                refresh()
                val value = draft ?: return@launch
                if (!confirmed && value * 1_048_576L < (playback ?: 0)) { confirmation = "shrink"; return@launch }
                preferences.setPlaybackCacheSizeMb(value)
                withContext(Dispatchers.IO) { PlaybackCacheStore.resize(context, value) }
                refresh(); message = resources.getString(R.string.cache_saved)
                capacityOpen = false
            } catch (error: Exception) { message = error.message } finally { busy = false }
        }
    }
    Scaffold(
        topBar = { ClearTuneTopAppBar(stringResource(R.string.cache_management), onBack = back) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            CacheSection {
                Text(stringResource(R.string.cache_audio_title), style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.cache_storage_usage, playback?.let(::storageBytes) ?: "…",
                        storageBytes((settings?.playbackCacheSizeMb ?: 512) * 1_048_576L)),
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    val clearPlaybackLabel = stringResource(R.string.cache_audio_title)
                    OutlinedButton(onClick = { confirmation = "playback" },
                        enabled = !busy && (playback ?: 0) > 0,
                        modifier = Modifier.semantics { contentDescription = clearPlaybackLabel }) {
                        Text(stringResource(R.string.cache_cleanup_action))
                    }
                }
                LinearProgressIndicator(progress = {
                    ((playback ?: 0).toFloat() / ((settings?.playbackCacheSizeMb ?: 512) * 1_048_576L)).coerceIn(0f, 1f)
                }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.cache_audio_description), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(enabled = !busy && settings != null, onClick = {
                    draft = settings?.playbackCacheSizeMb
                    capacityOpen = true
                }) { Text(stringResource(R.string.cache_adjust_limit)) }

            }
            CacheSection {
                CacheCleanupItem(stringResource(R.string.cache_temporary_title), temporary,
                    stringResource(R.string.cache_temporary_description), !busy) { confirmation = "temporary" }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(onClick = { downloads = true }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Rounded.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.device_downloads), modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium)
                            Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                        }
                        Text(stringResource(R.string.cache_download_protection_short), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)

                    }
                }
            }
            Text(stringResource(R.string.device_free_space, storageBytes(free)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (capacityOpen) AlertDialog(
        onDismissRequest = closeCapacity,
        title = { Text(stringResource(R.string.cache_adjust_limit)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(storageBytes((draft ?: 512) * 1_048_576L), style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary)
                if (draft !in PLAYBACK_CACHE_SIZE_OPTIONS_MB && draft != null) {
                    Text(stringResource(R.string.cache_custom_value), style = MaterialTheme.typography.bodySmall)
                }
                val sliderLabel = stringResource(R.string.cache_adjust_limit)
                Slider(modifier = Modifier.semantics {
                    contentDescription = sliderLabel
                    stateDescription = storageBytes((draft ?: 512) * 1_048_576L)
                }, value = PLAYBACK_CACHE_SIZE_OPTIONS_MB.indexOf(draft).takeIf { it >= 0 }?.toFloat() ?: 0f,
                    onValueChange = { draft = PLAYBACK_CACHE_SIZE_OPTIONS_MB[it.roundToInt()] },
                    valueRange = 0f..6f, steps = 5, enabled = !busy && draft != null)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("512 MB", style = MaterialTheme.typography.labelMedium)
                    Text("4 GB", style = MaterialTheme.typography.labelMedium)
                    Text("32 GB", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(modifier = Modifier.align(Alignment.End), enabled = !busy && draft != null,
                    onClick = { input = draft.toString(); gb = false; custom = true }) {
                    Text(stringResource(R.string.cache_custom))
                }
                Text(stringResource(R.string.cache_policy), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (changed) Text(stringResource(R.string.cache_unsaved), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = changed && !busy, onClick = { save() }) {
            Text(stringResource(R.string.cache_save))
        } },
        dismissButton = { TextButton(enabled = !busy, onClick = closeCapacity) {
            Text(stringResource(R.string.cancel_action))
        } },
    )
    if (custom) AlertDialog(onDismissRequest = { custom = false }, title = { Text(stringResource(R.string.cache_custom)) },
        text = { Column {
            OutlinedTextField(input, { input = it }, label = { Text(if (gb) "GB" else "MB") },
                isError = parseCacheCapacity(input, gb) == null,
                supportingText = { Text("512 MB – 32 GB") })
            Row { TextButton(onClick = { gb = false }) { Text("MB") }; TextButton(onClick = { gb = true }) { Text("GB") } }
        } }, confirmButton = { TextButton(enabled = parseCacheCapacity(input, gb) != null, onClick = {
            draft = parseCacheCapacity(input, gb); custom = false
        }) { Text(stringResource(R.string.cache_select)) } }, dismissButton = { TextButton(onClick = { custom = false }) { Text(stringResource(R.string.cancel_action)) } })
    confirmation?.let { kind ->
        AlertDialog(onDismissRequest = { confirmation = null },
            title = { Text(stringResource(if (kind == "leave") R.string.cache_unsaved else R.string.cache_confirm)) },
            text = { Text(stringResource(when (kind) { "leave" -> R.string.cache_capacity_leave_message; "shrink" -> R.string.cache_shrink_message; "playback" -> R.string.cache_audio_warning; else -> R.string.cache_temporary_warning })) },
            confirmButton = { TextButton(onClick = {
                confirmation = null
                when (kind) {
                    "leave" -> save()
                    "shrink" -> save(true)
                    else -> scope.launch {
                        busy = true
                        try {
                            message = withContext(Dispatchers.IO) {
                                if (kind == "playback") {
                                    val result = PlaybackCacheStore.clear(context)
                                    resources.getString(R.string.cache_clear_result, storageBytes(result.bytes), result.skipped, result.failed)
                                } else {
                                    val before = com.cleartune.app.artwork.ArtworkCache.temporarySize(context)
                                    val ok = com.cleartune.app.artwork.ArtworkCache.clearTemporary(context)
                                    val after = com.cleartune.app.artwork.ArtworkCache.temporarySize(context)
                                    resources.getString(R.string.cache_clear_result, storageBytes((before - after).coerceAtLeast(0)), 0, if (ok) 0 else 1)
                                }
                            }; refresh()
                        } catch (error: Exception) { message = error.message } finally { busy = false }
                    }
                }
            }) { Text(stringResource(if (kind == "leave") R.string.cache_save else R.string.cache_confirm)) } },
            dismissButton = { Row {
                if (kind == "leave") TextButton(onClick = { confirmation = null; draft = settings?.playbackCacheSizeMb; capacityOpen = false }) { Text(stringResource(R.string.cache_discard)) }
                TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.cancel_action)) }
            } })
    }
}

@Composable
private fun CacheSection(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun CacheCleanupItem(title: String, bytes: Long?, description: String, enabled: Boolean, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(bytes?.let(::storageBytes) ?: "…", modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onClear, enabled = enabled && (bytes ?: 0) > 0,
                modifier = Modifier.semantics { contentDescription = title }) {
                Text(stringResource(R.string.cache_cleanup_action))
            }
        }
    }
}
