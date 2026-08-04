package com.cleartune.feature.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun SourcesScreen(
    sources: List<SourceUiItem>,
    onAddWebDav: () -> Unit,
    onOpenSource: (SourceUiItem) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("音乐来源", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(
                "管理本地音乐和 WebDAV 资料库",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }
        if (sources.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("还没有音乐来源", style = MaterialTheme.typography.titleMedium)
                        Text("添加 WebDAV 后即可同步远程音乐；本地音乐权限可稍后开启。")
                    }
                }
            }
        } else {
            val local = sources.filter(SourceUiItem::local)
            val remote = sources.filterNot(SourceUiItem::local)
            if (local.isNotEmpty()) {
                item { SectionTitle("设备本地") }
                items(local, key = { it.id.value }) { SourceRow(it) { onOpenSource(it) } }
            }
            if (remote.isNotEmpty()) {
                item { SectionTitle("WebDAV") }
                items(remote, key = { it.id.value }) { SourceRow(it) { onOpenSource(it) } }
            }
        }
        item {
            Button(onClick = onAddWebDav, modifier = Modifier.fillMaxWidth()) { Text("添加 WebDAV") }
        }
    }
}
@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SourceRow(item: SourceUiItem, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium)
                Text(item.location, style = MaterialTheme.typography.bodyMedium)
                Text(
                    item.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.insecure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("›", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
fun WebDavSourceForm(
    state: WebDavFormState,
    onStateChange: (WebDavFormState) -> Unit,
    onTestConnection: () -> Unit,
    onSave: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("添加 WebDAV", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold) }
        item {
            OutlinedTextField(state.name, { onStateChange(state.copy(name = it)) }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
        }
        item {
            OutlinedTextField(state.url, { onStateChange(state.copy(url = it)) }, label = { Text("服务器地址") }, supportingText = { Text("推荐使用 HTTPS") }, modifier = Modifier.fillMaxWidth())
        }
        item {
            OutlinedTextField(state.username, { onStateChange(state.copy(username = it)) }, label = { Text("用户名") }, modifier = Modifier.fillMaxWidth())
        }
        item {
            OutlinedTextField(
                state.password,
                { onStateChange(state.copy(password = it)) },
                label = { Text("密码") },
                visualTransformation = if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { onStateChange(state.copy(passwordVisible = !state.passwordVisible)) }) {
                        Text(if (state.passwordVisible) "隐藏" else "显示")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("允许未加密 HTTP")
                    Text("仅用于可信局域网", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Switch(state.allowCleartext, { onStateChange(state.copy(allowCleartext = it)) })
            }
        }
        if (state.allowCleartext) item {
            Text("HTTP 会暴露传输内容，请优先配置 HTTPS。", color = MaterialTheme.colorScheme.error)
        }
        state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        state.connectionResult?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary) } }
        item { HorizontalDivider() }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onTestConnection, enabled = !state.testing, modifier = Modifier.weight(1f)) {
                    if (state.testing) CircularProgressIndicator(Modifier.height(20.dp)) else Text("测试连接")
                }
                Button(onClick = onSave, enabled = state.connectionResult != null, modifier = Modifier.weight(1f)) { Text("保存") }
            }
        }
    }
}

@Composable
fun SourceDetailScreen(
    source: SourceUiItem,
    onEdit: () -> Unit,
    onBrowse: () -> Unit,
    onSync: suspend () -> SourceResult<Unit>,
    onDelete: suspend () -> SourceResult<Unit>,
) {
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(source.name, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(source.location)
        Text(source.status)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) { Text("Browse") }
        Button(onClick = { scope.launch { error = onSync().failure?.message } }, modifier = Modifier.fillMaxWidth()) {
            Text("Sync now")
        }
        TextButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text("Edit") }
        TextButton(
            onClick = { scope.launch { error = onDelete().failure?.message } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Delete") }
    }
}

@Composable
fun SourceBrowseScreen(
    path: String,
    items: List<SourceBrowseItem>,
    loading: Boolean,
    error: String?,
    onOpen: (SourceBrowseItem) -> Unit,
    onSync: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(if (path.isBlank()) "Source files" else path, style = MaterialTheme.typography.headlineLarge)
            Button(onClick = onSync, modifier = Modifier.fillMaxWidth()) { Text("Sync now") }
        }
        if (loading) item { CircularProgressIndicator() }
        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        items(items, key = SourceBrowseItem::key) { entry ->
            Card(onClick = { onOpen(entry) }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (entry.isDirectory) "${entry.name}/" else entry.name,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}
