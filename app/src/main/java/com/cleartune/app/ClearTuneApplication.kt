package com.cleartune.app

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.cleartune.data.download.DownloadWorkerHost
import com.cleartune.data.download.DownloadWorkerRunner
import com.cleartune.data.webdav.WebDavSyncRunner
import com.cleartune.data.webdav.WebDavSyncWorkerHost
import com.cleartune.playback.LibrarySessionCatalog
import com.cleartune.playback.LibrarySessionCatalogOwner
import com.cleartune.playback.PlaybackRequestHeadersOwner
import com.cleartune.playback.PlaybackRequestHeadersProvider

class ClearTuneApplication :
    Application(),
    PlaybackRequestHeadersOwner,
    LibrarySessionCatalogOwner,
    DownloadWorkerHost,
    WebDavSyncWorkerHost,
    Configuration.Provider {
    lateinit var container: AppContainer
        private set

    private val delegatingWorkerFactory = object : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? = if (::container.isInitialized) {
            container.workerFactory.createWorker(appContext, workerClassName, workerParameters)
        } else {
            null
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(delegatingWorkerFactory).build()

    override val playbackRequestHeadersProvider: PlaybackRequestHeadersProvider
        get() = container.playbackRequestHeadersProvider

    override val librarySessionCatalog: LibrarySessionCatalog
        get() = container.librarySessionCatalog

    override val downloadWorkerRunner: DownloadWorkerRunner
        get() = container.downloadWorkerRunner

    override val webDavSyncRunner: WebDavSyncRunner
        get() = container.webDavSyncRunner

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.scheduleStartupWork()
    }

    override fun onTerminate() {
        if (::container.isInitialized) container.close()
        super.onTerminate()
    }
}
