package com.cleartune.playback

import java.security.MessageDigest
import java.util.LinkedHashMap

internal data class PrivateMediaSource(
    val actualUri: String,
    val sourceId: String? = null,
    val locationId: String? = null,
)

internal object PrivateMediaSourceRegistry {
    private const val SCHEME = "cleartune-media"
    private const val MAX_ENTRIES = 256
    private val sources = LinkedHashMap<String, PrivateMediaSource>(MAX_ENTRIES, 0.75f, true)
    private val activeSources = linkedMapOf<String, String>()

    internal val capacity: Int get() = MAX_ENTRIES
    internal val size: Int @Synchronized get() = sources.size

    @Synchronized
    fun register(mediaId: String, actualUri: String): String =
        register(mediaId, PrivateMediaSource(actualUri))

    @Synchronized
    fun register(mediaId: String, source: PrivateMediaSource): String {
        val opaqueUri = opaqueUri(mediaId, source)
        if (opaqueUri !in sources) makeRoomForOne()
        sources[opaqueUri] = source
        return opaqueUri
    }

    @Synchronized
    fun registerActive(mediaId: String, actualUri: String): String =
        registerActive(mediaId, PrivateMediaSource(actualUri))

    @Synchronized
    fun registerActive(mediaId: String, source: PrivateMediaSource): String {
        activeSources.remove(mediaId)?.let(sources::remove)
        val opaqueUri = opaqueUri(mediaId, source)
        if (opaqueUri !in sources) makeRoomForOne()
        sources[opaqueUri] = source
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
            val source = PrivateMediaSource(actualUri)
            val opaqueUri = opaqueUri(mediaId, source)
            sources[opaqueUri] = source
            activeSources[mediaId] = opaqueUri
            opaqueUri
        }
    }

    @Synchronized
    fun replaceSources(entries: List<Pair<String, PrivateMediaSource>>): List<String> {
        require(entries.size <= MAX_ENTRIES) {
            "Active playback queue exceeds private media registry capacity of $MAX_ENTRIES"
        }
        sources.clear()
        activeSources.clear()
        return entries.map { (mediaId, source) ->
            val opaqueUri = opaqueUri(mediaId, source)
            sources[opaqueUri] = source
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
    fun resolve(opaqueUri: String): String? = sources[opaqueUri]?.actualUri

    @Synchronized
    fun resolveEntry(opaqueUri: String): PrivateMediaSource? = sources[opaqueUri]

    fun opaqueUri(mediaId: String, actualUri: String): String =
        opaqueUri(mediaId, PrivateMediaSource(actualUri))

    private fun opaqueUri(mediaId: String, source: PrivateMediaSource): String {
        val token = MessageDigest.getInstance("SHA-256")
            .digest(
                "$mediaId\u0000${source.actualUri}\u0000${source.sourceId.orEmpty()}\u0000${source.locationId.orEmpty()}"
                    .toByteArray(Charsets.UTF_8),
            )
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
