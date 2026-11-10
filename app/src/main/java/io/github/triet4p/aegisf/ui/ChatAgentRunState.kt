package io.github.triet4p.aegisf.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.triet4p.aegisf.agent.BoundedWorkflowExecutor
import io.github.triet4p.aegisf.agent.WorkflowIntent
import io.github.triet4p.aegisf.agent.WorkflowRunContinuation
import io.github.triet4p.aegisf.agent.WorkflowRunFollowUp
import io.github.triet4p.aegisf.agent.WorkflowRunOutcome
import io.github.triet4p.aegisf.agent.WorkflowRunRequest
import io.github.triet4p.aegisf.data.AgentConversationEntity
import io.github.triet4p.aegisf.data.AgentRunEntity
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.privacy.MaskedPromptBoundary
import kotlinx.coroutines.Job

/** A saved note the user explicitly attached to the chat, before and after sending. */
internal data class ChatNoteContext(
    val captureId: Long,
    val text: String,
) {
    /**
     * The request text actually sent: the attached note named first, then the user's own words.
     *
     * The note is part of the request only while this context is attached; removing it from the
     * chat leaves the next request exactly as the user typed it.
     */
    fun toRequestText(prompt: String): String = buildString {
        append(SAVED_NOTE_PREFIX)
        append("\"")
        append(text)
        append("\"\n\n")
        append(REQUEST_PREFIX)
        append(prompt)
    }

    private companion object {
        const val SAVED_NOTE_PREFIX = "Saved note context (attached by me):\n"
        const val REQUEST_PREFIX = "My request:\n"
    }
}

/**
 * The text one chat request carries.
 *
 * Without an attached note this is the user's own request, unchanged. With one, the same visible
 * text the chip shows is named before the request; removing the chip restores the typed request.
 */
internal fun chatRequestText(prompt: String, note: ChatNoteContext?): String =
    note?.toRequestText(prompt) ?: prompt

/** Where one visible assistant turn is in its lifecycle. */
internal enum class ChatAnswerState {
    WAITING,
    STREAMING,
    CLARIFYING,
    COMPLETE,
    STOPPED,
    ERROR,
    INTERRUPTED,
}

/** The non-color tone of one status line; the words always say the state too. */
internal enum class ChatStatusTone {
    INFO,
    WARNING,
    ERROR,
    MUTED,
}

/**
 * One line the transcript shows.
 *
 * A user line is exactly the words the user sent. An assistant line is the durable answer or the
 * partial text received so far; its state says whether it is still arriving, settled, stopped or
 * failed. A status line carries a lifecycle fact with no answer text of its own.
 */
internal sealed interface ChatTranscriptEntry {
    val key: Long
    val atMillis: Long

    data class User(
        override val key: Long,
        val text: String,
        override val atMillis: Long,
    ) : ChatTranscriptEntry

    data class Assistant(
        override val key: Long,
        val text: String,
        val state: ChatAnswerState,
        val statusLine: String?,
        val runId: String?,
        override val atMillis: Long,
    ) : ChatTranscriptEntry

    data class Status(
        override val key: Long,
        val text: String,
        val tone: ChatStatusTone,
        override val atMillis: Long,
    ) : ChatTranscriptEntry
}

/** One durable run as the history sheet names it: a title, a moment and a lifecycle state. */
internal data class ChatHistoryRow(
    val runId: String,
    val title: String,
    val atMillis: Long,
    val status: ChatHistoryStatus,
)

/** The lifecycle state a history row reports, in user words. */
internal enum class ChatHistoryStatus {
    COMPLETED,
    STOPPED,
    FAILED,
    NEEDS_ANSWER,
    IN_PROGRESS,
    INTERRUPTED,
}

/**
 * The Chat destination's conversation and in-progress exchange, owned by the shell rather than
 * by the destination subtree.
 *
 * The bottom navigation disposes the Chat subtree on a tab switch. Holding the transcript here
 * keeps the exchange on screen when the user comes back: the appended partial text, the visible
 * tool step, the settled answer and the last outcome stay alive. The Room rows remain the
 * authority for committed work; this type owns only the visible projection and the job handle.
 *
 * [noteContext] is the note handed over from the Notes destination. It is visible and removable
 * on the Chat screen and is included in a request only when the user sends one; attaching it
 * starts nothing by itself.
 *
 * [projectionRevision] is bumped whenever an exchange settles so the capture list reloads and its
 * status projection cannot stay stale after an event is recorded.
 */
