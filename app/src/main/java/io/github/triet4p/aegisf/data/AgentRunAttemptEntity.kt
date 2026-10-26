package io.github.triet4p.aegisf.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One durable record of a single attempt of an [AgentRunEntity]: what the run tried,
 * what the application actually did, and why the attempt ended.
 *
 * An attempt is the unit of retry and the unit of evidence. It records only allowed
 * effect references — an event id and revision, a link id, state and revision, a draft
 * id with its decision source — never raw capture text, amounts, dates, memos, or
 * model reasoning. The observation column is a privacy-safe JSON object of statuses,
 * counts, ids, and reason codes, already masked through the run's own boundary before
 * it is written. A `PENDING` attempt is an interrupted claim: it is resumed or taken
 * over on reopen, never silently replayed and never reported as a success.
 */
@Entity(
    tableName = "agent_run_attempts",
    foreignKeys = [
        ForeignKey(
            entity = AgentRunEntity::class,
            parentColumns = ["runId"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["runId", "attempt"]),
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["state"]),
        Index(value = ["createdAtEpochMillis"]),
    ],
)
data class AgentRunAttemptEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** The run this attempt belongs to; a foreign key of the durable run row. */
    val runId: String,
    /** Which attempt of the request this row records; 1 for the first try. */
    val attempt: Int,
    /**
     * The trusted operation identity of this attempt. One key names one attempt, so a
     * repeated user send, a reopened conversation, and a cancelled-then-retried run
     * replay the same attempt instead of writing a second effect.
     */
    val idempotencyKey: String,
    /** The attempt lifecycle state; one of the `AGENT_RUN_ATTEMPT_*` values. */
    val state: String,
    /** Why the attempt ended, as a privacy-safe retry code, or null while it runs. */
    val stopReason: String?,
    /** The attempted effect when this attempt dispatched a mutation, or null. */
    val effectKind: String?,
    /** The event the attempt read or linked, or null for a query-only attempt. */
    val eventId: Long?,
    /** The event revision the attempt verified, or null. */
    val eventRevision: Int?,
    /** The link the attempt proposed or attached, or null. */
    val linkId: Long?,
    /** The link state the attempt verified (`PROPOSED`/`ACCEPTED`), or null. */
    val linkState: String?,
    /** The link revision the attempt verified, or null. */
    val linkRevision: Int?,
    /** The draft the attempt searched or classified, or null. */
    val draftId: Long?,
    /** Where a verified classification decision came from, or null. */
    val decisionSource: String?,
    /**
     * The privacy-safe masked observation of this attempt: turn/tool counts, statuses,
     * ids, reason codes — never raw text, mappings, credentials, or reasoning.
     */
    val observationJson: String,
    /**
     * The token of the attempt that holds a running row, or null. Every transition of
     * a running attempt is conditional on this token, so a late reply, a cancelled
     * run, or a competing reopen can never write its stale outcome over the run's
     * current attempt.
     */
    val ownerToken: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

/** The attempt was admitted; its claim token holds it. */
internal const val AGENT_RUN_ATTEMPT_PENDING = "PENDING"

/** The attempt ran and its effects verified; terminal for the attempt. */
internal const val AGENT_RUN_ATTEMPT_COMPLETED = "COMPLETED"

/**
 * The attempt ended without a verified effect: an uncertain write, a stale revision,
 * a provider refusal, or a budget. Terminal for the attempt; the run decides the retry.
 */
internal const val AGENT_RUN_ATTEMPT_UNKNOWN = "UNKNOWN"

/** The attempt was discarded before it applied anything; terminal for the attempt. */
internal const val AGENT_RUN_ATTEMPT_CANCELLED = "CANCELLED"
