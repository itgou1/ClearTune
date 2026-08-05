package com.cleartune.data.webdav

import com.cleartune.core.contracts.CredentialStore
import com.cleartune.core.contracts.SourceWriteGateway
import com.cleartune.core.contracts.WebDavCredential
import com.cleartune.core.model.CredentialAlias
import com.cleartune.core.model.MusicSource
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.SourceMutation
import com.cleartune.core.model.SourceType
import com.cleartune.core.network.WebDavUrlPolicy
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

data class WebDavSourceDraft(
    val name: String,
    val url: String,
    val username: String,
    val password: CharArray,
    val allowCleartext: Boolean = false,
    val sourceId: SourceId? = null,
)

class ValidatedWebDavSource internal constructor(
    val source: MusicSource,
    internal val fingerprint: ByteArray,
)

fun interface WebDavConnectionProbe {
    suspend fun test(source: MusicSource, credential: WebDavCredential)
}

class WebDavSourceManager(
    private val connectionProbe: WebDavConnectionProbe,
    private val sourceGateway: SourceWriteGateway,
    private val credentialStore: CredentialStore,
) {
    suspend fun test(draft: WebDavSourceDraft): ValidatedWebDavSource {
        val source = createSource(draft)
        val credential = WebDavCredential(draft.username, draft.password.copyOf())
        try {
            connectionProbe.test(source, credential)
        } finally {
            credential.password.fill('\u0000')
        }
        return ValidatedWebDavSource(source, fingerprint(draft, source))
    }

    suspend fun save(draft: WebDavSourceDraft, validation: ValidatedWebDavSource): MusicSource {
        val normalized = createSource(draft, validation.source.id)
        require(MessageDigest.isEqual(validation.fingerprint, fingerprint(draft, normalized))) {
            "Source details changed after connection test"
        }
        val alias = requireNotNull(normalized.credentialAlias)
        val previous = credentialStore.get(alias)
        val passwordCopy = draft.password.copyOf()
        try {
            credentialStore.put(alias, WebDavCredential(draft.username, passwordCopy))
            sourceGateway.applySourceMutation(SourceMutation.Upsert(normalized))
        } catch (error: Exception) {
            try {
                if (previous == null) {
                    credentialStore.delete(alias)
                } else {
                    credentialStore.put(alias, previous)
                }
            } catch (rollbackError: Exception) {
                error.addSuppressed(rollbackError)
            }
            throw error
        } finally {
            passwordCopy.fill('\u0000')
            previous?.password?.fill('\u0000')
        }
        return normalized
    }

    fun rebase(
        testedDraft: WebDavSourceDraft,
        validation: ValidatedWebDavSource,
        selectedDraft: WebDavSourceDraft,
    ): ValidatedWebDavSource {
        val testedSource = createSource(testedDraft, validation.source.id)
        require(MessageDigest.isEqual(validation.fingerprint, fingerprint(testedDraft, testedSource))) {
            "Source details changed after connection test"
        }
        val selectedSource = createSource(selectedDraft, validation.source.id)
        val testedBase = WebDavUrlPolicy.normalizeBaseUrl(
            requireNotNull(testedSource.baseUrl),
            testedSource.allowCleartext,
        )
        val selectedBase = WebDavUrlPolicy.normalizeBaseUrl(
            requireNotNull(selectedSource.baseUrl),
            selectedSource.allowCleartext,
        )
        require(WebDavUrlPolicy.isInBaseSubtree(testedBase, selectedBase)) {
            "Selected root must stay inside the tested WebDAV root"
        }
        return ValidatedWebDavSource(selectedSource, fingerprint(selectedDraft, selectedSource))
    }

    suspend fun delete(source: MusicSource) {
        sourceGateway.applySourceMutation(SourceMutation.Remove(source.id))
        source.credentialAlias?.let { credentialStore.delete(it) }
    }

    private fun createSource(draft: WebDavSourceDraft, forcedId: SourceId? = null): MusicSource {
        require(draft.name.isNotBlank()) { "Source name is required" }
        val normalizedUrl = WebDavUrlPolicy.normalizeBaseUrl(draft.url, draft.allowCleartext)
        val id = forcedId ?: draft.sourceId ?: SourceId(UUID.randomUUID().toString())
        return MusicSource(
            id = id,
            name = draft.name.trim(),
            type = SourceType.WEBDAV,
            baseUrl = normalizedUrl.toString(),
            allowCleartext = draft.allowCleartext,
            credentialAlias = CredentialAlias("webdav-${id.value}"),
        )
    }

    private fun fingerprint(draft: WebDavSourceDraft, source: MusicSource): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
            digest.update(0)
            digest.update(bytes)
            bytes.fill(0)
        }
        add(source.id.value)
        add(source.name)
        add(requireNotNull(source.baseUrl))
        add(draft.username)
        val passwordBytes = draft.password.concatToString().toByteArray(StandardCharsets.UTF_8)
        digest.update(passwordBytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
        digest.update(0)
        digest.update(passwordBytes)
        passwordBytes.fill(0)
        digest.update(if (draft.allowCleartext) 1 else 0)
        return digest.digest()
    }
}