@Stable
internal class ChatAgentRunState {
    var outcome by mutableStateOf<WorkflowRunOutcome?>(null)
    var progress by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var noteContext by mutableStateOf<ChatNoteContext?>(null)
    var runId by mutableStateOf<String?>(null)
    var executor by mutableStateOf<BoundedWorkflowExecutor?>(null)
    var continuation by mutableStateOf<WorkflowRunContinuation?>(null)
    var job by mutableStateOf<Job?>(null)
    var clarificationAnswer by mutableStateOf("")
    var projectionRevision by mutableIntStateOf(0)

    /** The visible conversation: typed turns, streamed assistant text and lifecycle lines. */
    var messages by mutableStateOf<List<ChatTranscriptEntry>>(emptyList())
        private set

    /** True once the screen has tried to read the newest durable conversation after creation. */
    var initialLoadAttempted by mutableStateOf(false)

    /** The durable run state shown when the transcript was re-read rather than produced here. */
    var restoredRunState by mutableStateOf<String?>(null)

    /**
     * The token of the exchange currently allowed to update the transcript, or null when no
     * exchange is live.
     *
     * Every progress callback captures the token it was started with. A stopped, settled or
     * replaced exchange has a different token, so its late chunks and tool updates are dropped
     * instead of appending to a transcript the user has already left.
     */
    var activeToken by mutableStateOf<Int?>(null)
        private set

    /** The assistant entry the live exchange is filling in, or null when it is not live. */
    var activeAssistantKey by mutableStateOf<Long?>(null)
        private set

    /** Single-use assisted follow-up authority minted by the settled outcome, if any. */
    var followUp by mutableStateOf<WorkflowRunFollowUp?>(null)

    /** Trusted session scope of the last sent run; bounds retry and visible references. */
    var lastScope by mutableStateOf<WorkflowCallerScope.AgentSession?>(null)
    var lastPrompt by mutableStateOf<String?>(null)
    var lastIntents by mutableStateOf<Set<WorkflowIntent>>(emptySet())
    var lastRequest by mutableStateOf<WorkflowRunRequest?>(null)
    /** The attempt and privacy boundary of the last request, needed by an explicit retry. */
    var lastAttempt by mutableStateOf<Int?>(null)
    var lastBoundary by mutableStateOf<MaskedPromptBoundary?>(null)
    var lastMaxOutputTokens by mutableStateOf<Int?>(null)
    var followUpInput by mutableStateOf("")
    /** Explicit visible reference chosen to disambiguate a follow-up; null while ambiguous. */
    var followUpSelection by mutableStateOf<String?>(null)

    private var nextToken = 0
    private var nextEntryKey = 0L

    private fun nextKey(): Long = ++nextEntryKey

    /** The visible request preceding the current exchange, for the retry action. */
    var lastUserText by mutableStateOf<String?>(null)

    /**
     * Starts one user turn and the assistant entry it will fill.
     *
     * The user's words and the waiting assistant turn appear immediately; nothing has reached
     * the provider yet. The returned token is the only authority allowed to update this exchange.
     */
    fun beginTurn(text: String, atMillis: Long = System.currentTimeMillis()): Int {
        val token = ++nextToken
        activeToken = token
        busy = true
        outcome = null
        progress = null
        clarificationAnswer = ""
        followUp = null
        restoredRunState = null
        // A stop before the next admission must not cancel the previous run's identity.
        runId = null
        lastUserText = text
        val userKey = nextKey()
        val assistantKey = nextKey()
        activeAssistantKey = assistantKey
        messages = messages + ChatTranscriptEntry.User(
            key = userKey,
            text = text,
            atMillis = atMillis,
        ) + ChatTranscriptEntry.Assistant(
            key = assistantKey,
            text = "",
            state = ChatAnswerState.WAITING,
            statusLine = null,
            runId = null,
            atMillis = atMillis,
        )
        return token
    }

    /** Whether [token] still owns the visible exchange. */
    fun isActive(token: Int): Boolean = activeToken == token

