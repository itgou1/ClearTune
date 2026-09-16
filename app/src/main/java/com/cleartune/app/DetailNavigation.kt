package com.cleartune.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.cleartune.app.library.DetailInvalidation
import com.cleartune.app.library.DetailTarget
import com.cleartune.app.library.DetailViewModel
import com.cleartune.app.library.MusicViewModel

@Composable
internal fun entryDetailViewModel(
    entry: NavBackStackEntry,
    music: MusicViewModel,
    target: DetailTarget,
): DetailViewModel = viewModel(viewModelStoreOwner = entry) { music.createDetailViewModel(target) }

/** Sticky invalidation is handled only after this exact entry is interactive, never in preview. */
@Composable
internal fun DetailInvalidationEffect(
    entry: NavBackStackEntry,
    nav: NavHostController,
    detail: DetailViewModel,
    onMissing: () -> Unit,
) {
    val reportMissing by rememberUpdatedState(onMissing)
    LaunchedEffect(entry, nav, detail) {
        entry.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            detail.invalidation.collect { reason ->
                if (reason != null && detail.active && nav.currentBackStackEntry?.id == entry.id &&
                    entry.lifecycle.currentState == Lifecycle.State.RESUMED
                ) {
                    if (reason == DetailInvalidation.MISSING) reportMissing()
                    nav.popBackStack()
                }
            }
        }
    }
}

@Composable
internal fun detailSelection(
    entryId: String,
    currentEntryId: String?,
    currentSelection: SongBatchSelection,
): SongBatchSelection {
    // Preview compositions must not retain/filter the foreground page's selected song IDs.
    val previewSelection = rememberSaveable(entryId, saver = SongBatchSelection.Saver) { SongBatchSelection() }
    return if (entryId == currentEntryId) currentSelection else previewSelection
}
