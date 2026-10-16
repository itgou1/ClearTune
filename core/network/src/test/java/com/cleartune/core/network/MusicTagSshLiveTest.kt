package com.cleartune.core.network

import com.cleartune.core.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.net.Socket
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

/** Opt-in deployment diagnostics. save-lifecycle temporarily changes the user-authorized track's
 * album and restores it in finally; all other modes are read-only. Secrets use process environment. */
class MusicTagSshLiveTest {
    @Test fun actualSaveReadbackAndRestore() = runBlocking {
        assumeTrue(System.getenv("CLEARTUNE_SSH_LIVE_MODE") == "save-lifecycle")
        val config = settings(false)
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = MusicTagEditorConnection(owner)
        var original: MusicTagSnapshot? = null
        var attempted = false
        var activePort = 0
        try {
            manager.enter()
            manager.withClient(config) { client ->
                val path = discoverTrack(client, requireNotNull(System.getenv("CLEARTUNE_TAG_LIVE_WRITE_TITLE")) {
                    "An explicitly authorized test track title is required"
                })
                val before = client.read(path)
                original = before
                java.io.File(requireNotNull(System.getenv("CLEARTUNE_TAG_ROLLBACK_FILE"))).writeText(
                    buildJsonObject { put("path", path); put("album", before.values[MusicTagField.ALBUM].orEmpty()) }.toString())
                println("AUTHORIZED_TRACK_FOUND")
                activePort = URI(clientField(client, "base")).port
                val marker = "ClearTune temporary SSH lifecycle " + java.util.UUID.randomUUID()
                val prepared = client.prepareWrite(before, mapOf(MusicTagField.ALBUM to marker))
                // Keep the real save lease active beyond the idle deadline before submitting.
                delay(65_000)
                assertEquals(before, client.read(path))
                attempted = true
                client.write(prepared)
                println("ACTUAL_SAVE_OK_AFTER_65S_ACTIVE_LEASE")
                manager.leave()
                // Readback deliberately remains pending past another idle interval after leaving.
                delay(65_000)
                assertEquals(marker, client.read(path).values[MusicTagField.ALBUM])
                println("ACTUAL_READBACK_OK_65S_AFTER_LEAVE_SAME_CONNECTION")
            }
            withTimeout(5_000) {
                while (runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", activePort), 500) } }.isSuccess) delay(50)
            }
            println("SAVE_AND_VERIFY_FINISHED_CONNECTION_RELEASED")
        } finally {
            manager.leave(); owner.cancel(); owner.coroutineContext[Job]?.join()
            if (attempted) withContext(NonCancellable) {
                val before = checkNotNull(original)
                MusicTagConnection().withClient(config) { client ->
                    client.write(client.read(before.path), mapOf(MusicTagField.ALBUM to before.values[MusicTagField.ALBUM].orEmpty()))
                    val restored = client.read(before.path)
                    assertEquals(before.values, restored.values)
                    println("ORIGINAL_ALL_TAG_FIELDS_RESTORED_AND_VERIFIED")
                }
            }
        }
    }

    @Test fun inspectTemporaryFixtureAccess() = runBlocking {
        assumeTrue(System.getenv("CLEARTUNE_SSH_LIVE_MODE") == "fixture-access")
        val config = settings(false).ssh
        val jsch = com.jcraft.jsch.JSch()
        jsch.hostKeyRepository = PinnedSshHostKeys(config)
        val session = jsch.getSession(config.username, config.host, config.port)
        session.setPassword(config.password)
        session.setConfig("StrictHostKeyChecking", "yes")
        try {
            session.connect(15_000)
            val channel = session.openChannel("sftp") as com.jcraft.jsch.ChannelSftp
            try {
                channel.connect(10_000)
                println("TEMP_FIXTURE_SFTP_AVAILABLE")
                println("MEDIA_ROOT_AVAILABLE=" + runCatching { channel.stat("/app/media").isDir }.getOrDefault(false))
            } catch (_: Exception) { println("TEMP_FIXTURE_SFTP_UNAVAILABLE") }
            finally { channel.disconnect() }
        } finally { session.disconnect() }
    }

    @Test fun editorLifecycleAgainstRealServer() = runBlocking {
        assumeTrue(System.getenv("CLEARTUNE_SSH_LIVE_MODE") == "lifecycle")
        val config = settings(false)
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val opens = java.util.concurrent.atomic.AtomicInteger()
        val closes = java.util.concurrent.atomic.AtomicInteger()
        val manager = MusicTagEditorConnection(owner, 60_000) { settings, progress, use ->
            opens.incrementAndGet()
            try { MusicTagConnection().withClient(settings, progress, use) }
            finally { closes.incrementAndGet() }
        }
        fun port(client: MusicTagClient) = URI(clientField(client, "base")).port
        fun listening(port: Int) = runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
        }.isSuccess
        suspend fun closed(port: Int) {
            withTimeout(5_000) { while (listening(port)) delay(50) }
        }
        try {
            manager.enter()
            var path = ""
            val first = manager.withClient(config) { client ->
                path = System.getenv("CLEARTUNE_TAG_LIVE_PATH") ?: discoverTrack(client)
                client.read(path)
                client
            }
            repeat(3) {
                manager.withClient(config) { client ->
                    assertSame(first, client); client.read(path)
                }
            }
            assertEquals(1, opens.get())
            println("EDIT_REUSE_OK: 4 reads, 1 SSH/WebTag session")
            val idleStart = System.nanoTime()
            delay(59_000)
            assertTrue(listening(port(first)))
            assertEquals(0, closes.get())
            closed(port(first))
            val idleMs = (System.nanoTime() - idleStart) / 1_000_000
            assertTrue(idleMs in 59_900..65_000)
            println("IDLE_RELEASE_OK_MS=$idleMs")

            val second = manager.withClient(config) { it.read(path); it }
            assertNotSame(first, second)
            manager.leave(); closed(port(second))
            println("PAGE_LEAVE_RELEASE_OK")

            manager.enter()
            var activePort = 0
            manager.withClient(config) { client ->
                activePort = port(client)
                val before = client.read(path)
                // Exercise the same full-operation lease used by repository.apply; no existing song is modified.
                client.prepareWrite(before, mapOf(MusicTagField.ALBUM to "ClearTune lease diagnostic (not submitted)"))
                delay(65_000)
                assertTrue(listening(activePort))
                assertEquals(before, client.read(path))
                println("ACTIVE_OPERATION_OVER_60S_OK")
                manager.leave()
                delay(65_000)
                assertTrue(listening(activePort))
                assertEquals(before, client.read(path))
                println("LEAVE_DURING_OPERATION_READBACK_OVER_60S_OK")
            }
            closed(activePort)
            withTimeout(5_000) { while (closes.get() != opens.get()) delay(50) }
            println("OPERATION_FINISH_RELEASE_OK: opens=${opens.get()}, closes=${closes.get()}")
            println("TAG_WRITES_PERFORMED=false")
        } finally { manager.leave(); owner.cancel(); owner.coroutineContext[Job]?.join() }
    }

