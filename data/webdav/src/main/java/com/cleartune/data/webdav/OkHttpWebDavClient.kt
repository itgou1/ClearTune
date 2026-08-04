package com.cleartune.data.webdav

import com.cleartune.core.contracts.CredentialStore
import com.cleartune.core.model.MusicSource
import com.cleartune.core.model.SourceType
import com.cleartune.core.network.NetworkFailure
import com.cleartune.core.network.WebDavAuthenticator
import com.cleartune.core.network.WebDavUrlPolicy
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class WebDavProtocolException(val failure: NetworkFailure) : Exception(failure.safeMessage)

fun interface DirectoryListingClient {
    suspend fun list(source: MusicSource, directory: HttpUrl): List<WebDavEntry>
}

class OkHttpWebDavClient(
    private val baseClient: OkHttpClient,
    private val credentialStore: CredentialStore? = null,
    private val xmlParser: WebDavXmlParser = WebDavXmlParser(),
    private val maxXmlBytes: Int = 8 * 1024 * 1024,
) : DirectoryListingClient {
    override suspend fun list(source: MusicSource, directory: HttpUrl): List<WebDavEntry> = withContext(Dispatchers.IO) {
        val baseUrl = validatedBase(source)
        require(WebDavUrlPolicy.isInBaseSubtree(baseUrl, directory)) { "Directory is outside source root" }
        val requestBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getetag/><d:getlastmodified/></d:prop></d:propfind>
        """.trimIndent().toRequestBody("application/xml; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(directory)
            .header("Depth", "1")
            .method("PROPFIND", requestBody)
            .build()
        execute(source, baseUrl, request) { response ->
            if (response.code != 207) throw WebDavProtocolException(NetworkFailure.fromHttpStatus(response.code))
            val bytes = response.body.byteStream().readCapped(maxXmlBytes)
            xmlParser.parse(baseUrl, directory, bytes.inputStream())
        }
    }

    suspend fun readRange(
        source: MusicSource,
        url: HttpUrl,
        start: Long,
        endInclusive: Long,
        maxBytes: Int,
    ): RangeResponse = withContext(Dispatchers.IO) {
        require(start >= 0 && endInclusive >= start)
        require(endInclusive - start + 1 <= maxBytes)
        val baseUrl = validatedBase(source)
        require(WebDavUrlPolicy.isInBaseSubtree(baseUrl, url)) { "Range URL is outside source root" }
        val request = Request.Builder().url(url).header("Range", "bytes=$start-$endInclusive").build()
        execute(source, baseUrl, request) { response ->
            if (response.code !in listOf(200, 206)) {
                throw WebDavProtocolException(NetworkFailure.fromHttpStatus(response.code))
            }
            val bytes = response.body.byteStream().readCapped(maxBytes)
            RangeResponse(
                bytes = bytes,
                contentRange = response.header("Content-Range"),
                rangeHonored = response.code == 206,
                etag = response.header("ETag"),
            )
        }
    }

    private suspend fun <T> execute(
        source: MusicSource,
        baseUrl: HttpUrl,
        request: Request,
        block: (okhttp3.Response) -> T,
    ): T {
        val credential = source.credentialAlias?.let { alias ->
            credentialStore?.get(alias) ?: error("Credential unavailable")
        }
        val client = baseClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .apply {
                if (credential != null) {
                    authenticator(WebDavAuthenticator(baseUrl, { credential }))
                }
            }
            .build()
        return try {
            client.newCall(request).execute().use(block)
        } finally {
            credential?.password?.fill('\u0000')
        }
    }

    private fun validatedBase(source: MusicSource): HttpUrl {
        require(source.type == SourceType.WEBDAV) { "Source must be WebDAV" }
        return WebDavUrlPolicy.normalizeBaseUrl(
            raw = requireNotNull(source.baseUrl) { "WebDAV source is missing a base URL" },
            allowCleartext = source.allowCleartext,
        )
    }
}

private fun java.io.InputStream.readCapped(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read == -1) break
        total += read
        if (total > maxBytes) throw IllegalStateException("Remote response exceeds safe limit")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
