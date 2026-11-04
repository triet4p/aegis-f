package io.github.triet4p.aegisf.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** The durable, bounded work queue for production arrival reconciliation. */
@Dao
abstract class ArrivalReconciliationOperationDao {
    /** A repeated event/delivery identity coalesces to the operation already stored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIfAbsent(operation: ArrivalReconciliationOperationEntity): Long

    /** Pending operations in stable creation order, with a hard read bound. */
    @Query(
        "SELECT * FROM arrival_reconciliation_operations WHERE state = 'PENDING' " +
            "ORDER BY createdAtEpochMillis, operationId LIMIT :limit",
    )
    abstract suspend fun findPending(limit: Int): List<ArrivalReconciliationOperationEntity>

    @Query(
        "SELECT * FROM arrival_reconciliation_operations WHERE operationId = :operationId LIMIT 1",
    )
    abstract suspend fun findById(operationId: String): ArrivalReconciliationOperationEntity?

    /** Commits one event-page cursor only while the same draft operation remains pending. */
    @Query(
        "UPDATE arrival_reconciliation_operations SET cursorEventId = :nextEventId, " +
            "linkedAny = :linkedAny, incompleteAny = :incompleteAny " +
            "WHERE operationId = :operationId AND state = 'PENDING' " +
            "AND cursorEventId = :expectedCursorEventId",
    )
    abstract suspend fun advanceDraftCursor(
        operationId: String,
        expectedCursorEventId: Long,
        nextEventId: Long,
        linkedAny: Boolean,
        incompleteAny: Boolean,
    ): Int

    /** Marks one operation complete in the same transaction as its last durable effects. */
    @Query(
        "UPDATE arrival_reconciliation_operations SET state = 'COMPLETE', outcome = :outcome, " +
            "linkedAny = :linkedAny, incompleteAny = :incompleteAny " +
            "WHERE operationId = :operationId AND state = 'PENDING'",
    )
    abstract suspend fun complete(
        operationId: String,
        outcome: String,
        linkedAny: Boolean,
        incompleteAny: Boolean,
    ): Int

    /** A narrow read for lifecycle evidence and replay assertions. */
    @Query(
        "SELECT COUNT(*) FROM arrival_reconciliation_operations WHERE operationId = :operationId",
    )
    abstract suspend fun countByOperationId(operationId: String): Int
}
