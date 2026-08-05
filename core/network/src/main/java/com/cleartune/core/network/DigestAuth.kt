package com.cleartune.core.network

import java.security.MessageDigest
import java.util.Locale

enum class DigestAlgorithm(val token: String, val messageDigest: String, val session: Boolean) {
    MD5("MD5", "MD5", false),
    MD5_SESS("MD5-sess", "MD5", true),
    SHA_256("SHA-256", "SHA-256", false),
    SHA_256_SESS("SHA-256-sess", "SHA-256", true),
}

data class DigestChallenge(
    val realm: String,
    val nonce: String,
    val algorithm: DigestAlgorithm,
    val qop: Set<String>,
    val opaque: String?,
    val stale: Boolean,
)

class UnsupportedDigestChallenge(message: String) : IllegalArgumentException(message)

object DigestAuth {
    fun parseChallenge(header: String): DigestChallenge {
        if (!header.trimStart().startsWith("Digest ", ignoreCase = true)) {
            throw UnsupportedDigestChallenge("Not a Digest challenge")
        }
        val parameters = parseParameters(header.trim().substringAfter(' '))
        val realm = parameters["realm"] ?: throw UnsupportedDigestChallenge("Missing realm")
        val nonce = parameters["nonce"] ?: throw UnsupportedDigestChallenge("Missing nonce")
        val algorithmToken = parameters["algorithm"] ?: "MD5"
        val algorithm = DigestAlgorithm.entries.firstOrNull {
            it.token.equals(algorithmToken, ignoreCase = true)
        } ?: throw UnsupportedDigestChallenge("Unsupported digest algorithm")
        val qop = parameters["qop"]
            ?.split(',')
            ?.map { it.trim().lowercase(Locale.US) }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()
        if (qop.isNotEmpty() && "auth" !in qop) {
            throw UnsupportedDigestChallenge("Only qop=auth is supported")
        }
        return DigestChallenge(
            realm = realm,
            nonce = nonce,
            algorithm = algorithm,
            qop = qop,
            opaque = parameters["opaque"],
            stale = parameters["stale"].equals("true", ignoreCase = true),
        )
    }

    fun authorization(
        challenge: String,
        method: String,
        requestUri: String,
        username: String,
        password: CharArray,
        nonceCount: Int,
        cnonce: String,
    ): String {
        require(nonceCount > 0)
        val parsed = parseChallenge(challenge)
        val nc = nonceCount.toString(16).padStart(8, '0')
        val passwordText = String(password)
        var ha1 = hash(parsed.algorithm, "$username:${parsed.realm}:$passwordText")
        if (parsed.algorithm.session) {
            ha1 = hash(parsed.algorithm, "$ha1:${parsed.nonce}:$cnonce")
        }
        val ha2 = hash(parsed.algorithm, "${method.uppercase(Locale.US)}:$requestUri")
        val response = if (parsed.qop.isEmpty()) {
            hash(parsed.algorithm, "$ha1:${parsed.nonce}:$ha2")
        } else {
            hash(parsed.algorithm, "$ha1:${parsed.nonce}:$nc:$cnonce:auth:$ha2")
        }

        return buildString {
            append("Digest username=\"").append(quote(username)).append("\"")
            append(", realm=\"").append(quote(parsed.realm)).append("\"")
            append(", nonce=\"").append(quote(parsed.nonce)).append("\"")
            append(", uri=\"").append(quote(requestUri)).append("\"")
            append(", response=\"").append(response).append("\"")
            append(", algorithm=").append(parsed.algorithm.token)
            if (parsed.qop.isNotEmpty()) {
                append(", qop=auth, nc=").append(nc)
                append(", cnonce=\"").append(quote(cnonce)).append("\"")
            }
            parsed.opaque?.let { append(", opaque=\"").append(quote(it)).append("\"") }
        }
    }

    private fun hash(algorithm: DigestAlgorithm, value: String): String =
        MessageDigest.getInstance(algorithm.messageDigest)
            .digest(value.toByteArray(Charsets.ISO_8859_1))
            .joinToString("") { "%02x".format(it) }

    private fun quote(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun parseParameters(input: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var index = 0
        while (index < input.length) {
            while (index < input.length && (input[index].isWhitespace() || input[index] == ',')) index++
            val keyStart = index
            while (index < input.length && input[index] != '=' && input[index] != ',') index++
            if (index >= input.length || input[index] != '=') break
            val key = input.substring(keyStart, index).trim().lowercase(Locale.US)
            index++
            while (index < input.length && input[index].isWhitespace()) index++
            val value = if (index < input.length && input[index] == '"') {
                index++
                buildString {
                    while (index < input.length) {
                        val character = input[index++]
                        when {
                            character == '\\' && index < input.length -> append(input[index++])
                            character == '"' -> break
                            else -> append(character)
                        }
                    }
                }
            } else {
                val start = index
                while (index < input.length && input[index] != ',') index++
                input.substring(start, index).trim()
            }
            if (key.isNotEmpty()) result[key] = value
            while (index < input.length && input[index] != ',') index++
        }
        return result
    }
}
