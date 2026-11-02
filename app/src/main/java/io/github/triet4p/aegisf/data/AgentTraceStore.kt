package io.github.triet4p.aegisf.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The append-only local production trace of agent runs.
 *
 * Every writer passes the exact evidence it observed and nothing else. The store adds no
 * interpretation, performs no truncation, and never rewrites an existing row, so a reader can
 * reconstruct a run's ordered provider exchanges, tool calls, clarification and outcomes from
 * the rows alone.
 *
 * Two properties matter for correctness:
 *
 * - **Tracing never changes the business outcome.** An append failure never fails a run, retries
 *   a request, duplicates a tool call or authorizes an effect. It is also never presented as a
 *   complete capture: the store records an explicit `TRACE_WRITE_FAILED` (or
 *   `TRACE_WRITE_FAILED_EARLIER`) [AGENT_TRACE_KIND_EVIDENCE_GAP] row, and keeps a per-run marker
 *   pending when even that write fails. If the database itself is unavailable, no marker can be
 *   written at all — an unavoidable limit of local storage that must never be read as "the trace
 *   is complete". The trace is complete only for the events whose rows exist.
 * - **Evidence survives cancellation.** Each append runs in a non-cancellable context, so the
 *   response that arrived, the cancellation event, or the failed exchange is still recorded after
 *   the caller's coroutine was cancelled.
 */
class AgentTraceStore(
    private val traces: AgentTraceDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * One or more events of one run/attempt that could not be written yet: how many, which kinds,
     * the first event's identifiers (so the marker lands on the right run), and the failure kind.
     */
    private data class PendingLoss(
        val events: Int,
        val kinds: Set<String>,
        val runId: String,
        val scopeId: String,
        val attempt: Int,
        val turn: Int?,
        val callId: String?,
        val detail: String,
    )

    private val lossLock = Any()
    private val pendingLosses = LinkedHashMap<String, PendingLoss>()

    /**
     * Appends one complete event. [payloadJson] is the caller-built JSON object of the event and
     * is stored verbatim.
     */
    suspend fun append(
        runId: String,
        scopeId: String,
        attempt: Int,
        kind: String,
        payloadJson: String,
        direction: String = AGENT_TRACE_DIRECTION_INTERNAL,
        completion: String = AGENT_TRACE_COMPLETION_NOT_APPLICABLE,
        turn: Int? = null,
        callId: String? = null,
    ) {
        write(
            AgentTraceEventEntity(
                runId = runId,
                scopeId = scopeId,
                attempt = attempt,
                kind = kind,
                turn = turn,
                callId = callId,
                direction = direction,
                completion = completion,
                payloadJson = payloadJson,
                createdAtEpochMillis = clock(),
            ),
        )
    }

    /**
     * Records that a piece of evidence is missing and why, instead of inventing it.
     *
     * Used for runs that predate trace capture, exchanges the process lost, and trace writes the
     * store could not persist. The reason and detail are the only content.
     */
    suspend fun markGap(
        runId: String,
        scopeId: String,
        attempt: Int,
        reason: String,
        detail: String,
    ) {
        append(
            runId = runId,
            scopeId = scopeId,
            attempt = attempt,
            kind = AGENT_TRACE_KIND_EVIDENCE_GAP,
            payloadJson = JSONObject()
                .put("reason", reason)
                .put("detail", detail)
                .toString(),
            direction = AGENT_TRACE_DIRECTION_INTERNAL,
            completion = AGENT_TRACE_COMPLETION_UNAVAILABLE,
        )
    }

    /** Every recorded event of one run, in append order. */
    suspend fun findForRun(runId: String): List<AgentTraceEventEntity> = traces.findForRun(runId)

    /** Every recorded event of one attempt, in append order. */
    suspend fun findForAttempt(runId: String, attempt: Int): List<AgentTraceEventEntity> =
        traces.findForAttempt(runId, attempt)

    /** How many events one run carries. */
    suspend fun countForRun(runId: String): Long = traces.countForRun(runId)

    private suspend fun write(event: AgentTraceEventEntity) {
        try {
            withContext(NonCancellable) {
                flushPendingLosses()
                traces.insert(event)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            recordLoss(event, failure)
        }
    }

    /**
     * Writes the markers of earlier losses now that the database answers again. A marker keeps its
     * original run/attempt identities, so a loss is never attributed to whichever run happens to
     * write next.
     */
    private suspend fun flushPendingLosses() {
        val pending = synchronized(lossLock) { pendingLosses.entries.map { it.key to it.value } }
        for ((key, loss) in pending) {
            try {
                withContext(NonCancellable) {
                    traces.insert(gapRow(loss, "TRACE_WRITE_FAILED_EARLIER"))
                }
                synchronized(lossLock) {
                    if (pendingLosses[key] === loss) pendingLosses.remove(key)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Storage is still unavailable: keep every marker pending and let this append go
                // on to its own attempt (which records another loss if it fails too).
                return
            }
        }
    }

    private suspend fun recordLoss(event: AgentTraceEventEntity, failure: Exception) {
        val key = "${event.runId}\u0000${event.attempt}"
        val accumulated = synchronized(lossLock) {
            val previous = pendingLosses[key]
            PendingLoss(
                events = (previous?.events ?: 0) + 1,
                kinds = (previous?.kinds ?: emptySet()) + event.kind,
                runId = previous?.runId ?: event.runId,
                scopeId = previous?.scopeId ?: event.scopeId,
                attempt = previous?.attempt ?: event.attempt,
                turn = previous?.turn ?: event.turn,
                callId = previous?.callId ?: event.callId,
                detail = failure::class.simpleName ?: "error",
            ).also { pendingLosses[key] = it }
        }
        try {
            withContext(NonCancellable) { traces.insert(gapRow(accumulated, "TRACE_WRITE_FAILED")) }
            synchronized(lossLock) {
                if (pendingLosses[key] === accumulated) pendingLosses.remove(key)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The database itself is unavailable. Nothing can be written now; the marker stays
            // pending for the first later successful write. Where even that never happens, the
            // absence is an unavoidable local-storage limit, never a claim of complete capture.
        }
    }

    private fun gapRow(loss: PendingLoss, reason: String): AgentTraceEventEntity = AgentTraceEventEntity(
        runId = loss.runId,
        scopeId = loss.scopeId,
        attempt = loss.attempt,
        kind = AGENT_TRACE_KIND_EVIDENCE_GAP,
        turn = loss.turn,
        callId = loss.callId,
        direction = AGENT_TRACE_DIRECTION_INTERNAL,
        completion = AGENT_TRACE_COMPLETION_UNAVAILABLE,
        payloadJson = JSONObject()
            .put("reason", reason)
            .put("failedEvents", loss.events)
            .put(
                "failedKinds",
                org.json.JSONArray().also { kinds -> loss.kinds.forEach { kinds.put(it) } },
            )
            .put("detail", loss.detail)
            .toString(),
        createdAtEpochMillis = clock(),
    )
}
