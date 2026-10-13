package com.cleartune.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cleartune.app.player.RadioUiState
import com.cleartune.core.model.InternetRadioStation
import com.cleartune.core.player.PlayerUiState
import com.cleartune.core.player.PlaybackStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RadioScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun fullPlayerCyclesStationsAndOpensListWithoutSongControls() {
        val stations = listOf(
            InternetRadioStation("1", "中国之声", "https://example.com/1.mp3"),
            InternetRadioStation("2", "央广经典音乐广播", "https://example.com/2.mp3"),
            InternetRadioStation("3", "音乐之声", "https://example.com/3.mp3"),
        )
        var selected: String? = null
        var toggles = 0
        var backs = 0
        compose.setContent { MaterialTheme {
            RadioPlayerScreen(RadioUiState(stations = stations),
                PlayerUiState(currentSong = stations.last().asSong(), status = PlaybackStatus.PLAYING),
                onToggle = { toggles++ }, onSelect = { selected = it.id }, onRefresh = {}, onBack = { backs++ })
        } }
        compose.onNodeWithContentDescription("下一电台").performScrollTo().performClick()
        assertEquals("1", selected)
        compose.onNodeWithContentDescription("上一电台").performClick()
        assertEquals("2", selected)
        compose.onNodeWithContentDescription("暂停音乐之声").performClick()
        assertEquals(1, toggles)
        compose.onNodeWithText("均衡器").assertDoesNotExist()
        compose.onNodeWithText("歌词").assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetProgress)).assertCountEquals(0)
        compose.onNodeWithContentDescription("电台列表").performClick()
        compose.onNodeWithContentDescription("播放中国之声").performClick()
        assertEquals("1", selected)
        compose.onNodeWithContentDescription("收起播放器").performClick()
        assertEquals(1, backs)
    }
    @Test fun singleStationDisablesSkippingAndErrorAllowsRetry() {
        val station = InternetRadioStation("1", "音乐之声", "https://example.com/live.mp3")
        var retries = 0
        compose.setContent { MaterialTheme {
            RadioPlayerScreen(RadioUiState(stations = listOf(station)),
                PlayerUiState(currentSong = station.asSong(), status = PlaybackStatus.ERROR),
                onToggle = { retries++ }, onSelect = {}, onRefresh = {}, onBack = {})
        } }
        compose.onNodeWithContentDescription("上一电台").assertIsNotEnabled()
        compose.onNodeWithContentDescription("下一电台").assertIsNotEnabled()
        compose.onNodeWithContentDescription("播放音乐之声").performScrollTo().performClick()
        assertEquals(1, retries)
    }
    @Test fun radioCardOpensListAndStationActionMatchesLiveState() {
        val station = InternetRadioStation("1", "音乐之声", "https://example.com/live.mp3")
        var selected: String? = null
        compose.setContent {
            MaterialTheme {
                RadioScreen(RadioUiState(stations = listOf(station)),
                    PlayerUiState(currentSong = station.asSong(), status = PlaybackStatus.PLAYING),
                    onPlay = { selected = it.id }, onRefresh = {}, onBack = {})
            }
        }
        compose.onNodeWithText("音乐之声").assertIsDisplayed()
        compose.onNodeWithText("正在直播 · 播放中").assertIsDisplayed()
        compose.onNodeWithContentDescription("暂停音乐之声").performClick()
        assertEquals("1", selected)
    }
    @Test fun emptyServerExplainsHowToAddStations() {
        compose.setContent { MaterialTheme { RadioScreen(RadioUiState(), PlayerUiState(), {}, {}, {}) } }
        compose.onNodeWithText("暂无电台，请先在 Navidrome 网页端添加电台").assertIsDisplayed()
    }
}
