package com.cleartune.playback

import java.security.MessageDigest
import java.util.LinkedHashMap

internal object PrivateMediaSourceRegistry {
    private const val SCHEME = "cleartune-media"
    private const val MAX_ENTRIES = 256
    private val sources = LinkedHashMap<String, String>(MAX_ENTRIES, 0.75f, true)
    private val activeSources = linkedMapOf<String, String>()

    internal val capacity: Int get() = MAX_ENTRIES
    internal val size: Int @Synchronized get() = sources.size

    @Synchronized
    fun register(mediaId: String, actualUri: String): String {
        val opaqueUri = opaqueUri(mediaId, actualUri)
        if (opaqueUri !in sources) makeRoomForOne()
        sources[opaqueUri] = actualUri
        return opaqueUri
    }

    @Synchronized
    fun registerActive(mediaId: String, actualUri: String): String {
        activeSources.remove(mediaId)?.let(sources::remove)
        val opaqueUri = opaqueUri(mediaId, actualUri)
        if (opaqueUri !in sources) makeRoomForOne()
        sources[opaqueUri] = actualUri
        activeSources[mediaId] = opaqueUri
        return opaqueUri
    }

    @Synchronized
    fun replace(entries: List<Pair<String, String>>): List<String> {
        require(entries.size <= MAX_ENTRIES) {
            "Active playback queue exceeds private media registry capacity of $MAX_ENTRIES"
        }
        sources.clear()
        activeSources.clear()
        return entries.map { (mediaId, actualUri) ->
            val opaqueUri = opaqueUri(mediaId, actualUri)
            sources[opaqueUri] = actualUri
            activeSources[mediaId] = opaqueUri
            opaqueUri
        }
    }

    @Synchronized
    fun clear() {
        sources.clear()
        activeSources.clear()
    }

    @Synchronized
    fun resolve(opaqueUri: String): String? = sources[opaqueUri]

    fun opaqueUri(mediaId: String, actualUri: String): String {
        val token = MessageDigest.getInstance("SHA-256")
            .digest("$mediaId\u0000$actualUri".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "$SCHEME://item/$token"
    }

    private fun makeRoomForOne() {
        if (sources.size < MAX_ENTRIES) return
        val pinned = activeSources.values.toSet()
        val evictable = sources.keys.firstOrNull { it !in pinned }
            ?: error("Private media registry is full of active playback entries")
        sources.remove(evictable)
    }
}
