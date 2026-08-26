package com.cleartune.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class ChineseUiSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun chineseInterfaceRenders() {
        composeRule.onNodeWithText("连接音乐服务器").assertIsDisplayed()
        composeRule.onNodeWithText("服务器地址").assertIsDisplayed()
        composeRule.onNodeWithText("测试连接").assertIsDisplayed()
    }
}