    @Test fun probeHostIdentityWithoutAuthenticating() = runBlocking {
        assumeTrue(System.getenv("CLEARTUNE_SSH_LIVE_MODE") == "probe")
        val settings = settings(probe = true)
        val result = runCatching { MusicTagConnection().withClient(settings) {} }.exceptionOrNull()
        assertTrue("Expected a host-key verification request; got ${result?.javaClass?.simpleName}", result is SshHostKeyRequired)
        val key = result as SshHostKeyRequired
        println("SSH_ENDPOINT=${key.endpoint}")
        println("SSH_FINGERPRINT=${key.fingerprint}")
        println("SSH_PUBLIC_KEY=${key.key}")
        println("AUTHENTICATION_NOT_ATTEMPTED")
    }

    @Test fun verifiedSshLoginAndReadOnlyTagOperations() = runBlocking {
        assumeTrue(System.getenv("CLEARTUNE_SSH_LIVE_MODE") == "verified")
        val settings = settings(probe = false)
        require(settings.ssh.hostKey.isNotBlank()) { "An independently verified host key is required" }
        repeat(2) { attempt ->
            var localPort = 0
            try {
                MusicTagConnection().withClient(settings) { client ->
                    localPort = URI(clientField(client, "base")).port
                    println("OPERATION_${attempt + 1}_SSH_AND_WEBTAG_LOGIN_OK")
                    val path = System.getenv("CLEARTUNE_TAG_LIVE_PATH")
                        ?: if (System.getenv("CLEARTUNE_TAG_LIVE_DISCOVER") == "true") discoverTrack(client) else null
                    if (!path.isNullOrBlank()) {
                        val snapshot = client.read(path)
                        assertEquals(path, snapshot.path)
                        assertEquals(snapshot, client.read(path))
                        println("OPERATION_${attempt + 1}_TAG_READ_OK")
                    }
                    val query = System.getenv("CLEARTUNE_TAG_LIVE_QUERY")
                    if (!query.isNullOrBlank()) {
                        val candidates = client.search(query, "", "", "netease")
                        println("OPERATION_${attempt + 1}_SEARCH_OK_COUNT=${candidates.size}")
                    }
                }
                assertTrue("Local SSH listener must close after an operation", runCatching {
                    Socket().use { it.connect(InetSocketAddress("127.0.0.1", localPort), 1_000) }
                }.isFailure)
                println("OPERATION_${attempt + 1}_CONNECTION_RELEASED")
            } catch (error: Exception) {
                throw AssertionError("Read-only live operation ${attempt + 1} failed: ${error.javaClass.simpleName}")
            }
        }
    }

