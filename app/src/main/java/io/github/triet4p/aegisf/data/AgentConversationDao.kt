package io.github.triet4p.aegisf.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Reads and writes the durable conversation-visible message rows.
 *
 * A message is one user-visible line of an agent run: the user's own request words in
 * the roles the user may re-read, the agent's answer or status line in the roles the
 * run produced. No credentials, no vault mappings, and no transient reasoning are
 * columns here and therefore cannot leave through this read.
 */
@Dao
abstract class AgentConversationDao {

    /** Records one user-visible message; a repeated delivery keeps the first row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(message: AgentConversationEntity): Long

    /** Every message of one run in display order; the durable visible conversation. */
    @Query(
        "SELECT * FROM `agent_conversation` WHERE `runId` = :runId " +
            "ORDER BY `sequence` ASC, `id` ASC",
    )
    abstract suspend fun findForRun(runId: String): List<AgentConversationEntity>

    /**
     * The export-safe conversation of one session: run identity, role, text, and time
     * only. Attempt keys, digests, and internal states are not columns of this read.
     */
    @Query(
        "SELECT * FROM `agent_conversation` WHERE `runId` IN " +
            "(SELECT `runId` FROM `agent_runs` WHERE `scopeId` = :scopeId) " +
            "ORDER BY `createdAtEpochMillis` ASC, `id` ASC LIMIT :limit OFFSET :offset",
    )
    abstract suspend fun findExportPage(
        scopeId: String,
        limit: Int,
        offset: Int,
    ): List<AgentConversationEntity>
}
