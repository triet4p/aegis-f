package io.github.triet4p.aegisf.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** The kind of durable user/business capture; transient prompts and tool traces have no kind. */
enum class CaptureKind {
    /** Immutable reference to parsed, permitted bank evidence; bodyText is only its parsed memo. */
    BANK_EVIDENCE_REF,

    /** An intentionally saved user quick capture, independent of any bank notification. */
    USER_NOTE,

    /** An intentionally saved user annotation, separate from the source evidence it describes. */
    MANUAL_ANNOTATION,
}

/** Truthful provenance for the text in a capture row. */
enum class CaptureSourceKind {
    BANK_NOTIFICATION,
    USER_QUICK_CAPTURE,
    USER_ANNOTATION,
    LEGACY_LEDGER_DESCRIPTION,
}

/**
 * Versioned, user-visible source text and provenance.
 *
 * [bodyText] is the sole retained text for this row. An explicit edit replaces it and increments
 * [revision]; no prior version is copied into an audit record. An explicit delete clears it and
 * leaves a text-free [tombstone]. Bank evidence rows are immutable and contain only the parsed
 * allowed memo, never the notification body. Legacy ledger descriptions retain a legacy source
 * kind and unknown capture time/zone rather than claiming provenance the old schema lacked.
 */
@Entity(
    tableName = "capture_messages",
    indices = [
        Index(value = ["sourceKind", "sourceIdentity"], unique = true),
        Index(value = ["originRef"]),
        Index(value = ["capturedAtEpochMillis"]),
        Index(value = ["tombstone", "capturedAtEpochMillis"]),
    ],
)
data class CaptureMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val captureKind: CaptureKind,
    val sourceKind: CaptureSourceKind,
    /** Stable source identity; never derived from the retained text itself. */
    val sourceIdentity: String,
    /** Allowed source text only; null for text-free references and deleted tombstones. */
    val bodyText: String?,
    /** Null only where the legacy schema did not record a truthful capture instant. */
    val capturedAtEpochMillis: Long?,
    /** IANA/device timezone for intentional captures; null when the source supplied no zone. */
    val captureTimezone: String?,
    /** Compare-and-set revision for explicit user edits/deletions. */
    val revision: Int,
    /** Typed stable pointer such as BANK_DRAFT:42 or LEDGER_RECORD:42; never copied source text. */
    val originRef: String?,
    val tombstone: Boolean = false,
)

/** Stable identity of one bank-source draft row, shared by migration and runtime persistence. */
internal fun bankEvidenceSourceIdentity(draftId: Long): String = "BANK_DRAFT:$draftId"

/** Stable pointer spelling shared by migration and explicit ledger correction/deletion. */
internal fun ledgerRecordOriginRef(transactionId: Long): String = "LEDGER_RECORD:$transactionId"
