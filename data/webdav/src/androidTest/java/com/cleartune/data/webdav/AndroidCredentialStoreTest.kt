package com.cleartune.data.webdav

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cleartune.core.contracts.WebDavCredential
import com.cleartune.core.model.CredentialAlias
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidCredentialStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val keyAliases = mutableListOf<String>()

    @After
    fun cleanupKeys() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyAliases.forEach(keyStore::deleteEntry)
    }

    @Test
    fun keystore_round_trip_and_delete() = runBlocking {
        val keyAlias = uniqueKeyAlias()
        val alias = CredentialAlias("source")
        val blobs = SharedPreferencesCredentialBlobStore(context)
        val store = EncryptedCredentialStore(AndroidKeystoreCredentialCipher(keyAlias), blobs)

        store.put(alias, WebDavCredential("alice", charArrayOf('s', 'e', 'c', 'r', 'e', 't')))
        val restored = requireNotNull(store.get(alias))
        assertArrayEquals(charArrayOf('s', 'e', 'c', 'r', 'e', 't'), restored.password)

        store.delete(alias)
        assertNull(store.get(alias))
    }

    @Test
    fun incompatible_restored_blob_is_reported_and_store_is_no_backup_backed() = runBlocking {
        val alias = CredentialAlias("restored")
        val blobs = SharedPreferencesCredentialBlobStore(context)
        assertTrue(blobs.rootDirectory.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        EncryptedCredentialStore(AndroidKeystoreCredentialCipher(uniqueKeyAlias()), blobs)
            .put(alias, WebDavCredential("alice", charArrayOf('x')))

        try {
            EncryptedCredentialStore(AndroidKeystoreCredentialCipher(uniqueKeyAlias()), blobs).get(alias)
            error("Expected a restored blob encrypted by a missing key to be rejected")
        } catch (_: CredentialUnavailableException) {
            Unit
        } finally {
            blobs.delete(alias.value)
        }
    }

    private fun uniqueKeyAlias(): String = "cleartune-test-${UUID.randomUUID()}".also(keyAliases::add)
}
