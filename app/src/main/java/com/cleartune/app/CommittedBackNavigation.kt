package com.cleartune.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState

/** Keeps the current page still until the system commits Back after gesture release. */
internal fun NavGraphBuilder.committedBackComposable(
    navController: NavHostController,
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    navigationEnabled: @Composable () -> Boolean = { true },
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable(route = route, arguments = arguments) { entry ->
        val currentEntry by navController.currentBackStackEntryAsState()
        // Override NavHost's predictive preview inside the destination. Register before
        // content so screen-level handlers (selection, sheets, etc.) retain priority.
        BackHandler(
            enabled = navigationEnabled() && currentEntry?.id == entry.id &&
                navController.previousBackStackEntry != null,
        ) {
            if (navController.currentBackStackEntry?.id == entry.id) {
                navController.popBackStack()
            }
        }
        content(entry)
    }
}
