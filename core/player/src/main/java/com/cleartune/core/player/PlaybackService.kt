package com.cleartune.core.player

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.cleartune.core.datastore.AppPreferences
import com.cleartune.core.datastore.AppSettings
import com.cleartune.core.datastore.DEFAULT_PLAYBACK_CACHE_SIZE_MB
import com.cleartune.core.datastore.EqualizerSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.File

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
    private var equalizerTransitionJob: Job? = null
    private var audioSettingsLoaded = false
    private val audioStartupGate = AudioStartupGate { player.playWhenReady = it }
    private var replayGainVolume = 1f
    private var equalizerHeadroom = 1f
    private var equalizerSessionId = 0
    private var audioOutputReady = false
    private val equalizerController = EqualizerController(
        createEffect = ::SystemEqualizer,
        onHeadroom = { headroom ->
            equalizerHeadroom = headroom
            applyOutputVolume()
        },
        onFailure = { Log.w(TAG, "Could not update system equalizer", it) },
    )
    private var forcedNext: ForcedNext? = null
    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Release an endless live connection on pause, including headset/system controls.
            // Preparing it again starts at the live edge rather than replaying buffered audio.
            if (!playWhenReady && player.currentMediaItem?.mediaMetadata?.extras
                    ?.getBoolean("com.cleartune.song.is_radio", false) == true &&
                player.playbackState != Player.STATE_IDLE
            ) {
                player.stop()
                player.seekToDefaultPosition()
            }
        }
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
            if (audioSettingsLoaded && audioSessionId != equalizerSessionId) applyEqualizer(animate = false)
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
            .setMediaSourceFactory(RadioMediaSourceFactory(playbackDataSourceFactory, directDataSourceFactory))
            .build().apply {
            // AudioTrack may be prepared before saved settings/native effects finish.
            // Its very first buffer must never inherit the default full volume.
            volume = 0f
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
        }
        // Bind the effect before the first AudioTrack starts instead of reacting
        // to its creation after audio may already be audible.
        val audioSessionId = getSystemService(AudioManager::class.java).generateAudioSessionId()
        if (audioSessionId > 0) player.audioSessionId = audioSessionId
        player.addListener(playerListener)
        val sessionPlayer = object : AudioStartupPlayer(player, audioStartupGate) {
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
                .catch { error ->
                    Log.w(TAG, "Could not load audio settings; using defaults", error)
                    emit(AppSettings())
                }
                .map { it.volumeNormalizationEnabled to it.equalizer }
                .distinctUntilChanged()
                .collect { (normalizationEnabled, newEqualizerSettings) ->
                    val equalizerChanged = !audioSettingsLoaded || equalizerSettings != newEqualizerSettings
                    audioSettingsLoaded = true
                    volumeNormalizationEnabled = normalizationEnabled
                    equalizerSettings = newEqualizerSettings
                    applyReplayGain()
                    if (equalizerChanged) applyEqualizer(animate = true)
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
        audioStartupGate.close()
        playbackScope.cancel()
        cacheScope.cancel()
        equalizerController.close()
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
        val sessionId = player.audioSessionId
        val initializing = !audioStartupGate.isReady || sessionId != equalizerSessionId || !audioOutputReady
        if (initializing) {
            audioOutputReady = false
            applyOutputVolume()
        }
        equalizerSessionId = sessionId
        val transition = equalizerController.update(sessionId, equalizerSettings, animate && !initializing)
        equalizerTransitionJob = transition
        playbackScope.launch {
            transition.join()
            // A newer settings/session update owns readiness. Native failures are
            // handled by the controller, so unsupported effects do not block play.
            if (equalizerTransitionJob === transition && (!equalizerSettings.enabled || sessionId > 0)) {
                audioOutputReady = true
                applyOutputVolume()
                audioStartupGate.open()
            }
        }
    }

    private fun applyOutputVolume() {
        if (::player.isInitialized) {
            player.volume = if (audioOutputReady) {
                (replayGainVolume * equalizerHeadroom).coerceIn(0f, 1f)
            } else 0f
        }
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

        fun Int.toBytes(): Long = toLong() * BYTES_PER_MEGABYTE
    }
}

private data class ForcedNext(
    val sourceMediaId: String,
    val targetMediaId: String,
    val restoreShuffle: Boolean,
    val restoreRepeatMode: Int,
)

const val PLAYBACK_CACHE_DIRECTORY_NAME = "playback_cache"
