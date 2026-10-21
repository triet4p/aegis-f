package io.github.triet4p.aegisf.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Reads and writes the durable record of the preparation commands.
 *
 * The idempotency key is unique in the whole table, not per caller: one key names one
 * request everywhere, so a key that was used by one caller cannot be reused by another
 * to make a second request, and two callers that pick the same key at the same time
 * cannot both apply their request.
 */
@Dao
abstract class WorkflowOperationDao {

    /** Records one command; a repeated key is refused rather than duplicated. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(operation: WorkflowOperationEntity): Long

    /** The recorded command of one idempotency key, or null when the key was never used. */
    @Query("SELECT * FROM workflow_operations WHERE idempotencyKey = :idempotencyKey LIMIT 1")
    abstract suspend fun findByKey(idempotencyKey: String): WorkflowOperationEntity?

    /**
     * Takes over a claim whose attempt is gone.
     *
     * The claim moves only when the row still carries the token the caller observed and is
     * still pending, so two attempts that race for the same abandoned claim cannot both
     * believe they own it.
     */
    @Query(
        "UPDATE workflow_operations SET ownerToken = :ownerToken, " +
            "leaseExpiresAtEpochMillis = :leaseExpiresAtEpochMillis " +
            "WHERE id = :id AND ownerToken = :expectedOwnerToken AND outcome = :pendingOutcome",
    )
    abstract suspend fun takeOverClaim(
        id: Long,
        expectedOwnerToken: String,
        ownerToken: String,
        leaseExpiresAtEpochMillis: Long,
        pendingOutcome: String,
    ): Int

    /**
     * Extends the claim of a request that is still running.
     *
     * The claim moves only when the row still carries the caller's own token and is
     * still pending. An extension therefore never resurrects a claim that another
     * attempt took over: a taken-over claim carries that attempt's token, and this
     * update does not match it. A claim whose request already finished is not pending
     * either, and is never extended.
     *
     * The lease itself is not part of the condition, and that is deliberate. The lease
     * is the bound on a claim whose attempt is gone — a process that died leaves the
     * row pending until the lease ends, and a later attempt may then take it over. An
     * attempt that is still running is not gone, and an extension that arrives after
     * the lease ended is that attempt's own heartbeat, late; refusing it would lose a
     * claim that nobody took over, and would fail a request that was never in danger.
     * The proof that the attempt is still alive is the in-process guard, which the
     * command boundary consults before it lets any attempt take a claim over.
     */
    @Query(
        "UPDATE workflow_operations SET leaseExpiresAtEpochMillis = :leaseExpiresAtEpochMillis " +
            "WHERE id = :id AND ownerToken = :ownerToken AND outcome = :pendingOutcome",
    )
    abstract suspend fun renewClaim(
        id: Long,
        ownerToken: String,
        leaseExpiresAtEpochMillis: Long,
        pendingOutcome: String,
    ): Int

    /**
     * Finishes a claim, and with it the request it holds.
     *
     * The row moves only when it still carries the caller's own token and is still
     * pending, so an attempt whose claim was taken over can never write its result over
     * the attempt that took it.
     */
    @Query(
        "UPDATE workflow_operations SET outcome = :outcome, changeSummary = :changeSummary, " +
            "ownerToken = NULL, leaseExpiresAtEpochMillis = NULL " +
            "WHERE id = :id AND ownerToken = :ownerToken AND outcome = :pendingOutcome",
    )
    abstract suspend fun completeClaim(
        id: Long,
        ownerToken: String,
        outcome: String,
        changeSummary: String?,
        pendingOutcome: String,
    ): Int

    /**
     * Releases a claim without a result.
     *
     * A request that was refused, that failed, or that was cancelled leaves the key free
     * again, so a corrected request may use it. The row moves only when it still carries
     * the caller's own token.
     */
    @Query(
        "DELETE FROM workflow_operations WHERE id = :id AND ownerToken = :ownerToken " +
            "AND outcome = :pendingOutcome",
    )
    abstract suspend fun releaseClaim(
        id: Long,
        ownerToken: String,
        pendingOutcome: String,
    ): Int

    /**
     * A bounded page of the recorded commands.
     *
     * [command] narrows the read to one command family and is open when it is null. The
     * order is the stored id, which is the order the commands were recorded in.
     */
    @Query(
        "SELECT * FROM workflow_operations WHERE " +
            "(:command IS NULL OR command = :command) " +
            "ORDER BY id ASC LIMIT :limit OFFSET :offset",
    )
    abstract suspend fun findPage(
        command: String?,
        limit: Int,
        offset: Int,
    ): List<WorkflowOperationEntity>

    /** How many commands of one family were recorded. */
    @Query("SELECT COUNT(*) FROM workflow_operations WHERE (:command IS NULL OR command = :command)")
    abstract suspend fun countOf(command: String?): Long
}
