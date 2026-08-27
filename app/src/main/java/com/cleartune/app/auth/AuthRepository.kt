package com.cleartune.app.auth

import com.cleartune.core.datastore.CredentialsStore
import com.cleartune.core.model.ConnectionResult
import com.cleartune.core.model.ServerCredentials
import com.cleartune.core.model.ServerProfile
import com.cleartune.core.network.OpenSubsonicClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

data class AuthRestoreResult(
    val credentials: ServerCredentials?,
    val connectionResult: ConnectionResult?,
    val cachedProfile: ServerProfile?,
)

@Singleton
class AuthRepository @Inject constructor(
    private val credentialsStore: CredentialsStore,
    private val client: OpenSubsonicClient,
) {
    suspend fun restore(): AuthRestoreResult {
        val credentials = credentialsStore.credentials.first()
        return AuthRestoreResult(
            credentials = credentials,
            connectionResult = credentials?.let { client.connect(it) },
            cachedProfile = credentialsStore.profile.first(),
        )
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
