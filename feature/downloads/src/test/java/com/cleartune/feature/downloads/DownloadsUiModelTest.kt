package com.cleartune.feature.downloads

import com.cleartune.core.model.DownloadId
import com.cleartune.core.model.DownloadState
import com.cleartune.core.model.DownloadSummary
import com.cleartune.core.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadsUiModelTest {
    @Test
    fun `groups active completed and attention items in display order`() {
        val groups = groupDownloads(
            listOf(
                summary("complete", DownloadState.COMPLETED, 10, 10, "/music/a"),
                summary("failed", DownloadState.FAILED, 2, 10),
                summary("running", DownloadState.RUNNING, 4, 10),
            ),
        )

        assertEquals(listOf("进行中", "需要处理", "已完成"), groups.map { it.title })
        assertEquals(0.4f, groups.first().items.first().progress)
    }

    @Test
    fun `unknown total has indeterminate progress and safe status`() {
        val item = summary("running", DownloadState.RUNNING, 1024, null).toUiItem()

        assertNull(item.progress)
        assertEquals("正在下载", item.status)
    }

    private fun summary(
        id: String,
        state: DownloadState,
        bytes: Long,
        total: Long?,
        path: String? = null,
    ) = DownloadSummary(DownloadId(id), TrackId("track-$id"), state, bytes, total, path)
}
