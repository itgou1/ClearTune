package com.cleartune.app.metadata

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cleartune.core.model.*
import com.cleartune.core.network.sshFingerprint
import kotlinx.coroutines.*

@Composable
internal fun MusicTagSshFields(ssh: MusicTagSshSettings, busy: Boolean, onChange: (MusicTagSshSettings) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentSsh by rememberUpdatedState(ssh)
    val currentOnChange by rememberUpdatedState(onChange)
    var importMessage by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            try {
                val key = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val size = input.read(buffer)
                            if (size < 0) break
                            require(output.size() + size <= 65536) { "私钥文件不能超过 64 KB" }
                            output.write(buffer, 0, size)
                        }
                        output.toString("UTF-8")
                    } ?: error("无法读取私钥文件")
                }
                require(key.contains("PRIVATE KEY-----")) { "请选择 PEM 或 OpenSSH 格式的私钥" }
                currentOnChange(currentSsh.copy(privateKey = key))
                importMessage = "私钥已载入，测试成功后加密保存"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { importMessage = "无法导入私钥，请选择不超过 64 KB 的 PEM 或 OpenSSH 私钥文件" }
            finally { importing = false }
        }
    }
    Text("SSH 账号", style = MaterialTheme.typography.titleMedium)
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(ssh.host, { onChange(ssh.copy(host = it, hostKey = "", hostKeyEndpoint = "")) },
                label = { Text("SSH 服务器") }, supportingText = { Text("IP 或主机名，不含协议和端口") },
                modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, shape = RoundedCornerShape(12.dp))
            OutlinedTextField(if (ssh.port == 0) "" else ssh.port.toString(), { text ->
                if (text.all(Char::isDigit) && text.length <= 5)
                    onChange(ssh.copy(port = text.toIntOrNull() ?: 0, hostKey = "", hostKeyEndpoint = ""))
            }, label = { Text("SSH 端口") }, modifier = Modifier.fillMaxWidth(), enabled = !busy,
                singleLine = true, shape = RoundedCornerShape(12.dp))
            OutlinedTextField(ssh.username, { onChange(ssh.copy(username = it)) }, label = { Text("SSH 用户名") },
                modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, shape = RoundedCornerShape(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MusicTagSshAuth.entries.forEach { auth ->
                    FilterChip(selected = ssh.auth == auth, enabled = !busy && !importing,
                        onClick = { onChange(ssh.copy(auth = auth)) }, label = { Text(if (auth == MusicTagSshAuth.PASSWORD) "密码认证" else "私钥认证") })
                }
            }
            if (ssh.auth == MusicTagSshAuth.PASSWORD) {
                OutlinedTextField(ssh.password, { onChange(ssh.copy(password = it)) }, label = { Text("SSH 密码") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    enabled = !busy, singleLine = true, shape = RoundedCornerShape(12.dp))
            } else {
                OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }, enabled = !busy && !importing) {
                    Text(if (importing) "正在导入…" else if (ssh.privateKey.isBlank()) "导入私钥文件" else "更换私钥文件")
                }
                Text(if (ssh.privateKey.isBlank()) "尚未导入私钥" else "私钥已载入（内容隐藏）", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(ssh.passphrase, { onChange(ssh.copy(passphrase = it)) }, label = { Text("私钥口令（可留空）") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    enabled = !busy, singleLine = true, shape = RoundedCornerShape(12.dp))
                importMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Text("SSH 账号与 Music Tag 账号相互独立，凭据加密保存。", style = MaterialTheme.typography.bodySmall)
            if (ssh.hostKey.isNotEmpty()) {
                Text("已记录的服务器指纹", style = MaterialTheme.typography.labelMedium)
                Text(runCatching { sshFingerprint(ssh.hostKey) }.getOrDefault("指纹记录无效，请移除配置后重新核对"), style = MaterialTheme.typography.bodySmall)
            } else Text("首次测试需核对服务器指纹。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
