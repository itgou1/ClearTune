package com.cleartune.data.webdav

import com.cleartune.core.contracts.CredentialStore
import com.cleartune.core.contracts.SourceWriteGateway
import com.cleartune.core.contracts.WebDavCredential
import com.cleartune.core.model.CredentialAlias
import com.cleartune.core.model.MutationResult
import com.cleartune.core.model.SourceMutation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavSourceManagerTest {
    @Test
    fun `test connection validates but does not persist`() = runTest {
        val credentials = FakeCredentials()
        val gateway = FakeSources()
        val manager = WebDavSourceManager({ _, _ -> Unit }, gateway, credentials)

        val validated = manager.test(draft())

        assertTrue(validated.source.baseUrl == "https://music.example.com/dav/")
        assertTrue(credentials.values.isEmpty())
        assertTrue(gateway.mutations.isEmpty())
    }

    @Test
    fun `save accepts only the exact successfully tested draft`() = runTest {
        val credentials = FakeCredentials()
        val gateway = FakeSources()
        val manager = WebDavSourceManager({ _, _ -> Unit }, gateway, credentials)
        val draft = draft()
        val validated = manager.test(draft)

        val source = manager.save(draft, validated)

        assertEquals(source.credentialAlias, credentials.values.keys.single())
        assertTrue(gateway.mutations.single() is SourceMutation.Upsert)
        val mismatch = try {
            manager.save(draft.copy(url = "https://other.example/dav"), validated)
            null
        } catch (error: IllegalArgumentException) {
            error
        }
        assertTrue(mismatch != null)
    }

    @Test
    fun `http requires explicit cleartext confirmation`() = runTest {
        val manager = WebDavSourceManager({ _, _ -> Unit }, FakeSources(), FakeCredentials())
        val failure = runCatching { manager.test(draft().copy(url = "http://lan.example/dav")) }
        assertFalse(failure.isSuccess)
    }

    @Test
    fun `failed update restores the previous credential`() = runTest {
        val credentials = FakeCredentials()
        val gateway = FakeSources()
        val manager = WebDavSourceManager({ _, _ -> Unit }, gateway, credentials)
        val draft = draft()
        val validated = manager.test(draft)
        manager.save(draft, validated)
        gateway.failure = IllegalStateException("database unavailable")

        val changed = draft.copy(
            sourceId = validated.source.id,
            password = "new secret".toCharArray(),
        )
        val changedValidation = manager.test(changed)
        runCatching { manager.save(changed, changedValidation) }

        val restored = requireNotNull(credentials.get(requireNotNull(validated.source.credentialAlias)))
        assertTrue(restored.password.contentEquals("secret".toCharArray()))
    }

    private fun draft() = WebDavSourceDraft(
        name = "家庭音乐库",
        url = "https://music.example.com/dav",
        username = "alice",
        password = "secret".toCharArray(),
    )

    private class FakeCredentials : CredentialStore {
        val values = mutableMapOf<CredentialAlias, WebDavCredential>()
        override suspend fun put(alias: CredentialAlias, credential: WebDavCredential) {
            values[alias] = WebDavCredential(credential.username, credential.password.copyOf())
        }
        override suspend fun get(alias: CredentialAlias) = values[alias]?.let {
            WebDavCredential(it.username, it.password.copyOf())
        }
        override suspend fun delete(alias: CredentialAlias) { values.remove(alias) }
    }

    private class FakeSources : SourceWriteGateway {
        val mutations = mutableListOf<SourceMutation>()
        var failure: Exception? = null
        override suspend fun applySourceMutation(mutation: SourceMutation): MutationResult {
            failure?.let { throw it }
            mutations += mutation
            return MutationResult(inserted = 1)
        }
    }
}
