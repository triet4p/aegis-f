package io.github.triet4p.aegisf.agent

import io.github.triet4p.aegisf.data.AGENT_TRACE_COMPLETION_COMPLETE
import io.github.triet4p.aegisf.data.AGENT_TRACE_COMPLETION_INTERRUPTED
import io.github.triet4p.aegisf.data.AGENT_TRACE_COMPLETION_NOT_APPLICABLE
import io.github.triet4p.aegisf.data.AGENT_TRACE_COMPLETION_UNAVAILABLE
import io.github.triet4p.aegisf.data.AGENT_TRACE_DIRECTION_INBOUND
import io.github.triet4p.aegisf.data.AGENT_TRACE_DIRECTION_INTERNAL
import io.github.triet4p.aegisf.data.AGENT_TRACE_DIRECTION_OUTBOUND
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_CLARIFICATION_REPLY
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_CLARIFICATION_REQUEST
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_FOLLOW_UP_MESSAGE
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_PROVIDER_FAILURE
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_PROVIDER_REQUEST
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_PROVIDER_RESPONSE
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_PROVIDER_STREAM_CHUNK
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_PROVIDER_STREAM_END
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_TOOL_CALL
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_TOOL_ERROR
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_TOOL_RESULT
import io.github.triet4p.aegisf.data.AgentTraceStore
import io.github.triet4p.aegisf.provider.HostedHttpRequest
import io.github.triet4p.aegisf.provider.HostedHttpResponse
import io.github.triet4p.aegisf.provider.HostedSseEvent
import io.github.triet4p.aegisf.provider.HostedToolCall
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Binds one executing attempt to the local production trace and records what it observed.
 *
 * The recorder is created with the host, so the adapter's transport decorator and the executor
 * write to the same run/attempt binding. [bind] is called by the tracked executor entry points
 * after the durable run/attempt exists; until then (and for a host constructed without a store)
 * every method is a no-op instead of guessing a run identity.
 *
 * Two rules are enforced here rather than trusted from call sites:
 *
 * - **No transport secret is ever written.** Request headers are filtered by the documented
 *   credential header names, and credential-looking URL query parameters are replaced before the
 *   request row is built. Bodies, tool arguments/results and clarification text are stored
 *   complete.
 * - **Nothing is invented.** An exchange that produced no response is recorded as
 *   [AGENT_TRACE_COMPLETION_UNAVAILABLE], one that was cancelled mid-flight as
 *   [AGENT_TRACE_COMPLETION_INTERRUPTED]; neither is filled with a fabricated body.
 *
 * Records are local-only evidence: they never leave the app and are never read as authority.
 */
class AgentTraceRecorder internal constructor(private val store: AgentTraceStore?) {

    private data class Binding(val runId: String, val scopeId: String, val attempt: Int)

    @Volatile
    private var binding: Binding? = null

    private val exchange = AtomicInteger(0)

    /** True when this recorder writes to a durable store; false for a disabled host. */
    val enabled: Boolean get() = store != null

    /** Binds the recorder to the run/attempt the executor is about to execute. */
    fun bind(runId: String, scopeId: String, attempt: Int) {
        binding = Binding(runId, scopeId, attempt)
        exchange.set(0)
    }

    /** Detaches the binding; late writes are dropped instead of attributed to another run. */
    fun clear() {
        binding = null
    }

    /**
     * Records the exact provider request and returns its exchange identity, or null when the
     * recorder is unbound/disabled. The exchange identity correlates the request with its
     * response inside the same attempt.
     */
    suspend fun providerRequest(request: HostedHttpRequest): Int? {
        val bound = binding ?: return null
        val turn = exchange.incrementAndGet()
        val headers = safeHeaders(request.headers)
        store?.append(
            runId = bound.runId,
            scopeId = bound.scopeId,
            attempt = bound.attempt,
            kind = AGENT_TRACE_KIND_PROVIDER_REQUEST,
            turn = turn,
            direction = AGENT_TRACE_DIRECTION_OUTBOUND,
            completion = AGENT_TRACE_COMPLETION_COMPLETE,
            payloadJson = JSONObject()
                .put("url", safeUrl(request.url))
                .put("headers", headers.first)
                .put("redactedHeaders", headers.second)
                .put("body", request.body)
                .toString(),
        )
        return turn
    }

