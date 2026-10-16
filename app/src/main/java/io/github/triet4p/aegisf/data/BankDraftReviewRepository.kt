package io.github.triet4p.aegisf.data

import io.github.triet4p.aegisf.ui.ManualDateTime

/** The user's editable review values; date and time remain wall-clock text until confirmation. */
data class BankDraftReviewEdits(
    val amountText: String,
    val dateText: String,
    val timeText: String,
    val memoText: String,
    val type: TransactionType?,
    val categoryId: Long?,
    val categoryResolved: Boolean,
)

enum class BankDraftReviewField {
    AMOUNT,
    DATE,
    TIME,
    TYPE,
    CATEGORY,
}

/** Observable outcomes for saves, confirmation, and dismissal. */
sealed interface BankDraftReviewResult {
    data class Saved(val revision: Int) : BankDraftReviewResult
    data class Confirmed(val transactionId: Long) : BankDraftReviewResult
    data object Dismissed : BankDraftReviewResult
    data class NeedsCorrection(val fields: Set<BankDraftReviewField>) : BankDraftReviewResult
    data object CategoryUnavailable : BankDraftReviewResult
    data object NotFound : BankDraftReviewResult
    data object AlreadyReviewed : BankDraftReviewResult
    data object Changed : BankDraftReviewResult
}

/**
 * Validates the fields that create a ledger record and delegates the terminal write to the
 * single Room transaction in [BankDraftReviewDao]. Parsed evidence and S3.5 delivery identity
 * are never rewritten by a correction.
 */
class BankDraftReviewRepository(
    private val draftDao: PendingBankDraftDao,
    private val reviewDao: BankDraftReviewDao,
    private val clock: LedgerClock = SystemLedgerClock,
) {

    /** Persist editable values on the still-pending draft without creating a ledger record. */
    suspend fun saveReview(
        draftId: Long,
        expectedRevision: Int,
        edits: BankDraftReviewEdits,
    ): BankDraftReviewResult = reviewDao.saveReview(draftId, expectedRevision, edits)

    /**
     * Confirms only a complete, explicit review. Date/time validation and conversion use the
     * same `YYYY-MM-DD` / `HH:mm` device-zone policy as manual transaction entry, and the
     * converted instant is created here only after this method has been called by review.
     */
    suspend fun confirmDraft(
        draftId: Long,
        expectedRevision: Int,
        edits: BankDraftReviewEdits,
    ): BankDraftReviewResult {
        val draft = draftDao.findById(draftId) ?: return BankDraftReviewResult.NotFound
        if (draft.reviewStatus != IntakeReviewStatus.PENDING || draft.confirmedTransactionId != null) {
            return BankDraftReviewResult.AlreadyReviewed
        }
        if (draft.reviewRevision != expectedRevision) return BankDraftReviewResult.Changed

        val fieldsNeedingCorrection = buildSet {
            try {
                VndMoney.parseManualAmount(edits.amountText)
            } catch (_: LedgerValidationException) {
                add(BankDraftReviewField.AMOUNT)
            }
            if (ManualDateTime.dateError(edits.dateText) != null) add(BankDraftReviewField.DATE)
            if (ManualDateTime.timeError(edits.timeText) != null) add(BankDraftReviewField.TIME)
            if (edits.type == null) add(BankDraftReviewField.TYPE)
            if ((edits.type == null || edits.type != TransactionType.TRANSFER) &&
                !edits.categoryResolved
            ) {
                add(BankDraftReviewField.CATEGORY)
            }
        }
        if (fieldsNeedingCorrection.isNotEmpty()) {
            return BankDraftReviewResult.NeedsCorrection(fieldsNeedingCorrection)
        }

        val amountVnd = VndMoney.parseManualAmount(edits.amountText)
        val type = checkNotNull(edits.type)
        val occurredAtEpochMillis = ManualDateTime.toEpochMillis(edits.dateText, edits.timeText)
        val now = clock.nowEpochMillis()
        val transaction = TransactionEntity(
            type = type,
            amountVnd = amountVnd,
            occurredAtEpochMillis = occurredAtEpochMillis,
            description = edits.memoText.trim(),
            categoryId = edits.categoryId.takeIf { type != TransactionType.TRANSFER },
            confirmation = ConfirmationStatus.CONFIRMED,
            source = RecordSource.BANK_NOTIFICATION,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        return reviewDao.confirm(draftId, expectedRevision, edits, transaction)
    }

    /** Dismisses a pending draft only; a terminal draft is never reopened or changed. */
    suspend fun dismissDraft(draftId: Long): BankDraftReviewResult = reviewDao.dismiss(draftId)
}
