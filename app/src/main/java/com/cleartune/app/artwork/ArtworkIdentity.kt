package com.cleartune.app.artwork

import java.security.MessageDigest

internal fun artworkFileName(id: String): String = MessageDigest.getInstance("SHA-256")
    .digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } + ".img"

internal fun artworkCacheKey(account: String, id: String, size: Int): String {
    require(account.matches(Regex("[a-f0-9]{64}")))
    return "artwork:v2:$account:${artworkFileName(id)}:${size.coerceIn(96, 1_200)}"
}
