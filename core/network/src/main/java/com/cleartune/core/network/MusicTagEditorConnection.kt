package com.cleartune.core.network

import com.cleartune.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Page-owned authenticated connection. An operation is one lease, including write verification. */
class MusicTagEditorConnection internal constructor(
    private val scope: CoroutineScope,
    private val idleMillis: Long,
    private val openConnection: suspend (MusicTagSettings, (String) -> Unit, suspend (MusicTagClient) -> Unit) -> Unit,
) {
    constructor(scope: CoroutineScope) : this(scope, IDLE_TIMEOUT_MILLIS,
        { settings, progress, use -> MusicTagConnection().withClient(settings, progress, use) })

    private class Connection(val settings: MusicTagSettings) {
        val ready = CompletableDeferred<MusicTagClient>()
        lateinit var job: Job
    }

    private val guard = Any()
    private val operations = Mutex()
    private var visible = false
    private var generation = 0L
    private var busy = false
    private var connection: Connection? = null
    private var idleJob: Job? = null

    fun enter() = synchronized(guard) { visible = true }

    /** Do not cut off an active save/readback. Its finally block releases the connection. */
    fun leave() {
        val detached = synchronized(guard) {
            visible = false
            generation++
            idleJob?.cancel()
            idleJob = null
            if (busy) null else connection.also { connection = null }
        }
        detached?.job?.cancel()
    }

    suspend fun <T> withClient(settings: MusicTagSettings, onProgress: (String) -> Unit = {},
        block: suspend (MusicTagClient) -> T): T {
        // A queued operation must not reopen a connection after the page was left/reopened.
        val requestedGeneration = synchronized(guard) {
            check(visible) { "标签页面已离开，请重新打开后操作" }
            generation
        }
        return operations.withLock { coroutineScope {
            scope.coroutineContext.ensureActive()
            val identity = settings.copy(source = "", sourceRoot = "", targetRoot = "")
            var old: Connection? = null
            val current = synchronized(guard) {
                check(visible && generation == requestedGeneration) { "标签页面已变化，请重新操作" }
                idleJob?.cancel()
                idleJob = null
                busy = true
                connection?.takeIf { it.settings == identity && it.job.isActive } ?: run {
                    old = connection
                    Connection(identity).also { created ->
                        // Lazy start ensures the job is assigned before lifecycle callbacks can see it.
                        created.job = scope.launch(start = CoroutineStart.LAZY) {
                            try {
                                openConnection(settings, onProgress) { client ->
                                    created.ready.complete(client)
                                    awaitCancellation()
                                }
                            } catch (error: Throwable) {
                                created.ready.completeExceptionally(error)
                            } finally {
                                created.ready.completeExceptionally(CancellationException("标签连接已释放"))
                            }
                        }
                        created.job.invokeOnCompletion {
                            created.ready.completeExceptionally(CancellationException("标签连接已释放"))
                        }
                        connection = created
                    }
                }
            }
            val succeeded = java.util.concurrent.atomic.AtomicBoolean(false)
            val cancellationCloser = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { if (!succeeded.get()) current.job.cancel() }
            }
            try {
                old?.job?.cancelAndJoin()
                val reused = current.job.isActive
                current.job.start()
                val client = current.ready.await()
                currentCoroutineContext().ensureActive()
                if (reused) onProgress(if (settings.connectionMode == MusicTagConnectionMode.SSH)
                    "复用 SSH 加密连接，正在执行标签操作…" else "正在执行标签操作…")
                // Deliberately run business work on the caller's dispatcher (normally Main).
                block(client).also { succeeded.set(true) }
            } finally {
                withContext(NonCancellable) { cancellationCloser.cancelAndJoin() }
                val close = synchronized(guard) {
                    busy = false
                    if (!succeeded.get() || !visible || generation != requestedGeneration || !current.job.isActive) {
                        if (connection === current) connection = null
                        true
                    } else {
                        idleJob = scope.launch {
                            delay(idleMillis)
                            val expired = synchronized(guard) {
                                if (!busy && connection === current) {
                                    connection = null
                                    idleJob = null
                                    true
                                } else false
                            }
                            if (expired) current.job.cancelAndJoin()
                        }
                        false
                    }
                }
                if (close) withContext(NonCancellable) { current.job.cancelAndJoin() }
            }
        } }
    }

    companion object { const val IDLE_TIMEOUT_MILLIS = 60_000L }
}
