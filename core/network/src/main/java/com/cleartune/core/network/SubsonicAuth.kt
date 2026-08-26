package com.cleartune.core.network

import com.cleartune.core.model.ServerCredentials
import java.security.MessageDigest
import java.security.SecureRandom

class SubsonicAuth(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun query(credentials: ServerCredentials): Map<String, String> {
        val saltBytes = ByteArray(12).also(secureRandom::nextBytes)
        val salt = saltBytes.joinToString("") { "%02x".format(it) }
        val token = md5(credentials.password + salt)
        return linkedMapOf(
            "u" to credentials.username,
            "t" to token,
            "s" to salt,
            "v" to API_VERSION,
            "c" to CLIENT_ID,
            "f" to "json",
        )
    }

    private fun md5(value: String): String {
        return MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val API_VERSION = "1.16.1"
        const val CLIENT_ID = "ClearTune"
    }
}
