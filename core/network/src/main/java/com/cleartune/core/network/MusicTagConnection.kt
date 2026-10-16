package com.cleartune.core.network

import com.cleartune.core.model.*
import com.jcraft.jsch.*
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.*

class SshHostKeyRequired(val endpoint: String, val key: String, val fingerprint: String) :
    IllegalStateException("请核对 SSH 服务器指纹")
class SshHostKeyChanged : IllegalStateException("SSH 服务器指纹已变化，已阻止连接。请通过可信渠道核实服务器身份")

fun sshFingerprint(key: String): String = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(
    MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(key)))

fun normalizeMusicTagSettings(settings: MusicTagSettings): MusicTagSettings {
    if (settings.connectionMode == MusicTagConnectionMode.DIRECT)
        return settings.copy(baseUrl = normalizeMusicTagAddress(settings.baseUrl, settings.allowHttp))
    val ssh = settings.ssh
    require(ssh.configured) { "请填写完整的 SSH 登录信息" }
    require(ssh.host.trim().none { it.isWhitespace() || it in "/@?#" } && !ssh.host.contains("://")) { "SSH 服务器只填写 IP 或主机名" }
    val address = normalizeMusicTagAddress(ssh.serviceUrl, true)
    val uri = URI(address)
    require(uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "[::1]", "::1")) {
        "SSH 模式请填写服务器本机 HTTP 地址，例如 http://127.0.0.1:8002"
    }
    require(uri.port == -1 || uri.port in 1..65535) { "WebTag 端口无效" }
    require(ssh.hostKey.isEmpty() || ssh.hostKeyEndpoint == ssh.endpoint) { "SSH 地址已变化，请重新核对指纹" }
    return settings.copy(ssh = ssh.copy(host = ssh.host.trim(), username = ssh.username.trim(), serviceUrl = address))
}

/** Stable journal identity: never includes the ephemeral local port or credentials. */
fun musicTagServiceIdentity(settings: MusicTagSettings): String =
    if (settings.connectionMode == MusicTagConnectionMode.DIRECT) normalizeMusicTagAddress(settings.baseUrl, settings.allowHttp)
    else "ssh:${settings.ssh.endpoint}|${normalizeMusicTagAddress(settings.ssh.serviceUrl, true)}"

internal class PinnedSshHostKeys(private val ssh: MusicTagSshSettings) : HostKeyRepository {
    var observedKey: String? = null
        private set
    override fun check(host: String, key: ByteArray): Int {
        val encoded = Base64.getEncoder().encodeToString(key)
        observedKey = encoded
        if (ssh.hostKey.isEmpty()) return HostKeyRepository.NOT_INCLUDED
        return if (ssh.hostKeyEndpoint == ssh.endpoint && MessageDigest.isEqual(
                encoded.toByteArray(Charsets.US_ASCII), ssh.hostKey.toByteArray(Charsets.US_ASCII)))
            HostKeyRepository.OK else HostKeyRepository.CHANGED
    }
    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID() = "ClearTune pinned host key"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

/** Each operation owns its connection. Concurrent operations cannot close one another's tunnel. */
class MusicTagConnection {
    suspend fun <T> withClient(settings: MusicTagSettings, onProgress: (String) -> Unit = {},
        block: suspend (MusicTagClient) -> T): T {
        val callerContext = currentCoroutineContext().minusKey(Job)
        return withIoClient(settings, onProgress) { client ->
            withContext(callerContext) { block(client) }
        }
    }

    private suspend fun <T> withIoClient(settings: MusicTagSettings, onProgress: (String) -> Unit,
        block: suspend (MusicTagClient) -> T): T = withContext(Dispatchers.IO) {
        val normalized = normalizeMusicTagSettings(settings)
        if (normalized.connectionMode == MusicTagConnectionMode.DIRECT) {
            return@withContext withOperationClient(normalized, block)
        }
        val ssh = normalized.ssh
        val jsch = JSch()
        val keys = PinnedSshHostKeys(ssh)
        jsch.setHostKeyRepository(keys)
        val session = jsch.getSession(ssh.username, ssh.host, ssh.port)
        // Keep the default modern algorithms; never enable SHA-1 host signatures or trust-all verification.
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", if (ssh.auth == MusicTagSshAuth.PASSWORD) "password" else "publickey")
        session.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        session.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
        session.timeout = 15_000
        session.serverAliveInterval = 10_000
        session.serverAliveCountMax = 2
        val cancellationCloser = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { session.disconnect() }
        }
        try {
            if (ssh.auth == MusicTagSshAuth.PASSWORD) {
                val password = ssh.password.toByteArray(Charsets.UTF_8)
                try { session.setPassword(password) } finally { password.fill(0) }
            } else {
                val privateKey = ssh.privateKey.toByteArray(Charsets.UTF_8)
                val passphrase = ssh.passphrase.toByteArray(Charsets.UTF_8)
                try { jsch.addIdentity("cleartune-tag", privateKey, null, passphrase) }
                finally { privateKey.fill(0); passphrase.fill(0) }
            }
            onProgress("正在建立 SSH 加密隧道…")
            try { runInterruptible { session.connect(15_000) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: JSchException) {
                val observed = keys.observedKey
                if (observed != null && ssh.hostKey.isEmpty())
                    throw SshHostKeyRequired(ssh.endpoint, observed, sshFingerprint(observed))
                if (observed != null && observed != ssh.hostKey) throw SshHostKeyChanged()
                throw IllegalStateException("SSH 连接或认证失败，请检查服务器、账号及认证方式")
            }
            ensureActive()
            val target = URI(ssh.serviceUrl)
            val port = session.setPortForwardingL("127.0.0.1", 0, target.host.removeSurrounding("[", "]"),
                if (target.port == -1) 80 else target.port)
            val address = "http://127.0.0.1:$port${target.rawPath}"
            onProgress("SSH 已加密，正在执行标签操作…")
            val local = normalized.copy(baseUrl = address, allowHttp = true, connectionMode = MusicTagConnectionMode.DIRECT)
            withOperationClient(local, block, localTunnel = true)
        } finally {
            withContext(NonCancellable) { cancellationCloser.cancelAndJoin() }
            session.disconnect()
            jsch.removeAllIdentity()
        }
    }

    private suspend fun <T> withOperationClient(settings: MusicTagSettings, block: suspend (MusicTagClient) -> T,
        localTunnel: Boolean = false): T = coroutineScope {
        val client = MusicTagClient(settings, localTunnel)
        val cancellationCloser = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { client.close() }
        }
        try { client.login(); ensureActive(); block(client) }
        finally {
            withContext(NonCancellable) { cancellationCloser.cancelAndJoin() }
            client.close()
        }
    }
}
