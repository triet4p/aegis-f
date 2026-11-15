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
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
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
 * - **No transport secret is ever written.** Credential-bearing headers are omitted, credential-
 *   looking URL values are redacted, and known credential values are scrubbed from every recorded
 *   JSON value. Other body, tool and clarification content is retained complete.
 * - **Nothing is invented.** An exchange that produced no response is recorded as
 *   [AGENT_TRACE_COMPLETION_UNAVAILABLE], one that was cancelled mid-flight as
 *   [AGENT_TRACE_COMPLETION_INTERRUPTED]; neither is filled with a fabricated body.
 *
 * Records are local-only evidence: they never leave the app and are never read as authority.
 */
class AgentTraceRecorder internal constructor(
    private val store: AgentTraceStore?,
    private val credentialRedactor: (String) -> String = { it },
) {

    private data class Binding(val runId: String, val scopeId: String, val attempt: Int)

    @Volatile
    private var binding: Binding? = null

    private val exchange = AtomicInteger(0)

    private val secretLock = Any()
    private val sensitiveValues = LinkedHashSet<String>()

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
     * Records the provider request, with known transport credentials removed, and returns its
     * exchange identity or null when the recorder is unbound/disabled.
     */
    suspend fun providerRequest(request: HostedHttpRequest): Int? {
        val bound = binding ?: return null
        val turn = exchange.incrementAndGet()
        if (store != null) rememberRequestSecrets(request)
        val headers = safeHeaders(request.headers)
        appendTrace(
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
        appendTrace(
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
     * Records one decoded server-sent event of a streamed exchange. Known credential values are
     * redacted; the rest of the event remains complete and in wire order.
     */
    suspend fun providerStreamChunk(turn: Int?, index: Int, event: HostedSseEvent) {
        val bound = binding ?: return
        if (turn == null) return
        appendTrace(
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
        appendTrace(
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
        appendTrace(
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
        appendTrace(
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
        appendTrace(
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
        appendTrace(
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
        appendTrace(
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
        appendTrace(
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
        appendTrace(
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
        appendTrace(
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
        appendTrace(
            runId = bound.runId,
            scopeId = bound.scopeId,
            attempt = bound.attempt,
            kind = AGENT_TRACE_KIND_FOLLOW_UP_MESSAGE,
            direction = AGENT_TRACE_DIRECTION_INTERNAL,
            completion = AGENT_TRACE_COMPLETION_COMPLETE,
            payloadJson = JSONObject().put("message", message).toString(),
        )
    }

    private suspend fun appendTrace(
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
        store?.append(
            runId = runId,
            scopeId = scopeId,
            attempt = attempt,
            kind = kind,
            payloadJson = safePayloadJson(payloadJson),
            direction = direction,
            completion = completion,
            turn = turn,
            callId = callId?.let(::redactSecrets),
        )
    }

    private fun rememberRequestSecrets(request: HostedHttpRequest) {
        request.headers.forEach { (name, value) ->
            if (name.lowercase() in SECRET_HEADERS) {
                rememberSecret(value)
                if (name.equals("authorization", ignoreCase = true)) {
                    rememberSecret(value.trim().substringAfter(' ', "").trim())
                } else if (
                    name.equals("cookie", ignoreCase = true) ||
                    name.equals("set-cookie", ignoreCase = true)
                ) {
                    value.split(';').forEach { cookie ->
                        val equals = cookie.indexOf('=')
                        if (equals >= 0) rememberSecret(cookie.substring(equals + 1).trim())
                    }
                }
            }
        }
        val queryStart = request.url.indexOf('?')
        if (queryStart >= 0) {
            request.url.substring(queryStart + 1).substringBefore('#')
                .split('&')
                .forEach { part ->
                    val equals = part.indexOf('=')
                    if (equals >= 0 && isSensitiveQueryName(part.substring(0, equals))) {
                        val value = part.substring(equals + 1)
                        rememberSecret(value)
                        rememberSecret(decodeQueryComponent(value))
                    }
                }
        }
    }

    private fun rememberSecret(value: String) {
        if (value.isNotEmpty()) synchronized(secretLock) { sensitiveValues.add(value) }
    }

    private fun safePayloadJson(payloadJson: String): String =
        scrubJsonValue(JSONObject(payloadJson)).toString()

    private fun scrubJsonValue(value: Any): Any = when (value) {
        is JSONObject -> JSONObject().apply {
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, scrubJsonValue(value.get(key)))
            }
        }
        is JSONArray -> JSONArray().apply {
            for (index in 0 until value.length()) put(scrubJsonValue(value.get(index)))
        }
        is String -> redactSecrets(value)
        else -> value
    }

    private fun redactSecrets(text: String): String = synchronized(secretLock) {
        sensitiveValues.fold(credentialRedactor(text)) { safe, secret -> safe.replace(secret, REDACTED_CREDENTIAL) }
    }

    companion object {
        private const val REDACTED_CREDENTIAL = "[redacted-credential]"
        /** A recorder for a host that is deliberately constructed without durable tracing. */
        fun disabled(): AgentTraceRecorder = AgentTraceRecorder(null)

        /** The production recorder over the app's durable trace store. */
        fun enabled(
            store: AgentTraceStore,
            credentialRedactor: (String) -> String = { it },
        ): AgentTraceRecorder = AgentTraceRecorder(store, credentialRedactor)

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
            "api-key",
            "x-api-key",
            "x-goog-api-key",
            "apikey",
            "token",
            "access_token",
            "access-token",
            "id_token",
            "auth",
            "authorization",
            "auth_token",
            "auth-token",
            "client_secret",
            "client-secret",
            "secret",
            "secret_key",
            "secret-key",
            "password",
            "credential",
            "credentials",
            "jwt",
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

        /** The request URL without user info or credential-looking query values. */
        fun safeUrl(url: String): String {
            var safeUrl = url
            val schemeEnd = safeUrl.indexOf("://")
            val authorityStart = if (schemeEnd >= 0) schemeEnd + 3 else -1
            if (authorityStart >= 0) {
                val authorityEnd = listOf(
                    safeUrl.indexOf('/', authorityStart),
                    safeUrl.indexOf('?', authorityStart),
                    safeUrl.indexOf('#', authorityStart),
                ).filter { it >= 0 }.minOrNull() ?: safeUrl.length
                val userInfo = safeUrl.lastIndexOf('@', authorityEnd - 1)
                if (userInfo >= authorityStart) {
                    safeUrl = safeUrl.replaceRange(authorityStart, userInfo, "[redacted]")
                }
            }
            val queryStart = safeUrl.indexOf('?')
            if (queryStart < 0) return safeUrl
            val fragmentStart = safeUrl.indexOf('#', queryStart).takeIf { it >= 0 } ?: safeUrl.length
            val query = safeUrl.substring(queryStart + 1, fragmentStart)
            val rebuilt = query.split('&').joinToString("&") { part ->
                val equals = part.indexOf('=')
                if (equals < 0) {
                    part
                } else {
                    val name = part.substring(0, equals)
                    if (isSensitiveQueryName(name)) "$name=[redacted]" else part
                }
            }
            return safeUrl.substring(0, queryStart + 1) + rebuilt + safeUrl.substring(fragmentStart)
        }

        private fun isSensitiveQueryName(name: String): Boolean =
            decodeQueryComponent(name).lowercase() in SECRET_QUERY_NAMES

        private fun decodeQueryComponent(value: String): String = try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            value
        }
    }
}
