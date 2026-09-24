package com.cleartune.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.cleartune.app.share.MusicShare
import com.cleartune.app.share.ShareKind
import com.cleartune.app.share.shareMessage
import com.cleartune.core.designsystem.ClearTuneTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ShareMessageUiTest {
    @get:Rule val ui = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val share = MusicShare("demo", "https://music.example.com/share/demo", "通勤路上", "路上听的歌",
        ShareKind.PLAYLIST, null, 12, null, null, 0)

    @Test fun previewClipboardAndOutgoingTextMatchAndRawLinkRemainsAvailable() {
        ui.setContent { ClearTuneTheme { ShareContentPreview(share) } }
        val message = shareMessage(share)
        ui.onNodeWithText(message).assertIsDisplayed()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val original = clipboard.primaryClip
        try {
            ui.runOnIdle {
                copyShareMessage(context, share)
                assertEquals(message, clipboard.primaryClip?.getItemAt(0)?.text.toString())
                val intent = shareLinkIntent(context, share)
                assertEquals(Intent.ACTION_SEND, intent.action)
                assertEquals("text/plain", intent.type)
                assertEquals(message, intent.getStringExtra(Intent.EXTRA_TEXT))
                assertNull(intent.clipData)
            }
            ui.onNodeWithText("仅复制链接").performClick()
            ui.onNodeWithText("链接已复制").assertIsDisplayed()
            ui.runOnIdle { assertEquals(share.url, clipboard.primaryClip?.getItemAt(0)?.text.toString()) }
        } finally {
            ui.runOnIdle { if (original != null) clipboard.setPrimaryClip(original) else clipboard.clearPrimaryClip() }
        }
    }

    @Test fun addingCoverPreviewKeepsReadableBodyAndReadPermission() {
        val directory = File(context.cacheDir, "share_previews").apply { mkdirs() }
        val image = File.createTempFile("message-test-", ".jpg", directory)
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.sharefileprovider", image)
            val intent = shareLinkIntent(context, share, uri)
            assertEquals(shareMessage(share), intent.getStringExtra(Intent.EXTRA_TEXT))
            assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertFalse(intent.hasExtra(Intent.EXTRA_STREAM))
        } finally { image.delete() }
    }
}
