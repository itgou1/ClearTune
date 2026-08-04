package com.cleartune.data.webdav

import com.cleartune.core.network.WebDavUrlPolicy
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory
import okhttp3.HttpUrl
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

class WebDavXmlParser {
    fun parse(baseUrl: HttpUrl, directoryUrl: HttpUrl, input: InputStream): List<WebDavEntry> {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val handler = MultiStatusHandler(baseUrl, directoryUrl)
        factory.newSAXParser().parse(input, handler)
        return handler.entries
    }
}

private class MultiStatusHandler(
    private val baseUrl: HttpUrl,
    private val directoryUrl: HttpUrl,
) : DefaultHandler() {
    val entries = mutableListOf<WebDavEntry>()
    private val seen = mutableSetOf<String>()
    private var response: ResponseValues? = null
    private var propstat: PropertyValues? = null
    private var text = StringBuilder()
    private var inCollection = false

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
        text = StringBuilder()
        when (localName.orEmpty().lowercase()) {
            "response" -> response = ResponseValues()
            "propstat" -> propstat = PropertyValues()
            "collection" -> inCollection = true
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        text.append(ch, start, length)
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        val value = text.toString().trim()
        when (localName.orEmpty().lowercase()) {
            "href" -> response?.href = value
            "getcontentlength" -> propstat?.sizeBytes = value.toLongOrNull()
            "getetag" -> propstat?.etag = value.ifEmpty { null }
            "collection" -> propstat?.isDirectory = inCollection.also { inCollection = false }
            "status" -> propstat?.status = value
            "propstat" -> {
                val properties = propstat
                if (properties != null && properties.status.isSuccessStatus()) {
                    response?.merge(properties)
                }
                propstat = null
            }
            "response" -> {
                response?.toEntry(baseUrl, directoryUrl)?.let { entry ->
                    if (seen.add(entry.href.toString())) entries += entry
                }
                response = null
            }
        }
        text = StringBuilder()
    }
}

private data class PropertyValues(
    var status: String? = null,
    var sizeBytes: Long? = null,
    var etag: String? = null,
    var isDirectory: Boolean = false,
)

private data class ResponseValues(
    var href: String? = null,
    var sizeBytes: Long? = null,
    var etag: String? = null,
    var isDirectory: Boolean = false,
) {
    fun merge(properties: PropertyValues) {
        properties.sizeBytes?.let { sizeBytes = it }
        properties.etag?.let { etag = it }
        isDirectory = isDirectory || properties.isDirectory
    }

    fun toEntry(baseUrl: HttpUrl, directoryUrl: HttpUrl): WebDavEntry? {
        val resolved = href?.let(directoryUrl::resolve) ?: return null
        if (!WebDavUrlPolicy.isInBaseSubtree(baseUrl, resolved)) return null
        if (resolved.encodedPath == directoryUrl.encodedPath) return null
        val name = resolved.pathSegments.lastOrNull { it.isNotBlank() } ?: return null
        return WebDavEntry(resolved, name, isDirectory, sizeBytes, etag)
    }
}

private fun String?.isSuccessStatus(): Boolean {
    val statusCode = this?.split(' ')?.firstOrNull { it.length == 3 && it.all(Char::isDigit) }?.toIntOrNull()
    return statusCode in 200..299
}
