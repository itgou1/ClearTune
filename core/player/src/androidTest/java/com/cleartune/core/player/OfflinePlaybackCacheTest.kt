package com.cleartune.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class OfflinePlaybackCacheTest {
    @Test fun mobileCachePlaysOfflineAfterRestartWithOriginalQualityUrl() = fixture { f ->
        f.seed(f.key("&maxBitRate=192"))
        f.reopen()
        f.offline = true
        assertArrayEquals(f.audio, f.read(f.key()))
        assertEquals(0, f.upstreamOpens)
    }

    @Test fun rawMobileOriginalCacheAlsoPlaysOffline() = fixture { f ->
        f.seed(f.key("&format=raw"))
        f.offline = true
        assertArrayEquals(f.audio, f.read(f.key()))
        assertEquals(0, f.upstreamOpens)
    }

    @Test fun onlinePlaybackKeepsRequestedQuality() = fixture { f ->
        f.seed(f.key("&maxBitRate=192"))
        assertArrayEquals(f.audio, f.read(f.key()))
        assertEquals(1, f.upstreamOpens)
    }

    @Test fun matchingQualityRemainsPreferred() = fixture { f ->
        val mobile = f.key("&maxBitRate=192")
        val mobileAudio = f.audio.copyOf().apply { this[lastIndex] = 7 }
        f.seed(mobile, audio = mobileAudio)
        f.seed(f.key())
        assertEquals(mobile, offlinePlaybackCacheKey(f.cache, mobile))
        assertEquals(f.key(), offlinePlaybackCacheKey(f.cache, f.key()))
        f.offline = true
        assertArrayEquals(mobileAudio, f.read(mobile))
        assertEquals(0, f.upstreamOpens)
    }

    @Test fun downloadedFileBypassesExistingStreamingCacheOffline() = fixture { f ->
        f.seed(f.key())
        val downloadedAudio = f.audio.copyOf().apply { this[lastIndex] = 9 }
        val download = File(f.directory, "download.wav").apply { writeBytes(downloadedAudio) }
        val source = PlaybackDataSourceFactory(f.cache, DefaultDataSource.Factory(f.context)) { true }
            .createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(Uri.fromFile(download)).build())
            val actual = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                actual.write(buffer, 0, count)
            }
            assertArrayEquals(downloadedAudio, actual.toByteArray())
        } finally {
            source.close()
            download.delete()
        }
    }

    @Test fun incompleteMobileCacheIsNotUsedAsACompleteAlternative() = fixture { f ->
        f.seed(f.key("&maxBitRate=192"), length = 1024)
        f.offline = true
        assertEquals(f.key(), offlinePlaybackCacheKey(f.cache, f.key()))
        assertThrows(IOException::class.java) { f.read(f.key()) }
        assertEquals(0, f.upstreamOpens)
    }

    @Test fun otherAccountsSongsAndServersCannotSupplyOfflineAudio() = fixture { f ->
        f.seed(f.key("&maxBitRate=192", user = "bob"))
        f.seed(f.key("&maxBitRate=192", song = "another-song"))
        f.seed(f.key("&maxBitRate=192").replace("music.invalid", "another.invalid"))
        f.offline = true
        assertEquals(f.key(), offlinePlaybackCacheKey(f.cache, f.key()))
        assertThrows(IOException::class.java) { f.read(f.key()) }
        assertEquals(0, f.upstreamOpens)
    }

    @Test fun exactCachedAudioSurvivesAuthenticationRefresh() = fixture { f ->
        f.seed(playbackCacheKey("song", f.url.replace("t=fresh&s=new", "t=old&s=old"))!!)
        f.offline = true
        assertArrayEquals(f.audio, f.read(f.key()))
        assertEquals(0, f.upstreamOpens)
    }

    @Test fun cachedMobileAudioDecodesAndPlaysToEndOffline() = fixture { f ->
        f.seed(f.key("&maxBitRate=192"))
        f.offline = true
        val done = CountDownLatch(1)
        val failure = AtomicReference<PlaybackException>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var player: ExoPlayer? = null
        try {
            instrumentation.runOnMainSync {
                val activePlayer = ExoPlayer.Builder(f.context)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(f.factory())).build()
                player = activePlayer
                activePlayer.volume = 0f
                activePlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) done.countDown()
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        failure.set(error)
                        done.countDown()
                    }
                })
                activePlayer.setMediaItem(MediaItem.Builder().setUri(f.url).setCustomCacheKey(f.key()).build())
                activePlayer.prepare()
                activePlayer.play()
            }
            assertTrue("Cached audio must play to completion", done.await(15, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertEquals(0, f.upstreamOpens)
        } finally { instrumentation.runOnMainSync { player?.release() } }
    }

    @Test fun disconnectDuringSeekDoesNotMixQualityByteOffsets() = fixture { f ->
        f.seed(f.key("&maxBitRate=192"))
        val source = f.factory().createDataSource()
        val request = DataSpec.Builder().setUri(f.url).setKey(f.key()).build()
        try {
            source.open(request)
            source.read(ByteArray(1024), 0, 1024)
            source.close()
            f.offline = true
            assertThrows(IOException::class.java) { source.open(request.subrange(2048)) }
            assertEquals(1, f.upstreamOpens)
        } finally { source.close() }
    }

    @Test fun evictedAlternativeCannotBeRefilledWithOriginalQualityBytes() = fixture { f ->
        val mobile = f.key("&maxBitRate=192")
        f.seed(mobile)
        f.offline = true
        val source = f.factory().createDataSource()
        val request = DataSpec.Builder().setUri(f.url).setKey(f.key()).build()
        try {
            source.open(request)
            source.read(ByteArray(1024), 0, 1024)
            source.close()
            f.offline = false
            f.cache.removeResource(mobile)
            assertThrows(IOException::class.java) { source.open(request.subrange(2048)) }
            assertEquals(0, f.upstreamOpens)
        } finally { source.close() }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val f = Fixture()
        try { block(f) } finally { f.close() }
    }

    private class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "offline-playback-${UUID.randomUUID()}")
        val provider = StandaloneDatabaseProvider(context)
        var cache = SimpleCache(directory, NoOpCacheEvictor(), provider)
        // One second of silent PCM WAV, usable by the actual ExoPlayer decoder.
        val audio = ByteBuffer.allocate(32_044).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(32_036); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(16_000); putInt(32_000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(32_000)
        }.array()
        var offline = false
        var upstreamOpens = 0
        val url = "https://music.invalid/rest/stream.view?u=alice&id=song&t=fresh&s=new"

        fun key(quality: String = "", song: String = "song", user: String = "alice"): String =
            playbackCacheKey(song, url.replace("u=alice", "u=$user") + quality)!!

        fun seed(key: String, audio: ByteArray = this.audio, length: Int = audio.size) {
            val source = CacheDataSource.Factory().setCache(cache)
                .setUpstreamDataSourceFactory { ByteArrayDataSource(audio) }.createDataSource()
            try {
                source.open(DataSpec.Builder().setUri(url).setKey(key).build())
                val buffer = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val count = source.read(buffer, offset, length - offset)
                    check(count != C.RESULT_END_OF_INPUT)
                    offset += count
                }
            } finally { source.close() }
        }

        fun reopen() {
            cache.release()
            cache = SimpleCache(directory, NoOpCacheEvictor(), provider)
        }

        fun factory(): DataSource.Factory {
            val upstream = DataSource.Factory {
                val bytes = ByteArrayDataSource(audio)
                object : DataSource by bytes {
                    override fun open(dataSpec: DataSpec): Long {
                        upstreamOpens++
                        if (offline) throw IOException("Test network is disconnected")
                        return bytes.open(dataSpec)
                    }
                }
            }
            return PlaybackDataSourceFactory(
                cache = cache,
                directFactory = upstream,
                isOffline = { offline },
            )
        }

        fun read(key: String): ByteArray {
            val source = factory().createDataSource()
            try {
                source.open(DataSpec.Builder().setUri(Uri.parse(url)).setKey(key).build())
                val result = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = source.read(buffer, 0, buffer.size)
                    if (count == C.RESULT_END_OF_INPUT) break
                    result.write(buffer, 0, count)
                }
                return result.toByteArray()
            } finally { source.close() }
        }

        fun close() {
            cache.release()
            provider.close()
            directory.deleteRecursively()
        }
    }
}
