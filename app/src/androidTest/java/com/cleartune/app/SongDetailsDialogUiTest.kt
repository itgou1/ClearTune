package com.cleartune.app

import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.cleartune.core.designsystem.ClearTuneTheme
import com.cleartune.core.model.Song
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SongDetailsDialogUiTest {
    @get:Rule val ui = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun lightDetailsShowGroupedMetadataAndClose() {
        show(Song("detail", "我很想爱他", artistName = "张星遥", albumName = "张星遥的作品集",
            durationSeconds = 227, suffix = "m4a", bitRate = 181, sizeBytes = 5_136_384))
        listOf("我很想爱他", "张星遥", "张星遥的作品集", "3:47", "M4A", "181 kbps").forEach {
            ui.onNodeWithText(it).assertIsDisplayed()
        }
        ui.onNodeWithText(context.getString(R.string.close_action)).assertIsDisplayed()
        capture("song-details-light")
        ui.onNodeWithText(context.getString(R.string.close_action)).performClick()
        ui.onNodeWithTag("song-details").assertDoesNotExist()
    }

    @Test fun darkMissingMetadataShowsUnknownAndBackDismisses() {
        show(Song("missing", "未填写音频信息的歌曲", suffix = " ", bitRate = 0, sizeBytes = 0), dark = true)
        ui.onAllNodesWithText(context.getString(R.string.unknown_format)).assertCountEquals(5)
        ui.onNodeWithText("0 kbps").assertDoesNotExist()
        capture("song-details-dark")
        pressBack()
        ui.onNodeWithTag("song-details").assertDoesNotExist()
    }

    @Test fun largeTextScrollsWhileCloseStaysReachable() {
        show(Song("long", "这是一首名字很长的歌曲，完整标题仍然可以阅读",
            artistName = "一位名字很长的艺术家与另一位音乐人",
            albumName = "一张很长名字的专辑，包含特别发行版与现场录音说明",
            year = 2026, genre = "流行 / 现场录音", durationSeconds = 420, suffix = "flac",
            bitRate = 1411, sizeBytes = 74_077_500), fontScale = 2f)
        ui.onNodeWithText(context.getString(R.string.close_action)).assertIsDisplayed()
        ui.onNodeWithText(context.getString(R.string.song_details_duration)).performScrollTo()
        val durationLeft = ui.onNodeWithText(context.getString(R.string.song_details_duration))
            .fetchSemanticsNode().boundsInRoot.left
        ui.onNodeWithText(context.getString(R.string.song_details_format)).performScrollTo()
        assertEquals(
            durationLeft,
            ui.onNodeWithText(context.getString(R.string.song_details_format)).fetchSemanticsNode().boundsInRoot.left,
            1f,
        )
        ui.onNodeWithText(context.getString(R.string.song_details_size)).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText(Formatter.formatShortFileSize(context, 74_077_500)).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText(context.getString(R.string.close_action)).assertIsDisplayed()
        capture("song-details-large-font")
        ui.onNodeWithText(context.getString(R.string.close_action)).performClick()
        ui.onNodeWithTag("song-details").assertDoesNotExist()
    }

    private fun show(song: Song, dark: Boolean = false, fontScale: Float = 1f) {
        ui.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ClearTuneTheme(darkTheme = dark) {
                    var visible by remember { mutableStateOf(true) }
                    if (visible) SongDetailsDialog(song, onDismiss = { visible = false }) { modifier ->
                        Box(modifier.background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.MusicNote, contentDescription = null, modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        val bitmap = ui.onNodeWithTag("song-details").captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
