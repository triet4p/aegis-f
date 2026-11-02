package io.github.triet4p.aegisf.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One ordered event of the local production trace of an agent run.
 *
 * This is the private diagnostic record the owner asked for: it keeps what the application
 * actually sent to and received from the configured provider, what the model asked the tools to
 * do and what they answered, the clarification question and the user's reply, and the run's
 * terminal/cancel evidence. It exists because the run/attempt journal deliberately keeps only
 * aggregates and allowed effect references, so a run could previously never be reconstructed.
 *
 * What it must never contain, by construction of every writer:
 *
 * - authentication headers, API credentials, cookies or any other transport secret; the
 *   transport decorator drops the credential-carrying request headers by name before the
 *   request row exists;
 * - content that was never sent or received. A gap is recorded as [AGENT_TRACE_KIND_EVIDENCE_GAP]
 *   with an explicit reason ([AGENT_TRACE_COMPLETION_UNAVAILABLE]) instead of a fabricated body,
 *   which is how pre-trace runs and interrupted exchanges stay honest.
 *
 * One deliberate scope statement: a provider response the inbound privacy guard refused is still
 * stored here exactly as the provider returned it. The guard governs publication, rehydration and
 * continuation, not local evidence; the refused body is what the owner needs to diagnose the
 * refusal. It is never displayed as an answer, never rehydrated, never continued and never
 * exported.
 *
 * A trace write that cannot reach the database is not reported as a complete capture. The store
 * records an explicit `TRACE_WRITE_FAILED` gap immediately, keeps a per-run pending marker when
 * even that write fails (recorded later as `TRACE_WRITE_FAILED_EARLIER`), and when the database
 * stays unavailable the absence is an unavoidable limit of local storage — never an assertion of
 * completeness. Only the events whose rows exist are evidence.
 *
 * Every payload column is stored exactly as recorded: provider request/response bodies, tool
 * arguments, tool results and clarification text are never shortened, summarized or replaced by
 * their aggregates. [id] is the ordered event identity (SQLite `INTEGER PRIMARY KEY
 * AUTOINCREMENT`), so per-run order is the ascending id order, and [createdAtEpochMillis] is the
 * wall clock the event was appended at.
 *
 * The store is local-only: it is never part of the privacy-safe conversation export, never sent
 * to a provider or host, and never read as authority by any command path. A row describes
 * evidence; it authorizes nothing and duplicates no business effect.
 */
@Entity(
    tableName = "agent_trace_events",
    foreignKeys = [
        ForeignKey(
            entity = AgentRunEntity::class,
            parentColumns = ["runId"],
            childColumns = ["runId"],
            onUpdate = ForeignKey.CASCADE,
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["runId", "attempt"]),
        Index(value = ["createdAtEpochMillis"]),
    ],
)
data class AgentTraceEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** The durable run this event belongs to; a foreign key of the run row. */
    val runId: String,
    /** The caller session that opened the run, repeated so a trace is readable on its own. */
    val scopeId: String,
    /**
     * Which attempt of the run the event belongs to. Run-scope events (admission, cancellation)
     * use [AGENT_TRACE_RUN_SCOPE_ATTEMPT] because they are not owned by a single attempt.
     */
    val attempt: Int,
    /** What this event is; one of the `AGENT_TRACE_KIND_*` values. */
    val kind: String,
    /**
     * Provider exchange identity inside the attempt: 1 for the attempt's first request, then
     * increasing. Null for events that are not one provider exchange (tools, lifecycle, gaps).
     */
    val turn: Int?,
    /** The model's tool-call id, when the event answers or records one specific call. */
    val callId: String?,
    /** Which direction the recorded evidence crossed; one of `AGENT_TRACE_DIRECTION_*`. */
    val direction: String,
    /**
     * How complete the recorded evidence is; one of `AGENT_TRACE_COMPLETION_*`. A streamed or
     * cancelled exchange that never finished is [AGENT_TRACE_COMPLETION_INTERRUPTED], and an
     * exchange with no observable body is [AGENT_TRACE_COMPLETION_UNAVAILABLE].
     */
    val completion: String,
    /**
     * The complete JSON payload of the event. Provider rows carry the exact URL, the
     * credential-free request headers and the full body; tool rows carry the full argument and
     * result JSON; lifecycle rows carry the full outcome detail. Never truncated.
     */
    val payloadJson: String,
    val createdAtEpochMillis: Long,
)

/** A run-scope trace event that belongs to no single attempt. */
internal const val AGENT_TRACE_RUN_SCOPE_ATTEMPT = 0

