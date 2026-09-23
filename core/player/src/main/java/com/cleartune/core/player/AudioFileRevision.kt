package com.cleartune.core.player

import android.content.Context
import java.security.MessageDigest

/** A persisted generation prevents byte ranges cached before tag edits from being reused. */
object AudioFileRevision {
    private fun preferences(context: Context) = context.getSharedPreferences("audio_file_revisions", Context.MODE_PRIVATE)
    private fun key(account: String, songId: String) = MessageDigest.getInstance("SHA-256")
        .digest("$account\n$songId".toByteArray()).joinToString("") { "%02x".format(it) }
    fun get(context: Context, account: String, songId: String): Long = preferences(context).getLong(key(account, songId), 0)
    fun bump(context: Context, account: String, songId: String) {
        val pref = preferences(context)
        val key = key(account, songId)
        check(pref.edit().putLong(key, pref.getLong(key, 0) + 1).commit())
    }

    fun cacheKey(context: Context, original: String): String {
        val identity = cacheIdentity(original) ?: return original
        val revision = get(context, identity.first, identity.second)
        return revisedPlaybackCacheKey(original, revision)
    }

    private fun cacheIdentity(key: String): Pair<String, String>? {
        if (!key.startsWith("cleartune:v2:") || ":user=" !in key || ":song=" !in key) return null
        val endpoint = key.removePrefix("cleartune:v2:").substringBefore(":user=")
        val username = java.net.URLDecoder.decode(key.substringAfter(":user=").substringBefore(":song="), "UTF-8")
        val base = endpoint.substringBeforeLast("/rest/")
        return com.cleartune.core.model.accountStorageKey(base, username) to key.substringAfter(":song=").substringBefore(":maxBitRate=")
    }
}

internal fun revisedPlaybackCacheKey(key: String, revision: Long): String =
    if (revision == 0L || ":maxBitRate=" !in key) key
    else key.replace(":maxBitRate=", ":tagRevision=$revision:maxBitRate=")
