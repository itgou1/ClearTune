package com.cleartune.feature.player

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.cleartune.core.contracts.PlaybackGateway
import com.cleartune.core.contracts.QueueRepository

data class PlayerFeatureDependencies(
    val playbackGateway: PlaybackGateway,
    val queueRepository: QueueRepository,
)

object PlayerFeatureEntry {
    const val route = "player"

    @Composable
    @Suppress("UNUSED_PARAMETER")
    fun Content(
        dependencies: PlayerFeatureDependencies,
        onNavigate: (String) -> Unit,
    ) {
        Text(
            text = "Player module",
            modifier = Modifier.semantics { stateDescription = "baseline stub" },
        )
    }
}
