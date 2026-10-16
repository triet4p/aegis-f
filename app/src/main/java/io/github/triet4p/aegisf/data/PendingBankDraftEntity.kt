package io.github.triet4p.aegisf.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One persisted pending bank draft: a permitted notification's parsed fields, kept as
 * reviewable evidence until the user confirms or dismisses it in review.
 *
 * Each field the bank stated keeps its own explicit state ([amountState], [momentState],
 * [memoState]) so a missing field is never confused with one that was stated but could
 * not be read, and [direction] stays the bank's own vocabulary ([IntakeDirection]), never
 * a ledger type — a debit is not an expense. A draft still waiting for review is
 * [IntakeReviewStatus.PENDING]; the `confirmedTransactionId` link stays null until the
 * user's explicit confirmation writes a ledger record from this draft, and the review
 * overrides remain separate from parsed evidence so they cannot change S3.5 replay identity.
 *
 * Storage identity is two columns, and they are what keeps repeated deliveries from becoming
 * extra drafts without ever collapsing a distinct transaction:
 *
 * - [deliveryId] is the platform post the row arrived as — the source package, the platform's
 *   notification key when it supplied one, and the post time — canonically encoded by
 *   [draftDeliveryId]. It is a delivery, not a transaction: a bank may reuse one notification
 *   key for a later real transaction, and that later post is a different delivery, so a delivery
 *   id alone never decides that two rows are the same money — and the post time in it is what
 *   keeps that later post from being mistaken for a replay of the earlier one.
 * - [evidenceDigest] is the canonical digest of the permitted fields this row states, derived
 *   by [draftEvidenceDigest] at construction from the fields above. Together with [deliveryId]
 *   it carries the table's unique constraint, which is what makes a replayed callback — the same
 *   post stating exactly the same evidence — write nothing at all rather than a second row.
 *
 * A row whose evidence matches an earlier row under a *different* delivery is an uncertain
 * cross-notification duplicate: it is kept, never discarded, and [possibleDuplicateOfDraftId]
 * points at the earliest such row so review can show the pair instead of guessing. That covers a
 * reused notification key carrying a later transaction with byte-identical permitted evidence,
 * which is exactly the case a parsed-evidence comparison cannot tell from a repeat. No supported
 * format states a transaction reference, so nothing here collapses two deliveries into one row
 * on evidence alone — amount and time similarity is exactly what must not imply identity.
 *
 * The parsed source fields store only permitted data: the notification's routing provenance
 * ([sourcePackage], [notificationKey], [postedAtEpochMillis]), bank attribution ([bank]), and
 * parsed values ([amountVnd], [statedDate], [statedTime], [memo]). Separate review override
 * columns retain the user's corrections without changing the source evidence or its digest.
 * The raw message body, OTP/security text, account identifiers, and balance values are never
 * written here — rejected notifications never reach this table at all, and neither the delivery
 * identity nor the evidence digest is computed over a raw body. The bank's wall-clock moment is
 * kept as [statedDate]/[statedTime] with no zone or invented instant; only user confirmation
 * converts it under the app's documented policy.
 */
@Entity(
    tableName = "pending_bank_drafts",
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["confirmedTransactionId"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PendingBankDraftEntity::class,
            parentColumns = ["id"],
            childColumns = ["possibleDuplicateOfDraftId"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["deliveryId", "evidenceDigest"], unique = true),
        Index(value = ["evidenceDigest"]),
        Index(value = ["possibleDuplicateOfDraftId"]),
        Index(value = ["reviewStatus"]),
        Index(value = ["confirmedTransactionId"]),
    ],
)
data class PendingBankDraftEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** The platform post this delivery arrived as; see [draftDeliveryId]. */
    val deliveryId: String,
    val sourcePackage: String,
    val notificationKey: String?,
    /** The platform's post time for this delivery; part of [deliveryId] and kept as provenance. */
    val postedAtEpochMillis: Long,
    val bank: String,
    val parseStatus: IntakeParseStatus,
    val amountState: IntakeFieldState,
    val amountVnd: Long?,
    /** Why the amount is unresolved; null unless it was stated but could not be read. */
    val amountUnresolvedReason: String?,
    val direction: IntakeDirection,
    val momentState: IntakeFieldState,
    val statedDate: String?,
    val statedTime: String?,
    /** Why the moment is unresolved; null unless one was stated but could not be read. */
    val momentUnresolvedReason: String?,
    val memoState: IntakeFieldState,
    val memo: String?,
    /** Why the memo is unresolved; null unless it was stated but could not be read. */
    val memoUnresolvedReason: String?,
    /**
     * Canonical digest of the permitted evidence in the fields above; see [draftEvidenceDigest].
     *
     * Derived from those fields as this row is constructed, so it can never drift from them.
     * It is stored because the unique delivery/evidence index and the cross-notification
     * possible-duplicate lookup both need it, and because review reads it back after a restart.
     * A `copy` that changes evidence keeps this value and must re-stamp it; no intake path
     * rewrites a stored row, so a stored digest always describes its own row.
     */
    val evidenceDigest: String = draftEvidenceDigest(
        bank = bank,
        parseStatus = parseStatus,
        amountState = amountState,
        amountVnd = amountVnd,
        amountUnresolvedReason = amountUnresolvedReason,
        direction = direction,
        momentState = momentState,
        statedDate = statedDate,
        statedTime = statedTime,
        momentUnresolvedReason = momentUnresolvedReason,
        memoState = memoState,
        memo = memo,
        memoUnresolvedReason = memoUnresolvedReason,
    ),
    val reviewStatus: IntakeReviewStatus = IntakeReviewStatus.PENDING,
    val confirmedTransactionId: Long? = null,
    /**
     * The earliest stored draft whose evidence is identical to this one, or null.
     *
     * Set only when this row was stored while an identical statement was already stored as a
     * different delivery: same amount, direction, moment, and memo, but a different post (another
     * notification key, the same key reused for a later post, or a later keyless post). That is
     * uncertain identity, so both rows are kept and review can show this one beside its possible
     * duplicate. A repeated delivery of the *same* post states the same evidence under the same
     * [deliveryId], which the unique index turns into no write at all, so a row never points at
     * itself or at its own delivery.
     */
    val possibleDuplicateOfDraftId: Long? = null,
    val createdAtEpochMillis: Long,
    /** User-entered amount text, with null meaning "use the bank's parsed value". */
    val reviewAmountText: String? = null,
    /** User-entered date/time overrides, kept as wall-clock text until confirmation. */
    val reviewDateText: String? = null,
    val reviewTimeText: String? = null,
    /** The optional user-entered description; the edit bit distinguishes blank from untouched. */
    val reviewMemo: String? = null,
    @ColumnInfo(defaultValue = "0")
    val reviewMemoEdited: Boolean = false,
    /** No default type: a bank debit/credit never selects a ledger type. */
    val reviewType: TransactionType? = null,
    /** A null category is valid only after the user explicitly chose "No category". */
    val reviewCategoryId: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val reviewCategoryResolved: Boolean = false,
    /** Compare-and-set revision so a stale editor cannot overwrite newer pending corrections. */
    @ColumnInfo(defaultValue = "0")
    val reviewRevision: Int = 0,
)
