package com.cleartune.playback

import java.security.MessageDigest
import java.util.LinkedHashMap

internal object PrivateMediaSourceRegistry {
    private const val SCHEME = "cleartune-media"
    private const val MAX_ENTRIES = 256
    private val sources = LinkedHashMap<String, String>(MAX_ENTRIES, 0.75f, true)

    @Synchronized
    fun register(mediaId: String, actualUri: String): String {
        val opaqueUri = opaqueUri(mediaId, actualUri)
        sources[opaqueUri] = actualUri
        while (sources.size > MAX_ENTRIES) {
            sources.remove(sources.entries.first().key)
        }
        return opaqueUri
    }

    @Synchronized
    fun replace(entries: List<Pair<String, String>>): List<String> {
        sources.clear()
        return entries.map { (mediaId, actualUri) -> register(mediaId, actualUri) }
    }

    @Synchronized
    fun clear() = sources.clear()

    @Synchronized
    fun resolve(opaqueUri: String): String? = sources[opaqueUri]

    fun opaqueUri(mediaId: String, actualUri: String): String {
        val token = MessageDigest.getInstance("SHA-256")
            .digest("$mediaId\u0000$actualUri".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "$SCHEME://item/$token"
    }
}
