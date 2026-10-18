package io.github.triet4p.aegisf.data

import kotlinx.coroutines.flow.Flow

/**
 * The user-supplied part of a **new** transaction, before storage adds the id and timestamps.
 *
 * [amountVnd] is a positive whole-dong magnitude — not a signed total — because [type]
 * supplies the direction. [categoryId] is null when the record is uncategorised, which is
 * allowed: an unknown category must not prevent saving. Because these fields describe how a
 * record comes to exist, this type is for [LedgerRepository.addTransaction]; changing a
 * stored record uses [TransactionCorrection], which cannot rewrite them.
 */
data class TransactionInput(
    val type: TransactionType,
    val amountVnd: Long,
    val occurredAtEpochMillis: Long,
    val description: String,
    val categoryId: Long? = null,
    val confirmation: ConfirmationStatus = ConfirmationStatus.CONFIRMED,
    val source: RecordSource = RecordSource.MANUAL,
)

/**
 * The user-editable fields of a correction to an existing transaction.
 *
 * A correction deliberately carries neither [TransactionEntity.confirmation] nor
 * [TransactionEntity.source]: those say how the record came to exist, and correcting a field
 * must not be able to rewrite them. Keeping them out of the type makes it impossible for a
 * correction to promote a draft into the confirmed records that enter totals, or to claim a
 * user-typed record was captured from a bank notification.
 */
data class TransactionCorrection(
    val type: TransactionType,
    val amountVnd: Long,
    val occurredAtEpochMillis: Long,
    val description: String,
    val categoryId: Long? = null,
)

/**
 * What permanently removing one category affected: the stored transactions that are now
 * uncategorized, the saved rules that went with the category, and the still-pending bank
 * drafts whose pre-set category was cleared.
 *
 * The ledger rows themselves are never counted as removed, because none is: a delete keeps
 * every record and only clears a link. The same type reports what a deletion *would* change
 * (for the confirmation) and what it *did* change (the return value), so the two can be
 * compared rather than assumed equal.
 */
data class CategoryDeletionImpact(
    val uncategorizedTransactionCount: Int,
    val removedRuleCount: Int,
    val clearedPendingReviewCount: Int,
)

/**
 * Reads and writes the ledger and explicit, description-scoped category rules.
 *
 * Data rules live here rather than in a screen:
 *
 * - an amount is a positive whole-dong magnitude of at least [VndMoney.MIN_AMOUNT_VND]; no sign is
 *   ever stored, because the record type decides the direction. The accepted manual input
 *   syntax, the displayed range and overflow checks belong to the money layer, not here;
 * - a category must exist and must not be archived, because an archived category is
 *   unavailable for new assignments, while a correction may keep the archived category its
 *   own record already carries — that link is existing historical attribution, not a new
 *   assignment;
 * - an internal transfer carries no category;
 * - active category names are unique, compared without case;
 * - deleting a category removes its own row and is distinct from archiving: every stored
 *   record filed under it is kept — amount, type, moment, description, and totals intact —
 *   and only loses the category link, while the saved rules that named the category are
 *   removed with it and a still-pending draft's pre-set category is cleared so the reviewer
 *   chooses again;
 * - a correction replaces only the user-editable fields of the record it names, keeping the
 *   record's id, creation time, confirmation, and provenance, and stamping the revision time;
 * - every rejection leaves the stored records untouched.
 * - a remembered category rule uses one exact normalized description and a stable active
 *   category id; it is never learned from a correction or applied to a ledger row;
 *
 * Reads and writes return the entity rows directly: the stored record is already the
 * ledger's unit of truth, and a separate model would duplicate it without adding a rule.
 */
