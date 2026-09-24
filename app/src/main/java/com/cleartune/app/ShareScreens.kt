package com.cleartune.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleartune.app.library.MusicViewModel
import com.cleartune.app.share.MusicShare
import com.cleartune.app.share.ShareKind
import com.cleartune.app.share.ShareTarget
import com.cleartune.app.share.ShareViewModel
import com.cleartune.app.share.shareMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val shareDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private fun formatShareDate(time: Long?): String = time?.let {
    shareDateFormatter.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
} ?: "由服务器决定"

private fun ShareKind.label(): String = when (this) {
    ShareKind.SONG -> "歌曲"
    ShareKind.ALBUM -> "专辑"
    ShareKind.PLAYLIST -> "歌单"
    ShareKind.UNKNOWN -> "音乐"
}

private fun shareAccessHint(url: String): String? {
    val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return null
    val local = host == "localhost" || host == "127.0.0.1" || host == "::1" ||
        host.startsWith("192.168.") || host.startsWith("10.") || host.endsWith(".local") ||
        Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(host)
    return if (local) "对方需要能访问你的音乐服务器" else null
}

private fun copyShareLink(context: Context, url: String) {
    if (url.isBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("音乐分享链接", url))
}

internal fun copyShareMessage(context: Context, share: MusicShare) {
    val message = shareMessage(share).takeIf(String::isNotBlank) ?: return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("音乐分享内容", message))
}

