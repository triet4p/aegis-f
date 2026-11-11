package io.github.triet4p.aegisf.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * One saved exact-description rule with the category it names.
 *
 * The canonical description is the stored key: matching ignores case and collapses whitespace
 * only, so the key is what the rule really matches. The category name and archived state are
 * read with the rule, so a rule whose category became archived is still shown truthfully
 * instead of disappearing from the list.
 */
data class CategoryRule(
    val patternKey: String,
    val categoryId: Long,
    val categoryName: String,
    val categoryArchived: Boolean,
)

/** Storage operations for the user's deliberately remembered exact-description rules. */
@Dao
abstract class RememberedCategoryRuleDao {

    @Query(
        "SELECT categories.* FROM remembered_category_rules " +
            "INNER JOIN categories ON categories.id = remembered_category_rules.categoryId " +
            "WHERE remembered_category_rules.patternKey = :patternKey " +
            "AND categories.archived = 0 LIMIT 1",
    )
    abstract suspend fun findActiveCategory(patternKey: String): CategoryEntity?

    /**
     * Every stored rule, ordered by its canonical description.
     *
     * The rules are read as a whole so a prediction can record which rule state it was made
     * against: a rule that was added or removed changes what the pre-inference lookup
     * answers, and therefore changes whether a stored prediction still applies.
     */
    @Query("SELECT * FROM remembered_category_rules ORDER BY patternKey")
    abstract suspend fun findAll(): List<RememberedCategoryRuleEntity>

    /**
     * One bounded page of the saved rules with the category each one names.
     *
     * The order is the canonical description, then the stable id, so a renamed category keeps
     * the rule in the same place. A rule is never dropped because its category is archived: it
     * is still stored and still shown, with that state, until the user changes or removes it.
     */
    @Query(
        "SELECT rules.patternKey AS patternKey, rules.categoryId AS categoryId, " +
            "categories.name AS categoryName, categories.archived AS categoryArchived " +
            "FROM remembered_category_rules AS rules " +
            "INNER JOIN categories ON categories.id = rules.categoryId " +
            "ORDER BY rules.patternKey, rules.categoryId LIMIT :limit OFFSET :offset",
    )
    abstract suspend fun findPage(limit: Int, offset: Int): List<CategoryRule>

    /** The same bounded page as [findPage], as a live stream. */
    @Query(
        "SELECT rules.patternKey AS patternKey, rules.categoryId AS categoryId, " +
            "categories.name AS categoryName, categories.archived AS categoryArchived " +
            "FROM remembered_category_rules AS rules " +
            "INNER JOIN categories ON categories.id = rules.categoryId " +
            "ORDER BY rules.patternKey, rules.categoryId LIMIT :limit OFFSET :offset",
    )
    abstract fun observePage(limit: Int, offset: Int): Flow<List<CategoryRule>>

    /** How many saved rules there are in the whole catalog. */
    @Query("SELECT COUNT(*) FROM remembered_category_rules")
    abstract suspend fun count(): Int

    /** The same count as [count], as a live stream. */
    @Query("SELECT COUNT(*) FROM remembered_category_rules")
    abstract fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM categories WHERE id = :categoryId AND archived = 0")
    protected abstract suspend fun countActiveCategory(categoryId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIfAbsent(rule: RememberedCategoryRuleEntity): Long

    @Transaction
    open suspend fun rememberIfActive(rule: RememberedCategoryRuleEntity): Boolean {
        if (countActiveCategory(rule.categoryId) == 0) return false
        if (findActiveCategory(rule.patternKey) != null) return false
        remove(rule.patternKey)
        return insertIfAbsent(rule) != -1L
    }

    @Query("DELETE FROM remembered_category_rules WHERE patternKey = :patternKey")
    abstract suspend fun remove(patternKey: String): Int
}
