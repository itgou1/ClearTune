package com.cleartune.feature.sources

import com.cleartune.core.model.MusicSource
import com.cleartune.core.model.SourceId
import com.cleartune.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesUiModelTest {
    @Test
    fun `maps source details without exposing credentials or full paths`() {
        val item = MusicSource(
            id = SourceId("remote"),
            name = "家庭音乐库",
            type = SourceType.WEBDAV,
            baseUrl = "https://music.example.com/private/alice/",
            lastSyncedAtEpochMs = 1_700_000_000_000,
        ).toUiItem(nowEpochMs = 1_700_000_060_000)

        assertEquals("music.example.com", item.location)
        assertEquals("1 分钟前同步", item.status)
        assertFalse(item.insecure)
    }

    @Test
    fun `cleartext source keeps a visible warning`() {
        val item = MusicSource(
            id = SourceId("remote"), name = "旧服务器", type = SourceType.WEBDAV,
            baseUrl = "http://lan.example/dav/", allowCleartext = true,
        ).toUiItem(nowEpochMs = 0)

        assertTrue(item.insecure)
        assertEquals("HTTP 未加密", item.status)
    }
}
