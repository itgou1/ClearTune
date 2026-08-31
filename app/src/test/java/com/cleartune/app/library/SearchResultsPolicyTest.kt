package com.cleartune.app.library

import com.cleartune.core.model.Album
import com.cleartune.core.model.Artist
import com.cleartune.core.model.Playlist
import com.cleartune.core.model.Song
import com.cleartune.core.network.SearchResults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResultsPolicyTest {
    @Test
    fun mergesLocalAndRemoteResultsWithoutDuplicateRows() {
        val local = SearchResults(
            artists = listOf(Artist("artist-1", "本地歌手")),
            albums = listOf(Album("album-1", "本地专辑")),
            songs = listOf(Song("song-1", "本地歌曲")),
            playlists = listOf(Playlist("playlist-1", "本地歌单")),
        )
        val remote = SearchResults(
            artists = listOf(Artist("artist-1", "服务器歌手"), Artist("artist-2", "新歌手")),
            albums = listOf(Album("album-2", "新专辑")),
            songs = listOf(Song("song-1", "服务器歌曲"), Song("song-2", "新歌曲")),
            playlists = listOf(Playlist("playlist-1", "本地歌单")),
        )

        val merged = mergeFreshSearchResults(local, remote)

        assertEquals(listOf("artist-1", "artist-2"), merged.artists.map(Artist::id))
        assertEquals(listOf("album-1", "album-2"), merged.albums.map(Album::id))
        assertEquals(listOf("song-1", "song-2"), merged.songs.map(Song::id))
        assertEquals(listOf("playlist-1"), merged.playlists.map(Playlist::id))
        assertEquals("服务器歌曲", merged.songs.first().title)
    }

    @Test
    fun freshMetadataPreservesLocalListeningState() {
        val local = SearchResults(
            artists = emptyList(),
            albums = emptyList(),
            songs = listOf(
                Song(
                    id = "song-1",
                    title = "旧标题",
                    playCount = 12,
                    lastPlayedAt = 200,
                    starredAt = 100,
                ),
            ),
        )
        val remote = SearchResults(
            artists = emptyList(),
            albums = emptyList(),
            songs = listOf(
                Song(
                    id = "song-1",
                    title = "新标题",
                    playCount = 3,
                    lastPlayedAt = 150,
                ),
            ),
        )

        val song = mergeFreshSearchResults(local, remote).songs.single()

        assertEquals("新标题", song.title)
        assertEquals(12L, song.playCount)
        assertEquals(200L, song.lastPlayedAt)
        assertEquals(100L, song.starredAt)
    }

    @Test
    fun serverSearchIsConditionalUnlessExplicitlyRequested() {
        val now = 2 * 24 * 60 * 60 * 1_000L
        assertEquals(false, shouldSearchServer(8, now - 1_000, forced = false, now = now))
        assertEquals(true, shouldSearchServer(2, now - 1_000, forced = false, now = now))
        assertEquals(true, shouldSearchServer(8, 0, forced = false, now = now))
        assertEquals(true, shouldSearchServer(8, now - 1_000, forced = true, now = now))
    }

    @Test
    fun categoryCountsExposeEmptyFilteredResults() {
        val results = SearchResults(
            artists = emptyList(),
            albums = emptyList(),
            songs = listOf(Song("song-1", "Fly Away")),
            playlists = emptyList(),
        )

        assertEquals(1, results.resultCount(SearchCategory.SONGS))
        assertEquals(0, results.resultCount(SearchCategory.ALBUMS))
    }

    @Test
    fun rankingPrefersExactTitleThenPrefixThenMetadata() {
        val plan = buildSearchQueryPlan("月光")
        val documents = listOf(
            searchDocument("song", "metadata", "其他", "林海 月光", "林海 月光"),
            searchDocument("song", "prefix", "月光曲", "", "月光曲"),
            searchDocument("song", "exact", "月光", "", "月光"),
        )

        assertEquals(
            listOf("exact", "prefix", "metadata"),
            rankSearchDocuments(documents, plan).map { it.entityId },
        )
    }

    @Test
    fun typoSuggestionsReturnClosestTitle() {
        val documents = listOf(
            searchDocument("artist", "alan", "Alan Walker", "", "Alan Walker"),
            searchDocument("artist", "adele", "Adele", "", "Adele"),
        )

        assertEquals(
            listOf("Alan Walker"),
            closestSearchSuggestions("Alen Walker", documents),
        )
    }

    @Test
    fun mixedPinyinAliasSupportsFullSurnameAndGivenNameInitials() {
        val artistAliases = pinyinSearchAliases("deng zi qi")
        val stageNameAliases = pinyinSearchAliases("g e m deng zi qi")
        val songMetadataAliases = pinyinSearchAliases("deng zi qi xin de xin tiao")

        assertTrue(artistAliases.toString(), artistAliases.contains("dengzq"))
        assertTrue(stageNameAliases.toString(), stageNameAliases.contains("dengzq"))
        assertTrue(stageNameAliases.toString(), stageNameAliases.contains("dzq"))
        assertTrue(songMetadataAliases.toString(), songMetadataAliases.any { it.startsWith("dengzq") })
    }

    private fun searchDocument(
        type: String,
        id: String,
        title: String,
        subtitle: String,
        keywords: String,
    ) = com.cleartune.core.database.SearchDocumentEntity(
        entityType = type,
        entityId = id,
        title = title,
        subtitle = subtitle,
        keywords = keywords,
        pinyin = "",
        initials = "",
    )
}
