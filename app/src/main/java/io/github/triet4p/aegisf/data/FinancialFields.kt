package io.github.triet4p.aegisf.data

import io.github.triet4p.aegisf.ui.ManualDateTime
import java.util.TimeZone

/**
 * Why one set of financial fields cannot be written.
 *
 * The reasons are the app's own rules and they are stated once: the ledger repository
 * turns them into its refusal, and the command boundary turns the same reasons into its
 * structured outcomes. There is one rule, and it is not written twice.
 */
internal sealed interface FinancialFieldRejection {
    /** The reason a caller is told, in the app's own words. */
    val reason: String

    /** The amount is not a usable whole-dong magnitude. */
    data class Amount(override val reason: String) : FinancialFieldRejection

    /** The chosen category is gone or archived, so the field cannot be written. */
    data class Category(override val reason: String) : FinancialFieldRejection

    /** An internal transfer carries no category. */
    data class TransferCategory(override val reason: String) : FinancialFieldRejection
}

/**
 * The app's rules for the fields a financial write stores.
 *
 * The rules are the same wherever the write happens:
 *
 * - an amount is a positive whole-dong magnitude of at least [VndMoney.MIN_AMOUNT_VND];
 *   no sign is ever stored, because the record type decides the direction;
 * - a category must exist and must not be archived, because an archived category is
 *   unavailable for new assignments, while a correction may keep the archived category
 *   its own record already carries — that link is existing historical attribution, not a
 *   new assignment;
 * - an internal transfer carries no category;
 * - a bank draft's review is complete only when the amount, the date, the time, and the
 *   type are stated and the category is explicitly resolved, so a missing field is never
 *   read as agreement.
 */
internal class FinancialFieldRules(
    private val categoryDao: CategoryDao,
) {

    /**
     * The reason these fields cannot be written, or null when they can.
     *
     * [retainedCategoryId] is the category the stored record already carries. A correction
     * that keeps it may keep an archived category, because the record's link to it already
     * exists; anything else must be unarchived and must exist.
     */
    suspend fun rejection(
        type: TransactionType,
        amountVnd: Long,
        categoryId: Long?,
        retainedCategoryId: Long?,
    ): FinancialFieldRejection? {
        if (amountVnd < VndMoney.MIN_AMOUNT_VND) {
            return FinancialFieldRejection.Amount(
                "Amount must be at least ${VndMoney.MIN_AMOUNT_VND} whole dong; got $amountVnd.",
            )
        }
        if (categoryId == null) return null
        if (type == TransactionType.TRANSFER) {
            return FinancialFieldRejection.TransferCategory(
                "An internal transfer moves money between the user's own holdings and " +
                    "carries no category.",
            )
        }
        val category = categoryDao.findById(categoryId)
            ?: return FinancialFieldRejection.Category("Category $categoryId does not exist.")
        if (category.archived && categoryId != retainedCategoryId) {
            return FinancialFieldRejection.Category(
                "Category \"${category.name}\" is archived and cannot be attached to a " +
                    "new record.",
            )
        }
        return null
    }

    /** Whether a category may still be attached to a record. */
    suspend fun categoryAssignable(categoryId: Long): Boolean {
        val category = categoryDao.findById(categoryId) ?: return false
        return !category.archived
    }

    /**
     * The exact ledger fields a review's edits would write, or the fields the user still
     * has to state.
     *
     * The wall-clock text is resolved here, under the zone the caller names, so a preview
     * and the confirmation that follows it resolve the same text into the same instant.
     */
    fun resolveReviewFields(
        edits: BankDraftReviewEdits,
        zone: TimeZone,
    ): ReviewFieldResolution {
        val amountVnd = try {
            VndMoney.parseManualAmount(edits.amountText)
        } catch (_: LedgerValidationException) {
            null
        }
        val dateProblem = ManualDateTime.dateError(edits.dateText)
        val timeProblem = ManualDateTime.timeError(edits.timeText)
        val type = edits.type
        val needsCorrection = buildSet {
            if (amountVnd == null) add(BankDraftReviewField.AMOUNT)
            if (dateProblem != null) add(BankDraftReviewField.DATE)
            if (timeProblem != null) add(BankDraftReviewField.TIME)
            if (type == null) add(BankDraftReviewField.TYPE)
            if ((type == null || type != TransactionType.TRANSFER) && !edits.categoryResolved) {
                add(BankDraftReviewField.CATEGORY)
            }
        }
        if (needsCorrection.isNotEmpty() || amountVnd == null || type == null) {
            return ReviewFieldResolution.Invalid(needsCorrection)
        }
        return ReviewFieldResolution.Resolved(
            ConfirmedTransactionFields(
                type = type,
                amountVnd = amountVnd,
                occurredAtEpochMillis = ManualDateTime.toEpochMillis(
                    dateText = edits.dateText,
                    timeText = edits.timeText,
                    zone = zone,
                ),
                description = edits.memoText.trim(),
                categoryId = edits.categoryId.takeIf { type != TransactionType.TRANSFER },
            ),
        )
    }
}

/** What one review's edits resolve to: the exact fields, or what the user still has to state. */
internal sealed interface ReviewFieldResolution {
    /** The exact ledger fields a confirmation would write. */
    data class Resolved(val fields: ConfirmedTransactionFields) : ReviewFieldResolution

    /** The fields the user has to state or correct first. */
    data class Invalid(val fields: Set<BankDraftReviewField>) : ReviewFieldResolution
}
