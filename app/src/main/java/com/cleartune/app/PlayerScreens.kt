package com.cleartune.app

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import com.cleartune.app.library.MusicViewModel
import com.cleartune.app.library.LyricsUiState
import com.cleartune.app.player.PlayerViewModel
import com.cleartune.core.model.PlaybackMode
import com.cleartune.core.player.PlaybackStatus
import com.cleartune.core.player.PlayerUiState
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

private data class PlayerHeroState(
    val songId: String,
    val title: String,
    val coverArtId: String?,
    val lyricsVisible: Boolean,
)

private data class PlayerMetadataState(
    val songId: String,
    val title: String,
    val artist: String?,
)

@Composable
internal fun MiniPlayer(
    state: PlayerUiState,
    musicViewModel: MusicViewModel,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    val song = state.currentSong ?: return
    Surface(tonalElevation = 0.dp, shadowElevation = 0.dp) {
        val durationMs = state.durationMs.takeIf { it > 0 } ?: song.durationSeconds * 1_000
        val progress = if (durationMs > 0) {
            state.positionMs.toFloat() / durationMs
        } else {
            0f
        }
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverArt(
                    song.displayCoverArtId(),
                    song.title,
                    musicViewModel,
                    Modifier.size(42.dp),
                    fallbackSeed = song.id,
                )
                Spacer(Modifier.size(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.now_playing_compact, song.title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium,
                    )
                    song.displayArtistName()?.let {
                        Text(
                            it,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                ClearTuneTonalIconButton(onClick = onToggle) {
                    Icon(
                        if (state.status == PlaybackStatus.PLAYING) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(
                            if (state.status == PlaybackStatus.PLAYING) R.string.pause_action else R.string.play_action,
                        ),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NowPlayingScreen(
    state: PlayerUiState,
    playerViewModel: PlayerViewModel,
    musicViewModel: MusicViewModel,
    lyricsState: LyricsUiState,
    isFavorite: Boolean,
    onBack: () -> Unit,
    onQueue: () -> Unit,
    onEqualizer: () -> Unit,
    onFavorite: (com.cleartune.core.model.Song, Boolean) -> Unit,
    onDownload: (com.cleartune.core.model.Song) -> Unit,
) {
    val song = state.currentSong
    val displayCoverArtId = song?.displayCoverArtId()
    var showLyrics by remember(song?.id) { mutableStateOf(false) }
    var showDetails by remember(song?.id) { mutableStateOf(false) }
    var showMoreActions by remember(song?.id) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(clearTuneGradient()),
    ) {
        AnimatedContent(
            targetState = song?.id?.takeIf { displayCoverArtId == null },
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                fadeIn(tween(durationMillis = 900)) togetherWith
                    fadeOut(tween(durationMillis = 700))
            },
            label = "playerBackdrop",
        ) { ambientSeed ->
            if (ambientSeed != null) {
                AmbientPlayerBackdrop(
                    seed = ambientSeed,
                    isPlaying = state.status == PlaybackStatus.PLAYING,
                )
            }
        }
        if (song == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { Text(stringResource(R.string.empty_queue)) }
        } else {
            var seekPosition by remember(song.id) { mutableFloatStateOf(state.positionMs.toFloat()) }
            var isSeeking by remember(song.id) { mutableStateOf(false) }
            val seekInteractionSource = remember(song.id) { MutableInteractionSource() }
            val durationMs = state.durationMs.takeIf { it > 0 } ?: song.durationSeconds * 1_000
            LaunchedEffect(song.id, state.positionMs) {
                if (!isSeeking) seekPosition = state.positionMs.toFloat()
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                    Text(
                        stringResource(if (showLyrics) R.string.lyrics else R.string.now_playing),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    IconButton(onClick = onQueue) {
                        Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = stringResource(R.string.play_queue))
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clickable { showLyrics = !showLyrics },
                    contentAlignment = Alignment.Center,
                ) {
                    val heroState = PlayerHeroState(
                        songId = song.id,
                        title = song.title,
                        coverArtId = displayCoverArtId,
                        lyricsVisible = showLyrics,
                    )
                    AnimatedContent(
                        targetState = heroState,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            val lyricsChanged = initialState.lyricsVisible != targetState.lyricsVisible
                            if (lyricsChanged) {
                                (fadeIn(ClearTuneMotion.standard()) + scaleIn(
                                    initialScale = 0.985f,
                                    animationSpec = ClearTuneMotion.standard(),
                                )) togetherWith fadeOut(ClearTuneMotion.quick())
                            } else {
                                (fadeIn(tween(durationMillis = 720)) + scaleIn(
                                    initialScale = 0.96f,
                                    animationSpec = tween(durationMillis = 720),
                                )) togetherWith (fadeOut(tween(durationMillis = 480)) + scaleOut(
                                    targetScale = 1.025f,
                                    animationSpec = tween(durationMillis = 480),
                                ))
                            }
                        },
                        contentAlignment = Alignment.Center,
                        label = "artworkLyrics",
                    ) { hero ->
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (hero.lyricsVisible) {
                                LyricsArtwork(
                                    lyricsState = lyricsState,
                                    positionMs = state.positionMs,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else if (hero.coverArtId == null) {
                                AmbientFallbackArtwork(
                                    isPlaying = state.status == PlaybackStatus.PLAYING,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                CoverArt(
                                    hero.coverArtId,
                                    hero.title,
                                    musicViewModel,
                                    Modifier
                                        .fillMaxWidth(0.84f)
                                        .aspectRatio(1f),
                                    fallbackSeed = hero.songId,
                                    requestSize = 768,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AnimatedContent(
                        targetState = PlayerMetadataState(song.id, song.title, song.displayArtistName()),
                        modifier = Modifier.weight(1f),
                        transitionSpec = {
                            fadeIn(tween(durationMillis = 360, delayMillis = 110)) togetherWith
                                fadeOut(tween(durationMillis = 180))
                        },
                        label = "playerMetadata",
                    ) { metadata ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                metadata.title,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            metadata.artist?.let {
                                Text(
                                    it,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    IconButton(onClick = { onFavorite(song, !isFavorite) }) {
                        Icon(
                            if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = stringResource(if (isFavorite) R.string.unlike_song else R.string.like_song),
                            tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Slider(
                    value = seekPosition.coerceIn(0f, durationMs.coerceAtLeast(1).toFloat()),
                    onValueChange = {
                        isSeeking = true
                        seekPosition = it
                    },
                    onValueChangeFinished = {
                        playerViewModel.seekTo(seekPosition.toLong())
                        isSeeking = false
                    },
                    valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp),
                    interactionSource = seekInteractionSource,
                    thumb = {
                        Surface(
                            modifier = Modifier.size(14.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            shadowElevation = 1.dp,
                        ) {}
                    },
                    track = {
                        val progress = (seekPosition / durationMs.coerceAtLeast(1))
                            .coerceIn(0f, 1f)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progress)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(seekPosition.toLong()), style = MaterialTheme.typography.labelMedium)
                    Text(formatTime(durationMs), style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = playerViewModel::previous,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(Icons.Rounded.SkipPrevious, contentDescription = stringResource(R.string.previous_song))
                    }
                    FilledIconButton(onClick = playerViewModel::togglePlayPause, modifier = Modifier.size(70.dp)) {
                        Icon(
                            if (state.status == PlaybackStatus.PLAYING) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(
                                if (state.status == PlaybackStatus.PLAYING) R.string.pause_action else R.string.play_action,
                            ),
                            modifier = Modifier.size(36.dp),
                        )
                    }
                    IconButton(
                        onClick = playerViewModel::next,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.next_song))
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerSecondaryButton(
                        selected = showLyrics,
                        onClick = { showLyrics = !showLyrics },
                    ) {
                        Icon(Icons.Rounded.Lyrics, contentDescription = stringResource(R.string.lyrics))
                    }
                    PlayerSecondaryButton(onClick = onEqualizer) {
                        Icon(Icons.Rounded.GraphicEq, contentDescription = stringResource(R.string.equalizer))
                    }
                    PlayerSecondaryButton(onClick = { showMoreActions = true }) {
                        Icon(Icons.Rounded.MoreHoriz, contentDescription = stringResource(R.string.more_actions))
                    }
                }
            }
        }
    }
    if (showDetails && song != null) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text(stringResource(R.string.song_details)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium)
                    song.displayArtistName()?.let { Text(stringResource(R.string.song_detail_artist, it)) }
                    song.displayAlbumName()?.let { Text(stringResource(R.string.song_detail_album, it)) }
                    Text(stringResource(R.string.song_detail_duration, formatTime(song.durationSeconds * 1_000)))
                    Text(
                        stringResource(
                            R.string.song_detail_format,
                            listOfNotNull(
                                song.suffix?.uppercase(),
                                song.bitRate?.let { "$it kbps" },
                            ).joinToString(" · ").ifBlank { stringResource(R.string.unknown_format) },
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetails = false }) {
                    Text(stringResource(R.string.close_action))
                }
            },
        )
    }
    if (showMoreActions && song != null) {
        ModalBottomSheet(onDismissRequest = { showMoreActions = false }) {
            Text(
                text = stringResource(R.string.more_actions),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.playback_mode)) },
                supportingContent = { Text(state.mode.label()) },
                leadingContent = { Icon(state.mode.icon(), contentDescription = null) },
                modifier = Modifier.clickable {
                    playerViewModel.cycleMode()
                    showMoreActions = false
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.download_current_song)) },
                leadingContent = { Icon(Icons.Rounded.Download, contentDescription = null) },
                modifier = Modifier.clickable {
                    onDownload(song)
                    showMoreActions = false
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.song_details)) },
                leadingContent = { Icon(Icons.Rounded.Info, contentDescription = null) },
                modifier = Modifier.clickable {
                    showMoreActions = false
                    showDetails = true
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PlayerSecondaryButton(
    selected: Boolean = false,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(52.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.20f)
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
        content = content,
    )
}

@Composable
private fun AmbientPlayerBackdrop(
    seed: String,
    isPlaying: Boolean,
) {
    val transition = rememberInfiniteTransition(label = "ambientBackdrop")
    val motionStrength by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.28f,
        animationSpec = tween(durationMillis = 1_200),
        label = "ambientBackdropActivity",
    )
    val breathingScale by transition.animateFloat(
        initialValue = 1.04f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14_000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ambientBackdropScale",
    )
    val driftX by transition.animateFloat(
        initialValue = -18f,
        targetValue = 18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18_000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ambientBackdropDriftX",
    )
    val driftY by transition.animateFloat(
        initialValue = 12f,
        targetValue = -12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 23_000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ambientBackdropDriftY",
    )
    val glowProgress by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 16_000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ambientGlowDrift",
    )
    val primaryGlow = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val secondaryGlow = MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f)
    Image(
        painter = painterResource(clearTuneFallbackCover(seed)),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val scale = 1.04f + (breathingScale - 1.04f) * motionStrength
                scaleX = scale
                scaleY = scale
                translationX = driftX * motionStrength
                translationY = driftY * motionStrength
                rotationZ = glowProgress * 0.8f * motionStrength
                alpha = 0.68f
            }
            .blur(10.dp),
    )
    Canvas(modifier = Modifier.fillMaxSize()) {
        val firstCenter = Offset(
            x = size.width * (0.34f + glowProgress * 0.08f * motionStrength),
            y = size.height * (0.38f - glowProgress * 0.05f * motionStrength),
        )
        val secondCenter = Offset(
            x = size.width * (0.72f - glowProgress * 0.07f * motionStrength),
            y = size.height * (0.66f + glowProgress * 0.04f * motionStrength),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(primaryGlow, Color.Transparent),
                center = firstCenter,
                radius = size.minDimension * 0.48f,
            ),
            radius = size.minDimension * 0.48f,
            center = firstCenter,
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(secondaryGlow, Color.Transparent),
                center = secondCenter,
                radius = size.minDimension * 0.40f,
            ),
            radius = size.minDimension * 0.40f,
            center = secondCenter,
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f),
                        MaterialTheme.colorScheme.background.copy(alpha = 0.20f),
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.62f),
                    ),
                ),
            ),
    )
}

