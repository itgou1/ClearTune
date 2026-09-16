package com.cleartune.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cleartune.core.designsystem.ClearTuneTheme
import com.cleartune.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecentlyAddedSongRowTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun recentBadgeKeepsPlaybackAndFavoriteActions() {
        var plays = 0
        var favorites = 0
        var additions = 0
        compose.setContent {
            ClearTuneTheme {
                Column {
                    SongRow(Song("1", "New track"), onClick = { plays++ },
                        onFavorite = { favorites++ }, recentlyAdded = true,
                        trailingContent = {
                            IconButton(onClick = { additions++ }) {
                                Icon(Icons.Rounded.Add, contentDescription = "Add to playlist")
                            }
                        })
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.song_recently_added), useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNode(hasText("New track") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.like_song)).performClick()
        compose.onNodeWithContentDescription("Add to playlist").performClick()
        compose.runOnIdle {
            assertEquals(1, plays)
            assertEquals(1, favorites)
            assertEquals(1, additions)
        }
    }

    @Test fun ordinarySongHasNoNewBadge() {
        compose.setContent {
            ClearTuneTheme { Column { SongRow(Song("1", "Old track"), onClick = {}) } }
        }
        compose.onNodeWithText(context.getString(R.string.song_recently_added), useUnmergedTree = true)
            .assertDoesNotExist()
        compose.onNodeWithText("Old track").assertIsDisplayed()
    }

    @Test fun longTitleAndLargeTextKeepBadgeVisibleInDarkTheme() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                ClearTuneTheme(darkTheme = true) {
                    Column(Modifier.width(320.dp)) {
                        SongRow(Song("1", "这是一首名字很长很长的新加入歌曲", trackNumber = 12),
                            onClick = {}, onFavorite = {}, recentlyAdded = true,
                            trailingContent = {
                                IconButton(onClick = {}) {
                                    Icon(Icons.Rounded.Add, contentDescription = "Add to playlist")
                                }
                            })
                    }
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.song_recently_added), useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.like_song)).assertIsDisplayed()
        compose.onNodeWithContentDescription("Add to playlist").assertIsDisplayed()
    }
}
