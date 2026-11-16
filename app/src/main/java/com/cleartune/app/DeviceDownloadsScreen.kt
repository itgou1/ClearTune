package com.cleartune.app

import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import com.cleartune.core.player.PlaybackStatus
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cleartune.app.download.DeviceDownload
import com.cleartune.app.download.DeviceDownloads
import com.cleartune.core.player.PlayerConnection
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun DeviceDownloadRow(
    item: DeviceDownload, busy: Boolean, playing: Boolean,
    onPlay: () -> Unit, onShare: () -> Unit, onOpen: () -> Unit,
    onExport: () -> Unit, onLocate: () -> Unit, onDetails: () -> Unit, onDelete: () -> Unit,
) {
    var menu by remember(item.uri) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${item.song.artistName.takeIf { it.isNotBlank() } ?: stringResource(R.string.unknown_artist)} · ${storageBytes(item.bytes)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val status = when {
                !item.complete -> R.string.device_partial_short
                !item.available -> R.string.device_unavailable_short
                item.needsUpdate -> R.string.device_update_short
                item.legacy -> R.string.device_legacy_title
                else -> null
            }
            status?.let { Text(stringResource(it), style = MaterialTheme.typography.labelSmall,
                color = if (!item.available) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (item.complete && item.available) IconButton(enabled = !busy, onClick = onPlay) {
            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                stringResource(if (playing) R.string.pause_action else R.string.play_action), tint = MaterialTheme.colorScheme.primary)
        }
        Box {
            IconButton(enabled = !busy, onClick = { menu = true }) {
                Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_actions))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (!item.legacy && item.complete && item.available) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.device_share)) }, enabled = !busy,
                        onClick = { menu = false; onShare() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.device_open)) }, enabled = !busy,
                        onClick = { menu = false; onOpen() })
                }
                if (item.legacy && item.complete && item.available) DropdownMenuItem(
                    text = { Text(stringResource(R.string.device_export)) }, enabled = !busy,
                    onClick = { menu = false; onExport() })
                if (!item.legacy && !item.available) DropdownMenuItem(
                    text = { Text(stringResource(R.string.device_relocate)) }, enabled = !busy,
                    onClick = { menu = false; onLocate() })
                DropdownMenuItem(text = { Text(stringResource(R.string.device_file_info)) },
                    onClick = { menu = false; onDetails() })
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.delete_action), color = MaterialTheme.colorScheme.error) },
                    enabled = !busy, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceDownloadsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val store = remember { DeviceDownloads(context) }
    val player = remember { PlayerConnection(context) }
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<DeviceDownload>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<DeviceDownload?>(null) }
    var details by remember { mutableStateOf<DeviceDownload?>(null) }
    var showLegacy by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); message = null } }
    var relocating by remember { mutableStateOf<DeviceDownload?>(null) }
    val playback by player.state.collectAsState()
    DisposableEffect(player) { onDispose { player.release() } }
    BackHandler(enabled = busy) {}
    fun action(block: suspend () -> Unit) {
        if (busy) return
        scope.launch { busy = true; try { block(); items = store.list() }
            catch (error: Exception) { message = error.message } finally { busy = false } }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (true) { if (!busy) runCatching { items = store.list() }.onFailure { message = it.message }; delay(5000) }
    } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        message = resources.getString(if (it) R.string.device_permission_granted else R.string.device_permission_denied)
    }
    val locate = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val item = relocating
        if (uri != null && item != null) action {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            store.relocate(item, uri)
        }
        relocating = null
    }
    fun external(item: DeviceDownload, share: Boolean) {
        runCatching {
            val original = Uri.parse(item.uri)
            val uri = if (original.scheme == "file") androidx.core.content.FileProvider.getUriForFile(context,
                "${context.packageName}.sharefileprovider", java.io.File(original.path!!)) else original
            val intent = if (share) Intent(Intent.ACTION_SEND).setType("audio/*").putExtra(Intent.EXTRA_STREAM, uri)
                else Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/*")
            context.startActivity(Intent.createChooser(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), item.name))
        }.onFailure { message = it.message }
    }
    val legacy = items.filter { it.legacy }
    Scaffold(
        topBar = { ClearTuneTopAppBar(stringResource(R.string.device_downloads), onBack = { if (!busy) onBack() }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(stringResource(R.string.device_download_summary, items.size, storageBytes(items.sumOf { it.bytes })),
                    modifier = Modifier.padding(bottom = 16.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (android.os.Build.VERSION.SDK_INT <= 28 &&
                    androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED) TextButton(enabled = !busy, onClick = {
                    permission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }) { Text(stringResource(R.string.device_allow_storage)) }
                if (legacy.isNotEmpty()) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.device_legacy_title), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(R.string.device_download_summary, legacy.size, storageBytes(legacy.sumOf { it.bytes })),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(enabled = !busy, onClick = { showLegacy = true }) { Text(stringResource(R.string.device_organize)) }
                        }
                    }
                }
                if (items.isEmpty() && !busy) Text(stringResource(R.string.no_downloads), modifier = Modifier.padding(vertical = 32.dp))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            items(items, key = { "${it.account}:${it.id}:${it.legacy}:${it.uri}" }) { item ->
                val isCurrent = playback.currentSong?.id == "device:${item.account}:${item.id}"
                DeviceDownloadRow(item, busy, isCurrent && playback.status == PlaybackStatus.PLAYING,
                    onPlay = {
                        if (isCurrent) player.togglePlayPause() else action {
                            check(store.readable(item.uri, item.bytes)) { resources.getString(R.string.device_unavailable) }
                            val song = item.song.copy(id = "device:${item.account}:${item.id}")
                            player.setQueue(listOf(song), 0, mapOf(song.id to item.uri))
                        }
                    },
                    onShare = { external(item, true) }, onOpen = { external(item, false) },
                    onExport = { action { store.migrate(item) } },
                    onLocate = { relocating = item; locate.launch(arrayOf("audio/*")) },
                    onDetails = { details = item }, onDelete = { pending = item })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
    if (showLegacy) AlertDialog(onDismissRequest = { showLegacy = false },
        title = { Text(stringResource(R.string.device_legacy_title)) },
        text = { Text(stringResource(R.string.device_legacy_help)) },
        confirmButton = { TextButton(enabled = !busy && legacy.any { it.complete && it.available }, onClick = {
            showLegacy = false
            val complete = legacy.filter { it.complete && it.available }
            action {
                var failed = 0
                for (item in complete) try { store.migrate(item) } catch (_: Exception) { failed++ }
                message = resources.getString(R.string.device_migration_result, complete.size - failed, failed)
            }
        }) { Text(stringResource(R.string.device_migrate_all, legacy.count { it.complete && it.available })) } },
        dismissButton = { TextButton(onClick = { showLegacy = false }) { Text(stringResource(R.string.cancel_action)) } })
    details?.let { item -> AlertDialog(onDismissRequest = { details = null },
        title = { Text(stringResource(R.string.device_file_info)) },
        text = { SelectionContainer {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(item.name)
                Text(storageBytes(item.bytes))
                Text(item.uri)
                if (item.legacy) Text(stringResource(R.string.device_legacy_account, item.account.take(8)))
                if (!item.complete) Text(stringResource(R.string.device_partial))
                if (!item.available) Text(stringResource(R.string.device_unavailable))
                if (item.needsUpdate) Text(stringResource(R.string.device_needs_update))
            }
        } },
        confirmButton = { TextButton(onClick = { details = null }) { Text(stringResource(R.string.device_close)) } }) }
    pending?.let { item -> AlertDialog(onDismissRequest = { pending = null },
        title = { Text(stringResource(R.string.device_delete_title)) }, text = { Text(stringResource(R.string.device_delete_message)) },
        confirmButton = { TextButton(onClick = { pending = null; action { store.remove(item, true) } }) { Text(stringResource(R.string.device_delete_file)) } },
        dismissButton = { Column {
            if (!item.legacy) TextButton(onClick = { pending = null; action { store.remove(item, false) } }) { Text(stringResource(R.string.device_remove_record)) }
            TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel_action)) }
        } }) }
}
