package com.cleartune.app.metadata

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cleartune.core.model.MusicTagField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MusicTagCandidateDialog(state: MusicTagUiState, onSelect: (MusicTagField, Boolean) -> Unit,
    onFill: () -> Unit, onDismiss: () -> Unit) {
    val differences = state.candidateValues.filter { (field, value) ->
        value.isNotBlank() && value != (state.values[field] ?: state.snapshot?.values?.get(field)).orEmpty()
    }
    val same = state.candidateValues.filter { (field, value) ->
        value.isNotBlank() && field !in differences
    }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("选择要填入的字段", style = MaterialTheme.typography.titleLarge)
                Text("填入草稿后仍可编辑，保存时才写入文件。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.candidateValues[MusicTagField.COVER]?.let { cover ->
                            AsyncImage(model = artworkModel(cover), contentDescription = "候选封面",
                                contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(state.candidateValues[MusicTagField.TITLE].orEmpty(),
                                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(state.candidateValues[MusicTagField.ARTIST],
                                state.candidateValues[MusicTagField.ALBUM]).filter(String::isNotBlank).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Text("有差异的字段", style = MaterialTheme.typography.titleMedium)
                if (differences.isEmpty()) Text("候选内容与当前草稿一致，无需填入。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                differences.forEach { (field, proposed) ->
                    val old = (state.values[field] ?: state.snapshot?.values?.get(field)).orEmpty()
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(14.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
                            Checkbox(field in state.selected, { onSelect(field, it) })
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(field.label, style = MaterialTheme.typography.titleSmall)
                                if (field == MusicTagField.COVER) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        listOf(old to "当前", proposed to "填入").forEach { (cover, label) ->
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                AsyncImage(model = cover.takeIf(String::isNotBlank)?.let(::artworkModel),
                                                    contentDescription = "$label${field.label}", contentScale = ContentScale.Fit,
                                                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)))
                                                Text(label, style = MaterialTheme.typography.labelSmall,
                                                    color = if (label == "填入") MaterialTheme.colorScheme.tertiary
                                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                } else {
                                    Text("当前：${old.ifBlank { "未填写" }}", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = if (field == MusicTagField.LYRICS) 2 else 3, overflow = TextOverflow.Ellipsis)
                                    Text("填入：$proposed", style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        maxLines = if (field == MusicTagField.LYRICS) 2 else 3, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                if (same.isNotEmpty()) Text("相同字段：${same.keys.joinToString { it.label }}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onFill, enabled = state.snapshot != null && state.selected.any { it in differences },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("填入草稿（${state.selected.count { it in differences }} 项）")
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("取消") }
        }
    }
}
