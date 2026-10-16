package com.cleartune.core.datastore

import android.content.Context
import android.util.Base64
import com.cleartune.core.model.MusicTagSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** All connection data is encrypted; neither token nor test-server defaults are persisted. */
class MusicTagSettingsStore(context: Context, private val cipher: CredentialCipher = CredentialCipher()) {
    private val preferences = context.getSharedPreferences("music_tag_connections", Context.MODE_PRIVATE)

    suspend fun read(account: String): MusicTagSettings = withContext(Dispatchers.IO) {
        val encoded = preferences.getString(account, null) ?: return@withContext MusicTagSettings()
        val parts = encoded.split(':', limit = 2)
        val value = cipher.decrypt(Base64.decode(parts[1], Base64.NO_WRAP), Base64.decode(parts[0], Base64.NO_WRAP))
        MusicTagSettingsCodec.decode(value)
    }

    suspend fun save(account: String, value: MusicTagSettings) = withContext(Dispatchers.IO) {
        val clear = MusicTagSettingsCodec.encode(value)
        val encrypted = cipher.encrypt(clear)
        check(preferences.edit().putString(account,
            Base64.encodeToString(encrypted.initializationVector, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted.cipherText, Base64.NO_WRAP)).commit())
    }

    suspend fun clear(account: String) = withContext(Dispatchers.IO) {
        check(preferences.edit().remove(account).commit()) { "无法退出 Music Tag 连接，请重试" }
    }
}
