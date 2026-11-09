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
import io.github.triet4p.aegisf.data.WorkflowCallerScope
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
        append("Saved note context (attached by me):\n\"")
        append(text)
        append("\"\n\nMy request:\n")
        append(prompt)
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

/**
 * The Chat destination's in-progress exchange, owned by the shell rather than by the destination
 * subtree.
 *
 * The bottom navigation disposes the Chat subtree on a tab switch. Holding the exchange here
 * keeps a pending clarification, the accumulated streamed answer and the running job alive, so
 * coming back shows the same conversation instead of an empty composer. The Room rows remain the
 * authority for committed work; this type owns only the transient projection and the job handle.
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
    var streamText by mutableStateOf("")
    var streamNote by mutableStateOf<String?>(null)
    var clarificationAnswer by mutableStateOf("")
    var projectionRevision by mutableIntStateOf(0)
    /** Single-use assisted follow-up authority minted by the settled outcome, if any. */
    var followUp by mutableStateOf<WorkflowRunFollowUp?>(null)
    /** Trusted session scope of the last sent run; bounds retry and visible references. */
    var lastScope by mutableStateOf<WorkflowCallerScope.AgentSession?>(null)
    var lastPrompt by mutableStateOf<String?>(null)
    var lastIntents by mutableStateOf<Set<WorkflowIntent>>(emptySet())
    var followUpInput by mutableStateOf("")
    /** Explicit visible reference chosen to disambiguate a follow-up; null while ambiguous. */
    var followUpSelection by mutableStateOf<String?>(null)
}
