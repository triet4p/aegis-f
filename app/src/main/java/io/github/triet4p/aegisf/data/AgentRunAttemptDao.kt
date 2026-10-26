package io.github.triet4p.aegisf.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Reads and writes the durable per-attempt records of agent runs.
 *
 * One attempt row names one bounded try of a run: the trusted operation identity it
 * dispatched under, the allowed effect references it verified, the privacy-safe
 * observation it left behind, and the reason it ended. A repeated delivery of one
 * attempt replays the row instead of running again; a new attempt of the same run
 * inserts a new row under a new key. Every transition of a running attempt is
 * conditional on the attempt's own owner token, so a late provider reply, a reopened
 * conversation, and a cancelled-then-retried run cannot write stale state over the
 * run's current attempt.
 */
@Dao
abstract class AgentRunAttemptDao {

    /** The attempt row of one trusted attempt identity, or null. */
    @Query("SELECT * FROM `agent_run_attempts` WHERE `idempotencyKey` = :idempotencyKey LIMIT 1")
    abstract suspend fun findByKey(idempotencyKey: String): AgentRunAttemptEntity?

    /** Every attempt of one run in attempt order; the durable retry history. */
    @Query(
        "SELECT * FROM `agent_run_attempts` WHERE `runId` = :runId " +
            "ORDER BY `attempt` ASC, `id` ASC",
    )
    abstract suspend fun findForRun(runId: String): List<AgentRunAttemptEntity>

    /** Records one attempt; a repeated delivery under the same key keeps the first row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(attempt: AgentRunAttemptEntity): Long

    /** Settles a running attempt with its durable end state. */
    @Query(
        "UPDATE `agent_run_attempts` SET `state` = :state, `stopReason` = :stopReason, " +
            "`effectKind` = :effectKind, `eventId` = :eventId, `eventRevision` = :eventRevision, " +
            "`linkId` = :linkId, `linkState` = :linkState, `linkRevision` = :linkRevision, " +
            "`draftId` = :draftId, `decisionSource` = :decisionSource, " +
            "`observationJson` = :observationJson, `ownerToken` = NULL, " +
            "`updatedAtEpochMillis` = :now " +
            "WHERE `id` = :id AND `ownerToken` = :ownerToken AND `state` = 'PENDING'",
    )
    abstract suspend fun settle(
        id: Long,
        ownerToken: String,
        state: String,
        stopReason: String?,
        effectKind: String?,
        eventId: Long?,
        eventRevision: Int?,
        linkId: Long?,
        linkState: String?,
        linkRevision: Int?,
        draftId: Long?,
        decisionSource: String?,
        observationJson: String,
        now: Long,
    ): Int

    /** Cancels a running attempt without an outcome. */
    @Query(
        "UPDATE `agent_run_attempts` SET `state` = 'CANCELLED', " +
            "`stopReason` = :stopReason, `ownerToken` = NULL, " +
            "`updatedAtEpochMillis` = :now " +
            "WHERE `id` = :id AND `ownerToken` = :ownerToken AND `state` = 'PENDING'",
    )
    abstract suspend fun cancel(id: Long, ownerToken: String, stopReason: String?, now: Long): Int

    /** Takes over an interrupted pending attempt after its process died. */
    @Query(
        "UPDATE `agent_run_attempts` SET `ownerToken` = :ownerToken, " +
            "`updatedAtEpochMillis` = :now " +
            "WHERE `id` = :id AND `ownerToken` = :expectedOwnerToken AND `state` = 'PENDING'",
    )
    abstract suspend fun takeOverClaim(
        id: Long,
        expectedOwnerToken: String,
        ownerToken: String,
        now: Long,
    ): Int

    /** How many attempts one session's history holds; the retention bound prunes it. */
    @Query(
        "SELECT COUNT(*) FROM `agent_run_attempts` WHERE `runId` IN " +
            "(SELECT `runId` FROM `agent_runs` WHERE `scopeId` = :scopeId)",
    )
    abstract suspend fun countOfScope(scopeId: String): Long
}
