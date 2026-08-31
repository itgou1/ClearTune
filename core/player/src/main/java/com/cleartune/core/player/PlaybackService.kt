package com.cleartune.core.player

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.Equalizer
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
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
    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = applyReplayGain()

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
            PlaybackDataSourceFactory(
                cachedFactory = CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(directDataSourceFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR),
                directFactory = directDataSourceFactory,
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
        val sessionBuilder = MediaLibraryService.MediaLibrarySession.Builder(
            this,
            player,
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

    private companion object {
        const val TAG = "ClearTunePlayback"
        const val BYTES_PER_MEGABYTE = 1_024L * 1_024L
        const val EQUALIZER_TRANSITION_STEPS = 8
        const val EQUALIZER_TRANSITION_DURATION_MS = 96L

        fun Int.toBytes(): Long = toLong() * BYTES_PER_MEGABYTE
    }
}

private data class EqualizerBandPlan(
    val band: Short,
    val targetMillibels: Short,
)

const val PLAYBACK_CACHE_DIRECTORY_NAME = "playback_cache"
