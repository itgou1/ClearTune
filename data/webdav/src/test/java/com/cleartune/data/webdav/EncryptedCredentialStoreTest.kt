package com.cleartune.data.webdav

import com.cleartune.core.contracts.WebDavCredential
import com.cleartune.core.model.CredentialAlias
import javax.crypto.KeyGenerator
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedCredentialStoreTest {
    private val alias = CredentialAlias("source-1")

    @Test
    fun `round trips credentials without storing plaintext`() = runTest {
        val blobs = MemoryCredentialBlobStore()
        val store = EncryptedCredentialStore(AesGcmCredentialCipher(newKey()), blobs)

        store.put(alias, WebDavCredential("alice", "correct horse".toCharArray()))

        val restored = requireNotNull(store.get(alias))
        assertTrue(restored.username == "alice")
        assertArrayEquals("correct horse".toCharArray(), restored.password)
        val persisted = requireNotNull(blobs.read(alias.value))
        assertFalse(persisted.toString(Charsets.UTF_8).contains("alice"))
        assertFalse(persisted.toString(Charsets.UTF_8).contains("correct horse"))
    }

    @Test
    fun `same credential uses a fresh nonce on every write`() = runTest {
        val blobs = MemoryCredentialBlobStore()
        val store = EncryptedCredentialStore(AesGcmCredentialCipher(newKey()), blobs)
        val credential = WebDavCredential("alice", "secret".toCharArray())

        store.put(alias, credential)
        val first = requireNotNull(blobs.read(alias.value))
        store.put(alias, credential)
        val second = requireNotNull(blobs.read(alias.value))

        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `delete removes the credential`() = runTest {
        val store = EncryptedCredentialStore(AesGcmCredentialCipher(newKey()), MemoryCredentialBlobStore())
        store.put(alias, WebDavCredential("alice", "secret".toCharArray()))

        store.delete(alias)

        assertNull(store.get(alias))
    }

    @Test
    fun `corrupted ciphertext is reported without leaking its contents`() = runTest {
        val blobs = MemoryCredentialBlobStore()
        val store = EncryptedCredentialStore(AesGcmCredentialCipher(newKey()), blobs)
        blobs.write(alias.value, byteArrayOf(1, 2, 3, 4))

        val failure = try {
            store.get(alias)
            error("Expected encrypted data to be rejected")
        } catch (error: CredentialUnavailableException) {
            error
        }

        assertTrue(failure.message == "Credential data is unavailable")
    }

    @Test
    fun `file blob store round trips and deletes opaque data`() {
        val root = Files.createTempDirectory("credential-blobs-").toFile()
        val store = FileCredentialBlobStore(root)
        val value = byteArrayOf(1, 2, 3, 4)

        store.write(alias.value, value)
        assertArrayEquals(value, store.read(alias.value))

        store.delete(alias.value)
        assertNull(store.read(alias.value))
        assertTrue(root.listFiles().orEmpty().none { it.isFile })
    }

    @Test
    fun `decoded password buffer is cleared after copying`() {
        val backing = charArrayOf('s', 'e', 'c', 'r', 'e', 't')
        val decoder = ClearableUtf8Decoder(decodeBuffer = { CharBuffer.wrap(backing) })

        val result = decoder.decode(byteArrayOf())

        assertArrayEquals("secret".toCharArray(), result)
        assertTrue(backing.all { it == '\u0000' })
    }

    @Test
    fun `decoded password buffer is cleared when result copying throws`() {
        val backing = charArrayOf('s', 'e', 'c', 'r', 'e', 't')
        val decoder = ClearableUtf8Decoder(
            decodeBuffer = { CharBuffer.wrap(backing) },
            copyBuffer = { throw IllegalStateException("copy failed") },
        )

        runCatching { decoder.decode(ByteBuffer.allocate(0).array()) }

        assertTrue(backing.all { it == '\u0000' })
    }

    private fun newKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}
