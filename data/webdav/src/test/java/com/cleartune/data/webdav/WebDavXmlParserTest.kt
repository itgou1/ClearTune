package com.cleartune.data.webdav

import com.cleartune.core.network.WebDavUrlPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WebDavXmlParserTest {
    @Test
    fun parses_namespaced_multistatus_filters_self_duplicates_and_untrusted_hrefs() {
        val base = WebDavUrlPolicy.normalizeBaseUrl("https://music.example/dav/", false)
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
              <d:response><d:href>/dav/Album%20One/song.mp3</d:href><d:propstat><d:prop><d:getcontentlength>1234</d:getcontentlength><d:getetag>"etag-1"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
              <d:response><d:href>Album%20One/song.mp3</d:href><d:propstat><d:prop><d:getcontentlength>1234</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
              <d:response><d:href>https://evil.example/leak.mp3</d:href><d:propstat><d:prop><d:getcontentlength>99</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
              <d:response><d:href>/outside/secret.mp3</d:href><d:propstat><d:prop/><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            </d:multistatus>
        """.trimIndent()

        val entries = WebDavXmlParser().parse(base, base, xml.byteInputStream())

        assertEquals(1, entries.size)
        assertEquals("song.mp3", entries.single().name)
        assertEquals(1234L, entries.single().sizeBytes)
        assertEquals("\"etag-1\"", entries.single().etag)
        assertFalse(entries.single().isDirectory)
    }

    @Test
    fun ignores_properties_from_failed_propstat() {
        val base = WebDavUrlPolicy.normalizeBaseUrl("https://music.example/dav/", false)
        val xml = """
            <multistatus xmlns="DAV:"><response><href>/dav/song.flac</href>
              <propstat><prop><getcontentlength>9000</getcontentlength></prop><status>HTTP/1.1 404 Not Found</status></propstat>
              <propstat><prop><getetag>good</getetag></prop><status>HTTP/1.1 200 OK</status></propstat>
            </response></multistatus>
        """.trimIndent()

        val entry = WebDavXmlParser().parse(base, base, xml.byteInputStream()).single()

        assertEquals(null, entry.sizeBytes)
        assertEquals("good", entry.etag)
    }
}
