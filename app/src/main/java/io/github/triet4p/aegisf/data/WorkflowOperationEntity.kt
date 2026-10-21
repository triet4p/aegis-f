package io.github.triet4p.aegisf.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One recorded preparation command.
 *
 * The row is the durable memory of a request: the key that identifies it, the caller it
 * was made by, the command that was run, a digest of what it was asked to do, and the
 * outcome it reached. A repeated request under the same key is answered from this row
 * instead of being run again, which is what makes a retry — after a restart, after a
 * crash, or from two callers at once — a no-change instead of a second effect.
 *
 * The row holds no private trace: the digest of a request is a hash of what was asked,
 * never the text that was asked about, and the stored outcome names the row it touched
 * and the revision it left behind.
 *
 * The row is also the durable claim of a request that is still running: a pending row is
 * written before the expensive work starts, so a second attempt under the same key finds
 * the claim instead of repeating the work, and a claim whose attempt is gone may be taken
 * over instead of reserving the key forever.
 */
@Entity(
    tableName = "workflow_operations",
    indices = [
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["createdAtEpochMillis"]),
        Index(value = ["command"]),
    ],
)
data class WorkflowOperationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** The caller's key for this request; the identity of the request across retries. */
    val idempotencyKey: String,
    /** The identity of the scope that made the request. */
    val scopeId: String,
    /** The command that was run. */
    val command: String,
    /** The digest of the request's own payload; never the payload itself. */
    val payloadDigest: String,
    /**
     * The state the request is in.
     *
     * It is [OPERATION_OUTCOME_PENDING] while the request holds the key and its work is
     * still running, and one of the finished outcomes once the work reached a durable
     * state. A pending row is the durable claim of one attempt: it is what keeps a second
     * attempt from repeating the work, and it is what a later attempt takes over when the
     * attempt that held it is gone.
     */
    val outcome: String,
    /** The encoded change the command made, or null when it made none. */
    val changeSummary: String?,
    /**
     * The attempt that holds a pending row, or null when the request is finished.
     *
     * The token is what makes the claim exclusive: only the attempt whose token the row
     * carries may finish it, so an attempt whose claim was taken over can never write over
     * the attempt that took it.
     */
    val ownerToken: String?,
    /**
     * When the claim of a pending row ends, or null when the request is finished.
     *
     * A claim that is not finished by then may be taken over, so an attempt that was
     * interrupted — a process that died, a device that was switched off — cannot leave the
     * key reserved forever.
     */
    val leaseExpiresAtEpochMillis: Long?,
    val createdAtEpochMillis: Long,
)

/** The outcome of a recorded command that applied a change. */
internal const val OPERATION_OUTCOME_APPLIED = "APPLIED"

/** The outcome of a recorded command that changed nothing. */
internal const val OPERATION_OUTCOME_NO_CHANGE = "NO_CHANGE"

/** The state of a recorded command whose work is still running. */
internal const val OPERATION_OUTCOME_PENDING = "PENDING"

/** The canonical encoding of a change, so a replayed outcome is the outcome that happened. */
internal fun encodeWorkflowChange(change: WorkflowChange): String = when (change) {
    is WorkflowChange.CaptureSaved -> encodeFields(
        listOf("CAPTURE_SAVED", change.captureId.toString(), change.revision.toString()),
    )
    is WorkflowChange.CaptureRevised -> encodeFields(
        listOf("CAPTURE_REVISED", change.captureId.toString(), change.revision.toString()),
    )
    is WorkflowChange.EventStored -> encodeFields(
        listOf("EVENT_STORED", change.eventId.toString(), change.revision.toString()),
    )
    is WorkflowChange.EventRevised -> encodeFields(
        listOf("EVENT_REVISED", change.eventId.toString(), change.revision.toString()),
    )
    is WorkflowChange.LinkDecided -> encodeFields(
        listOf("LINK_DECIDED", change.linkId.toString(), change.state.name, change.revision.toString()),
    )
    is WorkflowChange.LinkUnlinked -> encodeFields(
        listOf("LINK_UNLINKED", change.linkId.toString()),
    )
    is WorkflowChange.Classified -> encodeFields(
        listOf(
            "CLASSIFIED",
            change.draftId.toString(),
            change.snapshotId.toString(),
            change.source.name,
            change.suggestedCategoryId?.toString(),
            change.confidence?.toString(),
        ),
    )
    is WorkflowChange.TransactionSaved -> encodeFields(
        listOf(
            "TRANSACTION_SAVED",
            change.transactionId.toString(),
            change.revision.toString(),
            change.sourceEventId?.toString(),
        ),
    )
    is WorkflowChange.TransactionCorrected -> encodeFields(
        listOf(
            "TRANSACTION_CORRECTED",
            change.transactionId.toString(),
            change.revision.toString(),
        ),
    )
    is WorkflowChange.TransactionDeleted -> encodeFields(
        listOf("TRANSACTION_DELETED", change.transactionId.toString()),
    )
    is WorkflowChange.DraftReviewSaved -> encodeFields(
        listOf("DRAFT_REVIEW_SAVED", change.draftId.toString(), change.revision.toString()),
    )
    is WorkflowChange.DraftConfirmed -> encodeFields(
        listOf("DRAFT_CONFIRMED", change.draftId.toString(), change.transactionId.toString()),
    )
    is WorkflowChange.DraftDismissed -> encodeFields(
        listOf("DRAFT_DISMISSED", change.draftId.toString()),
    )
}

