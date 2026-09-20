package com.cleartune.app.player

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.cleartune.app.auth.AccountSession
import com.cleartune.core.database.DatabaseFactory
import com.cleartune.core.database.DownloadEntity
import com.cleartune.core.datastore.AppPreferences
import com.cleartune.core.model.ServerCredentials
import com.cleartune.core.model.ServerProfile
import com.cleartune.core.model.Song
import com.cleartune.core.model.accountStorageKey
import com.cleartune.core.datastore.MobileAudioQuality
import com.cleartune.core.network.OpenSubsonicApiFactory
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaybackSourcePriorityTest {
    @Test fun mobileBitrateSurvivesOfflineRepositoryRecreation() = fixture { f ->
        f.preferences.setMobileAudioQuality(MobileAudioQuality.RATE_192)
        assertEquals("192", f.stream(PlaybackNetwork.MOBILE).getQueryParameter("maxBitRate"))
        val restored = PlaybackRepository(f.context, f.session, OpenSubsonicApiFactory(), AppPreferences(f.context))
        val offline = restored.urls(listOf(f.song), PlaybackNetwork.OFFLINE)!!
        assertEquals("192", Uri.parse(offline.streams.getValue(f.song.id)).getQueryParameter("maxBitRate"))
        f.preferences.setMobileAudioQuality(MobileAudioQuality.RATE_128)
        assertEquals("128", f.stream(PlaybackNetwork.OFFLINE).getQueryParameter("maxBitRate"))
    }

    @Test fun wifiOriginalAndMobileRawBothKeepTheirFormatOffline() = fixture { f ->
        f.preferences.setMobileAudioQuality(MobileAudioQuality.ORIGINAL)
        assertEquals("raw", f.stream(PlaybackNetwork.MOBILE).getQueryParameter("format"))
        assertEquals("raw", f.stream(PlaybackNetwork.OFFLINE).getQueryParameter("format"))
        assertNull(f.stream(PlaybackNetwork.OTHER).getQueryParameter("format"))
        f.preferences.setMobileAudioQuality(MobileAudioQuality.RATE_320)
        assertNull(f.stream(PlaybackNetwork.OFFLINE).getQueryParameter("maxBitRate"))
        assertNull(f.stream(PlaybackNetwork.OFFLINE).getQueryParameter("format"))
    }

    @Test fun validDownloadWinsOnEveryNetworkAndDoesNotChangeStreamingPreference() = fixture { f ->
        f.preferences.setMobileAudioQuality(MobileAudioQuality.RATE_192)
        f.stream(PlaybackNetwork.MOBILE)
        f.download()
        for (network in PlaybackNetwork.entries) {
            assertEquals(Uri.fromFile(f.file), f.stream(network))
        }
        assertEquals(true, f.preferences.lastPlaybackUsedMobileQuality(f.session.accountKey))
        assertEquals("COMPLETED", f.session.database.downloadDao().forSong(f.song.id)!!.state)
    }

    @Test fun missingOrTruncatedDownloadFallsBackToCurrentBitrate() = fixture { f ->
        f.preferences.setMobileAudioQuality(MobileAudioQuality.RATE_192)
        f.stream(PlaybackNetwork.MOBILE)
        f.download()
        f.file.delete()
        assertEquals("192", f.stream(PlaybackNetwork.OFFLINE).getQueryParameter("maxBitRate"))
        assertEquals("FAILED", f.session.database.downloadDao().forSong(f.song.id)!!.state)
        f.download()
        f.file.writeBytes(byteArrayOf(1))
        assertEquals("192", f.stream(PlaybackNetwork.OFFLINE).getQueryParameter("maxBitRate"))
        assertEquals("FAILED", f.session.database.downloadDao().forSong(f.song.id)!!.state)
    }

    @Test fun unfinishedDownloadNeverOverridesPlaybackCache() = fixture { f ->
        f.preferences.setMobileAudioQuality(MobileAudioQuality.RATE_320)
        f.download(state = "PAUSED")
        assertEquals("320", f.stream(PlaybackNetwork.OFFLINE).getQueryParameter("maxBitRate"))
        assertEquals("PAUSED", f.session.database.downloadDao().forSong(f.song.id)!!.state)
    }

    @Test fun lastStreamingNetworkIsAccountScoped() = fixture { f ->
        f.preferences.setMobileAudioQuality(MobileAudioQuality.RATE_128)
        f.preferences.setLastPlaybackUsedMobileQuality("other-${f.session.accountKey}", false)
        assertEquals("128", f.stream(PlaybackNetwork.OFFLINE).getQueryParameter("maxBitRate"))
        f.stream(PlaybackNetwork.MOBILE)
        assertEquals(false, f.preferences.lastPlaybackUsedMobileQuality("other-${f.session.accountKey}"))
    }

    @Test fun revokedAccountCannotPlayItsDownload() = fixture { f ->
        f.download()
        f.session.revoke()
        assertNull(f.repository.urls(listOf(f.song), PlaybackNetwork.OFFLINE))
    }

    private fun fixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val f = Fixture()
        val originalQuality = f.preferences.settings.first().mobileAudioQuality
        try { block(f) } finally {
            f.preferences.setMobileAudioQuality(originalQuality)
            f.session.database.close()
            f.context.deleteDatabase("cleartune_account_${f.session.accountKey}.db")
            f.file.delete()
        }
    }

    private class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = AppPreferences(context)
        private val url = "https://${UUID.randomUUID()}.invalid/"
        private val credentials = ServerCredentials(url, "offline-test", "test")
        val session = AccountSession(credentials,
            ServerProfile(url, "offline-test", "test", "1", "1.16.1", true), "test",
            DatabaseFactory.create(context, accountStorageKey(url, "offline-test")))
        val repository = PlaybackRepository(context, session, OpenSubsonicApiFactory(), preferences)
        val song = Song("song", "Offline priority test")
        val file = File(context.cacheDir, "priority-${UUID.randomUUID()}.wav")

        suspend fun stream(network: PlaybackNetwork): Uri =
            Uri.parse(repository.urls(listOf(song), network)!!.streams.getValue(song.id))

        suspend fun download(state: String = "COMPLETED") {
            file.writeBytes(ByteArray(100) { 1 })
            session.database.downloadDao().upsert(DownloadEntity(
                requestId = "test", songId = song.id, state = state, bytesDownloaded = 100,
                totalBytes = 100, localUri = Uri.fromFile(file).toString(), failureReason = null,
                updatedAt = System.currentTimeMillis(),
            ))
        }
    }
}
