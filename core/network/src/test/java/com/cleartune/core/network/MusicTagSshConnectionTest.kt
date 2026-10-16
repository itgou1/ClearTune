package com.cleartune.core.network

import com.cleartune.core.model.*
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.junit.Assert.*
import org.junit.Test

class MusicTagSshConnectionTest {
    @Test fun editorReusesAuthenticatedTunnelAndReleasesOnLeave() = runBlocking {
        Peer().use { peer ->
            val settings = peer.discoverAndTrust()
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val editor = MusicTagEditorConnection(owner)
            editor.enter()
            try {
                repeat(3) { editor.withClient(settings) { it.read("/app/media/test.mp3") } }
                assertEquals(1, peer.auths.get())
                assertEquals(1, peer.ssh.activeSessions.size)
                editor.leave(); peer.awaitClosed()
            } finally { owner.cancel() }
        }
    }

    @Test fun editorCancellationInterruptsInFlightReadAndClosesTunnel() = runBlocking {
        Peer().use { peer ->
            val settings = peer.discoverAndTrust()
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val editor = MusicTagEditorConnection(owner)
            editor.enter()
            try {
                peer.stallRead = true
                val operation = launch { editor.withClient(settings) { it.read("/app/media/test.mp3") } }
                withTimeout(5_000) { peer.readEntered.await() }
                withTimeout(3_000) { operation.cancelAndJoin() }
                peer.awaitClosed()
            } finally { owner.cancel() }
        }
    }

    @Test fun businessCallbackStaysOnCallerThreadForBothConnectionModes() = runBlocking {
        Peer().use { peer ->
            val settings = peer.discoverAndTrust()
            java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { main ->
                withContext(main) {
                    val callerThread = Thread.currentThread()
                    MusicTagConnection().withClient(settings) { assertSame(callerThread, Thread.currentThread()) }
                    val direct = settings.copy(connectionMode = MusicTagConnectionMode.DIRECT,
                        baseUrl = settings.ssh.serviceUrl, allowHttp = true)
                    MusicTagConnection().withClient(direct) { assertSame(callerThread, Thread.currentThread()) }
                    val editor = MusicTagEditorConnection(this)
                    editor.enter()
                    try {
                        repeat(2) { editor.withClient(settings) { assertSame(callerThread, Thread.currentThread()) } }
                    } finally { editor.leave() }
                }
            }
            peer.awaitClosed()
        }
    }
    @Test fun cancellationInterruptsAnInFlightHttpRead() = runBlocking {
        Peer().use { peer ->
            val settings = peer.discoverAndTrust()
            peer.stallRead = true
            val operation = launch { MusicTagConnection().withClient(settings) { it.read("/app/media/test.mp3") } }
            withTimeout(5_000) { peer.readEntered.await() }
            withTimeout(3_000) { operation.cancelAndJoin() }
            peer.awaitClosed()
        }
    }

    @Test fun failedAuthenticationNeverFallsBackToDirectHttp() = runBlocking {
        Peer().use { peer ->
            val trusted = peer.discoverAndTrust()
            val settings = trusted.copy(baseUrl = trusted.ssh.serviceUrl, allowHttp = true,
                ssh = trusted.ssh.copy(password = "incorrect"))
            assertTrue(runCatching { MusicTagConnection().withClient(settings) {} }.isFailure)
            assertEquals(0, peer.requests.get())
            peer.awaitClosed()
        }
    }

