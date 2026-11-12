package io.github.triet4p.aegisf.provider

import kotlinx.coroutines.flow.Flow

/**
 * One decoded server-sent event of a streamed provider exchange.
 *
 * [event] is the SSE `event:` name when the endpoint sends one (OpenAI Responses does; Chat
 * Completions and Gemini SSE usually do not). [data] is the exact payload after the `data:`
 * prefix with multi-line payloads joined by `\n`; it is never parsed here.
 */
data class HostedSseEvent(val event: String?, val data: String)

/**
 * Incremental Server-Sent Events decoder.
 *
 * The decoder is fed whatever slice of the response body the transport read, including slices
 * that end in the middle of a line, of the two-character line terminator, or of a JSON payload.
 * It emits one [HostedSseEvent] per complete event (a blank line terminates an event) and
 * keeps a partial event buffered. `:` comments and unknown fields are ignored per the SSE
 * grammar; a lone final event without a trailing blank line is emitted by [finish].
 *
 * Bounded: the retained partial line and one event's data are capped, and a larger event fails
 * closed with [HostedResponseTooLargeException] instead of growing without limit. Nothing here
 * validates protocol JSON; adapters own that.
 */
class HostedSseDecoder(
    private val maxEventBytes: Int = DEFAULT_MAX_EVENT_BYTES,
) {
    private val line = StringBuilder()
    private val data = StringBuilder()
    private var eventName: String? = null
    private var sawAnyField = false

    init {
        require(maxEventBytes >= 1_024) { "the SSE event bound is not usable" }
    }

    /** Consumes one arbitrary slice of the response body and returns every event it completed. */
    fun feed(chunk: String): List<HostedSseEvent> {
        if (chunk.isEmpty()) return emptyList()
        val events = mutableListOf<HostedSseEvent>()
        var index = 0
        while (index < chunk.length) {
            val character = chunk[index]
            index += 1
            when (character) {
                '\n' -> consumeLine(events)
                '\r' -> {
                    // A CRLF pair is one terminator: skip the LF that follows the CR.
                    if (index < chunk.length && chunk[index] == '\n') index += 1
                    consumeLine(events)
                }

                else -> {
                    line.append(character)
                    if (line.length > maxEventBytes) {
                        throw HostedResponseTooLargeException("an SSE line exceeded $maxEventBytes characters")
                    }
                }
            }
        }
        return events
    }

    /** Emits a final event whose terminating blank line never arrived; never invents data. */
    fun finish(): List<HostedSseEvent> {
        val events = mutableListOf<HostedSseEvent>()
        if (line.isNotEmpty()) consumeLine(events)
        dispatch(events)
        return events
    }

    private fun consumeLine(events: MutableList<HostedSseEvent>) {
        val text = line.toString()
        line.setLength(0)
        if (text.isEmpty()) {
            dispatch(events)
            return
        }
        if (text.startsWith(':')) return
        val separator = text.indexOf(':')
        val field = if (separator < 0) text else text.substring(0, separator)
        var value = if (separator < 0) "" else text.substring(separator + 1)
        if (value.startsWith(' ')) value = value.substring(1)
        when (field) {
            "data" -> {
                sawAnyField = true
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
                if (data.length > maxEventBytes) {
                    throw HostedResponseTooLargeException("an SSE event exceeded $maxEventBytes characters")
                }
            }

            "event" -> {
                sawAnyField = true
                eventName = value.ifBlank { null }
            }

            else -> Unit
        }
    }

    private fun dispatch(events: MutableList<HostedSseEvent>) {
        if (data.isEmpty() && !sawAnyField) return
        events += HostedSseEvent(event = eventName, data = data.toString())
        data.setLength(0)
        eventName = null
        sawAnyField = false
    }

    companion object {
        /** One event payload larger than this is refused rather than buffered without bound. */
        const val DEFAULT_MAX_EVENT_BYTES: Int = 1 shl 20
    }
}

/** One frame of a streamed provider exchange. */
sealed interface HostedStreamFrame {
    /**
     * The response head arrived. [status] is the HTTP status; [errorBody] carries the bounded
     * body of a non-2xx answer (which is not an event stream) and is null for a 2xx stream.
     */
    data class Start(
        val status: Int,
        val headers: Map<String, String>,
        val errorBody: String?,
    ) : HostedStreamFrame

    /** One complete server-sent event of a 2xx stream. */
    data class Event(val event: HostedSseEvent) : HostedStreamFrame
}

/**
 * The optional streaming capability of a transport beside [HttpTransport].
 *
 * [postStream] is a cold flow: the endpoint is contacted when collection starts, the raw
 * response is read incrementally on a background dispatcher, and each decoded event is
 * delivered in wire order. Transport-level failures are thrown as `IOException` (or
 * [HostedResponseTooLargeException]); a non-2xx answer is delivered as a single
 * [HostedStreamFrame.Start] with no events so the adapter can classify it exactly like a
 * complete-body answer.
 */
interface HttpStreamTransport {
    fun postStream(request: HostedHttpRequest): Flow<HostedStreamFrame>
}

/**
 * Public, already-guarded progress of one model turn.
 *
 * A [PublicText] value is the model's user-visible answer text after the incremental local
 * public guard accepted it: it never carries a private reasoning fragment, a value the session
 * masked, a foreign token, or an unvalidated tool argument. Tool events name a declared tool
 * only after the provider named it; they never carry partial arguments.
 */
sealed interface HostedTurnProgress {
    /** One accepted incremental piece of the model's user-visible answer. */
    data class PublicText(val text: String) : HostedTurnProgress

    /**
     * The provider is producing private reasoning for this turn.
     *
     * It is real activity the surface may report, but it deliberately carries no text: the
     * reasoning itself is never displayed, stored or guarded as answer text.
     */
    data object Reasoning : HostedTurnProgress

    /** The provider began a tool call for a declared tool. */
    data class ToolCallStarted(val name: String) : HostedTurnProgress

    /** The provider finished the tool call's argument text; dispatch still requires validation. */
    data class ToolCallCompleted(val name: String) : HostedTurnProgress

    /**
     * This validated route has no honest incremental stream (no stream transport, or a
     * protocol route that cannot be derived). The caller must show run progress only.
     */
    data class StreamingUnavailable(val reason: String) : HostedTurnProgress
}