    // Diagnostic-only access: enumerate existing files through this client's already authenticated
    // loopback connection without expanding the production API or logging its token/filenames.
    private fun clientField(client: MusicTagClient, name: String): String =
        MusicTagClient::class.java.getDeclaredField(name).apply { isAccessible = true }.get(client) as String

    private fun discoverTrack(client: MusicTagClient, title: String? = null): String {
        val base = clientField(client, "base")
        require(URI(base).host == "127.0.0.1")
        val token = clientField(client, "token")
        val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(20, TimeUnit.SECONDS).build()
        val directories = ArrayDeque<Pair<String, Int>>().apply { add("/app/media" to 0) }
        val matches = mutableListOf<String>()
        try {
            repeat(if (title == null) 12 else 300) {
                if (directories.isEmpty()) {
                    check(matches.size == 1) { "Expected one authorized track; matches=${matches.size}" }
                    return matches.single()
                }
                val (directory, depth) = directories.removeFirst()
                val body = buildJsonObject {
                    put("file_path", directory); put("sorted_fields", JsonArray(emptyList()))
                    put("search_word", ""); put("refresh", false)
                }
                val request = Request.Builder().url(base + "apimt/file_list/")
                    .header("AUTHORIZATION", "jwt $token").header("X-Requested-With", "XMLHttpRequest")
                    .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                val children = http.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Read-only directory request failed" }
                    val reply = Json.parseToJsonElement(response.body.string()).jsonObject
                    check(reply["result"]?.jsonPrimitive?.booleanOrNull == true)
                    reply["data"]?.jsonObject?.get("file_list_data")?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("children")?.jsonArray.orEmpty()
                }
                children.forEach { item ->
                    val row = item.jsonObject
                    val name = row["name"]?.jsonPrimitive?.content.orEmpty()
                    if (name.isBlank() || name in setOf(".", "..") || name.any { it == '/' || it == '\\' || it.code < 32 }) return@forEach
                    val path = "$directory/$name"
                    if (name.substringAfterLast('.', "").lowercase() in setOf("mp3", "flac", "m4a", "ogg", "wav", "opus", "aac")) {
                        if (title == null) return path
                        if (name.contains(title, ignoreCase = true)) matches.add(path)
                    }
                    if (depth < (if (title == null) 3 else 12) && row["icon"]?.jsonPrimitive?.content.orEmpty().contains("folder")) directories.add(path to depth + 1)
                }
            }
            error("Read-only discovery limit reached")
        } finally { http.dispatcher.cancelAll(); http.connectionPool.evictAll() }
    }

    private fun settings(probe: Boolean): MusicTagSettings {
        fun env(name: String) = requireNotNull(System.getenv(name)) { "Missing diagnostic input: $name" }
        val host = env("CLEARTUNE_SSH_LIVE_HOST")
        val port = System.getenv("CLEARTUNE_SSH_LIVE_PORT")?.toInt() ?: 22
        return MusicTagSettings(username = if (probe) "unused" else env("CLEARTUNE_TAG_LIVE_USER"),
            password = if (probe) "unused" else env("CLEARTUNE_TAG_LIVE_PASSWORD"),
            connectionMode = MusicTagConnectionMode.SSH,
            ssh = MusicTagSshSettings(host, port, env("CLEARTUNE_SSH_LIVE_USER"),
                password = if (probe) "not-sent" else env("CLEARTUNE_SSH_LIVE_PASSWORD"),
                serviceUrl = "http://127.0.0.1:8002",
                hostKey = if (probe) "" else env("CLEARTUNE_SSH_LIVE_KEY"),
                hostKeyEndpoint = if (probe) "" else "$host:$port"))
    }
}
