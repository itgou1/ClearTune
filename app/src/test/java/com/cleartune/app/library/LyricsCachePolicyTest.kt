package com.cleartune.app.library

import com.cleartune.core.model.ClearTuneError
import com.cleartune.core.model.LyricLine
import com.cleartune.core.model.Lyrics
import com.cleartune.core.network.RemoteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LyricsCachePolicyTest {
    private val now = LYRICS_MAX_AGE_MS * 3
    private val old = Lyrics("song", false, listOf(LyricLine(text = "旧歌词")))
    private val fresh = old.copy(lines = listOf(LyricLine(text = "新歌词")))

    @Test fun emptyCachedResultFetchesNewlyAddedLyricsEvenWhenRecent() = runBlocking {
        val empty = old.copy(lines = emptyList())
        var saved: Lyrics? = null
        val results = lyricsResults(empty, now, false, now,
            fetch = { RemoteResult.Success(fresh) }, save = { saved = it }).toList()
        assertEquals(listOf(RemoteResult.Success(empty), RemoteResult.Success(fresh)), results)
        assertEquals(fresh, saved)
    }

    @Test fun freshNonemptyCacheAvoidsNetwork() = runBlocking {
        val results = lyricsResults(old, now - 1, false, now,
            fetch = { error("Must use fresh cache") }, save = { error("No write expected") }).toList()
        assertEquals(listOf(RemoteResult.Success(old)), results)
    }

    @Test fun expiredCacheIsEmittedBeforeFetchingAndThenReplaced() = runBlocking {
        val seen = mutableListOf<RemoteResult<Lyrics>>()
        var saved: Lyrics? = null
        lyricsResults(old, now - LYRICS_MAX_AGE_MS, false, now,
            fetch = {
                assertEquals(listOf(RemoteResult.Success(old)), seen)
                RemoteResult.Success(fresh)
            }, save = { saved = it }).collect { seen += it }
        assertEquals(RemoteResult.Success(fresh), seen.last())
        assertEquals(fresh, saved)
    }

    @Test fun manualRefreshBypassesFreshCache() = runBlocking {
        val results = lyricsResults(old, now, true, now,
            fetch = { RemoteResult.Success(fresh) }, save = {}).toList()
        assertEquals(RemoteResult.Success(fresh), results.last())
        assertEquals(2, results.size)
    }

    @Test fun unchangedServerLyricsAreNotReportedAsUpdated() {
        assertEquals("Navidrome 返回的歌词未变化", lyricsRefreshFeedback(true, false, old, old))
        assertEquals("已从 Navidrome 重新读取歌词", lyricsRefreshFeedback(true, false, old, fresh))
    }

    @Test fun networkFailureKeepsCacheAndNeverWritesEmptyLyrics() = runBlocking {
        val failure = RemoteResult.Failure(ClearTuneError.Authentication())
        val results = lyricsResults(old, 0, true, now,
            fetch = { failure }, save = { error("Failure must not overwrite cache") }).toList()
        assertEquals(listOf(RemoteResult.Success(old), failure), results)
    }

    @Test fun firstLoadFetchesAndSavesLyrics() = runBlocking {
        var saved: Lyrics? = null
        val results = lyricsResults(null, null, false, now,
            fetch = { RemoteResult.Success(fresh) }, save = { saved = it }).toList()
        assertEquals(listOf(RemoteResult.Success(fresh)), results)
        assertEquals(fresh, saved)
    }

    @Test fun cancellationDoesNotWriteOrEmitAReplacement() = runBlocking {
        val results = mutableListOf<RemoteResult<Lyrics>>()
        try {
            lyricsResults(old, 0, true, now,
                fetch = { throw CancellationException() }, save = { error("Cancelled write") })
                .collect { results += it }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(listOf(RemoteResult.Success(old)), results)
        }
    }

    @Test fun futureCacheTimestampDoesNotPreventRefresh() = runBlocking {
        val results = lyricsResults(old, now + 1, false, now,
            fetch = { RemoteResult.Success(fresh) }, save = {}).toList()
        assertEquals(RemoteResult.Success(fresh), results.last())
    }
}
