package com.cleartune.core.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavUrlPolicyTest {
    @Test
    fun normalizes_https_base_url_and_encodes_path() {
        val url = WebDavUrlPolicy.normalizeBaseUrl(
            raw = "https://music.example/我的 音乐",
            allowCleartext = false,
        )

        assertEquals("https://music.example/%E6%88%91%E7%9A%84%20%E9%9F%B3%E4%B9%90/", url.toString())
    }

    @Test
    fun rejects_cleartext_without_explicit_opt_in() {
        assertThrows(UnsafeWebDavUrl::class.java) {
            WebDavUrlPolicy.normalizeBaseUrl("http://music.example/dav", allowCleartext = false)
        }
    }

    @Test
    fun rejects_user_info_fragments_and_encoded_traversal() {
        listOf(
            "https://user:secret@music.example/dav",
            "https://music.example/dav#private",
            "https://music.example/dav/%2e%2e/secret",
            "https://music.example/dav/album%2Fsong",
            "https://music.example/dav/album%5Csong",
            "ftp://music.example/dav",
        ).forEach { raw ->
            assertThrows(raw, UnsafeWebDavUrl::class.java) {
                WebDavUrlPolicy.normalizeBaseUrl(raw, allowCleartext = false)
            }
        }
    }

    @Test
    fun origin_and_subtree_checks_block_untrusted_hrefs() {
        val base = WebDavUrlPolicy.normalizeBaseUrl("https://music.example/dav/root", false)
        val child = base.resolve("album/song.mp3")!!
        val sibling = base.resolve("../other/song.mp3")!!
        val foreign = WebDavUrlPolicy.normalizeBaseUrl("https://evil.example/dav", false)

        assertTrue(WebDavUrlPolicy.isSameOrigin(base, child))
        assertTrue(WebDavUrlPolicy.isInBaseSubtree(base, child))
        assertFalse(WebDavUrlPolicy.isInBaseSubtree(base, sibling))
        assertFalse(WebDavUrlPolicy.isSameOrigin(base, foreign))
    }

    @Test
    fun subtree_check_rejects_ambiguous_or_decorated_descendant_urls() {
        val base = WebDavUrlPolicy.normalizeBaseUrl("https://music.example/dav/root", false)
        val hostile = listOf(
            "https://user@music.example/dav/root/song.mp3",
            "https://music.example/dav/root/song.mp3?token=secret",
            "https://music.example/dav/root/song.mp3#fragment",
            "https://music.example/dav/root/album%2Fsong.mp3",
            "https://music.example/dav/root/album%5Csong.mp3",
            "https://music.example/dav/root/%252e%252e/secret.mp3",
            "https://music.example/dav/rooted/song.mp3",
        )

        hostile.forEach { raw ->
            assertFalse(raw, WebDavUrlPolicy.isInBaseSubtree(base, raw.toHttpUrl()))
        }
        assertTrue(
            WebDavUrlPolicy.isInBaseSubtree(
                base,
                "https://music.example/dav/root/album/song.mp3".toHttpUrl(),
            ),
        )
    }
}
