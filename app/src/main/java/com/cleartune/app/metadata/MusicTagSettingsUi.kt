package com.cleartune.app.metadata

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cleartune.core.model.*

@Composable
internal fun MusicTagSettingsPage(state: MusicTagUiState, onSave: (MusicTagSettings) -> Unit,
    onDisconnect: () -> Unit, modifier: Modifier = Modifier, onEdit: () -> Unit = {}) {
    var draft by remember(state.settings) { mutableStateOf(state.settings) }
    val showSaved = state.settings.configured && !state.editingSettings
    var confirmRemove by remember { mutableStateOf(false) }
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("移除标签服务配置？") },
        text = { Text("将删除保存的服务凭据和 SSH 信任记录；尚未确认的标签保存记录会保留。") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; onDisconnect() }) { Text("移除配置") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("取消") } })
    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (state.busy || state.isError) MusicTagNotice(state)
            if (showSaved) MusicTagConnectedContent(state.settings)
            else MusicTagSettingsFields(draft, state.busy, { draft = it })
        }
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                if (showSaved) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onEdit, enabled = !state.busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("修改配置") }
                    OutlinedButton(onClick = { confirmRemove = true }, enabled = !state.busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("移除配置") }
                } else Button(onClick = { onSave(draft) }, enabled = !state.busy && draft.configured,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (draft.connectionMode == MusicTagConnectionMode.SSH) "测试并保存配置" else "测试连接并保存") }
            }
        }
    }
}

@Composable
internal fun MusicTagConnectionSettings(settings: MusicTagSettings, busy: Boolean,
    onSave: (MusicTagSettings) -> Unit, onDisconnect: () -> Unit) {
    if (!settings.configured) {
        MusicTagSettingsForm(settings, busy, onSave)
        return
    }
    MusicTagConnectedContent(settings)
    OutlinedButton(onClick = onDisconnect, enabled = !busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
        Text("移除配置")
    }
}

