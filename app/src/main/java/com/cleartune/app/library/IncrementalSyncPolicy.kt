package com.cleartune.app.library

internal const val PLAYED_SONG_VERIFICATION_MAX_AGE_MS = 24L * 60 * 60 * 1_000
internal const val PLAYED_SONG_RETRY_DELAY_MS = 15L * 60 * 1_000
internal const val AUTOMATIC_LIBRARY_RECONCILIATION_AGE_MS = 7L * 24 * 60 * 60 * 1_000
internal const val DETAIL_REFRESH_MAX_AGE_MS = 24L * 60 * 60 * 1_000

internal fun shouldVerifyPlayedSong(
    lastVerifiedAt: Long,
    lastAttemptAt: Long,
    now: Long = System.currentTimeMillis(),
): Boolean = now - lastVerifiedAt >= PLAYED_SONG_VERIFICATION_MAX_AGE_MS &&
    now - lastAttemptAt >= PLAYED_SONG_RETRY_DELAY_MS

internal fun shouldRefreshDetail(updatedAt: Long?, now: Long = System.currentTimeMillis()): Boolean =
    updatedAt == null || now - updatedAt >= DETAIL_REFRESH_MAX_AGE_MS

internal fun shouldReconcileLibrary(lastSyncedAt: Long, now: Long = System.currentTimeMillis()): Boolean =
    lastSyncedAt > 0L && now - lastSyncedAt >= AUTOMATIC_LIBRARY_RECONCILIATION_AGE_MS
