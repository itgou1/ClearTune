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
import com.cleartune.playback.PlaybackCredentialResolver
import com.cleartune.playback.PlaybackCredentialResolverOwner
import com.cleartune.playback.PlaybackRuntimeSettingsOwner
import com.cleartune.playback.PlaybackRuntimeSettingsProvider

class ClearTuneApplication :
    Application(),
    PlaybackCredentialResolverOwner,
    PlaybackRuntimeSettingsOwner,
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

    override val playbackCredentialResolver: PlaybackCredentialResolver
        get() = container.playbackCredentialResolver

    override val playbackRuntimeSettingsProvider: PlaybackRuntimeSettingsProvider
        get() = container.playbackRuntimeSettingsProvider

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