    @Test fun uncertainWriteOverSshIsNotReplayed() = runBlocking {
        Peer().use { peer ->
            val settings = peer.discoverAndTrust()
            peer.dropWrite = true
            val error = runCatching {
                MusicTagConnection().withClient(settings) { client ->
                    client.write(client.read("/app/media/test.mp3"), mapOf(MusicTagField.ALBUM to "Changed"))
                }
            }.exceptionOrNull()
            assertTrue(error is MusicTagException)
            assertEquals("apimt/update_id3/", (error as MusicTagException).endpoint)
            assertEquals(1, peer.writes.get())
            peer.awaitClosed()
        }
    }
    @Test fun verifiesEd25519HostKeyUsingBundledAndroidCompatibleImplementation() = runBlocking {
        Peer("Ed25519").use { peer ->
            MusicTagConnection().withClient(peer.discoverAndTrust()) { it.read("/app/media/test.mp3") }
            peer.awaitClosed()
        }
    }
    @Test fun rejectsUnknownAndChangedKeysBeforeSendingCredentials() = runBlocking {
        Peer().use { peer ->
            val required = runCatching { MusicTagConnection().withClient(peer.settings) {} }.exceptionOrNull()
            assertTrue(required is SshHostKeyRequired)
            assertEquals(0, peer.auths.get())
            assertEquals(0, peer.requests.get())
            val trusted = peer.trusted(required as SshHostKeyRequired)
            val wrong = trusted.copy(ssh = trusted.ssh.copy(hostKey = Base64.getEncoder().encodeToString("wrong".toByteArray())))
            assertTrue(runCatching { MusicTagConnection().withClient(wrong) {} }.exceptionOrNull() is SshHostKeyChanged)
            assertEquals(0, peer.auths.get())
            assertEquals(0, peer.requests.get())
        }
    }

    @Test fun reusesTunnelWithinOperationAndReleasesAfterEachOperation() = runBlocking {
        Peer().use { peer ->
            val settings = peer.discoverAndTrust()
            repeat(2) {
                MusicTagConnection().withClient(settings) { client ->
                    assertEquals(1, peer.ssh.activeSessions.size)
                    assertEquals("Original", client.read("/app/media/test.mp3").values[MusicTagField.TITLE])
                    client.search("Title", "Artist", "Album", "netease")
                    assertEquals(1, peer.ssh.activeSessions.size)
                }
                peer.awaitClosed()
            }
            assertEquals(2, peer.auths.get())
            assertEquals(6, peer.requests.get())
        }
    }

    @Test fun releasesOnFailureAndCancellationAndKeepsConcurrentOperationAlive() = runBlocking {
        Peer().use { peer ->
            val settings = peer.discoverAndTrust()
            assertTrue(runCatching { MusicTagConnection().withClient(settings) { error("operation failed") } }.isFailure)
            peer.awaitClosed()
            val entered = CompletableDeferred<Unit>()
            val holding = launch { MusicTagConnection().withClient(settings) { entered.complete(Unit); awaitCancellation() } }
            entered.await()
            MusicTagConnection().withClient(settings) { it.read("/app/media/test.mp3") }
            assertTrue(holding.isActive)
            holding.cancelAndJoin()
            peer.awaitClosed()
        }
    }

