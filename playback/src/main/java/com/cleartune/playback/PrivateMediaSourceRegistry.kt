package com.cleartune.playback

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal object PrivateMediaSourceRegistry {
    private const val SCHEME = "cleartune-media"
    private val sources = ConcurrentHashMap<String, String>()

    fun register(mediaId: String, actualUri: String): String {
        val token = MessageDigest.getInstance("SHA-256")
            .digest("$mediaId\u0000$actualUri".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        val opaqueUri = "$SCHEME://item/$token"
        sources[opaqueUri] = actualUri
        return opaqueUri
    }

    fun resolve(opaqueUri: String): String? = sources[opaqueUri]
}