/** The change an encoded summary describes, or null when the summary is not one of ours. */
internal fun decodeWorkflowChange(summary: String?): WorkflowChange? {
    if (summary == null) return null
    val fields = decodeFields(summary)
    return when (fields.firstOrNull()) {
        "CAPTURE_SAVED" -> WorkflowChange.CaptureSaved(
            captureId = fields.longAt(1),
            revision = fields.intAt(2),
        )
        "CAPTURE_REVISED" -> WorkflowChange.CaptureRevised(
            captureId = fields.longAt(1),
            revision = fields.intAt(2),
        )
        "EVENT_STORED" -> WorkflowChange.EventStored(
            eventId = fields.longAt(1),
            revision = fields.intAt(2),
        )
        "EVENT_REVISED" -> WorkflowChange.EventRevised(
            eventId = fields.longAt(1),
            revision = fields.intAt(2),
        )
        "LINK_DECIDED" -> WorkflowChange.LinkDecided(
            linkId = fields.longAt(1),
            state = fields.textAt(2)?.let { name ->
                ContextLinkState.entries.firstOrNull { it.name == name }
            } ?: return null,
            revision = fields.intAt(3),
        )
        "LINK_UNLINKED" -> WorkflowChange.LinkUnlinked(linkId = fields.longAt(1))
        "CLASSIFIED" -> WorkflowChange.Classified(
            draftId = fields.longAt(1),
            snapshotId = fields.longAt(2),
            source = fields.textAt(3)?.let { name ->
                PredictionSource.entries.firstOrNull { it.name == name }
            } ?: return null,
            suggestedCategoryId = fields.textAt(4)?.toLongOrNull(),
            confidence = fields.textAt(5)?.toDoubleOrNull(),
            decision = null,
        )
        "TRANSACTION_SAVED" -> WorkflowChange.TransactionSaved(
            transactionId = fields.longAt(1),
            revision = fields.intAt(2),
            sourceEventId = fields.textAt(3)?.toLongOrNull(),
        )
        "TRANSACTION_CORRECTED" -> WorkflowChange.TransactionCorrected(
            transactionId = fields.longAt(1),
            revision = fields.intAt(2),
        )
        "TRANSACTION_DELETED" -> WorkflowChange.TransactionDeleted(
            transactionId = fields.longAt(1),
        )
        "DRAFT_REVIEW_SAVED" -> WorkflowChange.DraftReviewSaved(
            draftId = fields.longAt(1),
            revision = fields.intAt(2),
        )
        "DRAFT_CONFIRMED" -> WorkflowChange.DraftConfirmed(
            draftId = fields.longAt(1),
            transactionId = fields.longAt(2),
        )
        "DRAFT_DISMISSED" -> WorkflowChange.DraftDismissed(
            draftId = fields.longAt(1),
        )
        else -> null
    }
}

/**
 * Encodes the fields of one record.
 *
 * Every field is length-prefixed, so a value that contains the separator can never encode
 * like two values, and a missing field is distinct from an empty one.
 */
private fun encodeFields(values: List<String?>): String =
    values.joinToString(separator = OPERATION_FIELD_SEPARATOR) { value ->
        if (value == null) "n" else "v${value.length}:$value"
    }

/** Decodes the fields of one record. */
private fun decodeFields(summary: String): List<String?> =
    summary.split(OPERATION_FIELD_SEPARATOR).map { field ->
        when {
            field == "n" -> null
            field.startsWith("v") -> {
                val separator = field.indexOf(':')
                if (separator < 0) {
                    null
                } else {
                    field.substring(separator + 1)
                }
            }
            else -> null
        }
    }

/** The field at the index, or null when the record does not hold one. */
private fun List<String?>.textAt(index: Int): String? = getOrNull(index)

/** The field at the index as a number, or zero when the record does not hold one. */
private fun List<String?>.longAt(index: Int): Long = getOrNull(index)?.toLongOrNull() ?: 0L

/** The field at the index as a number, or zero when the record does not hold one. */
private fun List<String?>.intAt(index: Int): Int = getOrNull(index)?.toIntOrNull() ?: 0

/** Separates the canonical fields of one record; never a separator in a stored value. */
private const val OPERATION_FIELD_SEPARATOR = "\u001F"
