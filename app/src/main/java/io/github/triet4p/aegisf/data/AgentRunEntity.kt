package io.github.triet4p.aegisf.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One persisted agent run: the durable memory of a user-visible conversation turn that
 * asked the hosted workflow agent to do something.
 *
 * The row owns the run's identity and lifecycle: the caller session that started it,
 * the trusted intents it must satisfy, the retry attempt it is on, and the terminal
 * state it reached. A retried request reuses its run row instead of adding a second
 * one, which is what makes a repeated user send, a reopen after a process death, and a
 * restart after a timeout one run instead of a second workflow.
 *
 * The row carries no credentials, no vault mappings, and no protocol continuation
 * items: the request fingerprint is a one-way digest of the masked prompt (so an
 * identical retry is recognized without storing the request twice), and the answer
 * digest names the masked text the user actually saw. Credentials, mappings, and
 * transient reasoning are never written here, never exported from it, and never
 * reconstructed from it.
 */
@Entity(
    tableName = "agent_runs",
    indices = [
        Index(value = ["runId"], unique = true),
        Index(value = ["requestFingerprint", "scopeId"], unique = true),
        Index(value = ["scopeId", "createdAtEpochMillis"]),
        Index(value = ["state"]),
    ],
)
data class AgentRunEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** The run's own stable identity across retries and restarts; minted by the host. */
    val runId: String,
    /** The caller session that started the run; scope for every effect it may claim. */
    val scopeId: String,
    /** The trusted workflow intents the run must satisfy, comma-separated names. */
    val intentNames: String,
    /** The one-way digest of the masked request (intent set, text, limits, vault scope). */
    val requestFingerprint: String,
    /**
     * Which attempt of the request this row describes: 1 for the first try, higher for
     * a bounded retry. The row is the newest attempt; older attempts are recorded only
     * as the attempt events they left behind.
     */
    val attempt: Int,
    /** The lifecycle state; one of the `AGENT_RUN_STATE_*` values. */
    val state: String,
    /** Why the last attempt ended, as a privacy-safe code, or null while it runs. */
    val stopReason: String?,
    /** The requested workflow generation budget that bounds a retried generation. */
    val maxOutputTokens: Int?,
    /** The one-way digest of the masked answer text the user saw, or null. */
    val answerDigest: String?,
    /**
     * The user's own request words, stored raw like a capture body: local business
     * data the user already owns, never egressed except through the masked boundary
     * at use time. Bounded at admission.
     */
    val requestText: String,
    /**
     * The masked-safe answer text the user actually saw, or null while the run has no
     * terminal answer. It carries no raw literals and no mapping that could rehydrate
     * one; tokens in it resolve only while the session vault lives.
     */
    val answerText: String?,
    /** The current wall-clock revision the run revalidated before dispatching, or null. */
    val revisionEpochMillis: Long?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

/** The run is admitted and may be started. */
internal const val AGENT_RUN_STATE_PENDING = "PENDING"

/** An attempt of the run is executing; its claim token holds the run row. */
internal const val AGENT_RUN_STATE_RUNNING = "RUNNING"

/** The run waits for the user's answer to an application clarification. */
internal const val AGENT_RUN_STATE_CLARIFYING = "CLARIFYING"

/** Every requested effect verified against freshly read application state. */
internal const val AGENT_RUN_STATE_SUCCEEDED = "SUCCEEDED"

/** The run stopped honestly: budget, provider, refusal, or unverified effect. */
internal const val AGENT_RUN_STATE_FAILED = "FAILED"

/** The user cancelled the run; its effects were never claimed as done. */
internal const val AGENT_RUN_STATE_CANCELLED = "CANCELLED"
