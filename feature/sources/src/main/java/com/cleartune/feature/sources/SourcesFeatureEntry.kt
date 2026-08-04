package com.cleartune.feature.sources

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.cleartune.core.contracts.SourceRepository
import kotlinx.coroutines.launch

data class SourcesFeatureDependencies(
    val sourceRepository: SourceRepository,
    val controller: SourceController,
)

object SourcesFeatureEntry {
    const val route = "sources"

    @Composable
    fun Content(
        dependencies: SourcesFeatureDependencies,
        onNavigate: (String) -> Unit,
        currentRoute: String = route,
    ) {
        val sources by dependencies.sourceRepository.observeSources().collectAsState(initial = emptyList())
        when (val parsed = SourceRoute.parse(currentRoute)) {
            SourceRoute.List -> SourcesScreen(
                sources = sources.map { it.toUiItem() },
                onAddWebDav = { onNavigate(SourceRoute.AddWebDav.encoded()) },
                onOpenSource = { onNavigate(SourceRoute.Root(it.id).encoded()) },
            )
            SourceRoute.AddWebDav -> SourceFormRoute(dependencies.controller, null, onNavigate)
            is SourceRoute.Edit -> SourceFormRoute(dependencies.controller, parsed.sourceId, onNavigate)
            is SourceRoute.Root -> {
                val source = sources.firstOrNull { it.id == parsed.sourceId }
                if (source == null) LoadingSource()
                else SourceDetailScreen(
                    source = source.toUiItem(),
                    onEdit = { onNavigate(SourceRoute.Edit(source.id).encoded()) },
                    onBrowse = { onNavigate(SourceRoute.Browse(source.id, "").encoded()) },
                    onSync = { dependencies.controller.requestSync(source.id) },
                    onDelete = {
                        dependencies.controller.delete(source.id)
                        onNavigate(SourceRoute.List.encoded())
                    },
                )
            }
            is SourceRoute.Browse -> SourceBrowseRoute(parsed, dependencies.controller, onNavigate)
            is SourceRoute.Invalid -> Text("Source route is unavailable")
        }
    }
}

@Composable
private fun SourceFormRoute(
    controller: SourceController,
    sourceId: com.cleartune.core.model.SourceId?,
    onNavigate: (String) -> Unit,
) {
    var state by remember(sourceId) { mutableStateOf(WebDavFormState()) }
    var tested by remember(sourceId) { mutableStateOf<TestedSourceDraft?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(sourceId) {
        if (sourceId != null) controller.form(sourceId)?.let { state = it }
    }
    WebDavSourceForm(
        state = state,
        onStateChange = {
            tested = null
            state = it.copy(connectionResult = null, error = null)
        },
        onTestConnection = {
            state = state.copy(testing = true, error = null, connectionResult = null)
            scope.launch {
                val result = controller.testConnection(state, sourceId)
                tested = result.value
                state = state.copy(
                    testing = false,
                    connectionResult = result.value?.let { "Connection successful" },
                    error = result.failure?.message,
                )
            }
        },
        onSave = {
            val receipt = tested ?: return@WebDavSourceForm
            scope.launch {
                val result = controller.save(receipt)
                result.value?.let { onNavigate(SourceRoute.Root(it.id).encoded()) }
                result.failure?.let { state = state.copy(error = it.message) }
            }
        },
    )
}

@Composable
private fun SourceBrowseRoute(
    route: SourceRoute.Browse,
    controller: SourceController,
    onNavigate: (String) -> Unit,
) {
    var items by remember(route) { mutableStateOf<List<SourceBrowseItem>?>(null) }
    var error by remember(route) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun refresh() {
        val result = controller.browse(route.sourceId, route.relativePath)
        items = result.value
        error = result.failure?.message
    }
    LaunchedEffect(route) { refresh() }
    SourceBrowseScreen(
        path = route.relativePath,
        items = items.orEmpty(),
        loading = items == null && error == null,
        error = error,
        onOpen = { item ->
            if (item.isDirectory) {
                val child = listOf(route.relativePath.trim('/'), item.key.trim('/'))
                    .filter(String::isNotBlank).joinToString("/")
                onNavigate(SourceRoute.Browse(route.sourceId, child).encoded())
            }
        },
        onSync = { scope.launch { controller.requestSync(route.sourceId); refresh() } },
    )
}

@Composable
private fun LoadingSource() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}