    @Test fun authenticatesWithEncryptedPrivateKey() = runBlocking {
        Peer().use { peer ->
            val trusted = peer.discoverAndTrust()
            val key = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 2048)
            val output = ByteArrayOutputStream()
            try {
                key.writePrivateKey(output, "key-passphrase".toByteArray())
                val encodedPublic = key.publicKeyBlob
                peer.ssh.setPublickeyAuthenticator { _, publicKey, _ ->
                    // Compare the SSH wire encoding, not an unconditional trust-all authenticator.
                    val buffer = org.apache.sshd.common.util.buffer.ByteArrayBuffer()
                    buffer.putRawPublicKey(publicKey)
                    buffer.compactData.contentEquals(encodedPublic)
                }
                val settings = trusted.copy(ssh = trusted.ssh.copy(auth = MusicTagSshAuth.PRIVATE_KEY,
                    password = "", privateKey = output.toString("UTF-8"), passphrase = "key-passphrase"))
                MusicTagConnection().withClient(settings) { it.read("/app/media/test.mp3") }
                peer.awaitClosed()
            } finally { key.dispose() }
        }
    }

    @Test fun validationKeepsDirectConsentAndRestrictsTunnelToLoopback() {
        val http = MusicTagSettings("http://example.test:8002", "u", "p")
        assertTrue(runCatching { normalizeMusicTagSettings(http) }.isFailure)
        assertEquals("http://example.test:8002/", normalizeMusicTagSettings(http.copy(allowHttp = true)).baseUrl)
        val ssh = http.copy(connectionMode = MusicTagConnectionMode.SSH,
            ssh = MusicTagSshSettings(host = "ssh.example.test", username = "tunnel", password = "p"))
        assertTrue(normalizeMusicTagSettings(ssh).configured)
        assertTrue(runCatching { normalizeMusicTagSettings(ssh.copy(ssh = ssh.ssh.copy(serviceUrl = "http://example.test:8002"))) }.isFailure)
        assertNotEquals(musicTagServiceIdentity(ssh), musicTagServiceIdentity(ssh.copy(ssh = ssh.ssh.copy(host = "other.test"))))
        assertTrue(runCatching { MusicTagClient(ssh) }.isFailure)
        val keys = PinnedSshHostKeys(ssh.ssh.copy(hostKey = "a2V5", hostKeyEndpoint = "other.test:22"))
        assertEquals(HostKeyRepository.CHANGED, keys.check("ssh.example.test", "key".toByteArray()))
    }

    private class Peer(hostAlgorithm: String = "RSA") : java.io.Closeable {
        val requests = AtomicInteger()
        val auths = AtomicInteger()
        val writes = AtomicInteger()
        val readEntered = CompletableDeferred<Unit>()
        @Volatile var stallRead = false
        @Volatile var dropWrite = false
        private val releaseRead = java.util.concurrent.CountDownLatch(1)
        private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val ssh = SshServer.setUpDefaultServer()
        val settings: MusicTagSettings
        init {
            http.createContext("/") { exchange ->
                exchange.requestBody.use { it.readBytes() }
                requests.incrementAndGet()
                if (exchange.requestURI.path == "/apimt/music_id3/" && stallRead) {
                    readEntered.complete(Unit)
                    releaseRead.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    exchange.close()
                    return@createContext
                }
                if (exchange.requestURI.path == "/apimt/update_id3/") {
                    writes.incrementAndGet()
                    if (dropWrite) { exchange.close(); return@createContext }
                }
                val body = when (exchange.requestURI.path) {
                    "/apimt/token/" -> """{"result":true,"token":"test-token"}"""
                    "/apimt/music_id3/" -> """{"result":true,"data":{"filename":"test.mp3","path":"/app/media/test.mp3","title":"Original"}}"""
                    else -> """{"result":true,"data":[]}"""
                }.toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            http.start()
            ssh.host = "127.0.0.1"
            ssh.port = 0
            ssh.keyPairProvider = KeyPairProvider.wrap((if (hostAlgorithm == "Ed25519")
                KeyPairGenerator.getInstance(hostAlgorithm, org.bouncycastle.jce.provider.BouncyCastleProvider())
                else KeyPairGenerator.getInstance(hostAlgorithm)).apply {
                if (hostAlgorithm == "RSA") initialize(2048)
            }.generateKeyPair())
            ssh.setPasswordAuthenticator { user, password, _ -> auths.incrementAndGet(); user == "tunnel" && password == "secret" }
            ssh.forwardingFilter = AcceptAllForwardingFilter.INSTANCE
            ssh.start()
            settings = MusicTagSettings("http://unused.invalid", "webtag", "webtag-password", connectionMode = MusicTagConnectionMode.SSH,
                ssh = MusicTagSshSettings("127.0.0.1", ssh.port, "tunnel", password = "secret", serviceUrl = "http://127.0.0.1:${http.address.port}"))
        }
        fun trusted(required: SshHostKeyRequired) = settings.copy(ssh = settings.ssh.copy(hostKey = required.key, hostKeyEndpoint = required.endpoint))
        suspend fun discoverAndTrust(): MusicTagSettings {
            val error = runCatching { MusicTagConnection().withClient(settings) {} }.exceptionOrNull()
            check(error is SshHostKeyRequired) { "Expected host-key prompt, got ${error?.javaClass?.simpleName}" }
            awaitClosed()
            return trusted(error)
        }
        suspend fun awaitClosed() = withTimeout(5_000) { while (ssh.activeSessions.isNotEmpty()) delay(20) }
        override fun close() { releaseRead.countDown(); ssh.stop(true); http.stop(0) }
    }
}