    /** Records the provider's complete answer for one exchange. */
    suspend fun providerResponse(turn: Int?, response: HostedHttpResponse) {
        val bound = binding ?: return
        if (turn == null) return
        val headers = safeHeaders(response.headers)
        store?.append(
            runId = bound.runId,
            scopeId = bound.scopeId,
            attempt = bound.attempt,
            kind = AGENT_TRACE_KIND_PROVIDER_RESPONSE,
            turn = turn,
            direction = AGENT_TRACE_DIRECTION_INBOUND,
            completion = AGENT_TRACE_COMPLETION_COMPLETE,
            payloadJson = JSONObject()
                .put("status", response.status)
                .put("headers", headers.first)
                .put("redactedHeaders", headers.second)
                .put("body", response.body)
                .toString(),
        )
    }

    /**
     * Records one decoded server-sent event of a streamed exchange.
     *
     * The payload is exactly what the endpoint delivered for that event (field name and data
     * text), in wire order. Chunks are never merged, summarized or truncated; an over-large
     * stream fails the exchange instead of dropping evidence.
     */
    suspend fun providerStreamChunk(turn: Int?, index: Int, event: HostedSseEvent) {
        val bound = binding ?: return
        if (turn == null) return
        store?.append(
            runId = bound.runId,
            scopeId = bound.scopeId,
            attempt = bound.attempt,
            kind = AGENT_TRACE_KIND_PROVIDER_STREAM_CHUNK,
            turn = turn,
            direction = AGENT_TRACE_DIRECTION_INBOUND,
            completion = AGENT_TRACE_COMPLETION_COMPLETE,
            payloadJson = JSONObject()
                .put("index", index)
                .put("event", event.event ?: JSONObject.NULL)
                .put("data", event.data)
                .toString(),
        )
    }

    /**
     * Records the terminal state of one streamed exchange.
     *
     * [status] is null when no response head arrived. A cancelled stream is `INTERRUPTED`, a
     * failed one is `UNAVAILABLE`, and [chunks] states exactly how many events were delivered;
     * no assembled body is invented for a stream that did not produce a turn.
     */
    suspend fun providerStreamEnd(
        turn: Int?,
        status: Int?,
        chunks: Int,
        interrupted: Boolean,
        failure: Throwable? = null,
    ) {
        val bound = binding ?: return
        if (turn == null) return
        store?.append(
            runId = bound.runId,
            scopeId = bound.scopeId,
            attempt = bound.attempt,
            kind = AGENT_TRACE_KIND_PROVIDER_STREAM_END,
            turn = turn,
            direction = AGENT_TRACE_DIRECTION_INBOUND,
            completion = when {
                interrupted -> AGENT_TRACE_COMPLETION_INTERRUPTED
                failure != null -> AGENT_TRACE_COMPLETION_UNAVAILABLE
                else -> AGENT_TRACE_COMPLETION_COMPLETE
            },
            payloadJson = JSONObject()
                .put("status", status ?: JSONObject.NULL)
                .put("chunks", chunks)
                .put("error", failure?.let { it::class.simpleName } ?: JSONObject.NULL)
                .put("detail", failure?.message ?: JSONObject.NULL)
                .toString(),
        )
    }

    /**
     * Records the canonical response body a streamed turn was assembled into, for the exchange
     * that just finished. It is local evidence only: the provider's own delta content in the
     * protocol's ordinary response shape, never displayed, rehydrated or read as authority.
     */
    suspend fun providerAssembledResponse(body: String?) {
        val bound = binding ?: return
        if (body.isNullOrEmpty()) return
        val turn = exchange.get().takeIf { it > 0 } ?: return
        store?.append(
            runId = bound.runId,
            scopeId = bound.scopeId,
            attempt = bound.attempt,
            kind = AGENT_TRACE_KIND_PROVIDER_RESPONSE,
            turn = turn,
            direction = AGENT_TRACE_DIRECTION_INBOUND,
            completion = AGENT_TRACE_COMPLETION_COMPLETE,
            payloadJson = JSONObject()
                .put("streamed", true)
                .put("assembled", true)
                .put("body", body)
                .toString(),
        )
    }