    private fun updateActive(
        token: Int,
        transform: (ChatTranscriptEntry.Assistant) -> ChatTranscriptEntry.Assistant,
    ) {
        if (!isActive(token)) return
        val key = activeAssistantKey ?: return
        messages = messages.map { entry ->
            if (entry is ChatTranscriptEntry.Assistant && entry.key == key) transform(entry) else entry
        }
    }

    /** Appends one already-guarded piece of the model's visible answer. */
    fun appendChunk(token: Int, chunk: String) {
        if (chunk.isEmpty()) return
        updateActive(token) { entry ->
            entry.copy(
                text = entry.text + chunk,
                state = ChatAnswerState.STREAMING,
                statusLine = null,
            )
        }
    }

    /** Shows one compact, already-user-safe tool step; the raw tool name never reaches the UI. */
    fun setActivity(token: Int, label: String?) {
        updateActive(token) { entry ->
            entry.copy(
                state = if (entry.text.isEmpty()) ChatAnswerState.WAITING else ChatAnswerState.STREAMING,
                statusLine = label,
            )
        }
    }

    /** Settles the exchange with the verified final answer. */
    fun completeTurn(token: Int, answer: String, runId: String) {
        if (!isActive(token)) return
        updateActive(token) { entry ->
            entry.copy(
                text = answer.ifBlank { entry.text },
                state = ChatAnswerState.COMPLETE,
                statusLine = null,
                runId = runId,
            )
        }
        this.runId = runId
        activeToken = null
        activeAssistantKey = null
        busy = false
    }

    /** Settles the exchange as a clarification, keeping its question in the transcript. */
    fun clarifyTurn(token: Int, question: String, runId: String) {
        if (!isActive(token)) return
        updateActive(token) { entry ->
            entry.copy(
                text = question.ifBlank { entry.text },
                state = ChatAnswerState.CLARIFYING,
                statusLine = null,
                runId = runId,
            )
        }
        this.runId = runId
        activeToken = null
        activeAssistantKey = null
        busy = false
    }

    /**
     * Settles the exchange as failed, keeping every partial word the user already saw.
     *
     * The message is a lifecycle fact, not provider wire text; the partial answer above it is
     * never silently discarded.
     */
    fun failTurn(token: Int, message: String, runId: String?) {
        if (!isActive(token)) return
        updateActive(token) { entry ->
            entry.copy(
                state = ChatAnswerState.ERROR,
                statusLine = message,
                runId = runId ?: entry.runId,
            )
        }
        if (runId != null) this.runId = runId
        activeToken = null
        activeAssistantKey = null
        busy = false
    }

    /**
     * Stops the live exchange, preserving the partial text already shown.
     *
     * The token is cleared before the caller cancels the coroutine, so no callback that races the
     * cancellation can append another chunk. A stop before the first chunk leaves an explicit
     * stopped turn instead of an empty bubble.
     */
    fun stopTurn(stoppedLine: String, beforeReplyLine: String): Boolean {
        val token = activeToken ?: return false
        if (!isActive(token)) return false
        updateActive(token) { entry ->
            entry.copy(
                state = ChatAnswerState.STOPPED,
                statusLine = if (entry.text.isBlank()) beforeReplyLine else stoppedLine,
            )
        }
        activeToken = null
        activeAssistantKey = null
        busy = false
        return true
    }

    /** Replaces the visible transcript with one durable run's stored conversation. */
    fun restoreConversation(
        run: AgentRunEntity,
        lines: List<AgentConversationEntity>,
        interruptedLine: String,
        clarificationExpiredLine: String,
    ) {
        nextToken += 1
        activeToken = null
        activeAssistantKey = null
        busy = false
        outcome = null
        progress = null
        continuation = null
        followUp = null
        runId = run.runId
        lastScope = null
        lastRequest = null
        lastPrompt = null
        lastUserText = null
        restoredRunState = run.state
        messages = transcriptOf(run, lines, interruptedLine, clarificationExpiredLine) { nextKey() }
    }

    /** Starts a fresh conversation without deleting any durable history. */
    fun startNewConversation() {
        nextToken += 1
        activeToken = null
        activeAssistantKey = null
        busy = false
        outcome = null
        progress = null
        runId = null
        executor = null
        continuation = null
        followUp = null
        clarificationAnswer = ""
        restoredRunState = null
        lastScope = null
        lastRequest = null
        lastPrompt = null
        lastUserText = null
        initialLoadAttempted = true
        messages = emptyList()
    }
}

