package io.github.triet4p.aegisf.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes [TransactionEntity] rows.
 *
 * Every read and write is ordered by id and never rewrites an existing id: a correction
 * updates the same row, so the record's identity and creation time are preserved.
 */
@Dao
interface TransactionDao {

    @Insert
    suspend fun insert(transaction: TransactionEntity): Long

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Delete
    suspend fun delete(transaction: TransactionEntity)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun findById(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions ORDER BY occurredAtEpochMillis DESC, id DESC")
    suspend fun findAll(): List<TransactionEntity>

    /**
     * The same rows as [findAll], but as a live stream: every insert, update, or delete on
     * the table re-emits the current rows in the same newest-first order. Screens collect
     * this instead of re-querying, so a correction, a deletion, or a new record made
     * anywhere becomes visible on its own.
     */
    @Query("SELECT * FROM transactions ORDER BY occurredAtEpochMillis DESC, id DESC")
    fun observeAll(): Flow<List<TransactionEntity>>
}
