package com.cleartune.app.metadata

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cleartune.app.clearTuneFallbackCover
import com.cleartune.core.model.*
import java.io.File

internal data class MusicTagEditorActions(
    val query: (String, String) -> Unit = { _, _ -> },
    val source: (String) -> Unit = {},
    val search: () -> Unit = {},
    val reload: () -> Unit = {},
    val settings: () -> Unit = {},
    val choose: (MusicTagCandidate) -> Unit = {},
    val select: (MusicTagField, Boolean) -> Unit = { _, _ -> },
    val edit: (MusicTagField, String) -> Unit = { _, _ -> },
    val apply: () -> Unit = {},
    val verify: () -> Unit = {},
    val confirmCover: () -> Unit = {},
    val review: () -> Unit = {},
    val close: () -> Unit = {},
)

@Composable
internal fun MusicTagNotice(state: MusicTagUiState) {
    if (state.busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(state.progress, style = MaterialTheme.typography.bodySmall)
    }
    state.message?.let { message ->
        Surface(color = if (state.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
            contentColor = if (state.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (state.isError) Icons.Rounded.ErrorOutline else Icons.Rounded.Info, null, Modifier.size(20.dp))
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun MusicTagEditor(state: MusicTagUiState, actions: MusicTagEditorActions, modifier: Modifier = Modifier) {
    val editable = !state.busy && !state.pending
    var scraping by remember(state.song?.id) { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(state.fillVersion) {
        if (state.fillVersion > 0) { scraping = false; scroll.animateScrollTo(0) }
    }
    var showCoverReview by remember(state.song?.id, state.coverReview) { mutableStateOf(state.coverReview != null) }
    var invalidRequest by remember(state.song?.id) { mutableStateOf<Pair<MusicTagField, Int>?>(null) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val apply = {
        val invalid = state.changes.entries.firstOrNull { (field, value) -> tagDraftError(field, value) != null }?.key
        if (invalid != null) {
            scraping = false
            invalidRequest = invalid to ((invalidRequest?.second ?: 0) + 1)
        }
        else {
            focusManager.clearFocus()
            keyboard?.hide()
            actions.apply()
        }
    }
    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            MusicTagSongHeader(state)
            if (!state.settings.configured) {
                TagSection("连接标签服务", "连接能访问当前音乐库的 Music Tag 服务后，即可编辑或刮削标签。") {
                    Text("请先在设置中配置服务。", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                TagOriginalSection(state, actions)
                MusicTagModeTabs(scraping, enabled = !state.busy) { target ->
                    if (target && !scraping) actions.query(
                        state.values[MusicTagField.TITLE] ?: state.queryTitle,
                        state.values[MusicTagField.ARTIST] ?: state.queryArtist)
                    scraping = target
                }
                if (scraping) MusicTagScrapePanel(state, actions)
                else if (state.snapshot != null) MusicTagDraftForm(state, actions, invalidRequest)
            }
            Spacer(Modifier.height(4.dp))
        }
        MusicTagSaveBar(state, actions.copy(apply = apply), onReviewCover = { showCoverReview = true })
    }
    if (showCoverReview && state.coverReview != null) {
        MusicTagCoverReviewDialog(state, actions, onDismiss = { showCoverReview = false })
    }
}

@Composable
private fun MusicTagDraftForm(state: MusicTagUiState, actions: MusicTagEditorActions,
    invalidRequest: Pair<MusicTagField, Int>?) {
    val editable = !state.busy && !state.pending
    var coverExpanded by remember(state.song?.id) { mutableStateOf(false) }
    var extrasExpanded by remember(state.song?.id) { mutableStateOf(false) }
    var compareCover by remember(state.song?.id) { mutableStateOf(false) }
    val extraFields = listOf(MusicTagField.YEAR, MusicTagField.GENRE, MusicTagField.LYRICS)
    LaunchedEffect(invalidRequest) {
        if (invalidRequest?.first in extraFields) extrasExpanded = true
        if (invalidRequest?.first == MusicTagField.COVER) coverExpanded = true
    }
    val originalCover = state.snapshot?.values?.get(MusicTagField.COVER).orEmpty()
    val cover = state.values[MusicTagField.COVER] ?: originalCover
    TagSection(when {
        state.fileSaved -> "已保存的文件标签"
        state.dirty -> "编辑草稿"
        else -> "文件标签"
    }, if (state.pending) "保存结果确认后可继续编辑。"
        else if (state.dirty) "带颜色的字段尚未写入文件。"
        else "编辑草稿，保存后写入原文件。") {
        val coverChanged = !state.pending && MusicTagField.COVER in state.changes
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val placeholder = painterResource(clearTuneFallbackCover(state.song?.id.orEmpty()))
            AsyncImage(model = artworkModel(cover), contentDescription = "当前编辑封面", contentScale = ContentScale.Crop,
                placeholder = placeholder, error = placeholder, modifier = Modifier.size(60.dp).clip(RoundedCornerShape(12.dp)))
            Column(Modifier.weight(1f)) {
                Text("封面", style = MaterialTheme.typography.titleSmall,
                    color = if (coverChanged) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface)
                if (coverChanged) {
                    TextButton(onClick = { compareCover = true }, contentPadding = PaddingValues(0.dp)) {
                        Text("已修改 · 查看原封面", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary)
                    }
                } else Text(if (cover.isBlank()) "暂无封面" else "与原文件一致",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { coverExpanded = !coverExpanded }, enabled = editable) {
                Text(if (coverExpanded) "收起" else "更换")
            }
        }
        if (coverExpanded) TagDraftInput(MusicTagField.COVER, if (cover == originalCover) "" else cover,
            { actions.edit(MusicTagField.COVER, it.ifBlank { originalCover }) }, coverChanged, state, invalidRequest,
            label = "替换封面地址", hint = "填写图片 URL；留空保留原封面。")
        listOf(MusicTagField.TITLE, MusicTagField.ARTIST, MusicTagField.ALBUM).forEach { field ->
            TagDraftInput(field, state.values[field] ?: state.snapshot?.values?.get(field).orEmpty(),
                { actions.edit(field, it) }, !state.pending && field in state.changes, state, invalidRequest)
        }
    }
    val extraChanges = extraFields.count { it in state.changes }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth()) {
            TextButton(onClick = { extrasExpanded = !extrasExpanded }, modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                Text("更多字段", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(12.dp))
                Text(if (extraChanges == 0) "年份 · 流派 · 歌词" else "已修改 $extraChanges 项",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (extraChanges == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(if (extrasExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    if (extrasExpanded) "收起" else "展开")
            }
            if (extrasExpanded) Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                extraFields.forEach { field ->
                    TagDraftInput(field, state.values[field] ?: state.snapshot?.values?.get(field).orEmpty(),
                        { actions.edit(field, it) }, !state.pending && field in state.changes, state, invalidRequest)
                }
                Text("仅保存改动字段，暂不支持清空已有标签。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (compareCover) AlertDialog(onDismissRequest = { compareCover = false }, title = { Text("封面变更") },
        text = { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(originalCover to "原文件封面", cover to "草稿封面").forEach { (image, label) ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    val placeholder = painterResource(clearTuneFallbackCover(state.song?.id.orEmpty()))
                    AsyncImage(model = artworkModel(image), contentDescription = label,
                        placeholder = placeholder, error = placeholder, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)))
                }
            }
        } }, confirmButton = { TextButton(onClick = { compareCover = false }) { Text("关闭") } })
}

private fun tagDraftError(field: MusicTagField, value: String): String? = when {
    value.isBlank() -> "暂不支持清空已有标签"
    "\${" in value -> "暂不支持模板表达式"
    field == MusicTagField.YEAR && value.toIntOrNull() !in 1..9999 -> "年份应为 1–9999"
    else -> null
}

@Composable
private fun TagDraftInput(field: MusicTagField, value: String, onChange: (String) -> Unit, changed: Boolean,
    state: MusicTagUiState, invalidRequest: Pair<MusicTagField, Int>?, label: String = field.label, hint: String? = null) {
    val bringIntoView = remember { BringIntoViewRequester() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(invalidRequest) {
        if (invalidRequest?.first == field) {
            focus.requestFocus()
            bringIntoView.bringIntoView()
        }
    }
    val error = if (changed) tagDraftError(field, value) else null
    val original = state.snapshot?.values?.get(field).orEmpty()
    val support = when {
        error != null -> error
        changed && field != MusicTagField.COVER && original.isNotBlank() -> "原值：$original"
        changed -> "已修改"
        else -> hint
    }
    OutlinedTextField(value, onChange, label = { Text(label) },
        supportingText = if (support != null) { {
            Text(support,
                color = if (error != null) MaterialTheme.colorScheme.error else if (changed) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (field == MusicTagField.LYRICS) 2 else 1, overflow = TextOverflow.Ellipsis)
        } } else null,
        colors = tagDraftColors(changed), isError = error != null,
        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView).focusRequester(focus),
        enabled = !state.busy, readOnly = state.pending, shape = RoundedCornerShape(12.dp),
        minLines = if (field == MusicTagField.LYRICS) 4 else 1,
        maxLines = if (field == MusicTagField.LYRICS) 8 else 3)
}

@Composable
private fun tagDraftColors(changed: Boolean): TextFieldColors =
    if (!changed) OutlinedTextFieldDefaults.colors() else OutlinedTextFieldDefaults.colors(
        focusedTextColor = MaterialTheme.colorScheme.tertiary,
        unfocusedTextColor = MaterialTheme.colorScheme.tertiary,
        focusedBorderColor = MaterialTheme.colorScheme.tertiary,
        unfocusedBorderColor = MaterialTheme.colorScheme.tertiary,
        focusedLabelColor = MaterialTheme.colorScheme.tertiary,
        unfocusedLabelColor = MaterialTheme.colorScheme.tertiary,
        focusedContainerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f),
        unfocusedContainerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f))

@Composable
private fun TagOriginalSection(state: MusicTagUiState, actions: MusicTagEditorActions) {
    var expanded by remember(state.song?.id, state.isError, state.targetPath) {
        mutableStateOf(state.isError && state.snapshot == null)
    }
    val ready = state.snapshot != null
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val draft = state.dirty && !state.pending
            Surface(color = if (draft) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp)) {
                Text(when {
                    draft -> "草稿 · 未保存"
                    state.fileSaved -> "原文件已保存"
                    ready -> "原标签已读取"
                    state.busy -> "正在读取原标签"
                    else -> "原标签尚未读取"
                }, Modifier.padding(horizontal = 12.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium,
                    color = if (draft) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.weight(1f))
            if (state.targetPath != null || state.sourcePath != null || ready)
                TextButton(onClick = { expanded = !expanded }) { Text("文件信息") }
        }
        if (!ready && !state.busy) {
            TextButton(onClick = actions.reload, enabled = !state.pending) { Text("读取原标签") }
        }
        if (expanded) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.sourcePath?.let { Text("Navidrome 原路径\n$it", style = MaterialTheme.typography.bodySmall) }
                    (state.targetPath ?: state.snapshot?.path)?.let { Text("Music Tag 目标路径\n$it", style = MaterialTheme.typography.bodySmall) }
                    if (!ready) {
                        Text("请确认两端目录对应同一份音乐文件。", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = actions.settings, enabled = !state.busy) { Text("查看服务连接") }
                    } else {
                        listOf(MusicTagField.TITLE, MusicTagField.ARTIST, MusicTagField.ALBUM, MusicTagField.YEAR, MusicTagField.GENRE).forEach { field ->
                            Text("${field.label}：${state.snapshot.values[field].orEmpty().ifBlank { "未填写" }}", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = actions.reload, enabled = !state.busy && !state.pending) { Text("重新读取原标签") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TagSection(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}