/** The durable transcript of one run as visible lines, newest last. */
internal fun transcriptOf(
    run: AgentRunEntity,
    lines: List<AgentConversationEntity>,
    interruptedLine: String,
    clarificationExpiredLine: String,
    nextKey: () -> Long,
): List<ChatTranscriptEntry> {
    val entries = ArrayList<ChatTranscriptEntry>()
    lines.forEach { line ->
        when (line.role) {
            "USER" -> entries += ChatTranscriptEntry.User(
                key = nextKey(),
                text = line.text,
                atMillis = line.createdAtEpochMillis,
            )

            "AGENT" -> entries += ChatTranscriptEntry.Assistant(
                key = nextKey(),
                text = line.text,
                state = ChatAnswerState.COMPLETE,
                statusLine = null,
                runId = run.runId,
                atMillis = line.createdAtEpochMillis,
            )

            "STATUS" -> {
                val text = line.text.trim()
                if (text.isEmpty() || text in HISTORY_NOISE_STATUSES) return@forEach
                entries += ChatTranscriptEntry.Status(
                    key = nextKey(),
                    text = text,
                    tone = statusToneOf(text),
                    atMillis = line.createdAtEpochMillis,
                )
            }
        }
    }
    // A reopened transcript reports an interrupted lifecycle truthfully; it never shows a live
    // caret or implies that provider work resumed.
    when (run.state) {
        "RUNNING", "PENDING" -> entries += ChatTranscriptEntry.Status(
            key = nextKey(),
            text = interruptedLine,
            tone = ChatStatusTone.WARNING,
            atMillis = run.updatedAtEpochMillis,
        )

        "CLARIFYING" -> entries += ChatTranscriptEntry.Status(
            key = nextKey(),
            text = clarificationExpiredLine,
            tone = ChatStatusTone.WARNING,
            atMillis = run.updatedAtEpochMillis,
        )
    }
    return entries
}

/** The bounded history sheet's rows, newest first, named by the user's own request words. */
internal fun historyRowsOf(
    runs: List<AgentRunEntity>,
    activeRunId: String?,
    busy: Boolean,
): List<ChatHistoryRow> = runs.map { run ->
    ChatHistoryRow(
        runId = run.runId,
        title = historyTitleOf(run.requestText),
        atMillis = run.createdAtEpochMillis,
        status = historyStatusOf(
            state = run.state,
            isActive = busy && activeRunId != null && run.runId == activeRunId,
        ),
    )
}

/** The visible title of one stored request, with the attached-note wrapper removed. */
internal fun historyTitleOf(requestText: String): String {
    val marker = "\n\nMy request:\n"
    val request = requestText.substringAfter(marker, requestText)
    val firstLine = request.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?: request.trim()
    return firstLine.take(MAX_HISTORY_TITLE) + if (firstLine.length > MAX_HISTORY_TITLE) "…" else ""
}

/** The user-facing lifecycle of one stored run; a stale RUNNING row is not "running". */
internal fun historyStatusOf(state: String, isActive: Boolean): ChatHistoryStatus = when (state) {
    "SUCCEEDED" -> ChatHistoryStatus.COMPLETED
    "CANCELLED" -> ChatHistoryStatus.STOPPED
    "FAILED" -> ChatHistoryStatus.FAILED
    "CLARIFYING" -> ChatHistoryStatus.NEEDS_ANSWER
    "RUNNING", "PENDING" ->
        if (isActive) ChatHistoryStatus.IN_PROGRESS else ChatHistoryStatus.INTERRUPTED

    else -> ChatHistoryStatus.FAILED
}

private fun statusToneOf(text: String): ChatStatusTone = when {
    text.contains("cancel", ignoreCase = true) -> ChatStatusTone.MUTED
    text.contains("refused", ignoreCase = true) ||
        text.contains("ended", ignoreCase = true) ||
        text.contains("failed", ignoreCase = true) -> ChatStatusTone.WARNING

    else -> ChatStatusTone.MUTED
}

private val HISTORY_NOISE_STATUSES = setOf(
    "Run admitted.",
    "The run used a bounded retry.",
)

private const val MAX_HISTORY_TITLE = 72
