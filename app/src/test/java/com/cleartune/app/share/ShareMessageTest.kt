package com.cleartune.app.share

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ShareMessageTest {
    private val share = MusicShare("demo", "https://music.example.com/share/abc?source=music", "我很想爱他",
        "分享一首今天在听的歌", ShareKind.SONG, null, 1, null,
        Instant.parse("2026-10-01T00:00:00Z").toEpochMilli(), 0, artist = "张星遥")
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun songIncludesReadableIdentityDescriptionLinkAndAbsoluteExpiry() {
        assertEquals("""
            分享歌曲《我很想爱他》
            艺术家：张星遥

            分享说明：分享一首今天在听的歌

            收听链接：
            https://music.example.com/share/abc?source=music

            有效期至：2026-10-01 08:00
        """.trimIndent(), shareMessage(share, zone))
    }

    @Test fun albumIncludesArtistAndSongCount() {
        val text = shareMessage(share.copy(kind = ShareKind.ALBUM, title = "山海之间", songCount = 8), zone)
        assertTrue(text.startsWith("分享专辑《山海之间》\n艺术家：张星遥\n共 8 首歌曲"))
    }

    @Test fun playlistDoesNotMistakeSubtitleOrFirstSongArtistForPlaylistArtist() {
        val text = shareMessage(share.copy(kind = ShareKind.PLAYLIST, title = "通勤路上", songCount = 12), zone)
        assertTrue(text.startsWith("分享歌单《通勤路上》\n共 12 首歌曲"))
        assertFalse(text.contains("艺术家"))
    }

    @Test fun oldOrIncompleteShareDoesNotInventTypeCountOrExpiry() {
        assertEquals("分享音乐\n\n收听链接：\n${share.url}", shareMessage(share.copy(
            kind = ShareKind.UNKNOWN, title = "音乐分享", songCount = 0,
            description = "  ", artist = "", expiresAt = null), zone))
    }

    @Test fun optionalFieldsAreOmittedAndNamesStayOnOneLine() {
        val text = shareMessage(share.copy(title = "  长标题\n第二行  ", artist = "未知艺术家",
            description = "\n", expiresAt = 0), zone)
        assertEquals("分享歌曲《长标题 第二行》\n\n收听链接：\n${share.url}", text)
    }

    @Test fun editedDescriptionAndExpiryAreReflectedWithoutChangingLink() {
        val updated = share.copy(description = "新的推荐语\n第二行说明", expiresAt = share.expiresAt!! + 86_400_000L)
        val text = shareMessage(updated, zone)
        assertTrue(text.contains("分享说明：新的推荐语\n第二行说明"))
        assertTrue(text.endsWith("有效期至：2026-10-02 08:00"))
        assertEquals(1, text.lines().count { it == share.url })
    }

    @Test fun missingUrlProducesNoSendableMessage() {
        assertEquals("", shareMessage(share.copy(url = "  "), zone))
    }
}
