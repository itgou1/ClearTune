package com.cleartune.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.cleartune.app.metadata.*
import com.cleartune.core.designsystem.ClearTuneTheme
import com.cleartune.core.model.*
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MusicTagEditorUiTest {
    @get:Rule val ui = createComposeRule()
    private val settings = MusicTagSettings("https://example.com/", "test", "test-only")
    private val song = Song("tag-preview", "Morning", artistName = "卫兰", albumName = "Morning")
    private val savedSnapshot = MusicTagSnapshot("/app/media/morning.mp3", mapOf(
        MusicTagField.TITLE to song.title, MusicTagField.ARTIST to song.artistName, MusicTagField.ALBUM to "新专辑"))

    private fun show(state: MusicTagUiState, actions: MusicTagEditorActions = MusicTagEditorActions(), dark: Boolean = false) {
        ui.setContent {
            ClearTuneTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (dark) 1.3f else 1f)) {
                    Scaffold(topBar = { ClearTuneTopAppBar("音乐标签", {}) }) { padding ->
                        MusicTagEditor(state, actions, Modifier.padding(padding))
                    }
                }
            }
        }
    }

    @Test fun backgroundSyncAllowsReturningWithoutAnotherWriteOrCheck() {
        var closed = 0
        var submitted = 0
        var checked = 0
        show(MusicTagUiState(settings = settings, song = song, pending = true, fileSaved = true, syncing = true,
            snapshot = savedSnapshot, values = savedSnapshot.values,
            message = "标签已保存，曲库后台同步中，可返回继续使用"),
            MusicTagEditorActions(close = { closed++ }, apply = { submitted++ }, verify = { checked++ }))
        ui.onNodeWithText("已保存，曲库后台同步中").assertIsDisplayed()
        ui.onNodeWithText("完成").assertIsEnabled().performClick()
        ui.onNodeWithText("已在服务中核对，重新读取").assertDoesNotExist()
        ui.runOnIdle { assertEquals(1, closed); assertEquals(0, submitted); assertEquals(0, checked) }
        screenshot("tag-background-sync")
    }

    @Test fun failedSyncOffersSyncRetryInsteadOfSavingAgain() {
        var submitted = 0
        var retried = 0
        show(MusicTagUiState(settings = settings, song = song, pending = true, fileSaved = true,
            snapshot = savedSnapshot, values = savedSnapshot.values,
            message = "标签已保存，曲库同步未完成，可重试同步，不会重复写入标签"),
            MusicTagEditorActions(apply = { submitted++ }, verify = { retried++ }))
        ui.onNodeWithText("重试同步").assertIsEnabled().performClick()
        ui.onNodeWithText("已在服务中核对，重新读取").assertDoesNotExist()
        ui.runOnIdle { assertEquals(1, retried); assertEquals(0, submitted) }
        screenshot("tag-sync-retry")
    }

    @Test fun missingFileShowsPathsAndPreventsApplyingPreview() {
        var reloaded = false
        show(MusicTagUiState(settings = settings, song = song, queryTitle = song.title,
            queryArtist = song.artistName, sourcePath = "Music/morning.mp3", targetPath = "/app/media/Music/morning.mp3",
            isError = true, message = "Music Tag 找不到目标文件，请核对下方文件路径和服务目录映射",
            values = mapOf(MusicTagField.TITLE to "Morning"), selected = setOf(MusicTagField.TITLE)),
            MusicTagEditorActions(reload = { reloaded = true }))
        ui.onNodeWithText("保存修改").assertDoesNotExist()
        ui.onNodeWithText("Music Tag 目标路径\n/app/media/Music/morning.mp3").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("读取原标签").performScrollTo().performClick()
        ui.runOnIdle { assertEquals(true, reloaded) }
        screenshot("tag-read-error")
    }

    @Test fun primaryScrapeActionRemainsVisible() {
        var searches = 0
        show(MusicTagUiState(settings = settings, song = song, queryTitle = song.title, queryArtist = song.artistName,
            snapshot = MusicTagSnapshot("/app/media/morning.mp3", mapOf(MusicTagField.TITLE to "Morning"))),
            MusicTagEditorActions(search = { searches++ }))
        ui.onNodeWithText("原标签已读取").assertIsDisplayed()
        ui.onNode(hasText("刮削匹配") and hasClickAction()).assertIsDisplayed().performClick()
        ui.onNodeWithText("搜索").performScrollTo().performClick()
        ui.runOnIdle { assertEquals(1, searches) }
        screenshot("tag-editor-light")
    }

    @Test fun darkPreviewWithLargeTextKeepsApplyAccessible() {
        var applied = false
        show(MusicTagUiState(settings = settings, song = song, queryTitle = song.title, queryArtist = song.artistName,
            snapshot = MusicTagSnapshot("/app/media/morning.mp3", mapOf(MusicTagField.TITLE to "morning")),
            values = mapOf(MusicTagField.TITLE to "Morning"), selected = setOf(MusicTagField.TITLE)),
            MusicTagEditorActions(apply = { applied = true }), dark = true)
        ui.onNodeWithText("标题").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("保存修改").assertIsDisplayed().assertIsEnabled().performClick()
        ui.runOnIdle { assertEquals(true, applied) }
        screenshot("tag-preview-dark-large")
    }

    @Test fun v1CoverReviewConfirmsReadBackWithoutSubmittingAgain() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val image = "data:image/png;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        var confirmations = 0
        var submissions = 0
        show(MusicTagUiState(settings = settings, song = song, queryTitle = song.title, pending = true,
            snapshot = savedSnapshot.copy(values = savedSnapshot.values + (MusicTagField.COVER to image)),
            coverReview = MusicTagCoverReview(image, image, true, mapOf(MusicTagField.ALBUM to "新专辑"))),
            MusicTagEditorActions(confirmCover = { confirmations++ }, apply = { submissions++ }))
        ui.onNodeWithText("核对文件封面").assertIsDisplayed()
        ui.onNodeWithText("文件读回封面").assertIsDisplayed()
        ui.onNodeWithText("文字标签已验证").assertExists()
        ui.onNodeWithText("专辑：新专辑").assertExists()
        ui.waitUntil(10_000) { ui.onAllNodes(hasText("确认封面一致") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        screenshot("tag-cover-review")
        ui.onNodeWithText("确认封面一致").performClick()
        ui.runOnIdle { assertEquals(1, confirmations); assertEquals(0, submissions) }
    }

    @Test fun originalTagsCanBeEditedAndSavedWithoutScraping() {
        val original = mapOf(MusicTagField.TITLE to "Original", MusicTagField.ARTIST to "Artist", MusicTagField.LYRICS to "Lyrics")
        val state = mutableStateOf(MusicTagUiState(settings = settings, song = song,
            snapshot = MusicTagSnapshot("/music/song.mp3", original), values = original))
        var saved: Map<MusicTagField, String>? = null
        ui.setContent {
            ClearTuneTheme {
                Scaffold(topBar = { ClearTuneTopAppBar("音乐标签", {}) }) { padding ->
                    MusicTagEditor(state.value, MusicTagEditorActions(
                        edit = { field, value -> state.value = state.value.copy(values = state.value.values + (field to value)) },
                        apply = { saved = state.value.changes }), Modifier.padding(padding))
                }
            }
        }
        ui.onNodeWithText("保存修改").assertDoesNotExist()
        ui.onNodeWithText("标题").performScrollTo().performTextReplacement("Edited")
        ui.onNodeWithText("保存修改").assertIsEnabled()
        ui.onNodeWithText("标题").performTextReplacement("Original")
        ui.onNodeWithText("保存修改").assertDoesNotExist()
        ui.onNodeWithText("标题").performTextReplacement("Edited")
        ui.onNodeWithText("保存修改").performClick()
        ui.runOnIdle { assertEquals(mapOf(MusicTagField.TITLE to "Edited"), saved) }
        screenshot("tag-direct-edit")
    }

    @Test fun frozenLocalCoverLoadsForManualReadBackConfirmation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("tag-cover-review-", ".image", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val actual = "data:image/png;base64," + android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
            show(MusicTagUiState(settings = settings, song = song, pending = true,
                snapshot = savedSnapshot, coverReview = MusicTagCoverReview(actual, file.absolutePath, true)), dark = true)
            ui.waitUntil(10_000) { ui.onAllNodes(hasText("确认封面一致") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("所选封面").assertExists()
            screenshot("tag-frozen-cover-review")
        } finally { file.delete() }
    }

    @Test fun fillingCandidateReturnsToHighlightedForm() {
        val state = mutableStateOf(MusicTagUiState(settings = settings, song = song,
            snapshot = MusicTagSnapshot("/music/song.mp3", mapOf(MusicTagField.TITLE to "Original")),
            values = mapOf(MusicTagField.TITLE to "Original")))
        ui.setContent { ClearTuneTheme { MusicTagEditor(state.value, MusicTagEditorActions()) } }
        ui.onNode(hasText("刮削匹配") and hasClickAction()).performClick()
        ui.onNodeWithText("搜索").performScrollTo().assertIsDisplayed()
        ui.runOnIdle { state.value = state.value.copy(values = mapOf(MusicTagField.TITLE to "Filled"), fillVersion = 1) }
        ui.onNodeWithText("搜索").assertDoesNotExist()
        ui.onNodeWithText("标题").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("原值：Original").assertExists()
        screenshot("tag-filled")
    }

    @Test fun unavailableCoverCannotBeConfirmed() {
        show(MusicTagUiState(settings = settings, song = song, pending = true,
            coverReview = MusicTagCoverReview("data:image/png;base64,", "data:image/png;base64,", true)))
        ui.onNodeWithText("确认封面一致").assertIsNotEnabled()
        ui.onNodeWithText("封面不一致，重新选择").assertIsEnabled()
        androidx.test.espresso.Espresso.pressBack()
        ui.onNodeWithText("核对封面").assertIsDisplayed().performClick()
        ui.onNodeWithText("确认封面一致").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test fun candidatePreviewDoesNotFillUntilConfirmed() {
        val state = mutableStateOf(MusicTagUiState(snapshot = MusicTagSnapshot("/music/song.mp3", emptyMap()),
            candidateValues = mapOf(MusicTagField.TITLE to "New title", MusicTagField.ARTIST to "New artist"),
            selected = setOf(MusicTagField.TITLE, MusicTagField.ARTIST)))
        var filled: Set<MusicTagField>? = null
        ui.setContent {
            ClearTuneTheme {
                MusicTagCandidateDialog(state.value, { field, checked -> state.value = state.value.copy(
                    selected = if (checked) state.value.selected + field else state.value.selected - field) },
                    { filled = state.value.selected }, {})
            }
        }
        ui.onAllNodes(isToggleable())[1].performClick()
        ui.runOnIdle { assertEquals(null, filled) }
        ui.onNodeWithText("填入草稿（1 项）").performClick()
        ui.runOnIdle { assertEquals(setOf(MusicTagField.TITLE), filled) }
    }

    @Test fun candidateComparisonShowsAlbumBeforeFillingDraft() {
        val state = MusicTagUiState(snapshot = savedSnapshot, values = savedSnapshot.values,
            candidateValues = mapOf(MusicTagField.TITLE to song.title,
                MusicTagField.ALBUM to "夜色来信"), selected = setOf(MusicTagField.ALBUM))
        var filled = 0
        ui.setContent { ClearTuneTheme { MusicTagCandidateDialog(state, { _, _ -> }, { filled++ }, {}) } }
        ui.onNodeWithText("当前：新专辑").assertIsDisplayed()
        ui.onNodeWithText("填入：夜色来信").assertIsDisplayed()
        ui.onNodeWithText("相同字段：标题").assertExists()
        ui.runOnIdle { assertEquals(0, filled) }
        screenshot("tag-candidate-diff")
        ui.onNodeWithText("填入草稿（1 项）").performClick()
        ui.runOnIdle { assertEquals(1, filled) }
    }

    @Test fun saveFeedbackStaysVisibleAtEndOfLongFormAndKeepsScrollPosition() {
        val original = savedSnapshot.values + (MusicTagField.LYRICS to (1..80).joinToString("\n") { "歌词第 $it 行" })
        val state = mutableStateOf(MusicTagUiState(settings = settings, song = song,
            snapshot = savedSnapshot.copy(values = original), values = original + (MusicTagField.ALBUM to "编辑后的专辑")))
        var closed = 0
        ui.setContent { ClearTuneTheme { MusicTagEditor(state.value, MusicTagEditorActions(
            apply = { state.value = state.value.copy(busy = true, progress = "正在保存并检查结果…") }, close = { closed++ })) } }
        ui.onNodeWithText("更多字段").performScrollTo().performClick()
        ui.onNodeWithText("歌词").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("已修改 1 项，尚未保存").assertIsDisplayed()
        ui.onNodeWithText("保存修改").performClick()
        ui.onNodeWithText("正在保存并检查结果…").assertIsDisplayed()
        ui.onNodeWithText("处理中…").assertIsNotEnabled()
        ui.runOnIdle { state.value = state.value.copy(busy = false, fileSaved = true, pending = true, syncing = true) }
        ui.onNodeWithText("已保存，曲库后台同步中").assertIsDisplayed()
        ui.onNodeWithText("歌词").assertIsDisplayed()
        screenshot("tag-sticky-saved")
        ui.onNodeWithText("完成").performClick()
        ui.runOnIdle { assertEquals(1, closed) }
    }

    @Test fun collapsedLyricsAndCoverRetainEditsAndHighlight() {
        val original = savedSnapshot.values + (MusicTagField.LYRICS to "原歌词")
        val state = mutableStateOf(MusicTagUiState(settings = settings, song = song,
            snapshot = savedSnapshot.copy(values = original), values = original))
        ui.setContent { ClearTuneTheme { MusicTagEditor(state.value, MusicTagEditorActions(
            edit = { field, value -> state.value = state.value.copy(values = state.value.values + (field to value)) })) } }
        ui.onNodeWithText("替换封面地址").assertDoesNotExist()
        ui.onNodeWithText("歌词").assertDoesNotExist()
        ui.onNodeWithText("更换").performScrollTo().performClick()
        ui.onNodeWithText("替换封面地址").performScrollTo().performTextReplacement("https://example.com/new.jpg")
        ui.onNodeWithText("收起").performScrollTo().performClick()
        ui.onNodeWithText("已修改 · 查看原封面").assertExists()
        ui.onNodeWithText("更多字段").performScrollTo().performClick()
        ui.onNodeWithText("歌词").performScrollTo().performTextReplacement("新歌词")
        ui.onNodeWithText("更多字段").performScrollTo().performClick()
        ui.onNodeWithText("歌词").assertDoesNotExist()
        ui.onNodeWithText("已修改 2 项，尚未保存").assertIsDisplayed()
        ui.onNodeWithText("更多字段").performScrollTo().performClick()
        ui.onNodeWithText("歌词").assertTextContains("新歌词")
    }

    @Test fun uncertainWriteExposesFullErrorAndOnlyChecksResult() {
        var applied = 0
        var checked = 0
        val error = "保存请求连接中断，结果尚未确认。保存记录已保留，请检查保存结果；不要重复提交"
        show(MusicTagUiState(settings = settings, song = song, snapshot = savedSnapshot, pending = true,
            isError = true, message = error), MusicTagEditorActions(apply = { applied++ }, verify = { checked++ }))
        ui.onNodeWithText("保存结果待确认，请检查结果").assertIsDisplayed()
        ui.onNodeWithText("详情").performClick()
        ui.onNodeWithText(error).assertIsDisplayed()
        ui.onNodeWithText("关闭").performClick()
        ui.onNodeWithText("检查保存结果").assertIsDisplayed().performClick()
        ui.runOnIdle { assertEquals(0, applied); assertEquals(1, checked) }
        screenshot("tag-sticky-error")
    }

    @Test fun invalidYearIsRevealedInsteadOfSubmitting() {
        var applied = 0
        show(MusicTagUiState(settings = settings, song = song, snapshot = savedSnapshot,
            values = savedSnapshot.values + (MusicTagField.YEAR to "10000")), MusicTagEditorActions(apply = { applied++ }))
        ui.onNodeWithText("刮削匹配").performClick()
        ui.onNodeWithText("年份").assertDoesNotExist()
        ui.onNodeWithText("保存修改").performClick()
        ui.onNodeWithText("年份").assertIsDisplayed().assertIsFocused()
        ui.onNodeWithText("年份应为 1–9999").assertIsDisplayed()
        ui.runOnIdle { assertEquals(0, applied) }
    }

    @Test fun completedSaveOffersDoneWithoutAnotherWrite() {
        var closed = 0
        var applied = 0
        show(MusicTagUiState(settings = settings, song = song, snapshot = savedSnapshot, values = savedSnapshot.values,
            fileSaved = true), MusicTagEditorActions(close = { closed++ }, apply = { applied++ }))
        ui.onNodeWithText("已保存，曲库已同步").assertIsDisplayed()
        ui.onNodeWithText("完成").performClick()
        ui.runOnIdle { assertEquals(1, closed); assertEquals(0, applied) }
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ui.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
