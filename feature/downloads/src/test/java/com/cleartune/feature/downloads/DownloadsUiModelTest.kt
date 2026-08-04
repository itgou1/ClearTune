package com.cleartune.feature.downloads

import com.cleartune.core.model.DownloadId
import com.cleartune.core.model.DownloadState
import com.cleartune.core.model.DownloadSummary
import com.cleartune.core.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadsUiModelTest {
    private val titles = DownloadTitleResolver { "Title for ${it.value}" }

    @Test
    fun `groups active completed and attention items in display order`() {
        val groups = groupDownloads(
            listOf(
                summary("complete", DownloadState.COMPLETED, 10, 10, "/music/a"),
                summary("failed", DownloadState.FAILED, 2, 10),
                summary("running", DownloadState.RUNNING, 4, 10),
            ),
            titles,
        )

        assertEquals(3, groups.size)
        assertEquals(0.4f, groups.first().items.first().progress)
        assertEquals("Title for track-running", groups.first().items.first().title)
    }

    @Test
    fun `unknown total has indeterminate progress safe status and resolved title`() {
        val item = summary("running", DownloadState.RUNNING, 1024, null).toUiItem(titles)

        assertNull(item.progress)
        assertEquals("Title for track-running", item.title)
    }

    @Test
    fun `canceled rows remain visible for retry or deletion`() {
        val groups = groupDownloads(
            listOf(summary("canceled", DownloadState.CANCELED, 0, null)),
            titles,
        )

        assertEquals(DownloadState.CANCELED, groups.single().items.single().state)
    }

    private fun summary(id: String, state: DownloadState, bytes: Long, total: Long?, path: String? = null) =
        DownloadSummary(DownloadId(id), TrackId("track-$id"), state, bytes, total, path)
}
