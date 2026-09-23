package com.cleartune.core.network

import com.cleartune.core.model.*
import java.io.BufferedInputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MusicTagConnectionRecoveryTest {
    @Test fun readRecoversWhenReusedConnectionClosesWithoutAResponse() = runBlocking {
        Peer(dropFirstRead = true).use { peer ->
            val client = peer.client()
            client.login()
            val result = client.read("/app/media/test.mp3")
            assertEquals("Original", result.values[MusicTagField.TITLE])
            val reads = peer.calls.filter { it.second == "/apimt/music_id3/" }
            assertEquals(2, reads.size)
            assertEquals(peer.calls.first().first, reads.first().first)
            assertNotEquals(reads.first().first, reads.last().first)
        }
    }

    @Test fun saveUsesFreshConnectionAndNeverReplaysAfterLostResponse() = runBlocking {
        Peer(dropWrite = true).use { peer ->
            val client = peer.client()
            client.login()
            val before = client.read("/app/media/test.mp3")
            val error = runCatching { client.write(before, mapOf(MusicTagField.ALBUM to "Changed")) }.exceptionOrNull()
            assertTrue(error is MusicTagException)
            assertEquals("apimt/update_id3/", (error as MusicTagException).endpoint)
            assertTrue(error.message.orEmpty().contains("结果尚未确认"))
            val writes = peer.calls.filter { it.second == "/apimt/update_id3/" }
            assertEquals(1, writes.size)
            assertNotEquals(peer.calls.first().first, writes.single().first)
        }
    }

    @Test fun repeatedSavesNeverReuseAnIdleWriteConnection() = runBlocking {
        Peer().use { peer ->
            val client = peer.client()
            client.login()
            val before = client.read("/app/media/test.mp3")
            client.write(before, mapOf(MusicTagField.ALBUM to "First"))
            client.write(before, mapOf(MusicTagField.ALBUM to "Second"))
            val writes = peer.calls.filter { it.second == "/apimt/update_id3/" }
            assertEquals(2, writes.size)
            assertNotEquals(writes[0].first, writes[1].first)
        }
    }

    /** Real HTTP/1.1 keep-alive peer; closes a connection after consuming a request without responding. */
    private class Peer(private val dropFirstRead: Boolean = false, private val dropWrite: Boolean = false) : Closeable {
        private val listener = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } }
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val connections = AtomicInteger()
        private val reads = AtomicInteger()
        val calls = CopyOnWriteArrayList<Pair<Int, String>>()

        init {
            executor.execute {
                while (!listener.isClosed) {
                    val socket = runCatching { listener.accept() }.getOrNull() ?: break
                    sockets += socket
                    val id = connections.incrementAndGet()
                    executor.execute { runCatching { serve(socket, id) }; socket.close() }
                }
            }
        }

        fun client() = MusicTagClient(MusicTagSettings("http://127.0.0.1:${listener.localPort}", "u", "p", allowHttp = true))

        private fun serve(socket: Socket, id: Int) {
            socket.soTimeout = 5_000
            val input = BufferedInputStream(socket.getInputStream())
            while (!socket.isClosed) {
                val header = StringBuilder()
                while (!header.endsWith("\r\n\r\n")) {
                    val next = input.read()
                    if (next < 0) return
                    header.append(next.toChar())
                    check(header.length < 20_000)
                }
                val lines = header.toString().split("\r\n")
                val path = lines.first().split(' ')[1]
                val length = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.toInt() ?: 0
                repeat(length) { check(input.read() >= 0) }
                calls += id to path
                if (path == "/apimt/music_id3/" && reads.incrementAndGet() == 1 && dropFirstRead) return
                if (path == "/apimt/update_id3/" && dropWrite) return
                val body = when (path) {
                    "/apimt/token/" -> """{"result":true,"token":"test-token"}"""
                    "/apimt/music_id3/" -> """{"result":true,"data":{"filename":"test.mp3","path":"/app/media/test.mp3","title":"Original"}}"""
                    else -> """{"result":true}"""
                }.toByteArray()
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
                    write(body)
                    flush()
                }
            }
        }

        override fun close() {
            listener.close()
            sockets.forEach { runCatching { it.close() } }
            executor.shutdownNow()
        }
    }
}
