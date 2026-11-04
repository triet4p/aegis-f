package io.github.triet4p.aegisf.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One durable, content-free unit of note/draft arrival reconciliation. */
@Entity(
    tableName = "arrival_reconciliation_operations",
    indices = [
        Index(value = ["state", "createdAtEpochMillis"]),
        Index(value = ["triggerKind", "createdAtEpochMillis"]),
    ],
)
data class ArrivalReconciliationOperationEntity(
    /** Stable identity for one event revision or one notification draft revision. */
    @PrimaryKey val operationId: String,
    /** Whether the arrival named an interpreted event or a stored bank draft. */
    val triggerKind: String,
    /** The event identity and expected revisions for an event-arrival operation. */
    val eventId: Long?,
    val expectedEventRevision: Int?,
    val expectedCaptureRevision: Int?,
    /** The notification identity and expected review revision for a draft-arrival operation. */
    val draftId: Long?,
    val expectedDraftReviewRevision: Int?,
    /** Last event id committed by a paged draft-arrival scan. */
    val cursorEventId: Long,
    /** Pending work survives process death; completed work remains as replay/coalescing memory. */
    val state: String,
    /** The final privacy-safe result code, or null while the operation is pending. */
    val outcome: String?,
    val createdAtEpochMillis: Long,
    /** Cumulative results of committed pages, retained until the draft scan completes. */
    val linkedAny: Boolean = false,
    val incompleteAny: Boolean = false,
)

internal const val ARRIVAL_TRIGGER_EVENT = "EVENT"
internal const val ARRIVAL_TRIGGER_DRAFT = "DRAFT"
internal const val ARRIVAL_OPERATION_PENDING = "PENDING"
internal const val ARRIVAL_OPERATION_COMPLETE = "COMPLETE"
internal const val ARRIVAL_OUTCOME_LINKED = "LINKED"
internal const val ARRIVAL_OUTCOME_NO_MATCH = "NO_MATCH"
internal const val ARRIVAL_OUTCOME_INCOMPLETE = "INCOMPLETE"
internal const val ARRIVAL_OUTCOME_STALE = "STALE"
