package com.cleartune.core.model

import java.net.URI
import java.security.MessageDigest

/** Stable storage namespace; never includes a password or authentication token. */
fun accountStorageKey(baseUrl: String, username: String): String {
    val uri = URI(baseUrl.trim())
    val scheme = uri.scheme.lowercase()
    val port = uri.port.takeUnless { it == -1 || (scheme == "https" && it == 443) || (scheme == "http" && it == 80) }
    val server = "$scheme://${uri.host.lowercase()}${port?.let { ":$it" }.orEmpty()}${uri.rawPath.orEmpty().trimEnd('/')}"
    val identity = "${server.length}:$server${username.length}:$username"
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
