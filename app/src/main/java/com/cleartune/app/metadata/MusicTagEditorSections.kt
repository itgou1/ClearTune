package com.cleartune.app.metadata

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cleartune.app.clearTuneFallbackCover
import com.cleartune.core.model.MusicTagField
import java.io.File

@Composable
internal fun MusicTagSongHeader(state: MusicTagUiState) {
    val song = state.song ?: return
    val tags = state.snapshot?.values.orEmpty() + state.values
    val cover = tags[MusicTagField.COVER]?.takeIf { it.isNotBlank() }
        ?: state.snapshot?.values?.get(MusicTagField.COVER)?.takeIf { it.isNotBlank() }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val placeholder = painterResource(clearTuneFallbackCover(song.id))
        AsyncImage(model = cover?.let(::artworkModel) ?: state.artworkPath?.let(::File),
            contentDescription = "歌曲封面", placeholder = placeholder, error = placeholder,
            contentScale = ContentScale.Crop, modifier = Modifier.size(70.dp).clip(RoundedCornerShape(12.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tags[MusicTagField.TITLE]?.ifBlank { "未填写标题" } ?: song.title,
                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(tags[MusicTagField.ARTIST]?.ifBlank { "未填写艺术家" } ?: song.artistName,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val album = tags[MusicTagField.ALBUM] ?: song.albumName
            if (album.isNotBlank()) Text(album, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun MusicTagModeTabs(scraping: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(3.dp)) {
            listOf(false to "编辑标签", true to "刮削匹配").forEach { (tab, label) ->
                Surface(onClick = { onChange(tab) }, enabled = enabled,
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(15.dp),
                    color = if (scraping == tab) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (scraping == tab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) {
                    Box(Modifier.heightIn(min = 42.dp), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
internal fun MusicTagScrapePanel(state: MusicTagUiState, actions: MusicTagEditorActions) {
    val editable = !state.busy && !state.pending
    TagSection("搜索歌曲", "核对标题与艺术家，选择来源后搜索。") {
        OutlinedTextField(state.queryTitle, { actions.query(it, state.queryArtist) }, label = { Text("标题") },
            modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = editable, shape = RoundedCornerShape(12.dp))
        OutlinedTextField(state.queryArtist, { actions.query(state.queryTitle, it) }, label = { Text("艺术家") },
            modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = editable, shape = RoundedCornerShape(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("来源", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf("netease" to "网易云", "kuwo" to "酷我").forEach { (source, label) ->
                FilterChip(selected = state.settings.source == source, onClick = { actions.source(source) },
                    label = { Text(label) }, enabled = editable)
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = actions.search, enabled = editable && state.queryTitle.isNotBlank(),
                contentPadding = PaddingValues(horizontal = 16.dp)) { Text("搜索") }
        }
    }
    if (state.searched && state.candidates.isEmpty() && !state.busy) {
        Text("未找到匹配，试试其他检索词或来源。", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (state.candidates.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("匹配结果", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${state.candidates.size} 个候选", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.candidates.forEach { candidate ->
                Surface(onClick = { actions.choose(candidate) }, enabled = editable,
                    shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val placeholder = painterResource(clearTuneFallbackCover(candidate.id))
                        AsyncImage(model = candidate.values[MusicTagField.COVER]?.let(::artworkModel), contentDescription = "候选封面",
                            placeholder = placeholder, error = placeholder, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(58.dp).clip(RoundedCornerShape(12.dp)))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(candidate.values[MusicTagField.TITLE].orEmpty(), style = MaterialTheme.typography.titleSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(candidate.values[MusicTagField.ARTIST], candidate.values[MusicTagField.ALBUM])
                                .filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (candidate.source == "netease") "网易云音乐" else if (candidate.source == "kuwo") "酷我音乐" else candidate.source,
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Rounded.ChevronRight, "查看候选差异", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