@Composable
private fun AmbientFallbackArtwork(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "ambientArtwork")
    val playbackActivity by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(durationMillis = 720),
        label = "ambientArtworkActivity",
    )
    val breathingScale by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12_000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ambientArtworkScale",
    )
    val haloScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ambientHaloScale",
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(142.dp)
                .graphicsLayer {
                    val activeScale = 1f + (haloScale - 1f) * playbackActivity
                    scaleX = activeScale
                    scaleY = activeScale
                    alpha = 0.08f + 0.10f * playbackActivity
                }
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(MaterialTheme.colorScheme.surface),
        )
        Box(
            modifier = Modifier
                .size(112.dp)
                .graphicsLayer {
                    val scale = 1f + (breathingScale - 1f) * playbackActivity
                    scaleX = scale
                    scaleY = scale
                }
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.22f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            AmbientWaveform(
                isPlaying = isPlaying,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                modifier = Modifier.size(48.dp),
            )
        }
    }
}

@Composable
private fun AmbientWaveform(
    isPlaying: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "ambientWaveform")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_900, easing = LinearEasing),
        ),
        label = "ambientWaveformPhase",
    )
    val activity by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "ambientWaveformActivity",
    )
    Canvas(modifier = modifier) {
        val idleHeights = floatArrayOf(0.34f, 0.62f, 0.92f, 0.62f, 0.34f)
        val barWidth = size.width * 0.09f
        val gap = size.width * 0.09f
        val contentWidth = barWidth * idleHeights.size + gap * (idleHeights.size - 1)
        val startX = (size.width - contentWidth) / 2f
        idleHeights.forEachIndexed { index, idleHeight ->
            val wave = sin(phase + index * 0.92f) * 0.18f * activity
            val height = size.height * (idleHeight + wave).coerceIn(0.22f, 1f)
            val left = startX + index * (barWidth + gap)
            drawRoundRect(
                color = color,
                topLeft = Offset(left, (size.height - height) / 2f),
                size = androidx.compose.ui.geometry.Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

@Composable
private fun LyricsArtwork(
    lyricsState: LyricsUiState,
    positionMs: Long,
    modifier: Modifier = Modifier,
) {
    val lines = lyricsState.lyrics?.lines.orEmpty()
    val activeIndex = lines.indexOfLast { line ->
        line.startMs?.let { it <= positionMs } == true
    }
    val listState = rememberLazyListState()
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            listState.animateScrollToItem(activeIndex, scrollOffset = -160)
        }
    }
    Box(modifier = modifier) {
        when {
            lyricsState.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            lines.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(lyricsState.message ?: stringResource(R.string.no_lyrics))
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(lines) { index, line ->
                    Text(
                        text = line.text,
                        modifier = Modifier.fillMaxWidth(),
                        style = if (index == activeIndex) {
                            MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                        } else {
                            MaterialTheme.typography.bodyLarge
                        },
                        textAlign = TextAlign.Center,
                        color = if (index == activeIndex) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LyricsScreen(
    state: LyricsUiState,
    playerState: PlayerUiState,
    onSeek: (Long) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            ClearTuneTopAppBar(title = stringResource(R.string.lyrics), onBack = onBack)
        },
    ) { padding ->
        when {
            state.loading -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { Text(stringResource(R.string.loading_lyrics)) }
            state.lyrics?.lines.isNullOrEmpty() -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { Text(state.message ?: stringResource(R.string.no_lyrics)) }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val lines = state.lyrics.lines
                itemsIndexed(lines) { _, line ->
                    val active = line.startMs?.let { start ->
                        val next = lines.firstOrNull { (it.startMs ?: Long.MAX_VALUE) > start }?.startMs
                        playerState.positionMs >= start && (next == null || playerState.positionMs < next)
                    } == true
                    Text(
                        text = line.text,
                        style = if (active) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = line.startMs != null) { line.startMs?.let(onSeek) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QueueScreen(
    state: PlayerUiState,
    playerViewModel: PlayerViewModel,
    musicViewModel: MusicViewModel,
    onBack: () -> Unit,
    onBrowseLibrary: () -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    val latestQueue by rememberUpdatedState(state.queue)
    var draggedItemIndex by remember { mutableStateOf<Int?>(null) }
    var draggedItemOffset by remember { mutableFloatStateOf(0f) }
    var showMenu by remember { mutableStateOf(false) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    val moveUpLabel = stringResource(R.string.move_up)
    val moveDownLabel = stringResource(R.string.move_down)
    val removeMessage = stringResource(R.string.queue_song_removed)
    val queueClearedMessage = stringResource(R.string.queue_cleared)
    val undoLabel = stringResource(R.string.undo_action)

    LaunchedEffect(state.currentIndex, state.queue.size) {
        if (draggedItemIndex == null && state.currentIndex in state.queue.indices) {
            listState.scrollToItem(state.currentIndex)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ClearTuneTopAppBar(
                title = stringResource(R.string.play_queue),
                onBack = onBack,
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more_actions))
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.clear_queue)) },
                                leadingIcon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = null) },
                                enabled = state.queue.isNotEmpty(),
                                onClick = {
                                    showMenu = false
                                    showClearConfirmation = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            QueueModeSelector(
                mode = state.mode,
                onMode = playerViewModel::setMode,
            )
            if (state.queue.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = null,
                        modifier = Modifier.size(52.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(stringResource(R.string.empty_queue), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(18.dp))
                    FilledTonalButton(onClick = onBrowseLibrary) {
                        Text(stringResource(R.string.browse_music))
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.queue_section),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        stringResource(
                            R.string.queue_summary,
                            state.queue.size,
                            formatQueueDuration(state.queue.sumOf { it.durationSeconds }),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 10.dp,
                        end = 10.dp,
                        bottom = 24.dp,
                    ),
                ) {
                    itemsIndexed(state.queue, key = { _, song -> song.id }) { index, song ->
                        val isCurrent = index == state.currentIndex
                        val isDragging = draggedItemIndex == index
                        var removalHandled by remember(song.id) { mutableStateOf(false) }
                        val dismissState = rememberSwipeToDismissBoxState()
                        LaunchedEffect(dismissState.currentValue) {
                            if (
                                dismissState.currentValue == SwipeToDismissBoxValue.EndToStart &&
                                !removalHandled
                            ) {
                                removalHandled = true
                                val undoToken = playerViewModel.removeUndoable(index)
                                if (undoToken != null) {
                                    scope.launch {
                                        val result = snackbarHostState.showSnackbar(
                                            message = removeMessage,
                                            actionLabel = undoLabel,
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            playerViewModel.undoQueueMutation(undoToken)
                                        } else {
                                            playerViewModel.discardQueueUndo(undoToken)
                                        }
                                    }
                                }
                            }
                        }
                        val draggedScale by animateFloatAsState(
                            targetValue = if (isDragging) 1.015f else 1f,
                            animationSpec = ClearTuneMotion.quick(),
                            label = "queueItemScale",
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            modifier = Modifier
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer {
                                    translationY = if (isDragging) draggedItemOffset else 0f
                                    scaleX = draggedScale
                                    scaleY = draggedScale
                                }
                                .then(
                                    if (isDragging) {
                                        Modifier.shadow(8.dp, RoundedCornerShape(16.dp))
                                    } else {
                                        Modifier
                                    },
                                ),
                            enableDismissFromStartToEnd = false,
                            enableDismissFromEndToStart = !isDragging,
                            backgroundContent = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(vertical = 3.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.errorContainer)
                                        .padding(end = 22.dp),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = stringResource(R.string.remove_action),
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            },
                        ) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .semantics {
                                        customActions = buildList {
                                            if (index > 0) {
                                                add(CustomAccessibilityAction(moveUpLabel) {
                                                    playerViewModel.move(index, index - 1)
                                                    true
                                                })
                                            }
                                            if (index < state.queue.lastIndex) {
                                                add(CustomAccessibilityAction(moveDownLabel) {
                                                    playerViewModel.move(index, index + 1)
                                                    true
                                                })
                                            }
                                        }
                                    },
                                shape = RoundedCornerShape(16.dp),
                                color = when {
                                    isDragging -> MaterialTheme.colorScheme.surfaceContainerHighest
                                    isCurrent -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.54f)
                                    else -> MaterialTheme.colorScheme.surface
                                },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { playerViewModel.playAt(index) }
                                        .padding(start = 8.dp, end = 2.dp, top = 7.dp, bottom = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    QueueArtwork(
                                        song = song,
                                        isCurrent = isCurrent,
                                        positionMs = state.positionMs,
                                        durationMs = state.durationMs,
                                        musicViewModel = musicViewModel,
                                    )
                                    Spacer(Modifier.size(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                song.title,
                                                modifier = Modifier.weight(1f, fill = false),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                fontWeight = FontWeight.Medium,
                                                color = if (isCurrent) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                },
                                            )
                                            if (isCurrent) {
                                                Spacer(Modifier.size(6.dp))
                                                Text(
                                                    stringResource(R.string.now_playing),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            queueSongSubtitle(song),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(
                                        onClick = {},
                                        modifier = Modifier
                                            .size(48.dp)
                                            .pointerInput(song.id) {
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = {
                                                        val currentIndex = latestQueue.indexOfFirst { it.id == song.id }
                                                        if (currentIndex >= 0) {
                                                            draggedItemIndex = currentIndex
                                                            draggedItemOffset = 0f
                                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        }
                                                    },
                                                    onDragCancel = {
                                                        draggedItemIndex = null
                                                        draggedItemOffset = 0f
                                                    },
                                                    onDragEnd = {
                                                        draggedItemIndex = null
                                                        draggedItemOffset = 0f
                                                    },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        val currentIndex = draggedItemIndex
                                                            ?: return@detectDragGesturesAfterLongPress
                                                        draggedItemOffset += dragAmount.y
                                                        val layoutInfo = listState.layoutInfo
                                                        val draggedInfo = layoutInfo.visibleItemsInfo
                                                            .firstOrNull { it.index == currentIndex }
                                                            ?: return@detectDragGesturesAfterLongPress
                                                        val draggedTop = draggedInfo.offset + draggedItemOffset
                                                        val draggedBottom = draggedTop + draggedInfo.size
                                                        val draggedCenter = (draggedTop + draggedBottom) / 2f
                                                        val targetInfo = layoutInfo.visibleItemsInfo.firstOrNull { itemInfo ->
                                                            itemInfo.index != currentIndex &&
                                                                draggedCenter >= itemInfo.offset &&
                                                                draggedCenter <= itemInfo.offset + itemInfo.size
                                                        }
                                                        if (targetInfo != null) {
                                                            draggedItemOffset += draggedInfo.offset - targetInfo.offset
                                                            playerViewModel.move(currentIndex, targetInfo.index)
                                                            draggedItemIndex = targetInfo.index
                                                        }
                                                        val scrollAmount = when {
                                                            draggedTop < layoutInfo.viewportStartOffset -> -18f
                                                            draggedBottom > layoutInfo.viewportEndOffset -> 18f
                                                            else -> 0f
                                                        }
                                                        if (scrollAmount != 0f) {
                                                            scope.launch { listState.scrollBy(scrollAmount) }
                                                        }
                                                    },
                                                )
                                            },
                                    ) {
                                        Icon(
                                            Icons.Rounded.DragHandle,
                                            contentDescription = stringResource(R.string.drag_to_reorder),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text(stringResource(R.string.clear_queue_question)) },
            text = { Text(stringResource(R.string.clear_queue_explanation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmation = false
                        val undoToken = playerViewModel.clearQueueUndoable()
                        if (undoToken != null) {
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = queueClearedMessage,
                                    actionLabel = undoLabel,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    playerViewModel.undoQueueMutation(undoToken)
                                } else {
                                    playerViewModel.discardQueueUndo(undoToken)
                                }
                            }
                        }
                    },
                ) {
                    Text(
                        stringResource(R.string.clear_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun QueueModeSelector(
    mode: PlaybackMode,
    onMode: (PlaybackMode) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.playback_method),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    mode.label(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PlaybackMode.entries.forEach { candidate ->
                    val selected = candidate == mode
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.Transparent
                                },
                            )
                            .selectable(
                                selected = selected,
                                onClick = { onMode(candidate) },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 2.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            candidate.icon(),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (selected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            candidate.queueLabel(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueArtwork(
    song: com.cleartune.core.model.Song,
    isCurrent: Boolean,
    positionMs: Long,
    durationMs: Long,
    musicViewModel: MusicViewModel,
) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(12.dp)),
    ) {
        CoverArt(
            id = song.coverArtId,
            description = song.title,
            viewModel = musicViewModel,
            modifier = Modifier.fillMaxSize(),
            fallbackSeed = song.id,
            requestSize = 144,
        )
        if (isCurrent) {
            val fallbackDuration = song.durationSeconds * 1_000
            val duration = durationMs.takeIf { it > 0 } ?: fallbackDuration
            val progress = if (duration > 0) {
                (positionMs.toFloat() / duration).coerceIn(0f, 1f)
            } else {
                0f
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.34f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.onPrimary),
                )
            }
        }
    }
}

@Composable
private fun PlaybackMode.queueLabel(): String = stringResource(when (this) {
    PlaybackMode.SEQUENTIAL -> R.string.queue_mode_sequential
    PlaybackMode.REPEAT_ALL -> R.string.queue_mode_repeat_all
    PlaybackMode.REPEAT_ONE -> R.string.queue_mode_repeat_one
    PlaybackMode.SHUFFLE -> R.string.queue_mode_shuffle
})

private fun queueSongSubtitle(song: com.cleartune.core.model.Song): String {
    val artist = song.displayArtistName()
    val duration = song.durationSeconds.takeIf { it > 0 }?.let { formatTime(it * 1_000) }
    return listOfNotNull(artist, duration).joinToString(" · ")
}

@Composable
private fun formatQueueDuration(durationSeconds: Long): String {
    val minutes = durationSeconds.coerceAtLeast(0) / 60
    val hours = minutes / 60
    return if (hours > 0) {
        stringResource(R.string.queue_duration_hours_minutes, hours, minutes % 60)
    } else {
        stringResource(R.string.queue_duration_minutes, minutes)
    }
}

@Composable
private fun ClearTuneTonalIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        ),
        content = content,
    )
}

@Composable
private fun PlaybackMode.label(): String = stringResource(when (this) {
    PlaybackMode.SEQUENTIAL -> R.string.mode_sequential
    PlaybackMode.REPEAT_ALL -> R.string.mode_repeat_all
    PlaybackMode.REPEAT_ONE -> R.string.mode_repeat_one
    PlaybackMode.SHUFFLE -> R.string.mode_shuffle
})

private fun PlaybackMode.icon(): ImageVector = when (this) {
    PlaybackMode.SEQUENTIAL -> Icons.AutoMirrored.Rounded.PlaylistPlay
    PlaybackMode.REPEAT_ALL -> Icons.Rounded.Repeat
    PlaybackMode.REPEAT_ONE -> Icons.Rounded.RepeatOne
    PlaybackMode.SHUFFLE -> Icons.Rounded.Shuffle
}

private fun formatTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
