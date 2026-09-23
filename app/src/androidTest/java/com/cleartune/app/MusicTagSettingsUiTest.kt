package com.cleartune.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cleartune.app.metadata.MusicTagSettingsForm
import com.cleartune.app.metadata.MusicTagConnectionSettings
import androidx.compose.runtime.mutableStateOf
import com.cleartune.core.designsystem.ClearTuneTheme
import com.cleartune.core.model.MusicTagSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MusicTagSettingsUiTest {
    @get:Rule val ui = createComposeRule()

    @Test fun connectedServiceIsReadOnlyUntilDisconnected() {
        val settings = mutableStateOf(MusicTagSettings("https://example.com/", "test", "secret"))
        ui.setContent {
            ClearTuneTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    MusicTagConnectionSettings(settings.value, false, { settings.value = it },
                        { settings.value = MusicTagSettings() })
                }
            }
        }
        ui.onNodeWithText("已连接").assertExists()
        ui.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        ui.onNodeWithText("secret").assertDoesNotExist()
        ui.onNodeWithText("测试连接并保存").assertDoesNotExist()
        ui.onNodeWithText("退出连接").performScrollTo().performClick()
        ui.onNodeWithText("已连接").assertDoesNotExist()
        ui.onNodeWithText("服务地址").assertExists()
        ui.onNodeWithText("测试连接并保存").performScrollTo().assertIsNotEnabled()
    }

    @Test fun configurationRequiresCredentialsAndPreservesDirectoryMapping() {
        var saved: MusicTagSettings? = null
        ui.setContent {
            ClearTuneTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    MusicTagSettingsForm(MusicTagSettings(), false) { saved = it }
                }
            }
        }
        ui.onNodeWithText("测试连接并保存").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithText("服务地址").performScrollTo().performTextInput("https://example.com/tag/login")
        ui.onNodeWithText("账号").performScrollTo().performTextInput("test")
        ui.onNodeWithText("密码").performScrollTo().performTextInput("test-only-password")
        ui.onNodeWithText("高级设置 · 展开配置路径").performScrollTo().performClick()
        ui.onNodeWithText("Navidrome 音乐根目录（可留空）").performScrollTo().performTextInput("/music")
        ui.onNodeWithText("测试连接并保存").performScrollTo().assertIsEnabled().performClick()
        ui.runOnIdle {
            assertEquals("/music", saved?.sourceRoot)
            assertEquals("/app/media", saved?.targetRoot)
            assertFalse(saved!!.allowHttp)
        }
    }
}
