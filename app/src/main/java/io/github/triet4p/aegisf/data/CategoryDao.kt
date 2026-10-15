package io.github.triet4p.aegisf.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes [CategoryEntity] rows.
 *
 * Insert returns the generated stable id; Room uses `AUTOINCREMENT`, so an id is not
 * handed out again after a row is deleted and a correction can never re-point existing
 * references at a different category.
 */
@Dao
interface CategoryDao {

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    @Delete
    suspend fun delete(category: CategoryEntity)

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun findById(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories ORDER BY name COLLATE NOCASE ASC")
    suspend fun findAll(): List<CategoryEntity>

    /**
     * The same list as [findAll], but as a live stream: every insert, update, or delete on
     * the table re-emits the current rows. Screens collect this instead of re-querying,
     * so a category added, renamed, or archived anywhere becomes visible on its own.
     */
    @Query("SELECT * FROM categories ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE archived = 0 ORDER BY name COLLATE NOCASE ASC")
    suspend fun findAssignable(): List<CategoryEntity>

    /**
     * The id of the active category carrying [name], matched case-insensitively so that
     * "food" and "Food" cannot both be active names the user has to choose between.
     * Null when no active category has that name.
     */
    @Query("SELECT id FROM categories WHERE name = :name COLLATE NOCASE AND archived = 0 LIMIT 1")
    suspend fun findActiveIdByName(name: String): Long?

    /** How many stored transactions refer to this category. */
    @Query("SELECT COUNT(*) FROM transactions WHERE categoryId = :categoryId")
    suspend fun countReferencingTransactions(categoryId: Long): Int
}
