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
        val values = value.split('\n').map { String(Base64.decode(it, Base64.NO_WRAP), Charsets.UTF_8) }
        MusicTagSettings(values[0], values[1], values[2], values[3], values[4], values[5], values[6].toBoolean())
    }

    suspend fun save(account: String, value: MusicTagSettings) = withContext(Dispatchers.IO) {
        val clear = listOf(value.baseUrl, value.username, value.password, value.sourceRoot,
            value.targetRoot, value.source, value.allowHttp.toString()).joinToString("\n") {
            Base64.encodeToString(it.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
        val encrypted = cipher.encrypt(clear)
        check(preferences.edit().putString(account,
            Base64.encodeToString(encrypted.initializationVector, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted.cipherText, Base64.NO_WRAP)).commit())
    }

    suspend fun clear(account: String) = withContext(Dispatchers.IO) {
        check(preferences.edit().remove(account).commit()) { "无法退出 Music Tag 连接，请重试" }
    }
}
