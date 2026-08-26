package com.cleartune.core.network

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.QueryMap
import retrofit2.http.Query

interface OpenSubsonicApi {
    @GET("rest/ping.view")
    suspend fun ping(
        @QueryMap auth: Map<String, String>,
    ): Response<SubsonicResponseRoot>

    @GET("rest/getOpenSubsonicExtensions.view")
    suspend fun getOpenSubsonicExtensions(
        @QueryMap auth: Map<String, String>,
    ): Response<SubsonicResponseRoot>

    @GET("rest/getAlbumList2.view")
    suspend fun getAlbumList2(
        @Query("type") type: String,
        @Query("size") size: Int,
        @Query("offset") offset: Int,
        @QueryMap auth: Map<String, String>,
    ): Response<SubsonicResponseRoot>

    @GET("rest/getArtists.view")
    suspend fun getArtists(
        @QueryMap auth: Map<String, String>,
    ): Response<SubsonicResponseRoot>

    @GET("rest/getArtist.view")
    suspend fun getArtist(
        @Query("id") id: String,
        @QueryMap auth: Map<String, String>,
    ): Response<SubsonicResponseRoot>

    @GET("rest/getTopSongs.view")
    suspend fun getTopSongs(
        @Query("artist") artist: String,
        @Query("count") count: Int = 50,
        @QueryMap auth: Map<String, String>,
    ): Response<SubsonicResponseRoot>

    @GET("rest/getAlbum.view")
    suspend fun getAlbum(
        @Query("id") id: String,
        @QueryMap auth: Map<String, String>,
    ): Response<SubsonicResponseRoot>

    @GET("rest/getPlaylists.view")
    suspend fun getPlaylists(
        @QueryMap auth: Map<String, String>,
    ): Response<SubsonicResponseRoot>

    @GET("rest/getMusicFolders.view")
    suspend fun getMusicFolders(
        @QueryMap auth: Map<String, String>,
    ): Response<SubsonicResponseRoot>

    @GET("rest/getMusicDirectory.view")
    suspend fun getMusicDirectory(
        @Query("id") id: String,
        @QueryMap auth: Map<String, String>,
    ): Response<SubsonicResponseRoot>

    @GET("rest/getPlaylist.view")
    suspend fun getPlaylist(
        @Query("id") id: String,
        @QueryMap auth: Map<String, String>,
    ): Response<SubsonicResponseRoot>

    @GET("rest/search3.view")
    suspend fun search3(
        @Query("query") query: String,
        @Query("artistCount") artistCount: Int = 30,
        @Query("artistOffset") artistOffset: Int = 0,
        @Query("albumCount") albumCount: Int = 30,
        @Query("albumOffset") albumOffset: Int = 0,
        @Query("songCount") songCount: Int = 100,
        @Query("songOffset") songOffset: Int = 0,
        @QueryMap auth: Map<String, String>,
    ): Response<SubsonicResponseRoot>

    @GET("rest/getGenres.view")
    suspend fun getGenres(
        @QueryMap auth: Map<String, String>,
    ): Response<SubsonicResponseRoot>

    @GET("rest/savePlayQueue.view")
    suspend fun savePlayQueue(
        @Query("id") ids: List<String>,
        @Query("current") current: String?,
        @Query("position") positionMs: Long,
        @QueryMap auth: Map<String, String>,
    ): Response<SubsonicResponseRoot>

    @GET("rest/getPlayQueue.view")
    suspend fun getPlayQueue(
        @QueryMap auth: Map<String, String>,
    ): Response<SubsonicResponseRoot>

    @GET("rest/scrobble.view")
    suspend fun scrobble(
        @Query("id") id: String,
        @Query("time") time: Long,
        @Query("submission") submission: Boolean,
        @QueryMap auth: Map<String, String>,
    ): Response<SubsonicResponseRoot>

    @GET("rest/getStarred2.view")
    suspend fun getStarred2(
        @QueryMap auth: Map<String, String>,
    ): Response<SubsonicResponseRoot>

    @GET("rest/star.view")
    suspend fun star(
        @Query("id") songId: String?,
        @Query("albumId") albumId: String?,
        @Query("artistId") artistId: String?,
        @QueryMap auth: Map<String, String>,
    ): Response<SubsonicResponseRoot>

    @GET("rest/unstar.view")
    suspend fun unstar(
        @Query("id") songId: String?,
        @Query("albumId") albumId: String?,
        @Query("artistId") artistId: String?,
        @QueryMap auth: Map<String, String>,
    ): Response<SubsonicResponseRoot>

    @GET("rest/getLyricsBySongId.view")
    suspend fun getLyricsBySongId(
        @Query("id") id: String,
        @QueryMap auth: Map<String, String>,
    ): Response<SubsonicResponseRoot>

    @GET("rest/getLyrics.view")
    suspend fun getLyrics(
        @Query("artist") artist: String,
        @Query("title") title: String,
        @QueryMap auth: Map<String, String>,
    ): Response<SubsonicResponseRoot>

    @GET("rest/createPlaylist.view")
    suspend fun createPlaylist(
        @Query("name") name: String,
        @Query("songId") songIds: List<String>,
        @QueryMap auth: Map<String, String>,
    ): Response<SubsonicResponseRoot>

    @GET("rest/updatePlaylist.view")
    suspend fun updatePlaylist(
        @Query("playlistId") playlistId: String,
        @Query("name") name: String?,
        @Query("songIdToAdd") songIdsToAdd: List<String>,
        @Query("songIndexToRemove") songIndexesToRemove: List<Int>,
        @QueryMap auth: Map<String, String>,
    ): Response<SubsonicResponseRoot>

    @GET("rest/deletePlaylist.view")
    suspend fun deletePlaylist(
        @Query("id") id: String,
        @QueryMap auth: Map<String, String>,
    ): Response<SubsonicResponseRoot>
}