class LedgerRepository(
    private val categoryDao: CategoryDao,
    private val transactionDao: TransactionDao,
    private val rememberedCategoryRuleDao: RememberedCategoryRuleDao,
    private val clock: LedgerClock = SystemLedgerClock,
) {

    // --- Categories ---------------------------------------------------------

    /** Adds a category and returns its stable id. */
    suspend fun addCategory(name: String): Long {
        val trimmedName = requireCategoryName(name)
        rejectDuplicateActiveName(trimmedName, excludeId = null)
        val now = clock.nowEpochMillis()
        return categoryDao.insert(
            CategoryEntity(
                name = trimmedName,
                archived = false,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ),
        )
    }

    /**
     * Renames a category. The id and the transactions pointing at it are untouched, so
     * historical attribution survives under the new name.
     */
    suspend fun renameCategory(categoryId: Long, name: String) {
        val category = requireCategory(categoryId)
        val trimmedName = requireCategoryName(name)
        rejectDuplicateActiveName(trimmedName, excludeId = categoryId)
        categoryDao.update(
            category.copy(
                name = trimmedName,
                updatedAtEpochMillis = clock.nowEpochMillis(),
            ),
        )
    }

    /**
     * Archives a category: it leaves the pool of assignable categories while the stored
     * transactions that point at it keep doing so.
     */
    suspend fun archiveCategory(categoryId: Long) {
        val category = requireCategory(categoryId)
        if (category.archived) {
            return
        }
        categoryDao.update(
            category.copy(
                archived = true,
                updatedAtEpochMillis = clock.nowEpochMillis(),
            ),
        )
    }

    /**
     * Permanently deletes a category, its own row included, whether or not records refer to
     * it. This is distinct from [archiveCategory]: archive keeps the category so past records
     * stay filed under it, while a delete cannot be undone and no placeholder row is left
     * behind.
     *
     * The removal runs as one database transaction that first clears every reference to the
     * category — stored transactions lose the link and become uncategorized, still-pending
     * drafts lose their pre-set choice — and removes the saved rules that named it, then
     * deletes the row. No ledger row is ever deleted, and every other stored value is left
     * untouched, so the month's totals do not move.
     *
     * @return what the removal changed, counted inside that transaction.
     */
    suspend fun deleteCategory(categoryId: Long): CategoryDeletionImpact {
        val category = requireCategory(categoryId)
        return categoryDao.deletePermanently(category)
    }

    /**
     * What deleting [categoryId] would change right now, for a confirmation that names the
     * real impact instead of guessing. Reading never writes, and [deleteCategory] returns the
     * counts it actually applied, so a change between the two is visible rather than hidden.
     */
    suspend fun categoryDeletionImpact(categoryId: Long): CategoryDeletionImpact {
        val category = requireCategory(categoryId)
        return CategoryDeletionImpact(
            uncategorizedTransactionCount = categoryDao.countReferencingTransactions(category.id),
            removedRuleCount = categoryDao.countReferencingRules(category.id),
            clearedPendingReviewCount = categoryDao.countReferencingPendingReviews(category.id),
        )
    }

    /** All categories, including archived ones. */
    suspend fun categories(): List<CategoryEntity> = categoryDao.findAll()

    /**
     * The category list as a live stream. The collection re-emits whenever a category
     * row changes, so the management screen never asks for a refresh and cannot show a
     * stale list after an add, rename, or archive.
     */
    fun observeCategories(): Flow<List<CategoryEntity>> = categoryDao.observeAll()

    /** Categories that can be attached to a new record: everything not archived. */
    suspend fun assignableCategories(): List<CategoryEntity> = categoryDao.findAssignable()

    suspend fun findCategory(categoryId: Long): CategoryEntity? = categoryDao.findById(categoryId)

    /**
     * Saves an explicit rule for this exact description. Matching ignores case and collapses
     * whitespace only; punctuation and other text remain part of the pattern.
     *
     * @return false when an active rule already exists for this exact pattern; it is never
     * silently replaced by a later category choice.
     */
    suspend fun rememberCategoryForDescription(description: String, categoryId: Long): Boolean {
        val patternKey = rememberedCategoryRuleKey(description)
            ?: throw LedgerValidationException("Enter a description before remembering a rule.")
        val category = categoryDao.findById(categoryId)
            ?: throw LedgerValidationException("Category $categoryId does not exist.")
        if (category.archived) {
            throw LedgerValidationException("Only an active category can be remembered.")
        }
        val remembered = rememberedCategoryRuleDao.rememberIfActive(
            RememberedCategoryRuleEntity(patternKey = patternKey, categoryId = categoryId),
        )
        if (!remembered && rememberedCategoryRuleDao.findActiveCategory(patternKey) == null) {
            throw LedgerValidationException("The category is no longer active.")
        }
        return remembered
    }

    /** Finds an exact saved rule only when its stable category id is still active. */
    suspend fun findRememberedCategoryForDescription(description: String): CategoryEntity? {
        val patternKey = rememberedCategoryRuleKey(description) ?: return null
        return rememberedCategoryRuleDao.findActiveCategory(patternKey)
    }

    /** Removes only the rule for this exact normalized description. */
    suspend fun removeRememberedCategoryRule(description: String): Boolean {
        val patternKey = rememberedCategoryRuleKey(description) ?: return false
        return rememberedCategoryRuleDao.remove(patternKey) > 0
    }

    // --- Transactions -------------------------------------------------------

    /** Adds a transaction and returns its stable id. */
    suspend fun addTransaction(input: TransactionInput): Long {
        validateFields(
            type = input.type,
            amountVnd = input.amountVnd,
            categoryId = input.categoryId,
            retainedCategoryId = null,
        )
        val now = clock.nowEpochMillis()
        return transactionDao.insert(input.toEntity(createdAt = now, updatedAt = now))
    }

    /**
     * Corrects a stored transaction in place. Only the fields the user can edit are replaced,
     * and [TransactionEntity.updatedAtEpochMillis] records when the correction happened; the
     * row keeps its id, its creation time, its confirmation, and its provenance, so a
     * correction never looks like a new record and never changes how the record came to exist.
     *
     * The category the record already carries may be kept after it has been archived — that
     * link is existing historical attribution — while a different archived category is still
     * refused, exactly as it is for a new record.
     */
    suspend fun editTransaction(transactionId: Long, correction: TransactionCorrection) {
        val stored = transactionDao.findById(transactionId)
            ?: throw LedgerValidationException("Transaction $transactionId does not exist.")
        validateFields(
            type = correction.type,
            amountVnd = correction.amountVnd,
            categoryId = correction.categoryId,
            retainedCategoryId = stored.categoryId,
        )
        transactionDao.update(
            stored.copy(
                type = correction.type,
                amountVnd = correction.amountVnd,
                occurredAtEpochMillis = correction.occurredAtEpochMillis,
                description = correction.description,
                categoryId = correction.categoryId,
                updatedAtEpochMillis = clock.nowEpochMillis(),
            ),
        )
    }

    /** Deletes a stored transaction. Deleting an unknown id is a no-op. */
    suspend fun deleteTransaction(transactionId: Long) {
        val stored = transactionDao.findById(transactionId) ?: return
        transactionDao.delete(stored)
    }

    /** Newest first, with the stored id as a stable tie-breaker. */
    suspend fun transactions(): List<TransactionEntity> = transactionDao.findAll()

    /**
     * The transaction list as a live stream. The collection re-emits in the same
     * newest-first order whenever a transaction row changes, so the browsing list never
     * asks for a refresh and cannot show a stale list after a create, correction, or
     * deletion. Filters narrow what is shown; they never write.
     */
    fun observeTransactions(): Flow<List<TransactionEntity>> = transactionDao.observeAll()

    suspend fun findTransaction(transactionId: Long): TransactionEntity? =
        transactionDao.findById(transactionId)

    // --- Validation ---------------------------------------------------------

    /**
     * Checks the user-editable fields of a write before anything reaches the database.
     *
     * [retainedCategoryId] is the category the stored record already carries. A correction
     * that keeps it is allowed to keep an archived category, because the record's link to it
     * already exists; anything else must be unarchived and must exist.
     */
    private suspend fun validateFields(
        type: TransactionType,
        amountVnd: Long,
        categoryId: Long?,
        retainedCategoryId: Long?,
    ) {
        VndMoney.requirePositiveAmount(amountVnd)
        if (categoryId == null) return
        if (type == TransactionType.TRANSFER) {
            throw LedgerValidationException(
                "An internal transfer moves money between the user's own holdings and " +
                    "carries no category.",
            )
        }
        val category = categoryDao.findById(categoryId)
            ?: throw LedgerValidationException("Category $categoryId does not exist.")
        if (category.archived && categoryId != retainedCategoryId) {
            throw LedgerValidationException(
                "Category \"${category.name}\" is archived and cannot be attached to a " +
                    "new record.",
            )
        }
    }

    private fun requireCategoryName(name: String): String {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            throw LedgerValidationException("A category needs a name.")
        }
        return trimmedName
    }

    private suspend fun rejectDuplicateActiveName(name: String, excludeId: Long?) {
        val activeId = categoryDao.findActiveIdByName(name) ?: return
        if (activeId != excludeId) {
            throw LedgerValidationException(
                "Another active category is already called \"$name\".",
            )
        }
    }

    private suspend fun requireCategory(categoryId: Long): CategoryEntity =
        categoryDao.findById(categoryId)
            ?: throw LedgerValidationException("Category $categoryId does not exist.")

    private fun TransactionInput.toEntity(createdAt: Long, updatedAt: Long) = TransactionEntity(
        type = type,
        amountVnd = amountVnd,
        occurredAtEpochMillis = occurredAtEpochMillis,
        description = description,
        categoryId = categoryId,
        confirmation = confirmation,
        source = source,
        createdAtEpochMillis = createdAt,
        updatedAtEpochMillis = updatedAt,
    )

}
