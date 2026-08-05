package com.cleartune.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class UnsafeWebDavUrl(message: String) : IllegalArgumentException(message)

object WebDavUrlPolicy {
    private val traversalSegment = Regex("(?i)(?:^|/)(?:\\.|%2e){1,2}(?:/|$)")
    private val encodedSeparator = Regex("(?i)%2f|%5c")
    private val encodedTraversalSegment = Regex("(?i)(?:\\.|%2e){1,2}")

    fun normalizeBaseUrl(raw: String, allowCleartext: Boolean): HttpUrl {
        val candidate = raw.trim()
        if (candidate.isEmpty()) throw UnsafeWebDavUrl("WebDAV URL is empty")
        val pathAndBeyond = candidate.substringAfter("://", candidate)
        val rawPath = pathAndBeyond.substringAfter('/', "").substringBefore('?').substringBefore('#')
        if (traversalSegment.containsMatchIn("/$rawPath") ||
            encodedSeparator.containsMatchIn(rawPath) ||
            rawPath.contains('\\')
        ) {
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

    fun isInBaseSubtree(base: HttpUrl, candidate: HttpUrl): Boolean {
        if (!isSameOrigin(base, candidate) || hasUrlDecorations(candidate) || hasDangerousPath(candidate)) {
            return false
        }
        val baseSegments = normalizedPathSegments(base)
        val candidateSegments = normalizedPathSegments(candidate)
        return candidateSegments.size >= baseSegments.size &&
            candidateSegments.take(baseSegments.size) == baseSegments
    }

    fun resolveDescendant(base: HttpUrl, directory: HttpUrl, rawHref: String): HttpUrl? {
        val href = rawHref.trim()
        if (href.isEmpty() || href.contains('\\') || encodedSeparator.containsMatchIn(href)) return null
        val path = href.substringBefore('?').substringBefore('#')
        if (path.split('/').any(::isTraversalSegment)) return null
        val resolved = directory.resolve(href) ?: return null
        return resolved.takeIf { isInBaseSubtree(base, it) }
    }

    private fun normalizedPathSegments(url: HttpUrl): List<String> =
        url.pathSegments.dropLastWhile { it.isEmpty() }

    private fun hasUrlDecorations(url: HttpUrl): Boolean =
        url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null

    private fun hasDangerousPath(url: HttpUrl): Boolean =
        encodedSeparator.containsMatchIn(url.encodedPath) ||
            url.pathSegments.any { segment ->
                encodedSeparator.containsMatchIn(segment) || isTraversalSegment(segment)
            }

    private fun isTraversalSegment(segment: String): Boolean =
        encodedTraversalSegment.matches(segment)
}
