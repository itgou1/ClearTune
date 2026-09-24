package com.cleartune.app.metadata

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.cleartune.core.model.*

data class MusicTagActions(val open: (Song) -> Unit = {}, val settings: () -> Unit = {})
val LocalMusicTagActions = staticCompositionLocalOf { MusicTagActions() }

@Composable
fun MusicTagHost(viewModel: MusicTagViewModel, onSynced: (Song) -> Unit, content: @Composable () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val syncs by viewModel.syncs.collectAsStateWithLifecycle()
    val dismissedSyncs = remember(viewModel) { mutableStateMapOf<String, Pair<Long, Boolean>>() }
    val actions = remember(viewModel) { MusicTagActions(viewModel::open, viewModel::showSettings) }
    val currentOnSynced by rememberUpdatedState(onSynced)
    LaunchedEffect(viewModel) { viewModel.syncCompleted.collect { currentOnSynced(it) } }
    CompositionLocalProvider(LocalMusicTagActions provides actions) {
        Box(Modifier.fillMaxSize()) {
            content()
            if (state.song == null && !state.settingsShown) visibleSyncNotice(syncs.values, dismissedSyncs)?.let { sync ->
                LaunchedEffect(sync.song.id, sync.attempt, sync.running) {
                    delay(8_000)
                    dismissedSyncs[sync.song.id] = sync.attempt to sync.running
                }
                MusicTagSyncNotice(sync, onView = { viewModel.open(sync.song) },
                    onDismiss = { dismissedSyncs[sync.song.id] = sync.attempt to sync.running },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 100.dp))
            }
        }
        if (state.song != null || state.settingsShown) {
            MusicTagDialog(state, viewModel)
        }
    }
}

internal fun visibleSyncNotice(syncs: Collection<MusicTagSyncStatus>, dismissed: Map<String, Pair<Long, Boolean>>): MusicTagSyncStatus? =
    syncs.firstOrNull { dismissed[it.song.id] != (it.attempt to it.running) }

@Composable
internal fun MusicTagSyncNotice(sync: MusicTagSyncStatus, onView: () -> Unit, onDismiss: () -> Unit,
    modifier: Modifier = Modifier) {
    Snackbar(modifier = modifier,
        action = { TextButton(onClick = onView) { Text("查看") } },
        dismissAction = { IconButton(onClick = onDismiss) {
            Icon(Icons.Rounded.Close, contentDescription = "关闭同步提示")
        } }) {
        Text(if (sync.running) "标签已保存，曲库后台同步中（${sync.song.title}）"
            else "标签已保存，曲库同步未完成（${sync.song.title}），可进入重试")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicTagDialog(state: MusicTagUiState, viewModel: MusicTagViewModel) {
    var confirmClose by remember { mutableStateOf(false) }
    var confirmApply by remember { mutableStateOf(false) }
    var confirmReread by remember { mutableStateOf(false) }
    var confirmReload by remember { mutableStateOf(false) }
    val close = {
        if (!state.busy) {
            if (state.settingsShown) viewModel.closeSettings()
            else if (state.dirty && !state.pending) confirmClose = true
            else viewModel.close()
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = !state.busy, dismissOnClickOutside = false)) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize().imePadding(),
            topBar = {
                TopAppBar(title = { Text(if (state.settingsShown) "Music Tag 服务" else "音乐标签") },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    navigationIcon = { IconButton(onClick = close, enabled = !state.busy) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                    } }, actions = {
                        if (!state.settingsShown) IconButton(onClick = viewModel::showSettings, enabled = !state.busy) {
                            Icon(Icons.Rounded.Settings, "Music Tag 服务设置")
                        }
                    })
            },
        ) { padding ->
            if (state.settingsShown) {
                MusicTagSettingsPage(state, viewModel::configure, viewModel::disconnect, Modifier.padding(padding))
            } else {
                MusicTagEditor(state, MusicTagEditorActions(
                    query = viewModel::query, source = viewModel::source, search = viewModel::search,
                    reload = { if (state.dirty) confirmReload = true else viewModel.reload() }, settings = viewModel::showSettings, choose = viewModel::choose,
                    select = viewModel::select, edit = viewModel::edit, apply = { confirmApply = true },
                    verify = viewModel::verifyAndSync, confirmCover = viewModel::confirmCoverAndSync,
                    close = viewModel::close,
                    review = { if (state.coverReview != null) viewModel.rereadAfterReview() else confirmReread = true },
                ), Modifier.padding(padding))
            }
        }
    }
    if (state.candidateValues.isNotEmpty()) MusicTagCandidateDialog(state, viewModel::select, viewModel::fillCandidate, viewModel::dismissCandidate)
    if (confirmReload) AlertDialog(onDismissRequest = { confirmReload = false }, title = { Text("放弃修改并重新读取？") },
        text = { Text("重新读取会替换当前未保存的表单内容。") },
        confirmButton = { TextButton(onClick = { confirmReload = false; viewModel.reload() }) { Text("重新读取") } },
        dismissButton = { TextButton(onClick = { confirmReload = false }) { Text("继续编辑") } })
    if (confirmApply) AlertDialog(onDismissRequest = { confirmApply = false }, title = { Text("应用到这首歌曲？") },
        text = { Text("将通过 Music Tag 保存原文件的${state.changes.keys.joinToString { it.label }}。播放会短暂停顿后恢复。") },
        confirmButton = { TextButton(onClick = { confirmApply = false; viewModel.apply() }) { Text("保存修改") } },
        dismissButton = { TextButton(onClick = { confirmApply = false }) { Text("继续检查") } })
    if (confirmClose) AlertDialog(onDismissRequest = { confirmClose = false }, title = { Text("有未保存的修改") },
        text = { Text("可保留草稿，稍后在本次会话中继续编辑。") },
        confirmButton = { TextButton(onClick = { confirmClose = false; viewModel.keepDraftAndClose() }) { Text("保留草稿并返回") } },
        dismissButton = { Row {
            TextButton(onClick = { confirmClose = false }) { Text("继续编辑") }
            TextButton(onClick = { confirmClose = false; viewModel.close() }) { Text("放弃修改") }
        } })
    if (confirmReread) AlertDialog(onDismissRequest = { confirmReread = false }, title = { Text("重新读取当前文件？") },
        text = { Text("请先在 Music Tag 中确认上次操作已结束。此操作清除本机待确认记录，不撤销文件修改，也不会再次写入。") },
        confirmButton = { TextButton(onClick = { confirmReread = false; viewModel.rereadAfterReview() }) { Text("已核对，重新读取") } },
        dismissButton = { TextButton(onClick = { confirmReread = false }) { Text("取消") } })
}

internal fun artworkModel(value: String): Any = if (value.startsWith("data:image/"))
    runCatching { android.util.Base64.decode(value.substringAfter(','), android.util.Base64.DEFAULT) }.getOrDefault(byteArrayOf()) else value