    /**
     * Records that the request produced no observable response: a transport failure, a refused
     * over-large body, or a cancellation before the answer arrived. [interrupted] distinguishes a
     * cancelled in-flight call from a failed one; neither contributes a body.
     */
    suspend fun providerUnavailable(
        turn: Int?,
        failure: Throwable,
        interrupted: Boolean = false,
    ) {
        val bound = binding ?: return
        if (turn == null) return
        store?.append(
            runId = bound.runId,
            scopeId = bound.scopeId,
            attempt = bound.attempt,
            kind = AGENT_TRACE_KIND_PROVIDER_RESPONSE,
            turn = turn,
            direction = AGENT_TRACE_DIRECTION_INBOUND,
            completion = if (interrupted) {
                AGENT_TRACE_COMPLETION_INTERRUPTED
            } else {
                AGENT_TRACE_COMPLETION_UNAVAILABLE
            },
            payloadJson = JSONObject()
                .put("reason", if (interrupted) "INTERRUPTED" else "NO_RESPONSE")
                .put("error", failure::class.simpleName ?: "error")
                .put("detail", failure.message ?: JSONObject.NULL)
                .toString(),
        )
    }

    /** Records the classified provider failure the executor acted on. */
    suspend fun providerFailure(code: String, detail: String?) {
        val bound = binding ?: return
        store?.append(
            runId = bound.runId,
            scopeId = bound.scopeId,
            attempt = bound.attempt,
            kind = AGENT_TRACE_KIND_PROVIDER_FAILURE,
            turn = exchange.get().takeIf { it > 0 },
            direction = AGENT_TRACE_DIRECTION_INBOUND,
            completion = AGENT_TRACE_COMPLETION_NOT_APPLICABLE,
            payloadJson = JSONObject()
                .put("code", code)
                .put("detail", detail ?: JSONObject.NULL)
                .toString(),
        )
    }

    /** Records one model-authored call with its exact arguments before anything executes. */
    suspend fun toolCall(turn: Int?, call: HostedToolCall) {
        val bound = binding ?: return
        store?.append(
            runId = bound.runId,
            scopeId = bound.scopeId,
            attempt = bound.attempt,
            kind = AGENT_TRACE_KIND_TOOL_CALL,
            turn = turn,
            callId = call.callId,
            direction = AGENT_TRACE_DIRECTION_INTERNAL,
            completion = AGENT_TRACE_COMPLETION_COMPLETE,
            payloadJson = JSONObject()
                .put("name", call.name)
                .put("argumentsJson", call.argumentsJson ?: JSONObject.NULL)
                .put("disposition", call.disposition.name)
                .put("detail", call.detail ?: JSONObject.NULL)
                .toString(),
        )
    }

    /** Records the application's complete answer to one call. */
    internal suspend fun toolResult(
        turn: Int?,
        callId: String,
        name: String,
        resultJson: String,
        record: DispatchRecord?,
    ) {
        val bound = binding ?: return
        val payload = JSONObject()
            .put("name", name)
            .put("resultJson", resultJson)
        if (record != null) {
            payload
                .put("outcome", record.outcome)
                .put("effect", record.effect)
                .put("verified", record.verified)
                .put("code", record.code ?: JSONObject.NULL)
                .put("dispatch", if (record.dispatched) "dispatched" else "not_dispatched")
                .put("operationKey", record.operationKey ?: JSONObject.NULL)
                .put("targetKey", record.targetKey ?: JSONObject.NULL)
        }
        store?.append(
            runId = bound.runId,
            scopeId = bound.scopeId,
            attempt = bound.attempt,
            kind = AGENT_TRACE_KIND_TOOL_RESULT,
            turn = turn,
            callId = callId,
            direction = AGENT_TRACE_DIRECTION_INTERNAL,
            completion = AGENT_TRACE_COMPLETION_COMPLETE,
            payloadJson = payload.toString(),
        )
    }

    /** Records a tool call that raised before it could produce an envelope. */
    suspend fun toolError(turn: Int?, call: HostedToolCall, failure: Throwable) {
        val bound = binding ?: return
        store?.append(
            runId = bound.runId,
            scopeId = bound.scopeId,
            attempt = bound.attempt,
            kind = AGENT_TRACE_KIND_TOOL_ERROR,
            turn = turn,
            callId = call.callId,
            direction = AGENT_TRACE_DIRECTION_INTERNAL,
            completion = AGENT_TRACE_COMPLETION_INTERRUPTED,
            payloadJson = JSONObject()
                .put("name", call.name)
                .put("argumentsJson", call.argumentsJson ?: JSONObject.NULL)
                .put("error", failure::class.simpleName ?: "error")
                .put("detail", failure.message ?: JSONObject.NULL)
                .toString(),
        )
    }

