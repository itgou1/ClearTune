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
    private val sessions: AccountSessions,
) {
    suspend fun restore(): AuthRestoreResult {
        val credentials = credentialsStore.credentials.first()
        val result = AuthRestoreResult(
            credentials = credentials,
            connectionResult = credentials?.let { client.connect(it) },
            cachedProfile = credentialsStore.profile.first(),
        )
        val profile = (result.connectionResult as? ConnectionResult.Success)?.profile
            ?: result.cachedProfile.takeIf {
                (result.connectionResult as? ConnectionResult.Failure)?.error?.allowsOfflineRestore() == true
            }
        if (credentials != null && profile != null) {
            sessions.activate(credentials, profile, credentialsStore.ensureSessionToken())
        }
        return result
    }

    suspend fun connectAndSave(credentials: ServerCredentials): ConnectionResult {
        val result = client.connect(credentials)
        if (result is ConnectionResult.Success) {
            credentialsStore.save(
                credentials.copy(baseUrl = result.profile.baseUrl),
                result.profile,
            )
            sessions.activate(
                credentials.copy(baseUrl = result.profile.baseUrl), result.profile,
                credentialsStore.ensureSessionToken(),
            )
        }
        return result
    }

    suspend fun logout() {
        credentialsStore.clear()
        sessions.revoke()
    }
}
