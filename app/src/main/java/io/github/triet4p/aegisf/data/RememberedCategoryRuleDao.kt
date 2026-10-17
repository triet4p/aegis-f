package io.github.triet4p.aegisf.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

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
