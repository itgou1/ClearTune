package com.cleartune.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession

@OptIn(markerClass = [UnstableApi::class])
class ClearTunePlaybackService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private lateinit var playerCache: PlayerCache
    private var librarySession: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val headersProvider = (application as? PlaybackRequestHeadersOwner)?.playbackRequestHeadersProvider
            ?: PlaybackRequestHeadersProvider { emptyMap() }
        playerCache = PlayerCache(this, headersProvider)
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
            .setHandleAudioBecomingNoisy(true)
            .build()

        val catalog = (application as? LibrarySessionCatalogOwner)?.librarySessionCatalog
            ?: LibrarySessionCatalog.Empty
        val callback = ClearTuneLibrarySessionCallback(catalog)
        librarySession = MediaLibrarySession.Builder(this, player, callback)
            .apply { launcherPendingIntent()?.let(::setSessionActivity) }
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        librarySession

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        librarySession?.release()
        librarySession = null
        player.release()
        playerCache.close()
        super.onDestroy()
    }

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
