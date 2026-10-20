package io.github.triet4p.aegisf.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * The one statement every bounded ledger read uses.
 *
 * The paged read and the live paged read share it, so the rows a page holds and the rows
 * the live stream holds can never be read by two different filters.
 */
private const val LEDGER_PAGE_SQL =
    "SELECT * FROM transactions WHERE " +
        "(:type IS NULL OR type = :type) AND " +
        "(:confirmation IS NULL OR confirmation = :confirmation) AND " +
        "(:categoryId IS NULL OR categoryId = :categoryId) AND " +
        "(:uncategorizedOnly = 0 OR categoryId IS NULL) AND " +
        "(:fromEpochMillis IS NULL OR occurredAtEpochMillis >= :fromEpochMillis) AND " +
        "(:throughExclusiveEpochMillis IS NULL OR " +
        "occurredAtEpochMillis < :throughExclusiveEpochMillis) " +
        "ORDER BY occurredAtEpochMillis DESC, id DESC LIMIT :limit OFFSET :offset"

/**
 * The one statement the confirmed-range fold reads with.
 *
 * The fold walks the range in bounded chunks, each chunk starting after the last row of
 * the previous one, so a row is never skipped or counted twice and no read ever holds
 * more than one chunk of the range. The order is the instant the record happened, with
 * the stored id as the tie-breaker, which is the same total order the ledger read uses.
 */
private const val CONFIRMED_RANGE_CHUNK_SQL =
    "SELECT * FROM transactions WHERE confirmation = 'CONFIRMED' AND " +
        "occurredAtEpochMillis >= :fromEpochMillis AND " +
        "occurredAtEpochMillis < :throughExclusiveEpochMillis AND " +
        "(occurredAtEpochMillis > :afterOccurredAtEpochMillis OR " +
        "(occurredAtEpochMillis = :afterOccurredAtEpochMillis AND id > :afterId)) " +
        "ORDER BY occurredAtEpochMillis ASC, id ASC LIMIT :limit"

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

    /**
     * Every stored record, newest first, with the stored id as the tie-breaker.
     *
     * The whole-table read is a storage-level helper for the storage-level checks; the
     * reads the app and the tools use are the bounded ones below.
     */
    @Query("SELECT * FROM transactions ORDER BY occurredAtEpochMillis DESC, id DESC")
    abstract suspend fun findAll(): List<TransactionEntity>

    /**
     * A bounded page of the stored ledger records.
     *
     * Every dimension is an open dimension when it is null: the type, the confirmation
     * state, the category, and the two ends of the range. The category dimension is
     * [categoryId] together with [uncategorizedOnly], which are separate statements
     * because "no category" and "one named category" are different questions and one
     * nullable column cannot answer both.
     *
     * The order is the one every ledger read uses: the instant the record happened, newest
     * first, with the stored id as the tie-breaker, so two records that happened in the
     * same millisecond still have a fixed order and a page boundary never falls between
     * them unpredictably.
     */
    @Query(LEDGER_PAGE_SQL)
    abstract suspend fun findPage(
        type: String?,
        confirmation: String?,
        categoryId: Long?,
        uncategorizedOnly: Int,
        fromEpochMillis: Long?,
        throughExclusiveEpochMillis: Long?,
        limit: Int,
        offset: Int,
    ): List<TransactionEntity>

    /**
     * The same bounded page as [findPage], as a live stream: every insert, update, or
     * delete on the table re-emits the current page in the same order.
     */
    @Query(LEDGER_PAGE_SQL)
    abstract fun observePage(
        type: String?,
        confirmation: String?,
        categoryId: Long?,
        uncategorizedOnly: Int,
        fromEpochMillis: Long?,
        throughExclusiveEpochMillis: Long?,
        limit: Int,
        offset: Int,
    ): Flow<List<TransactionEntity>>

    /**
     * One bounded chunk of the confirmed records of a half-open range.
     *
     * The chunk starts after the row the cursor names, so the chunks of one fold cover
     * the range exactly once. The read is deliberately blocking: it is called inside
     * [foldConfirmedRange], on the thread that holds the transaction, so that every chunk
     * is read from the same snapshot.
     */
    @Query(CONFIRMED_RANGE_CHUNK_SQL)
    abstract fun findConfirmedChunk(
        fromEpochMillis: Long,
        throughExclusiveEpochMillis: Long,
        afterOccurredAtEpochMillis: Long,
        afterId: Long,
        limit: Int,
    ): List<TransactionEntity>

    /**
     * The exact figures one range's confirmed records make, folded in bounded chunks.
     *
     * Every chunk is read inside this one database transaction, so the whole fold sees a
     * single snapshot of the range: a record written while the figure is being built is
     * either in the figure or out of it, never half in it.
     *
     * The fold holds no record list. Each chunk is added to [fold] and forgotten, so the
     * memory the figure needs is the size of its own breakdown and not the number of
     * records the range holds.
     */
    @Transaction
    open fun foldConfirmedRange(chunkSize: Int, fold: LedgerRangeFold) {
        var afterOccurredAtEpochMillis = fold.fromEpochMillis
        var afterId = Long.MIN_VALUE
        while (true) {
            val chunk = findConfirmedChunk(
                fromEpochMillis = fold.fromEpochMillis,
                throughExclusiveEpochMillis = fold.throughExclusiveEpochMillis,
                afterOccurredAtEpochMillis = afterOccurredAtEpochMillis,
                afterId = afterId,
                limit = chunkSize,
            )
            if (chunk.isEmpty()) break
            chunk.forEach { fold.add(it) }
            val last = chunk.last()
            afterOccurredAtEpochMillis = last.occurredAtEpochMillis
            afterId = last.id
            if (chunk.size < chunkSize) break
        }
        fold.setExcludedDraftCount(
            countUnconfirmedInRange(fold.fromEpochMillis, fold.throughExclusiveEpochMillis),
        )
    }

    /** How many stored records of the range are not confirmed and are in no ledger figure. */
    @Query(
        "SELECT COUNT(*) FROM transactions WHERE confirmation = 'DRAFT' AND " +
            "occurredAtEpochMillis >= :fromEpochMillis AND " +
            "occurredAtEpochMillis < :throughExclusiveEpochMillis",
    )
    abstract fun countUnconfirmedInRange(
        fromEpochMillis: Long,
        throughExclusiveEpochMillis: Long,
    ): Int

    /**
     * A live signal that the range's records changed.
     *
     * The signal is the range's record count together with the sum of the records' own
     * revision times: an insert or a deletion changes the count, and a correction changes
     * the revision sum even though it changes neither the count nor the number of rows.
     * The figure is rebuilt from the range's own records whenever the signal moves, so a
     * correction is followed like a new record is. The signal is one row, so it costs one
     * row however many records the range holds.
     */
    @Query(
        "SELECT COUNT(*) || ':' || COALESCE(SUM(updatedAtEpochMillis), 0) FROM transactions WHERE " +
            "occurredAtEpochMillis >= :fromEpochMillis AND " +
            "occurredAtEpochMillis < :throughExclusiveEpochMillis",
    )
    abstract fun observeRangeRevision(
        fromEpochMillis: Long,
        throughExclusiveEpochMillis: Long,
    ): Flow<String>

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

}
