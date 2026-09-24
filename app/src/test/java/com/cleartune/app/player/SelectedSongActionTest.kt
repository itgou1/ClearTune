package com.cleartune.app.player

import com.cleartune.core.player.PlaybackStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectedSongActionTest {
    @Test fun selectingTheActiveSongKeepsPlaybackAndQueue() {
        for (status in listOf(PlaybackStatus.PLAYING, PlaybackStatus.BUFFERING, PlaybackStatus.READY)) {
            assertEquals(SelectedSongAction.KEEP, selectedSongAction("song-1", status, "song-1"))
        }
    }

    @Test fun selectingPausedSongResumesButFailuresCanBeRetried() {
        assertEquals(SelectedSongAction.RESUME, selectedSongAction("song-1", PlaybackStatus.PAUSED, "song-1"))
        for (status in listOf(PlaybackStatus.ERROR, PlaybackStatus.ENDED, PlaybackStatus.IDLE)) {
            assertEquals(SelectedSongAction.START, selectedSongAction("song-1", status, "song-1"))
        }
    }

    @Test fun anotherSongStillStartsEvenWhenPlaying() {
        assertEquals(SelectedSongAction.START, selectedSongAction("song-1", PlaybackStatus.PLAYING, "song-2"))
    }
}
