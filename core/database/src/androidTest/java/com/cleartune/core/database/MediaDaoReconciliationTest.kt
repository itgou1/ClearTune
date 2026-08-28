package com.cleartune.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaDaoReconciliationTest {
    private lateinit var database: ClearTuneDatabase
    private lateinit var dao: MediaDao

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ClearTuneDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.mediaDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun replacingLibraryRemovesStaleMediaAndPlaylistLinks() = runBlocking {
        dao.upsertAlbums(listOf(album("kept-album"), album("stale-album")))
        dao.upsertArtists(listOf(artist("kept-artist"), artist("stale-artist")))
        dao.upsertSongs(
            listOf(
                song("kept-song", "kept-album", "kept-artist"),
                song("stale-song", "stale-album", "stale-artist"),
            ),
        )
        dao.upsertPlaylists(listOf(playlist("playlist-1")))
        dao.replacePlaylistSongs(
            "playlist-1",
            listOf(
                PlaylistSongEntity("playlist-1", "kept-song", 0),
                PlaylistSongEntity("playlist-1", "stale-song", 1),
            ),
        )

        dao.replaceLibrary(
            albums = listOf(album("kept-album")),
            artists = listOf(artist("kept-artist")),
            songs = listOf(song("kept-song", "kept-album", "kept-artist")),
        )

        assertEquals(listOf("kept-album"), dao.observeAlbums().first().map(AlbumEntity::id))
        assertEquals(listOf("kept-artist"), dao.observeArtists().first().map(ArtistEntity::id))
        assertEquals(listOf("kept-song"), dao.observeSongs().first().map(SongEntity::id))
        assertEquals(listOf("kept-song"), dao.observePlaylistSongs("playlist-1").first().map(SongEntity::id))
    }

    private fun album(id: String) = AlbumEntity(
        id = id,
        name = id,
        artistId = "kept-artist",
        artistName = "Artist",
        year = null,
        songCount = 1,
        durationSeconds = 180,
        coverArtId = null,
        starredAt = null,
        createdAt = null,
        updatedAt = 1,
    )

    private fun artist(id: String) = ArtistEntity(
        id = id,
        name = id,
        albumCount = 1,
        coverArtId = null,
        starredAt = null,
        updatedAt = 1,
    )

    private fun song(id: String, albumId: String, artistId: String) = SongEntity(
        id = id,
        title = id,
        artistId = artistId,
        artistName = "Artist",
        albumId = albumId,
        albumName = albumId,
        durationSeconds = 180,
        trackNumber = 1,
        discNumber = 1,
        year = null,
        genre = null,
        coverArtId = null,
        contentType = null,
        suffix = null,
        bitRate = null,
        sizeBytes = null,
        playCount = 0,
        lastPlayedAt = null,
        starredAt = null,
        createdAt = null,
        replayGainTrackDb = null,
        replayGainAlbumDb = null,
        replayGainTrackPeak = null,
        replayGainAlbumPeak = null,
        replayGainBaseDb = null,
        replayGainFallbackDb = null,
        updatedAt = 1,
    )

    private fun playlist(id: String) = PlaylistEntity(
        id = id,
        name = id,
        songCount = 2,
        durationSeconds = 360,
        owner = null,
        isPublic = false,
        coverArtId = null,
        changedAt = null,
        updatedAt = 1,
    )
}
