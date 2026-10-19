package io.github.triet4p.aegisf.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes [TransactionEntity] rows.
 *
 * Every read and write is ordered by id and never rewrites an existing id: a correction
 * updates the same row, so the record's identity and creation time are preserved. The
 * cross-DAO write methods also keep a migrated legacy-description annotation aligned with
 * an explicit correction or clear it when the user explicitly deletes its ledger record.
 */
@Dao
abstract class TransactionDao {

    @Insert
    abstract suspend fun insert(transaction: TransactionEntity): Long

    @Update
    abstract suspend fun update(transaction: TransactionEntity)

    @Delete
    abstract suspend fun delete(transaction: TransactionEntity)

    @Query("SELECT * FROM transactions WHERE id = :id")
    abstract suspend fun findById(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions ORDER BY occurredAtEpochMillis DESC, id DESC")
    abstract suspend fun findAll(): List<TransactionEntity>

    /**
     * Updates the ledger row and its migrated legacy description together. Newer user
     * annotations have their own identity and are not rewritten by this source-copy path.
     */
    @Transaction
    open suspend fun updateWithLegacyDescription(
        transaction: TransactionEntity,
        captureMessageDao: CaptureMessageDao,
    ) {
        update(transaction)
        captureMessageDao.reviseLegacyLedgerDescription(
            ledgerRecordOriginRef(transaction.id),
            transaction.description,
        )
    }

    /** Clears the migrated source copy atomically with the user's explicit ledger deletion. */
    @Transaction
    open suspend fun deleteWithLegacyDescription(
        transaction: TransactionEntity,
        captureMessageDao: CaptureMessageDao,
    ) {
        captureMessageDao.tombstoneLegacyLedgerDescription(
            ledgerRecordOriginRef(transaction.id),
        )
        delete(transaction)
    }

    /**
     * The same rows as [findAll], but as a live stream: every insert, update, or delete on
     * the table re-emits the current rows in the same newest-first order. Screens collect
     * this instead of re-querying, so a correction, a deletion, or a new record made
     * anywhere becomes visible on its own.
     */
    @Query("SELECT * FROM transactions ORDER BY occurredAtEpochMillis DESC, id DESC")
    abstract fun observeAll(): Flow<List<TransactionEntity>>
}
