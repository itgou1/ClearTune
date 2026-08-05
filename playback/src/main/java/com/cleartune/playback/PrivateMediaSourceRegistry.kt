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
    private val queueCatalog = linkedMapOf<String, PrivateMediaSource>()

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
    fun replace(entries: List<Pair<String, String>>): List<String> =
        replaceSources(entries.map { (mediaId, actualUri) -> mediaId to PrivateMediaSource(actualUri) })

    @Synchronized
    fun replaceSources(entries: List<Pair<String, PrivateMediaSource>>): List<String> {
        sources.clear()
        activeSources.clear()
        queueCatalog.clear()
        val opaqueEntries = entries.map { (mediaId, source) ->
            val opaqueUri = opaqueUri(mediaId, source)
            opaqueUri to source
        }
        queueCatalog.putAll(opaqueEntries)
        opaqueEntries.take(MAX_ENTRIES).forEach { (opaqueUri, source) -> sources[opaqueUri] = source }
        if (entries.size <= MAX_ENTRIES) {
            entries.zip(opaqueEntries).forEach { pair ->
                activeSources[pair.first.first] = pair.second.first
            }
        }
        return opaqueEntries.map(Pair<String, PrivateMediaSource>::first)
    }

    @Synchronized
    fun clear() {
        sources.clear()
        activeSources.clear()
        queueCatalog.clear()
    }

    @Synchronized
    fun resolve(opaqueUri: String): String? = resolveEntry(opaqueUri)?.actualUri

    @Synchronized
    fun resolveEntry(opaqueUri: String): PrivateMediaSource? {
        sources[opaqueUri]?.let { return it }
        val queued = queueCatalog[opaqueUri] ?: return null
        makeRoomForOne()
        sources[opaqueUri] = queued
        return queued
    }

    @Synchronized
    fun retainOnly(opaqueUri: String?) {
        val retained = opaqueUri?.let { sources[it] ?: queueCatalog[it] }
        sources.clear()
        activeSources.clear()
        queueCatalog.clear()
        if (opaqueUri != null && retained != null) {
            sources[opaqueUri] = retained
            queueCatalog[opaqueUri] = retained
            activeSources[opaqueUri] = opaqueUri
        }
    }

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
