package io.github.triet4p.aegisf.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Appends and reads the local production trace events of agent runs.
 *
 * The table is append-only: a trace row describes evidence and is never updated or used as
 * authority. Reads are ordered by the event identity, which is the append order, so a run's
 * provider exchanges, tool calls and outcomes reconstruct in the order they actually happened.
 * Deleting a run (retention or an explicit history delete) cascades its trace rows.
 */
@Dao
abstract class AgentTraceDao {

    /** Appends one event; the returned id is its ordered event identity. */
    @Insert
    abstract suspend fun insert(event: AgentTraceEventEntity): Long

    /** Every recorded event of one run, oldest first. */
    @Query("SELECT * FROM `agent_trace_events` WHERE `runId` = :runId ORDER BY `id` ASC")
    abstract suspend fun findForRun(runId: String): List<AgentTraceEventEntity>

    /** Every recorded event of one attempt of a run, oldest first. */
    @Query(
        "SELECT * FROM `agent_trace_events` WHERE `runId` = :runId AND `attempt` = :attempt " +
            "ORDER BY `id` ASC",
    )
    abstract suspend fun findForAttempt(runId: String, attempt: Int): List<AgentTraceEventEntity>

    /** How many events one run carries, so a reader can tell a gap from a real trace. */
    @Query("SELECT COUNT(*) FROM `agent_trace_events` WHERE `runId` = :runId")
    abstract suspend fun countForRun(runId: String): Long
}
