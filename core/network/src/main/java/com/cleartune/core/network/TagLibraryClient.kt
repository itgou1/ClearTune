package com.cleartune.core.network

/** Keeps Retrofit response types inside the network module. */
class TagLibraryClient(private val authorized: AuthorizedOpenSubsonicApi) {
    suspend fun song(id: String): SongDto {
        val response = authorized.api.getSong(id, authorized.authQuery())
        return response.body()?.response?.takeIf { response.isSuccessful && it.status == "ok" }?.song
            ?: throw MusicTagException("无法读取服务器歌曲详情，请检查连接")
    }

    suspend fun startScan(): Boolean {
        val response = authorized.api.startScan(authorized.authQuery())
        return response.isSuccessful && response.body()?.response?.status == "ok"
    }

    suspend fun scanning(): Boolean? {
        val response = authorized.api.getScanStatus(authorized.authQuery())
        return response.body()?.response?.takeIf { response.isSuccessful && it.status == "ok" }?.scanStatus?.scanning
    }
}
