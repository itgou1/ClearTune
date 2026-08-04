package com.cleartune.feature.sources

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.cleartune.core.contracts.SourceRepository

data class SourcesFeatureDependencies(val sourceRepository: SourceRepository)

object SourcesFeatureEntry {
    const val route = "sources"

    @Composable
    @Suppress("UNUSED_PARAMETER")
    fun Content(
        dependencies: SourcesFeatureDependencies,
        onNavigate: (String) -> Unit,
    ) {
        Text(
            text = "Sources module",
            modifier = Modifier.semantics { stateDescription = "baseline stub" },
        )
    }
}
