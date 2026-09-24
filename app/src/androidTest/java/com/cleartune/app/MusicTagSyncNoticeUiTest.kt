package com.cleartune.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cleartune.app.metadata.MusicTagSyncNotice
import com.cleartune.app.metadata.MusicTagSyncStatus
import com.cleartune.core.designsystem.ClearTuneTheme
import com.cleartune.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MusicTagSyncNoticeUiTest {
    @get:Rule val ui = createComposeRule()

    @Test fun noticeCanBeClosedWithoutStoppingSyncAndNewAttemptIsVisible() {
        val status = mutableStateOf(MusicTagSyncStatus(Song("song", "很长的一首歌"), true, 1))
        val dismissed = mutableStateOf<Long?>(null)
        var opened = 0
        ui.setContent {
            ClearTuneTheme {
                if (status.value.attempt != dismissed.value) {
                    MusicTagSyncNotice(status.value, onView = { opened++ },
                        onDismiss = { dismissed.value = status.value.attempt })
                }
            }
        }
        ui.onNodeWithText("标签已保存，曲库后台同步中", substring = true).assertExists()
        ui.onNodeWithContentDescription("关闭同步提示").performClick()
        ui.onNodeWithText("标签已保存，曲库后台同步中", substring = true).assertDoesNotExist()
        ui.runOnIdle {
            assertEquals(true, status.value.running)
            status.value = status.value.copy(attempt = 2)
        }
        ui.onNodeWithText("标签已保存，曲库后台同步中", substring = true).assertExists()
        ui.onNodeWithText("查看").performClick()
        ui.runOnIdle { assertEquals(1, opened) }
    }
}
