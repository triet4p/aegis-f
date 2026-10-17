package io.github.triet4p.aegisf.data.laya

import io.github.triet4p.aegisf.data.CategoryEntity
import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/**
 * Process-owned access to the local Laya runtime. At most one inference is active and one
 * newer request is pending; a newer pending request replaces the older one. The runtime is
 * opened, used, and closed by its single worker only.
 *
 * Cancelling a caller removes its queued request. If native ORT execution has already
 * started, cancellation only withdraws delivery: it does not interrupt or claim to stop
 * native work. The worker keeps the request and session until `run` returns, then releases
 * the request without publishing its result. [releaseSession] asks the worker to close an
 * idle session (or close it after active native work); a later request can reopen it.
 *
 * The session is retained for a short idle window to avoid paying its cold-open cost for
 * closely spaced suggestions. Once idle, both the session and worker thread are released.
 * The notification listener does not access this service; notification persistence keeps
 * its independent IO lifecycle.
 */
class LayaInferenceLifecycle internal constructor(
    private val modelDirectory: File,
    private val idleTimeoutMillis: Long = SESSION_IDLE_TIMEOUT_MILLIS,
    private val runtimeFactory: LayaInferenceRuntimeFactory =
        LayaInferenceRuntimeFactory { directory: File -> openRuntime(directory) },
) : Closeable {

    private val monitor = java.lang.Object()
    private var pending: Request<*>? = null
    private var worker: Thread? = null
    private var closed = false
    private var releaseRequested = false
    private var idleDeadlineNanos = 0L

    /** Opens and verifies the model on the worker without retaining it in the caller. */
    suspend fun prepare(): LayaInferenceOutcome<Unit> = submit { Unit }

    /**
     * Returns one review-only decision, or an explicit lifecycle outcome. This suspends the
     * caller while the single background worker performs all blocking runtime work.
     */
    suspend fun suggest(
        description: String,
        categories: List<CategoryEntity>,
        correctionRules: List<LayaCategoryCorrectionRule> = emptyList(),
    ): LayaInferenceOutcome<LayaCandidatePlanner.Decision> = submit { session ->
        session.suggest(description, categories, correctionRules)
    }

    /** Low-level positional request used only by the existing debug parity probe. */
    internal suspend fun suggestOptions(
        description: String,
        categories: List<CategoryOption>,
    ): LayaInferenceOutcome<LayaRuntimeContract.Suggestion> = submit { session ->
        session.suggestOptions(description, categories)
    }

    /**
     * Closes the cached session when idle, or immediately after current native work returns.
     * The service remains usable and the next request opens a fresh verified session.
     */
    fun releaseSession() {
        synchronized(monitor) {
            if (!closed && worker != null) {
                releaseRequested = true
                monitor.notifyAll()
            }
        }
    }

    /**
     * Rejects future requests and drains the one pending slot. An active native call is not
     * interrupted; the worker discards its eventual result and closes the session afterwards.
     */
    override fun close() {
        val queued = synchronized(monitor) {
            if (closed) return
            closed = true
            monitor.notifyAll()
            pending.also { pending = null }
        }
        queued?.finishClosed()
    }

    private suspend fun <T> submit(
        operation: (LayaInferenceSession) -> T,
    ): LayaInferenceOutcome<T> {
        val result = CompletableDeferred<LayaInferenceOutcome<T>>(currentCoroutineContext()[Job])
        val request = Request(result, operation)
        result.invokeOnCompletion { cause ->
            if (cause is CancellationException) removePending(request)
        }

        var replaced: Request<*>? = null
        var rejectClosed = false
        synchronized(monitor) {
            when {
                !result.isActive -> Unit
                closed -> rejectClosed = true
                else -> {
                    replaced = pending
                    pending = request
                    ensureWorkerLocked()
                    monitor.notifyAll()
                }
            }
        }
        if (rejectClosed) request.finishClosed()
        replaced?.finishSuperseded()
        return result.await()
    }

    private fun removePending(request: Request<*>) {
        synchronized(monitor) {
            if (pending === request) {
                pending = null
                monitor.notifyAll()
            }
        }
    }

    private fun ensureWorkerLocked() {
        if (worker != null) return
        val thread = Thread(::runWorker, "aegis-laya-inference").apply {
            isDaemon = true
        }
        worker = thread
        thread.start()
    }

    private fun runWorker() {
        var session: LayaInferenceSession? = null
        var activeRequest: Request<*>? = null
        try {
            while (true) {
                when (val action = nextAction(session != null)) {
                    WorkerAction.Exit -> return
                    WorkerAction.Release -> {
                        session?.close()
                        session = null
                    }
                    WorkerAction.Stop -> return
                    is WorkerAction.Run -> {
                        activeRequest = action.request
                        try {
                            val request = action.request
                            if (!request.isActive) continue
                            if (session == null) {
                                when (val opened = runtimeFactory.open(modelDirectory)) {
                                    is LayaInferenceRuntimeOpen.Opened -> session = opened.session
                                    is LayaInferenceRuntimeOpen.Unavailable -> {
                                        request.finishUnavailable(opened.state)
                                        continue
                                    }
                                }
                            }
                            if (!request.isActive) continue
                            if (isClosed()) {
                                request.finishClosed()
                            } else {
                                request.execute(checkNotNull(session))
                            }
                        } catch (error: Exception) {
                            action.request.fail(error)
                            session?.close()
                            session = null
                        } finally {
                            activeRequest = null
                            if (session != null) {
                                synchronized(monitor) {
                                    idleDeadlineNanos = System.nanoTime() +
                                        TimeUnit.MILLISECONDS.toNanos(idleTimeoutMillis)
                                    monitor.notifyAll()
                                }
                            }
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            activeRequest?.fail(error)
            throw error
        } finally {
            try {
                session?.close()
            } finally {
                synchronized(monitor) {
                    if (worker === Thread.currentThread()) worker = null
                    if (closed) {
                        pending?.finishClosed()
                        pending = null
                    } else if (pending != null && worker == null) {
                        ensureWorkerLocked()
                    }
                    monitor.notifyAll()
                }
            }
        }
    }

    private fun nextAction(hasSession: Boolean): WorkerAction = synchronized(monitor) {
        while (true) {
            if (closed) return@synchronized WorkerAction.Stop
            if (hasSession && releaseRequested) {
                releaseRequested = false
                return@synchronized WorkerAction.Release
            }
            if (!hasSession) releaseRequested = false
            pending?.let { request ->
                pending = null
                return@synchronized WorkerAction.Run(request)
            }
            if (!hasSession) {
                if (worker === Thread.currentThread()) worker = null
                return@synchronized WorkerAction.Exit
            }
            val remainingNanos = idleDeadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) return@synchronized WorkerAction.Release
            try {
                TimeUnit.NANOSECONDS.timedWait(monitor, remainingNanos)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@synchronized WorkerAction.Stop
            }
        }
        error("unreachable")
    }

    private fun isClosed(): Boolean = synchronized(monitor) { closed }

    private inner class Request<T>(
        private val result: CompletableDeferred<LayaInferenceOutcome<T>>,
        private val operation: (LayaInferenceSession) -> T,
    ) {
        val isActive: Boolean
            get() = result.isActive

        fun execute(session: LayaInferenceSession) {
            if (!result.isActive) return
            val value = operation(session)
            synchronized(monitor) {
                if (closed) {
                    result.complete(LayaInferenceOutcome.Closed)
                } else {
                    result.complete(LayaInferenceOutcome.Completed(value))
                }
            }
        }

        fun finishUnavailable(state: LayaRuntimeContract.State) {
            synchronized(monitor) {
                if (closed) result.complete(LayaInferenceOutcome.Closed)
                else result.complete(LayaInferenceOutcome.Unavailable(state))
            }
        }

        fun finishSuperseded() {
            result.complete(LayaInferenceOutcome.Superseded)
        }

        fun finishClosed() {
            result.complete(LayaInferenceOutcome.Closed)
        }

        fun fail(error: Throwable) {
            result.completeExceptionally(error)
        }
    }

    private sealed interface WorkerAction {
        data object Exit : WorkerAction
        data object Release : WorkerAction
        data object Stop : WorkerAction
        data class Run(val request: Request<*>) : WorkerAction
    }

    companion object {
        private const val SESSION_IDLE_TIMEOUT_MILLIS = 30_000L

        private fun openRuntime(directory: File): LayaInferenceRuntimeOpen =
            when (val result = LayaRuntime.open(directory)) {
                is LayaRuntime.Result.Opened ->
                    LayaInferenceRuntimeOpen.Opened(RuntimeSession(result.runtime))
                is LayaRuntime.Result.Unavailable ->
                    LayaInferenceRuntimeOpen.Unavailable(result.state)
            }
    }
}

/** Result of an asynchronous lifecycle request. */
sealed interface LayaInferenceOutcome<out T> {
    data class Completed<T>(val value: T) : LayaInferenceOutcome<T>
    data class Unavailable(val state: LayaRuntimeContract.State) : LayaInferenceOutcome<Nothing>
    data object Superseded : LayaInferenceOutcome<Nothing>
    data object Closed : LayaInferenceOutcome<Nothing>
}

internal fun interface LayaInferenceRuntimeFactory {
    fun open(directory: File): LayaInferenceRuntimeOpen
}

internal sealed interface LayaInferenceRuntimeOpen {
    data class Opened(val session: LayaInferenceSession) : LayaInferenceRuntimeOpen
    data class Unavailable(val state: LayaRuntimeContract.State) : LayaInferenceRuntimeOpen
}

internal interface LayaInferenceSession : Closeable {
    fun suggest(
        description: String,
        categories: List<CategoryEntity>,
        correctionRules: List<LayaCategoryCorrectionRule>,
    ): LayaCandidatePlanner.Decision

    fun suggestOptions(
        description: String,
        categories: List<CategoryOption>,
    ): LayaRuntimeContract.Suggestion
}

private class RuntimeSession(private val runtime: LayaRuntime) : LayaInferenceSession {
    override fun suggest(
        description: String,
        categories: List<CategoryEntity>,
        correctionRules: List<LayaCategoryCorrectionRule>,
    ): LayaCandidatePlanner.Decision = runtime.suggest(description, categories, correctionRules)

    override fun suggestOptions(
        description: String,
        categories: List<CategoryOption>,
    ): LayaRuntimeContract.Suggestion = runtime.suggestOptions(description, categories)
    override fun close() {
        runtime.close()
    }
}
