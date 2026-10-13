package com.cleartune.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cleartune.app.player.RadioUiState
import com.cleartune.core.model.InternetRadioStation
import com.cleartune.core.player.PlaybackStatus
import com.cleartune.core.player.PlayerUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RadioPlayerScreen(
    state: RadioUiState,
    playback: PlayerUiState,
    onToggle: () -> Unit,
    onSelect: (InternetRadioStation) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    var showStations by rememberSaveable { mutableStateOf(false) }
    val currentIndex = state.stations.indexOfFirst { it.playbackId == playback.currentSong?.id }
    val connecting = playback.status in setOf(PlaybackStatus.BUFFERING, PlaybackStatus.READY)
    val playing = playback.status == PlaybackStatus.PLAYING
    val status = stringResource(when {
        connecting -> R.string.radio_connecting
        playing -> R.string.radio_player_live
        playback.status == PlaybackStatus.ERROR -> R.string.radio_play_error
        else -> R.string.radio_paused
    })
    val name = playback.currentSong?.title.orEmpty()
    val subtitle = stringResource(when {
        name.contains("经典") -> R.string.radio_classic_subtitle
        name.contains("中国之声") -> R.string.radio_news_subtitle
        name.contains("音乐之声") -> R.string.radio_music_subtitle
        else -> R.string.radio_home_subtitle
    })
    fun skip(offset: Int) {
        if (state.stations.size < 2) return
        val next = if (currentIndex < 0) 0 else (currentIndex + offset + state.stations.size) % state.stations.size
        onSelect(state.stations[next])
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.radio_collapse))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.radio_title), style = MaterialTheme.typography.titleSmall)
            }
            IconButton(onClick = { showStations = true }) {
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.radio_station_list))
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val viewportHeight = maxHeight
            val coverSize = minOf((maxWidth - 60.dp) * 0.9f, 324.dp,
                (viewportHeight - 270.dp).coerceAtLeast(120.dp))
            val bottomSpacing = if (viewportHeight >= 600.dp) 64.dp else 24.dp
            Layout(
                modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(min = viewportHeight)
                    .padding(horizontal = 30.dp),
                content = {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.radio_cover_portrait), null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(coverSize).clip(RoundedCornerShape(24.dp)))
                }
                Column(Modifier.fillMaxWidth().padding(bottom = bottomSpacing),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(6.dp).background(
                            if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape))
                        Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(24.dp))
                    PlayerTransportControls(
                        playing = playing, buffering = connecting,
                        previousLabel = stringResource(R.string.radio_previous),
                        toggleLabel = stringResource(if (playing || connecting) R.string.radio_pause_station else R.string.radio_play_station, name),
                        nextLabel = stringResource(R.string.radio_next),
                        onPrevious = { skip(-1) }, onToggle = onToggle, onNext = { skip(1) },
                        canSkip = state.stations.size > 1,
                    )
                }
                },
            ) { measurables, constraints ->
                val loose = constraints.copy(minHeight = 0)
                val controls = measurables[1].measure(loose)
                val cover = measurables[0].measure(loose.copy(
                    minHeight = (constraints.minHeight - controls.height).coerceAtLeast(0),
                ))
                layout(constraints.maxWidth, cover.height + controls.height) {
                    cover.placeRelative(0, 0)
                    controls.placeRelative(0, cover.height)
                }
            }
        }
    }
    if (showStations) {
        ModalBottomSheet(onDismissRequest = { showStations = false }) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.radio_station_list), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh, enabled = !state.loading) {
                    Icon(Icons.Rounded.Refresh, stringResource(R.string.radio_refresh))
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.error != null) Text(state.error, Modifier.padding(24.dp))
            if (!state.loading && state.error == null && state.stations.isEmpty()) {
                Text(stringResource(R.string.radio_empty), Modifier.padding(24.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
                items(state.stations, key = { it.id }) { station ->
                    RadioStationRow(station, playback) {
                        onSelect(it)
                        showStations = false
                    }
                }
            }
            Text(stringResource(R.string.radio_live_hint), Modifier.align(Alignment.CenterHorizontally).padding(20.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
