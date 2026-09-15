package com.cleartune.app.auth

import android.content.Context
import androidx.work.WorkManager
import com.cleartune.core.database.ClearTuneDatabase
import com.cleartune.core.database.DatabaseFactory
import com.cleartune.core.model.ServerCredentials
import com.cleartune.core.model.ServerProfile
import com.cleartune.core.model.accountStorageKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Repositories capture this session, never the mutable global login credentials. */
class AccountSession(
    private val savedCredentials: ServerCredentials,
    val profile: ServerProfile,
    val token: String,
    val database: ClearTuneDatabase,
) {
    val accountKey = accountStorageKey(savedCredentials.baseUrl, savedCredentials.username)
    @Volatile var active: Boolean = true
        private set

    fun credentials(): ServerCredentials? = savedCredentials.takeIf { active }
    fun revoke() { active = false }
}

@Singleton
class AccountSessions @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val databases = mutableMapOf<String, ClearTuneDatabase>()
    private var current: AccountSession? = null

    fun requireCurrent(): AccountSession = checkNotNull(current).also { check(it.active) }

    fun activate(credentials: ServerCredentials, profile: ServerProfile, token: String) {
        current?.revoke()
        val key = accountStorageKey(credentials.baseUrl, credentials.username)
        // Keep old handles valid for cancelled operations that are still unwinding. They can
        // only write their original account DB, never the newly selected account's DB.
        val database = databases.getOrPut(key) { DatabaseFactory.create(context, key) }
        current = AccountSession(credentials, profile, token, database)
    }

    suspend fun revoke() {
        val previous = current
        previous?.revoke()
        current = null
        // Also cancels pre-isolation workers, whose input has no account/session identity.
        withContext(Dispatchers.IO) {
            WorkManager.getInstance(context).cancelAllWorkByTag("com.cleartune.app.download.DownloadWorker").result.get()
        }
        previous?.database?.downloadDao()?.pauseActiveDownloads()
    }
}
