package com.cleartune.app

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CommittedBackNavigationTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun screenSelectionHandlesBackBeforeNavigation() = checkSelectionPriority(global = false)
    @Test fun appSelectionHandlesBackBeforeNavigation() = checkSelectionPriority(global = true)

    private fun checkSelectionPriority(global: Boolean) {
        lateinit var nav: NavHostController
        var selecting by mutableStateOf(true)
        var rootBacks = 0
        ui.runOnIdle {
            ui.activity.onBackPressedDispatcher.addCallback(ui.activity, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { rootBacks++ }
            })
        }
        ui.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "home") {
                committedBackComposable(nav, "home") { Text("Home") }
                committedBackComposable(nav, "detail", navigationEnabled = { !global || !selecting }) {
                    if (!global) BackHandler(enabled = selecting) { selecting = false }
                    Text("Detail")
                }
            }
            if (global) BackHandler(enabled = selecting) { selecting = false }
        }
        ui.runOnIdle { nav.navigate("detail") }
        ui.waitForIdle()
        val dispatcher = ui.activity.onBackPressedDispatcher
        fun progress() {
            ui.runOnIdle {
                dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 500f, 0f, BackEventCompat.EDGE_LEFT))
                dispatcher.dispatchOnBackProgressed(BackEventCompat(500f, 500f, 1f, BackEventCompat.EDGE_LEFT))
            }
            ui.waitForIdle()
            assertEquals("detail", nav.currentDestination?.route)
        }

        progress()
        assertTrue(selecting)
        ui.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        ui.waitForIdle()
        assertTrue(selecting)
        progress()
        ui.runOnIdle { dispatcher.onBackPressed() }
        ui.waitForIdle()
        assertFalse(selecting)
        assertEquals("detail", nav.currentDestination?.route)

        progress()
        ui.runOnIdle { dispatcher.onBackPressed() }
        ui.waitForIdle()
        assertEquals("home", nav.currentDestination?.route)
        assertEquals(0, rootBacks)
        // At the root, normal activity/system Back must remain available.
        ui.runOnIdle { dispatcher.onBackPressed() }
        ui.waitForIdle()
        assertEquals(1, rootBacks)
        assertEquals("home", nav.currentDestination?.route)
    }
}
