package com.cleartune.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class UnsafeWebDavUrl(message: String) : IllegalArgumentException(message)

object WebDavUrlPolicy {
    private val traversalSegment = Regex("(?i)(?:^|/)(?:\\.|%2e){1,2}(?:/|$)")

    fun normalizeBaseUrl(raw: String, allowCleartext: Boolean): HttpUrl {
        val candidate = raw.trim()
        if (candidate.isEmpty()) throw UnsafeWebDavUrl("WebDAV URL is empty")
        val pathAndBeyond = candidate.substringAfter("://", candidate)
        val rawPath = pathAndBeyond.substringAfter('/', "").substringBefore('?').substringBefore('#')
        if (traversalSegment.containsMatchIn("/$rawPath")) {
            throw UnsafeWebDavUrl("WebDAV URL contains a traversal segment")
        }

        val parsed = candidate.toHttpUrlOrNull()
            ?: throw UnsafeWebDavUrl("WebDAV URL is invalid")
        if (parsed.scheme != "https" && parsed.scheme != "http") {
            throw UnsafeWebDavUrl("Only HTTP and HTTPS are supported")
        }
        if (parsed.scheme == "http" && !allowCleartext) {
            throw UnsafeWebDavUrl("Cleartext HTTP requires explicit confirmation")
        }
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            throw UnsafeWebDavUrl("Credentials must not be embedded in the URL")
        }
        if (parsed.fragment != null) throw UnsafeWebDavUrl("Fragments are not supported")
        if (parsed.query != null) throw UnsafeWebDavUrl("Query parameters are not supported in a base URL")

        return if (parsed.encodedPath.endsWith('/')) {
            parsed
        } else {
            parsed.newBuilder().addPathSegment("").build()
        }
    }

    fun isSameOrigin(first: HttpUrl, second: HttpUrl): Boolean =
        first.scheme == second.scheme && first.host == second.host && first.port == second.port

    fun isInBaseSubtree(base: HttpUrl, candidate: HttpUrl): Boolean =
        isSameOrigin(base, candidate) && candidate.encodedPath.startsWith(base.encodedPath)
}
