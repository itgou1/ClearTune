package com.cleartune.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cleartune.app.library.MusicViewModel
import com.cleartune.app.library.genreLabelsMatch
import com.cleartune.app.library.normalizeGenreLabel
import com.cleartune.core.model.Song
import java.util.Locale
import kotlin.math.roundToInt

internal data class ListeningPreference(
    val label: String,
    val score: Long,
    val share: Float,
)

internal enum class ListenerPersona {
    BEGINNING,
    DEEP_DIVER,
    EXPLORER,
    CURATOR,
    BALANCED,
}

internal data class ListeningProfileStats(
    val totalPlays: Long,
    val estimatedMinutes: Long,
    val listenedSongCount: Int,
    val recentSongCount: Int,
    val favoriteCount: Int,
    val repeatRate: Int,
    val breadthRate: Int,
    val favoriteRate: Int,
    val topGenres: List<ListeningPreference>,
    val topArtists: List<ListeningPreference>,
    val topDecades: List<ListeningPreference>,
    val topSongs: List<Song>,
    val persona: ListenerPersona,
) {
    val hasSignal: Boolean
        get() = totalPlays > 0 || listenedSongCount > 0 || favoriteCount > 0
}

internal fun analyzeListeningProfile(
    songs: List<Song>,
    now: Long = System.currentTimeMillis(),
): ListeningProfileStats {
    val totalPlays = songs.sumOf { it.playCount.coerceAtLeast(0) }
    val listenedSongs = songs.filter { it.playCount > 0 || it.lastPlayedAt != null }
    val favoriteCount = songs.count { it.starredAt != null }
    val recentCutoff = now - 30L * 24 * 60 * 60 * 1_000
    val recentSongCount = songs.count { (it.lastPlayedAt ?: Long.MIN_VALUE) >= recentCutoff }
    val estimatedSeconds = songs.sumOf { song ->
        song.durationSeconds.coerceAtLeast(0) * song.playCount.coerceAtLeast(0)
    }
    val repeatRate = if (totalPlays > 0) {
        (((totalPlays - listenedSongs.size).coerceAtLeast(0) * 100f) / totalPlays)
            .roundToInt()
            .coerceIn(0, 100)
    } else {
        0
    }
    val breadthRate = if (songs.isNotEmpty()) {
        (listenedSongs.size * 100f / songs.size).roundToInt().coerceIn(0, 100)
    } else {
        0
    }
    val favoriteBase = maxOf(listenedSongs.size, favoriteCount).takeIf { it > 0 } ?: songs.size
    val favoriteRate = if (favoriteBase > 0) {
        (favoriteCount * 100f / favoriteBase).roundToInt().coerceIn(0, 100)
    } else {
        0
    }
    val hasPlayData = totalPlays > 0
    fun weight(song: Song): Long = when {
        hasPlayData -> song.playCount.coerceAtLeast(0)
        song.starredAt != null -> 2
        song.lastPlayedAt != null -> 1
        else -> 0
    }

    fun preferences(groups: Map<String, Long>, limit: Int): List<ListeningPreference> {
        val total = groups.values.sum().coerceAtLeast(1)
        return groups.entries
            .filter { it.value > 0 }
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { entry ->
                ListeningPreference(
                    label = entry.key,
                    score = entry.value,
                    share = (entry.value.toFloat() / total).coerceIn(0f, 1f),
                )
            }
    }

    val topGenres = preferences(
        songs.mapNotNull { song ->
            normalizeGenreLabel(song.genre)?.let { it to weight(song) }
        }.groupingBy(Pair<String, Long>::first).fold(0L) { total, item -> total + item.second },
        limit = 5,
    )
    val topArtists = preferences(
        songs.mapNotNull { song ->
            song.displayArtistName()?.let { it to weight(song) }
        }.groupingBy(Pair<String, Long>::first).fold(0L) { total, item -> total + item.second },
        limit = 5,
    )
    val topDecades = preferences(
        songs.mapNotNull { song ->
            song.year?.takeIf { it in 1900..2099 }?.let { year ->
                "${year / 10 * 10} 年代" to weight(song)
            }
        }.groupingBy(Pair<String, Long>::first).fold(0L) { total, item -> total + item.second },
        limit = 5,
    )
    val topSongs = songs
        .filter { weight(it) > 0 }
        .sortedWith(
            compareByDescending<Song> { weight(it) }
                .thenByDescending { it.lastPlayedAt ?: Long.MIN_VALUE }
                .thenBy { it.title.lowercase(Locale.getDefault()) },
        )
        .take(5)
    val persona = when {
        totalPlays == 0L && listenedSongs.isEmpty() && favoriteCount == 0 -> ListenerPersona.BEGINNING
        repeatRate >= 50 && totalPlays >= 10 -> ListenerPersona.DEEP_DIVER
        breadthRate >= 50 && listenedSongs.size >= 10 -> ListenerPersona.EXPLORER
        favoriteRate >= 35 && favoriteCount >= 5 -> ListenerPersona.CURATOR
        else -> ListenerPersona.BALANCED
    }

    return ListeningProfileStats(
        totalPlays = totalPlays,
        estimatedMinutes = estimatedSeconds / 60,
        listenedSongCount = listenedSongs.size,
        recentSongCount = recentSongCount,
        favoriteCount = favoriteCount,
        repeatRate = repeatRate,
        breadthRate = breadthRate,
        favoriteRate = favoriteRate,
        topGenres = topGenres,
        topArtists = topArtists,
        topDecades = topDecades,
        topSongs = topSongs,
        persona = persona,
    )
}

