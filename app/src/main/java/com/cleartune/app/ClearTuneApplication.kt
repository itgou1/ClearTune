package com.cleartune.app

import android.app.Application
import com.cleartune.playback.PlaybackRequestHeadersOwner
import com.cleartune.playback.PlaybackRequestHeadersProvider

class ClearTuneApplication : Application(), PlaybackRequestHeadersOwner {
    lateinit var container: AppContainer
        private set

    override val playbackRequestHeadersProvider: PlaybackRequestHeadersProvider
        get() = container.playbackRequestHeadersProvider

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun onTerminate() {
        container.close()
        super.onTerminate()
    }
}
