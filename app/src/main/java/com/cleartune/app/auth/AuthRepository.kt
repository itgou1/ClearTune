package com.cleartune.app.auth

import com.cleartune.core.datastore.CredentialsStore
import com.cleartune.core.model.ConnectionResult
import com.cleartune.core.model.ServerCredentials
import com.cleartune.core.network.OpenSubsonicClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class AuthRepository @Inject constructor(
    private val credentialsStore: CredentialsStore,
    private val client: OpenSubsonicClient,
) {
    suspend fun restore(): Pair<ServerCredentials?, ConnectionResult?> {
        val credentials = credentialsStore.credentials.first()
        return credentials to credentials?.let { client.connect(it) }
    }

    suspend fun connectAndSave(credentials: ServerCredentials): ConnectionResult {
        val result = client.connect(credentials)
        if (result is ConnectionResult.Success) {
            credentialsStore.save(
                credentials.copy(baseUrl = result.profile.baseUrl),
                result.profile,
            )
        }
        return result
    }

    suspend fun logout() {
        credentialsStore.clear()
    }
}
