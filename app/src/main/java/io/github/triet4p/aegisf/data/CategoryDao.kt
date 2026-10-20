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
 * [deletePermanently] is the only write here that spans tables, because removing a
 * category is exactly the event that changes what those tables point at. It never deletes
 * a ledger row: a transaction filed under the category keeps its amount, type, moment,
 * description, confirmation, and provenance, and only loses the category link. The rules
 * that named the category go with it through the `ON DELETE CASCADE` declared on
 * [RememberedCategoryRuleEntity], which is enforced by the database (`PRAGMA
 * foreign_keys = ON`), while the stored transactions' own foreign key is `RESTRICT` and
 * therefore never fires: the links are cleared first, inside the same transaction.
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

    /**
     * Clears the category link on every stored transaction filed under it, leaving every
     * other stored field — amount, type, moment, description, confirmation, provenance,
     * creation and revision time — exactly as it was.
     */
    @Query(
        "UPDATE transactions SET categoryId = NULL WHERE categoryId = :categoryId",
    )
    protected abstract suspend fun detachTransactions(categoryId: Long): Int

    /**
     * Undoes a still-pending draft's pre-set category: the id it named no longer exists, so
     * the choice cannot stand and the reviewer is asked again. The revision is advanced in
     * the same statement, so an editor holding the earlier revision is told the draft changed
     * instead of writing the removed id back.
     */
    @Query(
        "UPDATE pending_bank_drafts SET reviewCategoryId = NULL, reviewCategoryResolved = 0, " +
            "reviewRevision = reviewRevision + 1 " +
            "WHERE reviewStatus = 'PENDING' AND reviewCategoryId = :categoryId",
    )
    protected abstract suspend fun detachPendingReviews(categoryId: Long): Int

    @Query("DELETE FROM categories WHERE id = :id")
    protected abstract suspend fun deleteRow(id: Long): Int

    /**
     * Removes [category] for good and reports what the removal changed, all in one database
     * transaction: either every link below is cleared and the row is gone, or nothing is.
     * Rules that named the category are removed by the declared cascade on the delete itself.
     */
    @Transaction
    open suspend fun deletePermanently(category: CategoryEntity): CategoryDeletionImpact {
        val uncategorizedTransactions = detachTransactions(category.id)
        val clearedPendingReviews = detachPendingReviews(category.id)
        val removedRules = countReferencingRules(category.id)
        check(deleteRow(category.id) == 1) {
            "Category ${category.id} was already removed before its deletion committed."
        }
        return CategoryDeletionImpact(
            uncategorizedTransactionCount = uncategorizedTransactions,
            removedRuleCount = removedRules,
            clearedPendingReviewCount = clearedPendingReviews,
        )
    }
}
