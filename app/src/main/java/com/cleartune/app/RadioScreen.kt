package com.cleartune.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cleartune.app.player.RadioUiState
import com.cleartune.core.model.InternetRadioStation
import com.cleartune.core.player.PlaybackStatus
import com.cleartune.core.player.PlayerUiState

@Composable
internal fun RadioCover(modifier: Modifier = Modifier, rear: Boolean = false) {
    Image(
        painterResource(if (rear) R.drawable.radio_cover_window else R.drawable.radio_cover_listener),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(12.dp)),
    )
}

@Composable
internal fun RadioHomeCard(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    HomeRecommendationCard(
        title = stringResource(R.string.radio_title),
        description = stringResource(R.string.radio_home_subtitle),
        enabled = true,
        hasRearCover = true,
        onPlay = onOpen,
        modifier = modifier,
        artwork = { index, artworkModifier -> RadioCover(artworkModifier, rear = index == 1) },
    )
}

@Composable
internal fun RadioScreen(
    state: RadioUiState,
    playback: PlayerUiState,
    onPlay: (InternetRadioStation) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.radio_back))
            }
            Text(stringResource(R.string.radio_title), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh, enabled = !state.loading) {
                Icon(Icons.Rounded.Refresh, stringResource(R.string.radio_refresh))
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            item {
                Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(24.dp))) {
                    Image(painterResource(R.drawable.radio_cover_window), null,
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                        listOf(Color(0xFFF0F1E9).copy(alpha = 0.97f), Color(0xFFF0F1E9).copy(alpha = 0.65f), Color.Transparent),
                    )))
                    Column(Modifier.align(Alignment.CenterStart).padding(20.dp)) {
                        Text(stringResource(R.string.radio_hero_title), style = MaterialTheme.typography.headlineSmall,
                            color = Color(0xFF293528), fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(10.dp))
                        Text(stringResource(R.string.radio_hero_subtitle), style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF535F50))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.radio_all), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.radio_count, state.stations.size), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.loading) item {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
            }
            if (state.error != null) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onRefresh, enabled = !state.loading) { Text(stringResource(R.string.radio_retry)) }
                }
            }
            if (!state.loading && state.error == null && state.stations.isEmpty()) item {
                Text(stringResource(R.string.radio_empty), Modifier.padding(vertical = 24.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.stations, key = { it.id }) { station ->
                RadioStationRow(station, playback, onPlay)
            }
        }
    }
}

@Composable
internal fun RadioStationRow(station: InternetRadioStation, playback: PlayerUiState, onPlay: (InternetRadioStation) -> Unit) {
    val selected = playback.currentSong?.isRadio == true && playback.currentSong?.id == station.playbackId
    val buffering = selected && playback.status in setOf(PlaybackStatus.BUFFERING, PlaybackStatus.READY)
    val playing = selected && playback.status == PlaybackStatus.PLAYING
    val subtitle = when {
        buffering -> stringResource(R.string.radio_connecting)
        playing -> stringResource(R.string.radio_live)
        selected && playback.status == PlaybackStatus.ERROR -> stringResource(R.string.radio_play_error)
        selected -> stringResource(R.string.radio_paused)
        station.name.contains("经典") -> stringResource(R.string.radio_classic_subtitle)
        station.name.contains("中国之声") -> stringResource(R.string.radio_news_subtitle)
        station.name.contains("音乐之声") -> stringResource(R.string.radio_music_subtitle)
        else -> stringResource(R.string.radio_title)
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f) else Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).semantics { stateDescription = subtitle },
    ) {
        Row(Modifier.padding(start = 10.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    Icon(when {
                        station.name.contains("经典") -> Icons.Rounded.Album
                        station.name.contains("音乐") -> Icons.Rounded.MusicNote
                        else -> Icons.Rounded.Radio
                    }, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(27.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(station.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val actionLabel = stringResource(
                if (playing || buffering) R.string.radio_pause_station else R.string.radio_play_station, station.name,
            )
            FilledTonalIconButton(onClick = { onPlay(station) }, modifier = Modifier.semantics { contentDescription = actionLabel }) {
                if (buffering) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = null)
            }
        }
    }
}