    /** Records the clarification the application asked, including the row it refers to. */
    suspend fun clarificationRequested(
        turn: Int?,
        callId: String,
        field: String?,
        question: String,
        subjectRef: String?,
    ) {
        val bound = binding ?: return
        store?.append(
            runId = bound.runId,
            scopeId = bound.scopeId,
            attempt = bound.attempt,
            kind = AGENT_TRACE_KIND_CLARIFICATION_REQUEST,
            turn = turn,
            callId = callId,
            direction = AGENT_TRACE_DIRECTION_INTERNAL,
            completion = AGENT_TRACE_COMPLETION_COMPLETE,
            payloadJson = JSONObject()
                .put("field", field ?: JSONObject.NULL)
                .put("question", question)
                .put("subjectRef", subjectRef ?: JSONObject.NULL)
                .toString(),
        )
    }

    /** Records the user's own answer to a clarification, with the question it answered. */
    suspend fun clarificationReply(field: String?, question: String, userAnswer: String) {
        val bound = binding ?: return
        store?.append(
            runId = bound.runId,
            scopeId = bound.scopeId,
            attempt = bound.attempt,
            kind = AGENT_TRACE_KIND_CLARIFICATION_REPLY,
            direction = AGENT_TRACE_DIRECTION_INTERNAL,
            completion = AGENT_TRACE_COMPLETION_COMPLETE,
            payloadJson = JSONObject()
                .put("field", field ?: JSONObject.NULL)
                .put("question", question)
                .put("userAnswer", userAnswer)
                .toString(),
        )
    }

    /** Records the one assisted follow-up message a finished run accepted. */
    suspend fun followUpMessage(message: String) {
        val bound = binding ?: return
        store?.append(
            runId = bound.runId,
            scopeId = bound.scopeId,
            attempt = bound.attempt,
            kind = AGENT_TRACE_KIND_FOLLOW_UP_MESSAGE,
            direction = AGENT_TRACE_DIRECTION_INTERNAL,
            completion = AGENT_TRACE_COMPLETION_COMPLETE,
            payloadJson = JSONObject().put("message", message).toString(),
        )
    }

    companion object {
        /** A recorder for a host that is deliberately constructed without durable tracing. */
        fun disabled(): AgentTraceRecorder = AgentTraceRecorder(null)

        /** The production recorder over the app's durable trace store. */
        fun enabled(store: AgentTraceStore): AgentTraceRecorder = AgentTraceRecorder(store)

        /**
         * Header names that carry a credential, a session or another transport secret. They are
         * dropped from the recorded request and named (name only) in `redactedHeaders`.
         */
        val SECRET_HEADERS = setOf(
            "authorization",
            "proxy-authorization",
            "x-goog-api-key",
            "x-api-key",
            "api-key",
            "apikey",
            "cookie",
            "set-cookie",
        )

        /** Query parameters that look like credentials are replaced, not recorded. */
        val SECRET_QUERY_NAMES = setOf(
            "key",
            "api_key",
            "apikey",
            "token",
            "access_token",
            "id_token",
            "auth",
            "authorization",
            "secret",
            "password",
            "sig",
            "signature",
        )

        fun safeHeaders(headers: Map<String, String>): Pair<JSONObject, JSONArray> {
            val safe = JSONObject()
            val redacted = JSONArray()
            headers.forEach { (name, value) ->
                if (name.lowercase() in SECRET_HEADERS) {
                    redacted.put(name)
                } else {
                    safe.put(name, value)
                }
            }
            return safe to redacted
        }

        /** The request URL with credential-looking query values replaced. */
        fun safeUrl(url: String): String {
            val separator = url.indexOf('?')
            if (separator < 0) return url
            val base = url.substring(0, separator)
            val query = url.substring(separator + 1)
            val rebuilt = query.split('&').joinToString("&") { part ->
                val equals = part.indexOf('=')
                if (equals < 0) {
                    part
                } else {
                    val name = part.substring(0, equals)
                    if (name.lowercase() in SECRET_QUERY_NAMES) "$name=[redacted]" else part
                }
            }
            return "$base?$rebuilt"
        }
    }
}
