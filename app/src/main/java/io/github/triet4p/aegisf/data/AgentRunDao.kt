package io.github.triet4p.aegisf.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Reads and writes the durable agent run rows.
 *
 * The run row is the durable identity of one user-visible conversation turn. Every
 * transition of a running run is conditional on the attempt's own claim token, so two
 * openers of one conversation, a late reply, and a cancelled-then-retried run cannot
 * both move the same row. A reopened conversation claims the newest attempt only when
 * the previous attempt really finished; an interrupted attempt keeps the run out of a
 * terminal state until it is reconciled.
 */
@Dao
abstract class AgentRunDao {

    /** The run row of one run identity, or null when it was never admitted. */
    @Query("SELECT * FROM `agent_runs` WHERE `runId` = :runId LIMIT 1")
    abstract suspend fun findByRunId(runId: String): AgentRunEntity?

    /** Admits one run; a retried admission keeps the row the first attempt wrote. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(run: AgentRunEntity): Long

    /** Claims an idle run for one attempt. */
    @Query(
        "UPDATE `agent_runs` SET `state` = 'RUNNING', `attempt` = :attempt, " +
            "`stopReason` = NULL, `updatedAtEpochMillis` = :now " +
            "WHERE `runId` = :runId AND `state` = 'PENDING'",
    )
    abstract suspend fun claimPending(runId: String, attempt: Int, now: Long): Int

    /** Moves a running run into clarification while the run waits for the user. */
    @Query(
        "UPDATE `agent_runs` SET `state` = 'CLARIFYING', " +
            "`stopReason` = :stopReason, `updatedAtEpochMillis` = :now " +
            "WHERE `runId` = :runId AND `state` = 'RUNNING'",
    )
    abstract suspend fun markClarifying(runId: String, stopReason: String?, now: Long): Int

    /** Resumes a clarifying run for its next attempt. */
    @Query(
        "UPDATE `agent_runs` SET `state` = 'RUNNING', `attempt` = :attempt, " +
            "`stopReason` = NULL, `revisionEpochMillis` = :revisionEpochMillis, " +
            "`updatedAtEpochMillis` = :now " +
            "WHERE `runId` = :runId AND `state` = 'CLARIFYING'",
    )
    abstract suspend fun resumeClarifying(
        runId: String,
        attempt: Int,
        revisionEpochMillis: Long,
        now: Long,
    ): Int

    /** Reopens one failed run for its next bounded attempt. */
    @Query(
        "UPDATE `agent_runs` SET `state` = 'RUNNING', `attempt` = :attempt, " +
            "`stopReason` = NULL, `revisionEpochMillis` = :revisionEpochMillis, " +
            "`updatedAtEpochMillis` = :now " +
            "WHERE `runId` = :runId AND `state` = 'FAILED'",
    )
    abstract suspend fun reopenForRetry(
        runId: String,
        attempt: Int,
        revisionEpochMillis: Long,
        now: Long,
    ): Int

    /** Records the durable terminal state of a finished run. */
    @Query(
        "UPDATE `agent_runs` SET `state` = :state, `stopReason` = :stopReason, " +
            "`answerDigest` = :answerDigest, `answerText` = :answerText, " +
            "`updatedAtEpochMillis` = :now " +
            "WHERE `runId` = :runId AND `state` IN ('RUNNING', 'CLARIFYING')",
    )
    abstract suspend fun finish(
        runId: String,
        state: String,
        stopReason: String?,
        answerDigest: String?,
        answerText: String?,
        now: Long,
    ): Int

    /** Cancels one run without claiming any effect. */
    @Query(
        "UPDATE `agent_runs` SET `state` = 'CANCELLED', " +
            "`stopReason` = :stopReason, `updatedAtEpochMillis` = :now " +
            "WHERE `runId` = :runId AND `state` IN ('PENDING', 'RUNNING', 'CLARIFYING', 'FAILED')",
    )
    abstract suspend fun cancel(runId: String, stopReason: String?, now: Long): Int

    /** A bounded page of one session's runs, newest first. */
    @Query(
        "SELECT * FROM `agent_runs` WHERE `scopeId` = :scopeId " +
            "ORDER BY `createdAtEpochMillis` DESC, `id` DESC LIMIT :limit OFFSET :offset",
    )
    abstract suspend fun findPage(scopeId: String, limit: Int, offset: Int): List<AgentRunEntity>

    /** How many runs one session holds; the retention bound prunes above this count. */
    @Query("SELECT COUNT(*) FROM `agent_runs` WHERE `scopeId` = :scopeId")
    abstract suspend fun countOf(scopeId: String): Long

    /** The oldest run ids of one session, for bounded retention pruning. */
    @Query(
        "SELECT `id` FROM `agent_runs` WHERE `scopeId` = :scopeId " +
            "ORDER BY `createdAtEpochMillis` ASC, `id` ASC LIMIT :limit",
    )
    abstract suspend fun oldestIds(scopeId: String, limit: Int): List<Long>

    /** Deletes the runs (and, by cascade, their attempts) named by stored id. */
    @Query("DELETE FROM `agent_runs` WHERE `id` IN (:ids)")
    abstract suspend fun deleteByIds(ids: List<Long>): Int

    /** Deletes every run of one session; business stores stay untouched. */
    @Query("DELETE FROM `agent_runs` WHERE `scopeId` = :scopeId")
    abstract suspend fun deleteScope(scopeId: String): Int
}
