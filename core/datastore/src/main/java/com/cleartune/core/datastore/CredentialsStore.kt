package com.cleartune.core.datastore

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cleartune.core.model.ServerCredentials
import com.cleartune.core.model.ServerProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.credentialsDataStore by preferencesDataStore(name = "server_credentials")

class CredentialsStore(
    private val context: Context,
    private val cipher: CredentialCipher = CredentialCipher(),
) {
    val credentials: Flow<ServerCredentials?> = context.credentialsDataStore.data
        .map { preferences ->
            val baseUrl = preferences[Keys.BASE_URL] ?: return@map null
            val username = preferences[Keys.USERNAME] ?: return@map null
            val cipherText = preferences[Keys.PASSWORD_CIPHER] ?: return@map null
            val iv = preferences[Keys.PASSWORD_IV] ?: return@map null
            runCatching {
                ServerCredentials(
                    baseUrl = baseUrl,
                    username = username,
                    password = cipher.decrypt(
                        Base64.decode(cipherText, Base64.NO_WRAP),
                        Base64.decode(iv, Base64.NO_WRAP),
                    ),
                    allowInsecureHttp = preferences[Keys.ALLOW_HTTP] ?: false,
                )
            }.getOrNull()
        }
        .catch { emit(null) }

    val profile: Flow<ServerProfile?> = context.credentialsDataStore.data
        .map { preferences ->
            val baseUrl = preferences[Keys.BASE_URL] ?: return@map null
            val username = preferences[Keys.USERNAME] ?: return@map null
            ServerProfile(
                baseUrl = baseUrl,
                username = username,
                serverType = preferences[Keys.SERVER_TYPE].orEmpty(),
                serverVersion = preferences[Keys.SERVER_VERSION].orEmpty(),
                apiVersion = preferences[Keys.API_VERSION].orEmpty(),
                openSubsonic = preferences[Keys.OPEN_SUBSONIC] ?: false,
                extensions = preferences[Keys.EXTENSIONS]
                    .orEmpty()
                    .split(',')
                    .filter(String::isNotBlank)
                    .toSet(),
                allowInsecureHttp = preferences[Keys.ALLOW_HTTP] ?: false,
            )
        }
        .catch { emit(null) }

    suspend fun save(credentials: ServerCredentials, profile: ServerProfile) {
        val encrypted = cipher.encrypt(credentials.password)
        context.credentialsDataStore.edit { preferences ->
            preferences[Keys.BASE_URL] = profile.baseUrl
            preferences[Keys.USERNAME] = credentials.username
            preferences[Keys.PASSWORD_CIPHER] = Base64.encodeToString(
                encrypted.cipherText,
                Base64.NO_WRAP,
            )
            preferences[Keys.PASSWORD_IV] = Base64.encodeToString(
                encrypted.initializationVector,
                Base64.NO_WRAP,
            )
            preferences[Keys.ALLOW_HTTP] = credentials.allowInsecureHttp
            preferences[Keys.SERVER_TYPE] = profile.serverType
            preferences[Keys.SERVER_VERSION] = profile.serverVersion
            preferences[Keys.API_VERSION] = profile.apiVersion
            preferences[Keys.OPEN_SUBSONIC] = profile.openSubsonic
            preferences[Keys.EXTENSIONS] = profile.extensions.sorted().joinToString(",")
        }
    }

    suspend fun clear() {
        context.credentialsDataStore.edit { it.clear() }
    }

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD_CIPHER = stringPreferencesKey("password_cipher")
        val PASSWORD_IV = stringPreferencesKey("password_iv")
        val ALLOW_HTTP = booleanPreferencesKey("allow_http")
        val SERVER_TYPE = stringPreferencesKey("server_type")
        val SERVER_VERSION = stringPreferencesKey("server_version")
        val API_VERSION = stringPreferencesKey("api_version")
        val OPEN_SUBSONIC = booleanPreferencesKey("open_subsonic")
        val EXTENSIONS = stringPreferencesKey("extensions")
    }
}
