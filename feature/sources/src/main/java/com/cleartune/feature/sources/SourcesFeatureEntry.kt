package com.cleartune.feature.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.cleartune.core.contracts.SourceRepository

data class SourcesFeatureDependencies(val sourceRepository: SourceRepository)

object SourcesFeatureEntry {
    const val route = "sources"

    @Composable
    fun Content(
        dependencies: SourcesFeatureDependencies,
        onNavigate: (String) -> Unit,
    ) {
        val sources by dependencies.sourceRepository.observeSources().collectAsState(initial = emptyList())
        SourcesScreen(
            sources = sources.map { it.toUiItem() },
            onAddWebDav = { onNavigate("sources/add-webdav") },
            onOpenSource = { onNavigate("sources/${it.id.value}") },
        )
    }
}
