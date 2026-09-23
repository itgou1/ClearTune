package com.cleartune.app.metadata

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@Composable
internal fun MusicTagSaveBar(state: MusicTagUiState, actions: MusicTagEditorActions, onReviewCover: () -> Unit) {
    var details by remember(state.song?.id) { mutableStateOf(false) }
    val saved = state.fileSaved && !state.pending && !state.dirty
    val showButton = !state.settings.configured || state.busy || state.pending || state.fileSaved || state.dirty
    val status = when {
        state.busy -> state.progress
        state.syncing -> "已保存，曲库后台同步中"
        state.isError && state.pending -> if (state.fileSaved) "已保存，曲库同步未完成" else "保存结果待确认，请检查结果"
        state.isError -> state.message.orEmpty()
        state.coverReview != null -> if (state.coverReview.verifiedFields.isEmpty()) "封面已读回，请核对" else "文字已保存，请核对封面"
        state.pending -> if (state.fileSaved) "已保存，曲库待同步" else "上次保存结果待确认"
        saved -> "已保存，曲库已同步"
        !state.settings.configured -> "请先连接 Music Tag 服务"
        state.snapshot == null -> "请先读取原标签，再保存更改"
        state.dirty -> "已修改 ${state.changes.size} 项，尚未保存"
        else -> "当前内容与原文件一致"
    }
    val buttonText = when {
        state.busy -> "处理中…"
        !state.settings.configured -> "配置 Music Tag 服务"
        state.syncing || saved -> "完成"
        state.coverReview != null -> "核对封面"
        state.pending -> if (state.fileSaved) "重试同步" else "检查保存结果"
        else -> "保存修改"
    }
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = if (showButton) 12.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.busy || state.syncing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(when {
                    state.isError -> Icons.Rounded.ErrorOutline
                    saved || state.fileSaved -> Icons.Rounded.CheckCircle
                    state.pending -> Icons.Rounded.Info
                    else -> Icons.Rounded.Edit
                }, null, Modifier.size(18.dp), tint = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Text(status, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (!state.busy && (state.isError || state.pending || state.dirty))
                    TextButton(onClick = { details = true }) { Text("详情") }
            }
            if (showButton) Button(onClick = when {
                    !state.settings.configured -> actions.settings
                    state.syncing || saved -> actions.close
                    state.coverReview != null -> onReviewCover
                    state.pending -> actions.verify
                    else -> actions.apply
                }, enabled = !state.busy && (!state.settings.configured || state.pending || saved || (state.snapshot != null && state.dirty)),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(buttonText) }
            if (state.pending && !state.fileSaved && state.coverReview == null) {
                TextButton(onClick = actions.review, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("已在服务中核对，重新读取")
                }
            }
        }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("标签状态") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(status)
            state.message?.takeIf { it != status }?.let { Text(it) }
            if (state.dirty && !state.pending) Text("变更字段：${state.changes.keys.joinToString { it.label }}")
        } }, confirmButton = { TextButton(onClick = { details = false }) { Text("关闭") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MusicTagCoverReviewDialog(state: MusicTagUiState, actions: MusicTagEditorActions, onDismiss: () -> Unit) {
    val review = state.coverReview ?: return
    var actualLoaded by remember(review) { mutableStateOf(false) }
    var expectedLoaded by remember(review) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { if (!state.busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("核对文件封面", style = MaterialTheme.typography.titleLarge)
                Text(if (review.versionLimited) "服务仅提供缩略图，请核对封面内容是否与所选图片一致。"
                    else "读回图片与所选原图未完全一致，请核对是否符合预期。", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("所选封面", style = MaterialTheme.typography.labelLarge)
                        AsyncImage(model = artworkModel(review.expectedUrl), contentDescription = "所选封面图片",
                            onSuccess = { expectedLoaded = true }, onError = { expectedLoaded = false },
                            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("文件读回封面", style = MaterialTheme.typography.labelLarge)
                        AsyncImage(model = artworkModel(review.actual), contentDescription = "文件读回封面图片",
                            onSuccess = { actualLoaded = true }, onError = { actualLoaded = false },
                            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)))
                    }
                }
                if (review.verifiedFields.isNotEmpty()) {
                    Text("文字标签已验证", style = MaterialTheme.typography.titleSmall)
                    review.verifiedFields.forEach { (field, value) ->
                        Text("${field.label}：$value", style = MaterialTheme.typography.bodySmall,
                            maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text("确认后在后台同步曲库，可直接返回，不会重复写入。", style = MaterialTheme.typography.bodySmall)
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(if (state.busy) state.progress else if (!actualLoaded || !expectedLoaded)
                "需两张图片加载完成才能确认；可稍后重新检查。" else "请确认两张图片的封面内容一致。",
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = { onDismiss(); actions.confirmCover() }, enabled = !state.busy && actualLoaded && expectedLoaded,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("确认封面一致") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { onDismiss(); actions.review() }, enabled = !state.busy) { Text("封面不一致，重新选择") }
                TextButton(onClick = { onDismiss(); actions.verify() }, enabled = !state.busy) { Text("重新检查") }
            }
        }
    }
}
