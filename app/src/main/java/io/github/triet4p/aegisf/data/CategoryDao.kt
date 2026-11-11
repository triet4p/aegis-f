package io.github.triet4p.aegisf.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * The one statement every bounded category read uses.
 *
 * The paged read and the live paged read share it, so the rows a page holds and the rows
 * the live stream holds can never be read by two different filters.
 */
private const val CATEGORY_PAGE_SQL =
    "SELECT * FROM categories WHERE (:archived IS NULL OR archived = :archived) " +
        "ORDER BY name COLLATE NOCASE ASC, id ASC LIMIT :limit OFFSET :offset"

/**
 * Reads and writes [CategoryEntity] rows, and owns the one transaction that removes a
 * category for good.
 *
 * Insert returns the generated stable id; Room uses `AUTOINCREMENT`, so an id is not
 * handed out again after a row is deleted and a correction can never re-point existing
 * references at a different category. That is also what makes a deleted id permanently
 * invalid for a pending suggestion: the id can never come back as some other category.
 *
 * [deleteIfUnreferenced] is the only write here that spans tables. It refuses to remove a
 * category while any stored transaction, exact-description rule or pending draft still
 * references it; the reference counts and the delete are one transaction, so a reference
 * created after a screen read its impact cannot slip between the check and the delete.
 * Archive is the supported path that keeps history for a referenced category. When a delete
 * is allowed, the category has no referencing rows, so no ledger or draft data is rewritten.
 */
@Dao
abstract class CategoryDao {

    @Insert
    abstract suspend fun insert(category: CategoryEntity): Long

    @Update
    abstract suspend fun update(category: CategoryEntity)

    @Query("SELECT * FROM categories WHERE id = :id")
    abstract suspend fun findById(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories ORDER BY name COLLATE NOCASE ASC")
    abstract suspend fun findAll(): List<CategoryEntity>


    /**
     * A bounded page of the stored categories.
     *
     * [archived] selects the state: null reads every category, true reads the archived
     * ones, false the active ones. The order is the one every category read uses — the
     * name without case, then the stable id — so a page boundary never falls between two
     * rows that compare equal, and the same page of the same data is always the same rows.
     */
    @Query(CATEGORY_PAGE_SQL)
    abstract suspend fun findPage(
        archived: Boolean?,
        limit: Int,
        offset: Int,
    ): List<CategoryEntity>

    /**
     * The same bounded page as [findPage], as a live stream: every insert, update, or
     * delete on the table re-emits the current page in the same order.
     */
    @Query(CATEGORY_PAGE_SQL)
    abstract fun observePage(
        archived: Boolean?,
        limit: Int,
        offset: Int,
    ): Flow<List<CategoryEntity>>

    /**
     * The stored category with the given id, as a live stream.
     *
     * The read answers for one category by its stable id, so a screen that shows the
     * category a record is filed under keeps showing it even when the category is not on
     * the page the screen happens to be showing.
     */
    @Query("SELECT * FROM categories WHERE id = :id")
    abstract fun observeById(id: Long): Flow<CategoryEntity?>

    /**
     * How many stored categories there are, and how many of them are archived.
     *
     * The counts describe the whole catalog and not one page of it, so a screen that
     * pages through the catalog can still say how many categories exist.
     */
    @Query(
        "SELECT COUNT(*) AS total, " +
            "COUNT(*) - COUNT(CASE WHEN archived = 0 THEN 1 END) AS archived FROM categories",
    )
    abstract fun observeCatalogCounts(): Flow<CategoryCatalogCounts>

    /**
     * A live signal that the stored categories changed.
     *
     * The signal is the number of stored categories together with the sum of their own
     * revision times: an added or removed category changes the count, and a rename or an
     * archive changes the revision sum even though it changes neither the count nor the
     * number of rows. It is one row, so it costs one row however many categories exist.
     */
    @Query(
        "SELECT COUNT(*) || ':' || COALESCE(SUM(updatedAtEpochMillis), 0) FROM categories",
    )
    abstract fun observeCatalogRevision(): Flow<String>

    /** The stored categories with the given ids, for resolving the names a breakdown shows. */
    @Query("SELECT * FROM categories WHERE id IN (:ids)")
    abstract suspend fun findByIds(ids: List<Long>): List<CategoryEntity>

    /**
     * The stored categories with the given ids, as a live stream.
     *
     * The same read as [findByIds], live: a renamed or archived category re-emits the rows
     * an aggregate names, so a breakdown follows the category it is filed under.
     */
    @Query("SELECT * FROM categories WHERE id IN (:ids)")
    abstract fun observeByIds(ids: List<Long>): Flow<List<CategoryEntity>>

    /**
     * The id of the active category carrying [name], matched case-insensitively so that
     * "food" and "Food" cannot both be active names the user has to choose between.
     * Null when no active category has that name.
     */
    @Query("SELECT id FROM categories WHERE name = :name COLLATE NOCASE AND archived = 0 LIMIT 1")
    abstract suspend fun findActiveIdByName(name: String): Long?

    /** How many stored transactions are filed under this category. */
    @Query("SELECT COUNT(*) FROM transactions WHERE categoryId = :categoryId")
    abstract suspend fun countReferencingTransactions(categoryId: Long): Int

    /** How many saved exact-description rules name this category. */
    @Query("SELECT COUNT(*) FROM remembered_category_rules WHERE categoryId = :categoryId")
    abstract suspend fun countReferencingRules(categoryId: Long): Int

    /** How many still-pending bank drafts are pre-set to this category. */
    @Query(
        "SELECT COUNT(*) FROM pending_bank_drafts " +
            "WHERE reviewStatus = 'PENDING' AND reviewCategoryId = :categoryId",
    )
    abstract suspend fun countReferencingPendingReviews(categoryId: Long): Int

    @Query("DELETE FROM categories WHERE id = :id")
    protected abstract suspend fun deleteRow(id: Long): Int

    /**
     * Removes [category] for good only when nothing stored references it.
     *
     * The three reference counts and the delete are one database transaction: a transaction,
     * rule or pending draft that appears after the screen read its impact cannot slip in
     * between the check and the delete. When anything references the category, the row and
     * every reference stay exactly as they are and the impact is returned for the caller to
     * present. Archive is the supported way to keep history for a referenced category.
     */
    @Transaction
    open suspend fun deleteIfUnreferenced(category: CategoryEntity): CategoryDeletionResult {
        val impact = CategoryDeletionImpact(
            uncategorizedTransactionCount = countReferencingTransactions(category.id),
            removedRuleCount = countReferencingRules(category.id),
            clearedPendingReviewCount = countReferencingPendingReviews(category.id),
        )
        if (impact != CategoryDeletionImpact(0, 0, 0)) {
            return CategoryDeletionResult.Blocked(impact)
        }
        return if (deleteRow(category.id) == 1) {
            CategoryDeletionResult.Deleted(category.id)
        } else {
            CategoryDeletionResult.NotFound
        }
    }
}

/**
 * What one permanent-delete request did.
 *
 * [Blocked] is not a failure to report as a generic error: it carries the exact references
 * that keep the category in place, so the screen can say what to archive for or remove.
 */
sealed interface CategoryDeletionResult {
    data class Deleted(val categoryId: Long) : CategoryDeletionResult
    data class Blocked(val impact: CategoryDeletionImpact) : CategoryDeletionResult
    data object NotFound : CategoryDeletionResult
}
