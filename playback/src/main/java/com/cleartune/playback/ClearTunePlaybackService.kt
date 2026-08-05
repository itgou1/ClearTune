package com.cleartune.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(markerClass = [UnstableApi::class])
class ClearTunePlaybackService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private lateinit var playerCache: PlayerCache
    private lateinit var servicePolicy: PlaybackServicePolicy
    private lateinit var runtimeSettingsProvider: PlaybackRuntimeSettingsProvider
    private lateinit var currentRuntimeSettings: PlaybackRuntimeSettings
    private lateinit var requestHeadersProvider: PlaybackRequestHeadersProvider
    private lateinit var credentialResolver: PlaybackCredentialResolver
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var librarySession: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        requestHeadersProvider = (application as? PlaybackRequestHeadersOwner)?.playbackRequestHeadersProvider
            ?: PlaybackRequestHeadersProvider { emptyMap() }
        credentialResolver = (application as? PlaybackCredentialResolverOwner)?.playbackCredentialResolver
            ?: PlaybackCredentialResolver { null }
        runtimeSettingsProvider = (application as? PlaybackRuntimeSettingsOwner)
            ?.playbackRuntimeSettingsProvider
            ?: MutablePlaybackRuntimeSettingsProvider()
        val runtimeSettings = runtimeSettingsProvider.snapshot()
        currentRuntimeSettings = runtimeSettings
        servicePolicy = PlaybackServicePolicy(runtimeSettingsProvider)
        createPlaybackStack(runtimeSettings)

        serviceScope.launch {
            runtimeSettingsProvider.updates.distinctUntilChanged().collect { settings ->
                if (settings != currentRuntimeSettings) applyRuntimeSettings(settings)
            }
        }
    }

    private fun createPlaybackStack(runtimeSettings: PlaybackRuntimeSettings, restore: PlayerRestore? = null) {
        playerCache = PlayerCache(
            context = this,
            settings = runtimeSettings,
            credentialResolver = credentialResolver,
            headersProvider = requestHeadersProvider,
        )
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this).setDataSourceFactory(playerCache.dataSourceFactory()),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(servicePolicy.handleAudioBecomingNoisy)
            .build()

        restore?.let { state ->
            if (state.items.isNotEmpty()) {
                player.setMediaItems(state.items, state.currentIndex.coerceIn(state.items.indices), state.positionMs)
                player.repeatMode = state.repeatMode
                player.shuffleModeEnabled = state.shuffleEnabled
                player.prepare()
                player.playWhenReady = state.playWhenReady
            }
        }

        val catalog = (application as? LibrarySessionCatalogOwner)?.librarySessionCatalog
            ?: LibrarySessionCatalog.Empty
        val callback = ClearTuneLibrarySessionCallback(catalog)
        librarySession = MediaLibrarySession.Builder(this, player, callback)
            .apply { launcherPendingIntent()?.let(::setSessionActivity) }
            .build()
    }

    private fun applyRuntimeSettings(settings: PlaybackRuntimeSettings) {
        player.setHandleAudioBecomingNoisy(settings.pauseOnHeadphoneDisconnect)
        if (cacheConfigurationChanged(currentRuntimeSettings, settings)) {
            val restore = PlayerRestore(
                items = (0 until player.mediaItemCount).map(player::getMediaItemAt),
                currentIndex = player.currentMediaItemIndex.coerceAtLeast(0),
                positionMs = player.currentPosition.coerceAtLeast(0),
                playWhenReady = player.playWhenReady,
                repeatMode = player.repeatMode,
                shuffleEnabled = player.shuffleModeEnabled,
            )
            librarySession?.release()
            librarySession = null
            player.release()
            playerCache.close()
            createPlaybackStack(settings, restore)
        }
        currentRuntimeSettings = settings
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        librarySession

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (servicePolicy.shouldStopOnTaskRemoved(player.playWhenReady, player.mediaItemCount)) {
            player.pause()
            stopSelf()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        librarySession?.release()
        librarySession = null
        player.release()
        playerCache.close()
        super.onDestroy()
    }

    private data class PlayerRestore(
        val items: List<MediaItem>,
        val currentIndex: Int,
        val positionMs: Long,
        val playWhenReady: Boolean,
        val repeatMode: Int,
        val shuffleEnabled: Boolean,
    )

    private fun launcherPendingIntent(): PendingIntent? =
        packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
}
