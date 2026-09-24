package com.cleartune.core.player

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.Equalizer
import android.net.ConnectivityManager
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.cleartune.core.datastore.AppPreferences
import com.cleartune.core.datastore.DEFAULT_PLAYBACK_CACHE_SIZE_MB
import com.cleartune.core.datastore.EQUALIZER_FREQUENCIES_HZ
import com.cleartune.core.datastore.EqualizerSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File
import kotlin.math.roundToInt

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibraryService.MediaLibrarySession
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var playbackCache: SimpleCache? = null
    private var cacheEvictor: ResizableLeastRecentlyUsedCacheEvictor? = null
    private var volumeNormalizationEnabled = true
    private var equalizerSettings = EqualizerSettings()
    private var audioSessionId = C.AUDIO_SESSION_ID_UNSET
    private var equalizer: Equalizer? = null
    private var equalizerTransitionJob: Job? = null
    private var replayGainVolume = 1f
    private var equalizerHeadroom = 1f
    private var forcedNext: ForcedNext? = null
    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            forcedNext?.let { pending ->
                if (mediaItem?.mediaId == pending.targetMediaId || mediaItem?.mediaId != pending.sourceMediaId) {
                    restorePlaybackModeAfterForcedNext()
                }
            }
            applyReplayGain()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val targetId = forcedNext?.targetMediaId ?: return
            if ((0 until player.mediaItemCount).none { player.getMediaItemAt(it).mediaId == targetId }) {
                restorePlaybackModeAfterForcedNext()
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = applyReplayGain()

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            if (this@PlaybackService.audioSessionId == audioSessionId) return
            releaseEqualizer()
            this@PlaybackService.audioSessionId = audioSessionId
            applyEqualizer(animate = false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeService = this
        val directDataSourceFactory = DefaultDataSource.Factory(this)
        val playbackDataSourceFactory = runCatching<androidx.media3.datasource.DataSource.Factory> {
            val evictor = ResizableLeastRecentlyUsedCacheEvictor(
                DEFAULT_PLAYBACK_CACHE_SIZE_MB.toBytes(),
            )
            val cache = SimpleCache(
                File(filesDir, PLAYBACK_CACHE_DIRECTORY_NAME),
                evictor,
                StandaloneDatabaseProvider(this),
            )
            cacheEvictor = evictor
            playbackCache = cache
            val connectivity = getSystemService(ConnectivityManager::class.java)
            PlaybackDataSourceFactory(
                cache = cache,
                directFactory = directDataSourceFactory,
                isOffline = {
                    // A LAN music server can work without public Internet validation.
                    connectivity.activeNetwork == null
                },
                reviseKey = { AudioFileRevision.cacheKey(this, it) },
            )
        }.onFailure {
            Log.w(TAG, "Playback cache is unavailable; continuing without disk cache", it)
        }.getOrDefault(directDataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(playbackDataSourceFactory))
            .build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
            addListener(playerListener)
        }
        val sessionPlayer = object : ForwardingPlayer(player) {
            // Headsets and system controls must skip tracks just like the in-app button,
            // including doing nothing at the start of a non-looping queue.
            override fun seekToPrevious() = seekToPreviousMediaItem()

            // Keep controllers' predicted seek behavior consistent with the service.
            override fun getMaxSeekToPreviousPosition(): Long = Long.MAX_VALUE
        }
        val sessionBuilder = MediaLibraryService.MediaLibrarySession.Builder(
            this,
            sessionPlayer,
            object : MediaLibraryService.MediaLibrarySession.Callback {},
        )
        packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            sessionBuilder.setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        session = sessionBuilder.build()
        val settingsFlow = AppPreferences(this@PlaybackService).settings
        playbackScope.launch {
            settingsFlow
                .collect { settings ->
                    volumeNormalizationEnabled = settings.volumeNormalizationEnabled
                    equalizerSettings = settings.equalizer
                    applyReplayGain()
                    applyEqualizer(animate = true)
                }
        }
        cacheScope.launch {
            settingsFlow
                .map { it.playbackCacheSizeMb }
                .distinctUntilChanged()
                .collect { sizeMb ->
                    val cache = playbackCache ?: return@collect
                    cacheEvictor?.resize(cache, sizeMb.toBytes())
                }
        }
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaLibraryService.MediaLibrarySession = session

    override fun onDestroy() {
        if (activeService === this) activeService = null
        playbackScope.cancel()
        cacheScope.cancel()
        releaseEqualizer()
        player.removeListener(playerListener)
        session.release()
        player.release()
        runCatching { playbackCache?.release() }
            .onFailure { Log.w(TAG, "Could not close playback cache cleanly", it) }
        super.onDestroy()
    }

    private fun applyReplayGain() {
        if (!::player.isInitialized) return
        val values = ReplayGainMetadata.values(player.currentMediaItem?.mediaMetadata?.extras)
        val preferAlbum = values.albumSequence && !player.shuffleModeEnabled
        replayGainVolume = ReplayGainNormalizer.volume(
            replayGain = values.replayGain,
            enabled = volumeNormalizationEnabled,
            preferAlbum = preferAlbum,
            headroomDb = if (preferAlbum) values.albumHeadroomDb else values.trackHeadroomDb,
        )
        applyOutputVolume()
    }

    private fun forceNext(mediaId: String, item: MediaItem?): Boolean {
        if (!::player.isInitialized) return false
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex !in 0 until player.mediaItemCount) return false
        val sourceId = player.currentMediaItem?.mediaId ?: return false
        if (sourceId == mediaId) return false

        val existingIndex = (0 until player.mediaItemCount)
            .firstOrNull { player.getMediaItemAt(it).mediaId == mediaId }
        when {
            existingIndex == null && item == null -> return false
            existingIndex == null -> player.addMediaItem(currentIndex + 1, item!!)
            existingIndex != currentIndex + 1 -> {
                val targetIndex = if (existingIndex < currentIndex) currentIndex else currentIndex + 1
                player.moveMediaItem(existingIndex, targetIndex)
            }
        }

        val pending = forcedNext
        forcedNext = ForcedNext(
            sourceMediaId = sourceId,
            targetMediaId = mediaId,
            restoreShuffle = pending?.restoreShuffle ?: player.shuffleModeEnabled,
            restoreRepeatMode = pending?.restoreRepeatMode ?: player.repeatMode,
        )
        // Media3 keeps a separate randomized traversal order, and repeat-one prevents
        // automatic transitions entirely. Use the physical next item for this one hop,
        // then restore the user's selected mode as soon as the target starts.
        player.shuffleModeEnabled = false
        if (player.repeatMode == Player.REPEAT_MODE_ONE) player.repeatMode = Player.REPEAT_MODE_OFF
        return true
    }

    private fun applyRequestedPlaybackMode(shuffle: Boolean, repeatMode: Int) {
        val pending = forcedNext
        if (pending == null) {
            player.shuffleModeEnabled = shuffle
            player.repeatMode = repeatMode
            return
        }
        forcedNext = pending.copy(restoreShuffle = shuffle, restoreRepeatMode = repeatMode)
        player.shuffleModeEnabled = false
        player.repeatMode = if (repeatMode == Player.REPEAT_MODE_ONE) {
            Player.REPEAT_MODE_OFF
        } else {
            repeatMode
        }
    }

    private fun restorePlaybackModeAfterForcedNext() {
        val pending = forcedNext ?: return
        forcedNext = null
        player.shuffleModeEnabled = pending.restoreShuffle
        player.repeatMode = pending.restoreRepeatMode
    }

    private fun applyEqualizer(animate: Boolean) {
        if (!::player.isInitialized) return
        if (!equalizerSettings.enabled && equalizer == null) {
            equalizerHeadroom = 1f
            applyOutputVolume()
            return
        }

        val currentSessionId = player.audioSessionId
        if (currentSessionId <= 0) return
        if (audioSessionId != currentSessionId) {
            releaseEqualizer()
            audioSessionId = currentSessionId
        }

        val effect = equalizer ?: runCatching { Equalizer(0, currentSessionId) }
            .onFailure { Log.w(TAG, "Equalizer is unavailable for this audio session", it) }
            .getOrNull()
            ?.also { equalizer = it }
            ?: return

        if (!effect.hasControl()) {
            Log.w(TAG, "System equalizer control is held by another audio effect")
            return
        }

        val anchorLevels = if (equalizerSettings.enabled) {
            equalizerSettings.activeLevelsDb
        } else {
            List(EQUALIZER_FREQUENCIES_HZ.size) { 0 }
        }
        val plan = runCatching {
            val range = effect.bandLevelRange
            val deviceRange = range[0].toInt()..range[1].toInt()
            List(effect.numberOfBands.toInt()) { index ->
                val band = index.toShort()
                val centerHz = effect.getCenterFreq(band) / 1_000
                val levelDb = interpolatedEqualizerLevelDb(
                    anchorFrequenciesHz = EQUALIZER_FREQUENCIES_HZ,
                    anchorLevelsDb = anchorLevels,
                    frequencyHz = centerHz.coerceAtLeast(1),
                )
                EqualizerBandPlan(
                    band = band,
                    targetMillibels = EqualizerMath.millibels(levelDb, deviceRange),
                )
            }
        }.onFailure {
            handleEqualizerFailure(it)
        }.getOrNull() ?: return

        val targetHeadroom = EqualizerMath.headroomMultiplier(
            plan.map { it.targetMillibels.toInt() / 100f },
        )
        val startLevels = runCatching {
            plan.map { effect.getBandLevel(it.band) }
        }.onFailure {
            handleEqualizerFailure(it)
        }.getOrNull() ?: return

        equalizerTransitionJob?.cancel()
        equalizerTransitionJob = playbackScope.launch {
            try {
                if (!effect.enabled) effect.enabled = true
                equalizerHeadroom = minOf(equalizerHeadroom, targetHeadroom)
                applyOutputVolume()
                val steps = if (animate) EQUALIZER_TRANSITION_STEPS else 1
                repeat(steps) { step ->
                    val progress = (step + 1f) / steps
                    plan.forEachIndexed { index, target ->
                        val start = startLevels[index].toInt()
                        val level = (start + (target.targetMillibels.toInt() - start) * progress)
                            .roundToInt()
                            .toShort()
                        effect.setBandLevel(target.band, level)
                    }
                    if (step < steps - 1) delay(EQUALIZER_TRANSITION_DURATION_MS / steps)
                }
                if (!equalizerSettings.enabled) effect.enabled = false
                equalizerHeadroom = targetHeadroom
                applyOutputVolume()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                handleEqualizerFailure(error)
            }
        }
    }

    private fun applyOutputVolume() {
        if (::player.isInitialized) {
            player.volume = (replayGainVolume * equalizerHeadroom).coerceIn(0f, 1f)
        }
    }

    private fun releaseEqualizer() {
        equalizerTransitionJob?.cancel()
        equalizerTransitionJob = null
        runCatching { equalizer?.enabled = false }
        runCatching { equalizer?.release() }
        equalizer = null
    }

    private fun handleEqualizerFailure(error: Throwable) {
        Log.w(TAG, "Could not apply system equalizer settings", error)
        releaseEqualizer()
        equalizerHeadroom = 1f
        applyOutputVolume()
    }

    companion object {
        private var activeService: PlaybackService? = null

        internal fun forceNext(mediaId: String, item: MediaItem?): Boolean =
            activeService?.forceNext(mediaId, item) == true

        internal fun applyPlaybackMode(shuffle: Boolean, repeatMode: Int): Boolean {
            val service = activeService ?: return false
            service.applyRequestedPlaybackMode(shuffle, repeatMode)
            return true
        }

        fun isPlaybackActive(): Boolean = activeService?.player?.let { player ->
            player.playWhenReady && player.playbackState != Player.STATE_IDLE &&
                player.playbackState != Player.STATE_ENDED
        } == true

        /** Called on main. Release readers before changing tags, then reopen at the same position. */
        fun suspendForTagWrite(): () -> Unit {
            val service = activeService ?: return {}
            val player = service.player
            val item = player.currentMediaItem
            val index = player.currentMediaItemIndex
            val position = player.currentPosition
            val resume = player.playWhenReady
            player.pause()
            player.stop()
            return {
                if (activeService === service && player.currentMediaItem == item && player.currentMediaItemIndex == index) {
                    if (index >= 0) player.seekTo(index, position)
                    player.prepare()
                    player.playWhenReady = resume
                }
            }
        }

        const val TAG = "ClearTunePlayback"
        const val BYTES_PER_MEGABYTE = 1_024L * 1_024L
        const val EQUALIZER_TRANSITION_STEPS = 8
        const val EQUALIZER_TRANSITION_DURATION_MS = 96L

        fun Int.toBytes(): Long = toLong() * BYTES_PER_MEGABYTE
    }
}

private data class ForcedNext(
    val sourceMediaId: String,
    val targetMediaId: String,
    val restoreShuffle: Boolean,
    val restoreRepeatMode: Int,
)

private data class EqualizerBandPlan(
    val band: Short,
    val targetMillibels: Short,
)

const val PLAYBACK_CACHE_DIRECTORY_NAME = "playback_cache"
