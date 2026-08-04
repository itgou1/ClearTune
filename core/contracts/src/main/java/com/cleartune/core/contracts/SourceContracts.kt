package com.cleartune.core.contracts

import com.cleartune.core.model.CredentialAlias
import com.cleartune.core.model.MusicSource
import com.cleartune.core.model.MutationResult
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.SourceMutation
import kotlinx.coroutines.flow.Flow

interface SourceRepository {
    fun observeSources(): Flow<List<MusicSource>>
    suspend fun getSource(sourceId: SourceId): MusicSource?
}

interface SourceWriteGateway {
    suspend fun applySourceMutation(mutation: SourceMutation): MutationResult
}

class WebDavCredential(
    val username: String,
    val password: CharArray,
)

interface CredentialStore {
    suspend fun put(alias: CredentialAlias, credential: WebDavCredential)
    suspend fun get(alias: CredentialAlias): WebDavCredential?
    suspend fun delete(alias: CredentialAlias)
}