/** The run was admitted: the trusted request, its intents, limits and masked prompt text. */
internal const val AGENT_TRACE_KIND_RUN_ADMITTED = "RUN_ADMITTED"

/** One attempt began executing; repeated for a bounded retry or a clarification reopen. */
internal const val AGENT_TRACE_KIND_ATTEMPT_STARTED = "ATTEMPT_STARTED"

/** The exact provider request: URL, credential-free headers and the complete body. */
internal const val AGENT_TRACE_KIND_PROVIDER_REQUEST = "PROVIDER_REQUEST"

/** The provider answer: status, safe headers and the complete body, or an explicit gap. */
internal const val AGENT_TRACE_KIND_PROVIDER_RESPONSE = "PROVIDER_RESPONSE"

/**
 * One decoded server-sent event of a streamed provider answer, in wire order with its event
 * name and exact data payload. Chunks are never merged, summarized or truncated silently.
 */
internal const val AGENT_TRACE_KIND_PROVIDER_STREAM_CHUNK = "PROVIDER_STREAM_CHUNK"

/**
 * The terminal state of one streamed exchange: HTTP status, delivered chunk count, and whether
 * the stream completed, was interrupted by cancellation, or ended without a response.
 */
internal const val AGENT_TRACE_KIND_PROVIDER_STREAM_END = "PROVIDER_STREAM_END"

/** The classified provider failure the executor acted on (value-free code and detail). */
internal const val AGENT_TRACE_KIND_PROVIDER_FAILURE = "PROVIDER_FAILURE"

/** One model-authored tool call: identity, name, exact arguments and dispatch disposition. */
internal const val AGENT_TRACE_KIND_TOOL_CALL = "TOOL_CALL"

/** The application's complete answer to one tool call: envelope, effect, verification. */
internal const val AGENT_TRACE_KIND_TOOL_RESULT = "TOOL_RESULT"

/** A tool call that raised while executing, recorded before the failure is reported. */
internal const val AGENT_TRACE_KIND_TOOL_ERROR = "TOOL_ERROR"

/** The clarification the application asked the user, with its subject and field. */
internal const val AGENT_TRACE_KIND_CLARIFICATION_REQUEST = "CLARIFICATION_REQUEST"

/** The user's own answer to a clarification, with the question it answered. */
internal const val AGENT_TRACE_KIND_CLARIFICATION_REPLY = "CLARIFICATION_REPLY"

/** The user's single assisted follow-up message on a finished run. */
internal const val AGENT_TRACE_KIND_FOLLOW_UP_MESSAGE = "FOLLOW_UP_MESSAGE"

/** The settled end of one attempt: state, stop reason and the complete outcome detail. */
internal const val AGENT_TRACE_KIND_ATTEMPT_OUTCOME = "ATTEMPT_OUTCOME"

/** The run was cancelled by the user; recorded effects stay recorded. */
internal const val AGENT_TRACE_KIND_RUN_CANCELLED = "RUN_CANCELLED"

/**
 * Explicitly missing evidence. The reason names why nothing can be recorded (a run that predates
 * trace capture, an exchange that was interrupted before any body arrived, or a failed trace
 * write). Never replaced by invented content.
 */
internal const val AGENT_TRACE_KIND_EVIDENCE_GAP = "EVIDENCE_GAP"

/** Evidence leaving the application for the provider. */
internal const val AGENT_TRACE_DIRECTION_OUTBOUND = "OUTBOUND"

/** Evidence arriving from the provider. */
internal const val AGENT_TRACE_DIRECTION_INBOUND = "INBOUND"

/** Evidence produced locally (lifecycle, tool dispatch, cancellation). */
internal const val AGENT_TRACE_DIRECTION_INTERNAL = "INTERNAL"

/** The recorded evidence is complete as far as the application can observe. */
internal const val AGENT_TRACE_COMPLETION_COMPLETE = "COMPLETE"

/** The exchange or event ended before its evidence could be recorded (cancel, process death). */
internal const val AGENT_TRACE_COMPLETION_INTERRUPTED = "INTERRUPTED"

/** No evidence exists for this event and none is invented. */
internal const val AGENT_TRACE_COMPLETION_UNAVAILABLE = "UNAVAILABLE"

/** The event carries no completeness dimension (lifecycle, tool dispatch). */
internal const val AGENT_TRACE_COMPLETION_NOT_APPLICABLE = "NOT_APPLICABLE"
