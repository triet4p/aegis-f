package io.github.triet4p.aegisf.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/**
 * The cross-table write boundary for bank-draft review. Confirmation claims a still-pending,
 * expected revision, inserts its ledger row, and links that row before the Room transaction
 * commits; any failed insert rolls the claim back with it.
 */
@Dao
abstract class BankDraftReviewDao {

    @Query("SELECT * FROM pending_bank_drafts WHERE id = :id")
    abstract suspend fun findDraft(id: Long): PendingBankDraftEntity?

    @Query("SELECT COUNT(*) FROM categories WHERE id = :categoryId AND archived = 0")
    protected abstract suspend fun countAssignableCategory(categoryId: Long): Int

    @Query(
        "UPDATE pending_bank_drafts SET " +
            "reviewAmountText = :amountText, " +
            "reviewDateText = :dateText, " +
            "reviewTimeText = :timeText, " +
            "reviewMemo = :memo, " +
            "reviewMemoEdited = :memoEdited, " +
            "reviewType = :type, " +
            "reviewCategoryId = :categoryId, " +
            "reviewCategoryResolved = :categoryResolved, " +
            "reviewRevision = reviewRevision + 1 " +
            "WHERE id = :id AND reviewStatus = 'PENDING' AND reviewRevision = :expectedRevision",
    )
    protected abstract suspend fun updateReviewFields(
        id: Long,
        expectedRevision: Int,
        amountText: String,
        dateText: String,
        timeText: String,
        memo: String,
        memoEdited: Boolean,
        type: String?,
        categoryId: Long?,
        categoryResolved: Boolean,
    ): Int

    @Query(
        "UPDATE pending_bank_drafts SET " +
            "reviewAmountText = :amountText, " +
            "reviewDateText = :dateText, " +
            "reviewTimeText = :timeText, " +
            "reviewMemo = :memo, " +
            "reviewMemoEdited = :memoEdited, " +
            "reviewType = :type, " +
            "reviewCategoryId = :categoryId, " +
            "reviewCategoryResolved = :categoryResolved, " +
            "reviewRevision = reviewRevision + 1, " +
            "reviewStatus = 'CONFIRMED' " +
            "WHERE id = :id AND reviewStatus = 'PENDING' AND " +
            "reviewRevision = :expectedRevision AND confirmedTransactionId IS NULL",
    )
    protected abstract suspend fun claimForConfirmation(
        id: Long,
        expectedRevision: Int,
        amountText: String,
        dateText: String,
        timeText: String,
        memo: String,
        memoEdited: Boolean,
        type: String?,
        categoryId: Long?,
        categoryResolved: Boolean,
    ): Int

    @Insert
    protected abstract suspend fun insertTransaction(transaction: TransactionEntity): Long

    @Query(
        "UPDATE pending_bank_drafts SET confirmedTransactionId = :transactionId " +
            "WHERE id = :id AND reviewStatus = 'CONFIRMED' AND confirmedTransactionId IS NULL",
    )
    protected abstract suspend fun linkConfirmedTransaction(id: Long, transactionId: Long): Int

    @Query(
        "UPDATE pending_bank_drafts SET reviewStatus = 'DISMISSED' " +
            "WHERE id = :id AND reviewStatus = 'PENDING' AND confirmedTransactionId IS NULL",
    )
    protected abstract suspend fun markDismissed(id: Long): Int

    /** Saves corrections only while the same revision is pending. */
    @Transaction
    open suspend fun saveReview(
        id: Long,
        expectedRevision: Int,
        edits: BankDraftReviewEdits,
    ): BankDraftReviewResult {
        val draft = findDraft(id) ?: return BankDraftReviewResult.NotFound
        if (draft.reviewStatus != IntakeReviewStatus.PENDING || draft.confirmedTransactionId != null) {
            return BankDraftReviewResult.AlreadyReviewed
        }
        if (draft.reviewRevision != expectedRevision) return BankDraftReviewResult.Changed
        val updated = updateReviewFields(
            id = id,
            expectedRevision = expectedRevision,
            amountText = edits.amountText,
            dateText = edits.dateText,
            timeText = edits.timeText,
            memo = edits.memoText,
            memoEdited = true,
            type = edits.type?.name,
            categoryId = edits.categoryId,
            categoryResolved = edits.categoryResolved,
        )
        return if (updated == 1) {
            BankDraftReviewResult.Saved(expectedRevision + 1)
        } else {
            BankDraftReviewResult.Changed
        }
    }

    /**
     * Atomically marks the pending draft terminal, inserts exactly one transaction, and links
     * it back to the draft. SQLite serializes competing writers; the conditional claim also
     * prevents a stale editor from inserting after a dismiss, confirmation, or newer correction.
     */
    @Transaction
    open suspend fun confirm(
        id: Long,
        expectedRevision: Int,
        edits: BankDraftReviewEdits,
        transaction: TransactionEntity,
    ): BankDraftReviewResult {
        val draft = findDraft(id) ?: return BankDraftReviewResult.NotFound
        if (draft.reviewStatus != IntakeReviewStatus.PENDING || draft.confirmedTransactionId != null) {
            return BankDraftReviewResult.AlreadyReviewed
        }
        if (draft.reviewRevision != expectedRevision) return BankDraftReviewResult.Changed
        val categoryId = transaction.categoryId
        if (categoryId != null && countAssignableCategory(categoryId) == 0) {
            return BankDraftReviewResult.CategoryUnavailable
        }

        val claimed = claimForConfirmation(
            id = id,
            expectedRevision = expectedRevision,
            amountText = edits.amountText,
            dateText = edits.dateText,
            timeText = edits.timeText,
            memo = edits.memoText,
            memoEdited = true,
            type = edits.type?.name,
            categoryId = edits.categoryId,
            categoryResolved = edits.categoryResolved,
        )
        if (claimed != 1) {
            val current = findDraft(id) ?: return BankDraftReviewResult.NotFound
            return if (current.reviewStatus != IntakeReviewStatus.PENDING ||
                current.confirmedTransactionId != null
            ) {
                BankDraftReviewResult.AlreadyReviewed
            } else {
                BankDraftReviewResult.Changed
            }
        }

        val transactionId = insertTransaction(transaction)
        check(linkConfirmedTransaction(id, transactionId) == 1) {
            "Confirmed bank draft $id could not link transaction $transactionId."
        }
        return BankDraftReviewResult.Confirmed(transactionId)
    }

    /** A single conditional update makes dismissal terminal and keeps it outside the ledger. */
    @Transaction
    open suspend fun dismiss(id: Long): BankDraftReviewResult {
        val draft = findDraft(id) ?: return BankDraftReviewResult.NotFound
        if (draft.reviewStatus != IntakeReviewStatus.PENDING || draft.confirmedTransactionId != null) {
            return BankDraftReviewResult.AlreadyReviewed
        }
        return if (markDismissed(id) == 1) {
            BankDraftReviewResult.Dismissed
        } else {
            BankDraftReviewResult.AlreadyReviewed
        }
    }
}
