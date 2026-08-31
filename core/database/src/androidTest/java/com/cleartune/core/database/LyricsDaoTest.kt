package com.cleartune.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleartune.core.model.LyricLine
import com.cleartune.core.model.Lyrics
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LyricsDaoTest {
    private lateinit var database: ClearTuneDatabase
    private lateinit var dao: LyricsDao

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ClearTuneDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.lyricsDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun cachedLyricsRoundTripPreservesTimingAndOrder() = runBlocking {
        val lyrics = Lyrics(
            songId = "song-1",
            synced = true,
            lines = listOf(
                LyricLine(startMs = 1_200, text = "第一句"),
                LyricLine(startMs = 3_400, text = "第二句"),
            ),
        )
        val write = lyrics.toCacheWrite("https://music.example", "listener", now = 123)

        dao.replace(write.cache, write.lines)

        assertEquals(
            lyrics,
            dao.lyrics("https://music.example", "listener", "song-1")?.toModel(),
        )
    }

    @Test
    fun cachesAreIsolatedByServerAndAccount() = runBlocking {
        val lyrics = Lyrics(songId = "song-1", synced = false, lines = emptyList())
        val write = lyrics.toCacheWrite("https://one.example", "listener")
        dao.replace(write.cache, write.lines)

        assertEquals(
            lyrics,
            dao.lyrics("https://one.example", "listener", "song-1")?.toModel(),
        )
        assertNull(dao.lyrics("https://two.example", "listener", "song-1"))
        assertNull(dao.lyrics("https://one.example", "someone-else", "song-1"))
    }
}