@Composable
internal fun ShareContentPreview(share: MusicShare) {
    val context = LocalContext.current
    var linkCopied by remember(share.url) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
    ) {
        Column(Modifier.padding(15.dp)) {
            Text("分享内容", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Text(shareMessage(share).ifBlank { "服务器未返回可用链接" },
                    style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = { copyShareLink(context, share.url); linkCopied = true },
                enabled = share.url.isNotBlank(), modifier = Modifier.align(Alignment.End)) {
                Text(if (linkCopied) "链接已复制" else "仅复制链接")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareCreateSheet(
    viewModel: ShareViewModel,
    musicViewModel: MusicViewModel,
    onManage: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val target = state.target ?: return
    val songCount = state.created?.songCount ?: target.songCount
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var description by remember(target.id, target.kind) { mutableStateOf("") }
    var days by remember(target.id, target.kind) { mutableIntStateOf(7) }
    var copied by remember(state.created?.id) { mutableStateOf(false) }
    var sharing by remember(state.created?.id) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = viewModel::close) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .navigationBarsPadding().padding(horizontal = 22.dp, vertical = 8.dp),
        ) {
            Text("分享${target.kind.label()}", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(18.dp))
            ShareSummary(target.title, "${target.kind.label()} · ${if (target.kind == ShareKind.PLAYLIST || songCount > 1) "$songCount 首歌曲" else target.subtitle}", target.coverArtId, musicViewModel)
            if (state.created == null) {
                Spacer(Modifier.height(24.dp))
                Text("分享说明（可选）", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(200) },
                    placeholder = { Text("写一句推荐语（可选）") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
                    maxLines = 3,
                )
                Spacer(Modifier.height(18.dp))
                Text("有效期", style = MaterialTheme.typography.titleSmall)
                SharePeriodPicker(days, { days = it })
                if (target.kind == ShareKind.PLAYLIST) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    ) {
                        Text(
                            "以生成链接时读取到的歌曲为准，后续修改不会同步。",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Text(
                    "拥有链接的人可在有效期内访问，无需登录。",
                    modifier = Modifier.padding(vertical = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { viewModel.create(description, days) },
                    enabled = !state.busy && !(target.kind == ShareKind.PLAYLIST && target.songCount == 0),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text("生成分享链接")
                }
                if (target.kind == ShareKind.PLAYLIST && target.songCount == 0) {
                    Text("歌单还没有歌曲，添加后再分享", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                val share = state.created!!
                Spacer(Modifier.height(26.dp))
                Icon(Icons.Rounded.CheckCircle, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp).align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(8.dp))
                Text("链接已生成", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally))
                Text("有效期至 ${formatShareDate(share.expiresAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))
                ShareContentPreview(share)
                shareAccessHint(share.url)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 10.dp)) }
                Button(
                    onClick = {
                        sharing = true
                        scope.launch {
                            try { sendShareLink(context, share, musicViewModel) }
                            finally { sharing = false }
                        }
                    },
                    enabled = share.url.isNotBlank() && !sharing,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    if (sharing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Share, contentDescription = null)
                    Text(if (sharing) "准备分享…" else "分享${share.kind.label()}", Modifier.padding(start = 8.dp))
                }
                Text("包含作品信息、分享说明和收听链接",
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(
                    onClick = { copyShareMessage(context, share); copied = true },
                    enabled = share.url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp),
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                    Text(if (copied) "分享内容已复制" else "复制分享内容", Modifier.padding(start = 8.dp))
                }
                TextButton(onClick = { onManage(share.id) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("管理此分享")
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ShareSummary(title: String, subtitle: String, coverArtId: String?, musicViewModel: MusicViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CoverArt(coverArtId, title, musicViewModel, Modifier.size(64.dp), requestSize = 128)
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SharePeriodPicker(days: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1, 7, 30).forEach { value ->
            FilterChip(
                selected = days == value,
                onClick = { onChange(value) },
                label = { Text("$value 天") },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun MySharesScreen(viewModel: ShareViewModel, musicViewModel: MusicViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var revoke by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val selected = state.shares.firstOrNull { it.id == state.detailId }
    var copied by remember(selected?.id, selected?.description, selected?.expiresAt) { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refresh() }
    BackHandler(enabled = selected != null) { viewModel.select(null) }
    Scaffold(topBar = {
        ClearTuneTopAppBar(title = if (selected == null) "我的分享" else "分享详情",
            onBack = { if (selected == null) onBack() else viewModel.select(null) })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::refresh) { Text("重试") }
            }
            if (selected == null) {
                Text("当前账号创建的链接", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.loading && state.shares.isEmpty()) {
                    CircularProgressIndicator(Modifier.padding(24.dp))
                } else if (state.shares.isEmpty() && state.error == null) {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 120.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(20.dp))
                        Text("还没有分享", style = MaterialTheme.typography.titleMedium)
                        Text("从歌曲、专辑或歌单创建第一条链接",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                state.shares.forEach { share ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
                            .clickable { viewModel.select(share.id) },
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            ShareSummary(share.title, if (share.songCount > 0) "${share.kind.label()} · ${share.songCount} 首" else "音乐分享", share.coverArtId, musicViewModel)
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(if (share.expired) "已过期" else "生效中", color = MaterialTheme.colorScheme.primary)
                                Text("访问 ${share.visitCount} 次", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("${formatShareDate(share.expiresAt)} 到期", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                ShareSummary(selected.title, if (selected.songCount > 0) "${selected.kind.label()} · ${selected.songCount} 首" else "音乐分享", selected.coverArtId, musicViewModel)
                Spacer(Modifier.height(16.dp))
                Text(if (selected.expired) "已过期" else "生效中", color = MaterialTheme.colorScheme.primary)
                ShareContentPreview(selected)
                shareAccessHint(selected.url)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 10.dp)) }
                ShareDetailRow("有效期至", formatShareDate(selected.expiresAt))
                ShareDetailRow("创建时间", formatShareDate(selected.createdAt))
                ShareDetailRow("访问次数", "${selected.visitCount} 次")
                ShareDetailRow("分享说明", selected.description.ifBlank { "未填写" })
                if (selected.kind == ShareKind.PLAYLIST) {
                    Text("已分享创建时的 ${selected.songCount} 首歌曲。",
                        Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!selected.expired) {
                    Button(onClick = {
                        sharing = true
                        scope.launch {
                            try { sendShareLink(context, selected, musicViewModel) }
                            finally { sharing = false }
                        }
                    }, enabled = selected.url.isNotBlank() && !sharing,
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp).height(52.dp)) {
                        if (sharing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Share, contentDescription = null)
                        Text(if (sharing) "准备分享…" else "分享${selected.kind.label()}", Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(onClick = { copyShareMessage(context, selected); copied = true }, enabled = selected.url.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp)) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                        Text(if (copied) "分享内容已复制" else "复制分享内容", Modifier.padding(start = 8.dp))
                    }
                } else {
                    Text("链接已到期，修改有效期后可再次分享。",
                        Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Icon(Icons.Rounded.Edit, contentDescription = null)
                    Text(if (selected.expired) "编辑有效期" else "编辑分享", Modifier.padding(start = 8.dp))
                }
                TextButton(onClick = { revoke = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text("取消分享", Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (selected != null && editing) {
        ShareEditSheet(selected, state.busy, { editing = false }) { description, days ->
            viewModel.update(selected.id, description, days)
            editing = false
        }
    }
    if (selected != null && revoke) {
        AlertDialog(
            onDismissRequest = { revoke = false },
            title = { Text("取消这条分享？") },
            text = { Text("取消后，这个链接将无法访问。已发送的消息不会被删除。") },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(selected.id); revoke = false }, enabled = !state.busy) {
                    Text("取消分享", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { revoke = false }) { Text("保留链接") } },
        )
    }
}

@Composable
private fun ShareDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.padding(start = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareEditSheet(share: MusicShare, busy: Boolean, onDismiss: () -> Unit, onSave: (String, Int) -> Unit) {
    var description by remember(share.id) { mutableStateOf(share.description) }
    var days by remember(share.id) { mutableIntStateOf(7) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .navigationBarsPadding().padding(horizontal = 22.dp, vertical = 12.dp),
        ) {
            Text("编辑分享", style = MaterialTheme.typography.titleLarge)
            Text(share.title, modifier = Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleMedium)
            Text("分享说明（可选）", modifier = Modifier.padding(top = 20.dp))
            OutlinedTextField(value = description, onValueChange = { description = it.take(200) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), maxLines = 3)
            Text("新的有效期", modifier = Modifier.padding(top = 18.dp))
            SharePeriodPicker(days, { days = it })
            Text("从保存时开始计算，分享链接保持不变。",
                modifier = Modifier.padding(vertical = 14.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { onSave(description, days) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("保存修改") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
