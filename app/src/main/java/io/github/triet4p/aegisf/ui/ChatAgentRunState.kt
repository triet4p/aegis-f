package io.github.triet4p.aegisf.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.triet4p.aegisf.agent.BoundedWorkflowExecutor
import io.github.triet4p.aegisf.agent.WorkflowRunContinuation
import io.github.triet4p.aegisf.agent.WorkflowRunOutcome
import kotlinx.coroutines.Job

/**
 * The Chat destination's in-progress exchange, owned by the shell rather than by the destination
 * subtree.
 *
 * The bottom navigation disposes the Chat subtree on a tab switch. Holding the exchange here
 * keeps a pending clarification, the accumulated streamed answer and the running job alive, so
 * coming back shows the same conversation instead of an empty composer. The Room rows remain the
 * authority for committed work; this type owns only the transient projection and the job handle.
 *
 * [projectionRevision] is bumped whenever an exchange settles so the capture list reloads and its
 * status projection cannot stay stale after an event is recorded.
 */
@Stable
internal class ChatAgentRunState {
    var outcome by mutableStateOf<WorkflowRunOutcome?>(null)
    var progress by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var runId by mutableStateOf<String?>(null)
    var executor by mutableStateOf<BoundedWorkflowExecutor?>(null)
    var continuation by mutableStateOf<WorkflowRunContinuation?>(null)
    var job by mutableStateOf<Job?>(null)
    var streamText by mutableStateOf("")
    var streamNote by mutableStateOf<String?>(null)
    var clarificationAnswer by mutableStateOf("")
    var projectionRevision by mutableIntStateOf(0)
}