@Composable
internal fun ListeningProfileScreen(
    username: String,
    songs: List<Song>,
    musicViewModel: MusicViewModel,
    onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
) {
    val stats = remember(songs) { analyzeListeningProfile(songs) }
    Scaffold(
        topBar = {
            ClearTuneTopAppBar(
                title = stringResource(R.string.listening_profile),
                onBack = onBack,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                ListeningProfileHero(username = username, stats = stats)
            }
            if (!stats.hasSignal) {
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp)) {
                            Text(
                                stringResource(R.string.listening_profile_empty),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.listening_profile_empty_subtitle),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                item { ListeningDataScopeNote() }
                item { ListeningProfileMetrics(stats) }
                if (stats.topGenres.isNotEmpty()) {
                    item {
                        ListeningPreferenceCard(
                            title = stringResource(R.string.listening_preferences),
                            subtitle = stringResource(R.string.listening_preferences_subtitle),
                            preferences = stats.topGenres,
                            onPreference = { preference ->
                                songs.filter { genreLabelsMatch(it.genre, preference.label) }
                                    .takeIf(List<Song>::isNotEmpty)
                                    ?.let { onPlay(it, 0) }
                            },
                        )
                    }
                }
                if (stats.topArtists.isNotEmpty()) {
                    item {
                        ListeningRankCard(
                            title = stringResource(R.string.listening_top_artists),
                            subtitle = stringResource(R.string.listening_top_artists_subtitle),
                            preferences = stats.topArtists,
                            usesPlayCount = stats.totalPlays > 0,
                            onPreference = { preference ->
                                songs.filter { it.displayArtistName() == preference.label }
                                    .takeIf(List<Song>::isNotEmpty)
                                    ?.let { onPlay(it, 0) }
                            },
                        )
                    }
                }
                item {
                    Text(
                        stringResource(R.string.listening_habits),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ListeningHabitCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.Repeat,
                            value = stringResource(R.string.listening_percent, stats.repeatRate),
                            label = stringResource(R.string.listening_repeat_tendency),
                            description = stringResource(
                                if (stats.repeatRate >= 50) {
                                    R.string.listening_repeat_high
                                } else {
                                    R.string.listening_repeat_low
                                },
                            ),
                            accent = MaterialTheme.colorScheme.primary,
                        )
                        ListeningHabitCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.Explore,
                            value = stringResource(R.string.listening_percent, stats.breadthRate),
                            label = stringResource(R.string.listening_discovery_breadth),
                            description = stringResource(R.string.listening_discovery_description),
                            accent = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ListeningHabitCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.Favorite,
                            value = stringResource(R.string.listening_percent, stats.favoriteRate),
                            label = stringResource(R.string.listening_favorite_rate),
                            description = stringResource(R.string.listening_favorite_description),
                            accent = MaterialTheme.colorScheme.error,
                        )
                        ListeningHabitCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.Album,
                            value = stringResource(R.string.listening_song_value, stats.recentSongCount),
                            label = stringResource(R.string.listening_recent_activity),
                            description = stringResource(R.string.listening_recent_description),
                            accent = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                if (stats.topDecades.isNotEmpty()) {
                    item {
                        ListeningEraCard(stats.topDecades)
                    }
                }
                if (stats.topSongs.isNotEmpty()) {
                    item {
                        ListeningSignatureSongs(
                            songs = stats.topSongs,
                            musicViewModel = musicViewModel,
                            onPlay = onPlay,
                            usesPlayCount = stats.totalPlays > 0,
                        )
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.listening_profile_basis),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ListeningDataScopeNote() {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            stringResource(R.string.listening_data_scope),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun ListeningProfileHero(username: String, stats: ListeningProfileStats) {
    val topGenre = stats.topGenres.firstOrNull()?.label
        ?: stringResource(R.string.listening_music_fallback)
    val title = stringResource(
        when (stats.persona) {
            ListenerPersona.BEGINNING -> R.string.listening_persona_beginning
            ListenerPersona.DEEP_DIVER -> R.string.listening_persona_deep
            ListenerPersona.EXPLORER -> R.string.listening_persona_explorer
            ListenerPersona.CURATOR -> R.string.listening_persona_curator
            ListenerPersona.BALANCED -> R.string.listening_persona_balanced
        },
    )
    val summary = when (stats.persona) {
        ListenerPersona.BEGINNING -> stringResource(R.string.listening_summary_beginning)
        ListenerPersona.DEEP_DIVER -> stringResource(R.string.listening_summary_deep, topGenre)
        ListenerPersona.EXPLORER -> stringResource(R.string.listening_summary_explorer, topGenre)
        ListenerPersona.CURATOR -> stringResource(R.string.listening_summary_curator, topGenre)
        ListenerPersona.BALANCED -> stringResource(R.string.listening_summary_balanced, topGenre)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ),
            )
            .padding(22.dp),
    ) {
        Icon(
            Icons.Rounded.MusicNote,
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(92.dp),
            tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.10f),
        )
        Column(modifier = Modifier.padding(end = 42.dp)) {
            Text(
                stringResource(R.string.listening_voice_shape, username),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                summary,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.84f),
            )
        }
    }
}

@Composable
private fun ListeningProfileMetrics(stats: ListeningProfileStats) {
    val timeValue = if (stats.estimatedMinutes >= 60) {
        stringResource(R.string.listening_hour_value, stats.estimatedMinutes / 60)
    } else {
        stringResource(R.string.listening_minute_value, stats.estimatedMinutes)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ListeningMetric(
            modifier = Modifier.weight(1f),
            value = stats.totalPlays.toString(),
            label = stringResource(R.string.listening_total_plays),
        )
        ListeningMetric(
            modifier = Modifier.weight(1f),
            value = timeValue,
            label = stringResource(R.string.listening_estimated_time),
        )
        ListeningMetric(
            modifier = Modifier.weight(1f),
            value = stats.listenedSongCount.toString(),
            label = stringResource(R.string.listening_listened_songs),
        )
    }
}

@Composable
private fun ListeningMetric(modifier: Modifier, value: String, label: String) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ProfileSectionCard(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

@Composable
private fun ListeningPreferenceCard(
    title: String,
    subtitle: String,
    preferences: List<ListeningPreference>,
    onPreference: (ListeningPreference) -> Unit,
) {
    ProfileSectionCard(title, subtitle) {
        preferences.forEachIndexed { index, preference ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onPreference(preference) }
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        preference.label,
                        modifier = Modifier.weight(1f),
                        fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Medium,
                    )
                    Text(
                        stringResource(R.string.listening_percent, (preference.share * 100).roundToInt()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.play_action),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(preference.share.coerceAtLeast(0.04f))
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == 0) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.secondary
                                },
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ListeningRankCard(
    title: String,
    subtitle: String,
    preferences: List<ListeningPreference>,
    usesPlayCount: Boolean,
    onPreference: (ListeningPreference) -> Unit,
) {
    ProfileSectionCard(title, subtitle) {
        preferences.take(4).forEachIndexed { index, preference ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onPreference(preference) }
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(34.dp),
                    shape = CircleShape,
                    color = if (index == 0) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            (index + 1).toString(),
                            fontWeight = FontWeight.Bold,
                            color = if (index == 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    preference.label,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (usesPlayCount) {
                        stringResource(R.string.listening_play_count_short, preference.score)
                    } else {
                        stringResource(R.string.listening_preference_signal)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(R.string.play_action),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ListeningHabitCard(
    modifier: Modifier,
    icon: ImageVector,
    value: String,
    label: String,
    description: String,
    accent: Color,
) {
    Surface(
        modifier = modifier.heightIn(min = 156.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, contentDescription = null, tint = accent)
            Spacer(Modifier.height(12.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(5.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ListeningEraCard(preferences: List<ListeningPreference>) {
    ProfileSectionCard(
        title = stringResource(R.string.listening_era_preference),
        subtitle = stringResource(R.string.listening_era_preference_subtitle),
    ) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(preferences, key = ListeningPreference::label) { preference ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (preference == preferences.first()) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(preference.label, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(
                                R.string.listening_percent,
                                (preference.share * 100).roundToInt(),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ListeningSignatureSongs(
    songs: List<Song>,
    musicViewModel: MusicViewModel,
    onPlay: (List<Song>, Int) -> Unit,
    usesPlayCount: Boolean,
) {
    ProfileSectionCard(
        title = stringResource(R.string.listening_signature_songs),
        subtitle = stringResource(R.string.listening_signature_songs_subtitle),
    ) {
        songs.take(3).forEachIndexed { index, song ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onPlay(songs, index) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverArt(
                    id = song.displayCoverArtId(),
                    description = song.title,
                    viewModel = musicViewModel,
                    modifier = Modifier.size(50.dp),
                    fallbackSeed = song.id,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        song.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        song.displayArtistName() ?: stringResource(R.string.unknown_artist),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (usesPlayCount) {
                    Text(
                        stringResource(R.string.listening_play_count_short, song.playCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(R.string.play_action),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
