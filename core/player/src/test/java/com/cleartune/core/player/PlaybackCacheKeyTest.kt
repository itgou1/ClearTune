package com.cleartune.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackCacheKeyTest {
    @Test
    fun authenticationChangesDoNotInvalidateCachedAudio() {
        val first = playbackCacheKey(
            "song-1",
            "https://music.example/rest/stream?id=song-1&u=a&t=old&s=one&v=1.16.1&c=ClearTune",
        )
        val refreshed = playbackCacheKey(
            "song-1",
            "https://music.example/rest/stream?id=song-1&u=a&t=new&s=two&v=1.16.1&c=ClearTune",
        )

        assertEquals(first, refreshed)
    }

    @Test
    fun serverSongAndQualityRemainDistinct() {
        val original = playbackCacheKey("song-1", "https://one.example/rest/stream?id=song-1&u=a")
        val transcoded = playbackCacheKey(
            "song-1",
            "https://one.example/rest/stream?id=song-1&u=a&maxBitRate=192&format=mp3",
        )
        val otherSong = playbackCacheKey("song-2", "https://one.example/rest/stream?id=song-2&u=a")
        val otherServer = playbackCacheKey("song-1", "https://two.example/rest/stream?id=song-1&u=a")

        assertNotEquals(original, transcoded)
        assertNotEquals(original, otherSong)
        assertNotEquals(original, otherServer)
    }

    @Test
    fun localDownloadsDoNotEnterPlaybackCache() {
        assertNull(playbackCacheKey("song-1", "file:///data/user/0/app/files/offline/song.mp3"))
    }

    @Test
    fun accountsOnTheSameServerNeverShareCachedAudio() {
        assertNotEquals(
            playbackCacheKey("song-1", "https://music.example/rest/stream?id=song-1&u=alice"),
            playbackCacheKey("song-1", "https://music.example/rest/stream?id=song-1&u=bob"),
        )
        assertNull(playbackCacheKey("song-1", "https://music.example/rest/stream?id=song-1"))
    }

    @Test
    fun serverSubpathsAndCaseSensitiveUsernamesRemainIsolated() {
        val original = playbackCacheKey("song", "https://music.example/one/rest/stream?u=Alice")
        assertNotEquals(original, playbackCacheKey("song", "https://music.example/two/rest/stream?u=Alice"))
        assertNotEquals(original, playbackCacheKey("song", "https://music.example/one/rest/stream?u=alice"))
        assertNotEquals(original, playbackCacheKey("song", "https://music.example/one/rest/stream?u=Alice%26u%3Dbob"))
    }
}
