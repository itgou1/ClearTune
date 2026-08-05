package com.cleartune.data.webdav

import okhttp3.HttpUrl

data class WebDavEntry(
    val href: HttpUrl,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long?,
    val etag: String?,
    val modifiedEpochMs: Long? = null,
)

data class RangeResponse(
    val bytes: ByteArray,
    val contentRange: String?,
    val rangeHonored: Boolean,
    val etag: String?,
)
