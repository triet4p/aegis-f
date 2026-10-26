package io.github.triet4p.aegisf.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One user-visible conversation line of an agent run.
 *
 * The sequence orders the durable conversation the user re-reads on reopen: the
 * request, the status lines, the clarification question, and the terminal answer.
 * Agent-role text is the masked-safe line the user actually saw (no raw literals, no
 * mapping that could rehydrate one); the write refuses raw agent text instead of
 * storing it. The user-role line is the user's own bounded request words. Neither
 * role carries credentials, vault mappings, protocol continuation items, transient
 * reasoning, or provider wire detail — none of those are columns and none leave
 * through the conversation read.
 */
@Entity(
    tableName = "agent_conversation",
    foreignKeys = [
        ForeignKey(
            entity = AgentRunEntity::class,
            parentColumns = ["runId"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["runId", "sequence"], unique = true),
        Index(value = ["runId"]),
    ],
)
data class AgentConversationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** The run this line belongs to; a foreign key of the durable run row. */
    val runId: String,
    /** The display order of the line inside its run; starts at 0. */
    val sequence: Int,
    /** Who the line is from; one of the `AGENT_CONVERSATION_ROLE_*` values. */
    val role: String,
    /**
     * The user-visible text of the line, already masked-safe for agent roles and
     * bounded for user roles. Never a credential, a mapping, or reasoning.
     */
    val text: String,
    val createdAtEpochMillis: Long,
)

/** The user's own request words in the durable conversation. */
internal const val AGENT_CONVERSATION_ROLE_USER = "USER"

/** A status line the run produced (started, retried, cancelled, clarified). */
internal const val AGENT_CONVERSATION_ROLE_STATUS = "STATUS"

/** The agent's terminal or clarification answer the user actually saw. */
internal const val AGENT_CONVERSATION_ROLE_AGENT = "AGENT"
