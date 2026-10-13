package com.cleartune.app.library

import com.cleartune.core.model.ClearTuneError
import com.cleartune.core.model.Song
import com.cleartune.core.network.RemoteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MissingSongPolicyTest {
    @Test
    fun sameTitleAndMissingArtworkDoNotIdentifyDuplicates() {
        val local = listOf(Song("old", "成都"), Song("live", "成都"), Song("new", "成都"))
        assertEquals(listOf("old", "live"), missingSearchCandidates(local, listOf(local.last())))
    }

    @Test
    fun removesOnlyTheIdExplicitlyReportedMissing() = runBlocking {
        val removed = mutableListOf<String>()
        assertTrue(removeConfirmedMissingSong("old", { id ->
            assertEquals("old", id)
            RemoteResult.Failure(ClearTuneError.Server(70))
        }, { removed.add(it) }))
        assertEquals(listOf("old"), removed)
    }

    @Test
    fun searchMissDoesNotRemoveAnExistingSongOutsideTheRemotePage() = runBlocking {
        val song = Song("live", "成都")
        assertFalse(removeConfirmedMissingSong(song.id, { RemoteResult.Success(song) }, {
            fail("Existing version must remain")
        }))
    }

    @Test
    fun transportAuthenticationAndServerErrorsKeepLibraryEntries() = runBlocking {
        val errors = listOf(
            ClearTuneError.Server(404), ClearTuneError.Server(500), ClearTuneError.Server(50),
            ClearTuneError.Authentication(), ClearTuneError.Timeout(), ClearTuneError.Unreachable(),
            ClearTuneError.Unexpected(),
        )
        for (error in errors) {
            assertFalse(removeConfirmedMissingSong("song", { RemoteResult.Failure(error) }, {
                fail("Must not delete for $error")
            }))
        }
        assertFalse(removeConfirmedMissingSong("song", { throw IllegalStateException("bad response") }, {
            fail("Malformed responses must not delete songs")
        }))
    }

    @Test
    fun cancellingSearchOrSwitchingPlaybackStopsVerification() = runBlocking {
        try {
            removeConfirmedMissingSong("song", { throw CancellationException() }, {
                fail("Cancelled requests must not delete songs")
            })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected: collectLatest can stop an obsolete search or playback verification.
        }
    }
}
