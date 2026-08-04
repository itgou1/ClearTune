package com.cleartune.data.webdav

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.cleartune.core.contracts.CredentialStore
import com.cleartune.core.contracts.WebDavCredential
import com.cleartune.core.model.CredentialAlias
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface CredentialCipher {
    fun encrypt(alias: String, plaintext: ByteArray): ByteArray
    fun decrypt(alias: String, ciphertext: ByteArray): ByteArray
}

interface CredentialBlobStore {
    fun write(alias: String, ciphertext: ByteArray)
    fun read(alias: String): ByteArray?
    fun delete(alias: String)
}

class CredentialUnavailableException : Exception("Credential data is unavailable")

class EncryptedCredentialStore(
    private val cipher: CredentialCipher,
    private val blobs: CredentialBlobStore,
) : CredentialStore {
    override suspend fun put(alias: CredentialAlias, credential: WebDavCredential) {
        val username = credential.username.toByteArray(Charsets.UTF_8)
        val password = credential.password.concatToString().toByteArray(Charsets.UTF_8)
        val plaintext = ByteBuffer.allocate(Int.SIZE_BYTES * 2 + username.size + password.size)
            .putInt(username.size)
            .put(username)
            .putInt(password.size)
            .put(password)
            .array()
        try {
            blobs.write(alias.value, cipher.encrypt(alias.value, plaintext))
        } catch (_: Exception) {
            throw CredentialUnavailableException()
        } finally {
            username.fill(0)
            password.fill(0)
            plaintext.fill(0)
        }
    }

    override suspend fun get(alias: CredentialAlias): WebDavCredential? {
        val encrypted = blobs.read(alias.value) ?: return null
        var plaintext: ByteArray? = null
        return try {
            plaintext = cipher.decrypt(alias.value, encrypted)
            decode(requireNotNull(plaintext))
        } catch (_: Exception) {
            throw CredentialUnavailableException()
        } finally {
            encrypted.fill(0)
            plaintext?.fill(0)
        }
    }

    override suspend fun delete(alias: CredentialAlias) = blobs.delete(alias.value)

    private fun decode(bytes: ByteArray): WebDavCredential {
        val buffer = ByteBuffer.wrap(bytes)
        val usernameLength = buffer.int
        require(usernameLength in 0..buffer.remaining())
        val usernameBytes = ByteArray(usernameLength).also(buffer::get)
        require(buffer.remaining() >= Int.SIZE_BYTES)
        val passwordLength = buffer.int
        require(passwordLength in 0..buffer.remaining())
        require(passwordLength == buffer.remaining())
        val passwordBytes = ByteArray(passwordLength).also(buffer::get)
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val username = decoder.decode(ByteBuffer.wrap(usernameBytes)).toString()
            val password = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(passwordBytes))
                .let { chars -> CharArray(chars.remaining()).also(chars::get) }
            WebDavCredential(username, password)
        } finally {
            usernameBytes.fill(0)
            passwordBytes.fill(0)
        }
    }
}

class AesGcmCredentialCipher(
    private val key: SecretKey,
    private val secureRandom: SecureRandom = SecureRandom(),
) : CredentialCipher {
    override fun encrypt(alias: String, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_SIZE).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
        val payload = cipher.doFinal(plaintext)
        return byteArrayOf(FORMAT_VERSION) + nonce + payload
    }

    override fun decrypt(alias: String, ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > 1 + NONCE_SIZE)
        require(ciphertext[0] == FORMAT_VERSION)
        val nonce = ciphertext.copyOfRange(1, 1 + NONCE_SIZE)
        val payload = ciphertext.copyOfRange(1 + NONCE_SIZE, ciphertext.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
            cipher.doFinal(payload)
        } finally {
            nonce.fill(0)
            payload.fill(0)
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_SIZE = 12
        const val TAG_BITS = 128
        const val FORMAT_VERSION: Byte = 1
    }
}

class AndroidKeystoreCredentialCipher(
    keyAlias: String = DEFAULT_KEY_ALIAS,
) : CredentialCipher by AesGcmCredentialCipher(loadOrCreateKey(keyAlias)) {
    private companion object {
        const val DEFAULT_KEY_ALIAS = "cleartune.webdav.credentials.v1"

        fun loadOrCreateKey(alias: String): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }.generateKey()
        }
    }
}

class SharedPreferencesCredentialBlobStore(context: Context) : CredentialBlobStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun write(alias: String, ciphertext: ByteArray) {
        val value = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        check(preferences.edit().putString(alias, value).commit())
    }

    override fun read(alias: String): ByteArray? =
        preferences.getString(alias, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    override fun delete(alias: String) {
        check(preferences.edit().remove(alias).commit())
    }

    private companion object {
        const val PREFERENCES_NAME = "cleartune_webdav_credentials"
    }
}

class MemoryCredentialBlobStore : CredentialBlobStore {
    private val values = mutableMapOf<String, ByteArray>()

    override fun write(alias: String, ciphertext: ByteArray) {
        values.put(alias, ciphertext.copyOf())?.fill(0)
    }

    override fun read(alias: String): ByteArray? = values[alias]?.copyOf()

    override fun delete(alias: String) {
        values.remove(alias)?.fill(0)
    }
}