@Composable
private fun MusicTagConnectedContent(settings: MusicTagSettings) {
    var mappingsExpanded by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text("配置已验证", style = MaterialTheme.typography.titleLarge)
                Text(if (settings.connectionMode == MusicTagConnectionMode.SSH) "SSH 隧道 · 待使用，当前未连接"
                    else if (settings.baseUrl.startsWith("https://")) "直接连接 · HTTPS" else "直接连接 · HTTP 未加密",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Text("当前服务", style = MaterialTheme.typography.titleMedium)
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("服务地址", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(settings.serviceAddress, style = MaterialTheme.typography.bodyMedium)
            if (settings.connectionMode == MusicTagConnectionMode.SSH) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("SSH 服务器 · ${settings.ssh.endpoint}", style = MaterialTheme.typography.bodyMedium)
                Text("编辑期间复用连接，空闲 60 秒或离开页面后释放。", style = MaterialTheme.typography.bodySmall)
                Text("保存和核验期间保持连接，完成后才开始空闲计时。", style = MaterialTheme.typography.bodySmall)
                Text("隧道失败时不切换到直连 HTTP。", style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("账号 · ${settings.username}", style = MaterialTheme.typography.bodyMedium)
        }
    }
    Text("音乐目录映射", style = MaterialTheme.typography.titleMedium)
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("两端指向同一份音乐文件", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { mappingsExpanded = !mappingsExpanded }, contentPadding = PaddingValues(0.dp)) {
                Text(if (mappingsExpanded) "收起已配置的路径" else "查看已配置的路径")
                Icon(if (mappingsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            if (mappingsExpanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Navidrome：${settings.sourceRoot.ifBlank { "使用相对路径" }}",
                    style = MaterialTheme.typography.bodySmall)
                Text("Music Tag：${settings.targetRoot}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Text("配置验证代表上次测试通过；实际操作时会重新连接服务。", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun MusicTagSettingsForm(settings: MusicTagSettings, busy: Boolean,
    onSave: (MusicTagSettings) -> Unit) {
    var draft by remember(settings) { mutableStateOf(settings) }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        MusicTagSettingsFields(draft, busy, { draft = it })
        Button(onClick = { onSave(draft) }, enabled = !busy && draft.configured,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (draft.connectionMode == MusicTagConnectionMode.SSH) "测试并保存配置" else "测试连接并保存") }
    }
}

@Composable
private fun MusicTagSettingsFields(settings: MusicTagSettings, busy: Boolean,
    onChange: (MusicTagSettings) -> Unit) {
    var mappingsExpanded by remember { mutableStateOf(settings.sourceRoot.isNotBlank() || settings.targetRoot != "/app/media") }
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (settings.connectionMode == MusicTagConnectionMode.SSH) "按需加密访问" else "连接标签服务", style = MaterialTheme.typography.titleLarge)
            Text(if (settings.connectionMode == MusicTagConnectionMode.SSH) "按需连接，编辑期间复用，空闲 60 秒断开" else "连接后即可刮削和编辑音乐文件标签", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MusicTagConnectionMode.entries.forEach { mode ->
            FilterChip(selected = settings.connectionMode == mode, enabled = !busy,
                onClick = { onChange(settings.copy(connectionMode = mode)) },
                label = { Text(if (mode == MusicTagConnectionMode.DIRECT) "直接连接" else "SSH 隧道") },
                modifier = Modifier.weight(1f))
        }
    }
    if (settings.connectionMode == MusicTagConnectionMode.SSH) MusicTagSshFields(settings.ssh, busy) {
        onChange(settings.copy(ssh = it))
    }
    Text("服务账号", style = MaterialTheme.typography.titleMedium)
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(settings.serviceAddress, {
                onChange(if (settings.connectionMode == MusicTagConnectionMode.SSH) settings.copy(ssh = settings.ssh.copy(serviceUrl = it)) else settings.copy(baseUrl = it))
            },
                label = { Text(if (settings.connectionMode == MusicTagConnectionMode.SSH) "内部服务地址" else "服务地址") },
                supportingText = { Text(if (settings.connectionMode == MusicTagConnectionMode.SSH) "服务器本机 HTTP 地址，公网通过 SSH 加密" else "支持 HTTP / HTTPS，以及 /login、/admin 网页地址") },
                placeholder = { Text("https://music-tag.example.com") },
                modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                shape = RoundedCornerShape(12.dp))
            OutlinedTextField(settings.username, { onChange(settings.copy(username = it)) },
                label = { Text("账号") }, modifier = Modifier.fillMaxWidth(),
                enabled = !busy, singleLine = true, shape = RoundedCornerShape(12.dp))
            OutlinedTextField(settings.password, { onChange(settings.copy(password = it)) },
                label = { Text("密码") }, modifier = Modifier.fillMaxWidth(), enabled = !busy,
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(12.dp))
            if (settings.connectionMode == MusicTagConnectionMode.DIRECT) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = settings.allowHttp,
                    onCheckedChange = { onChange(settings.copy(allowHttp = it)) }, enabled = !busy)
                Column {
                    Text("允许 HTTP 连接", style = MaterialTheme.typography.bodyMedium)
                    if (settings.allowHttp) Text("连接和密码不加密传输",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    Text("音乐目录映射", style = MaterialTheme.typography.titleMedium)
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("默认映射 ${settings.targetRoot}，路径不同时可调整。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { mappingsExpanded = !mappingsExpanded },
                contentPadding = PaddingValues(0.dp)) {
                Text(if (mappingsExpanded) "收起目录配置" else "高级设置 · 展开配置路径")
                Icon(if (mappingsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            if (mappingsExpanded) {
                OutlinedTextField(settings.sourceRoot, { onChange(settings.copy(sourceRoot = it)) },
                    label = { Text("Navidrome 音乐根目录（可留空）") },
                    supportingText = { Text("原路径为相对路径时留空；绝对路径填写对应根目录") },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                    shape = RoundedCornerShape(12.dp))
                OutlinedTextField(settings.targetRoot, { onChange(settings.copy(targetRoot = it)) },
                    label = { Text("Music Tag 音乐根目录") },
                    supportingText = { Text("Music Tag 中对应音乐库的挂载路径") },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                    shape = RoundedCornerShape(12.dp))
            }
        }
    }
    Text("测试成功后保存配置并释放连接。", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
