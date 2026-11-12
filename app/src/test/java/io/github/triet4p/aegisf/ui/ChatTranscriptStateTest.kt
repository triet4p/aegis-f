package io.github.triet4p.aegisf.ui

import io.github.triet4p.aegisf.data.AegisLanguage
import io.github.triet4p.aegisf.data.AgentConversationEntity
import io.github.triet4p.aegisf.data.AgentRunEntity
import io.github.triet4p.aegisf.ui.chat.chatRelativeMoment
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Chat transcript's visible lifecycle and the durable-history projection.
 *
 * These are the properties the streaming screen depends on: a stopped exchange keeps the words
 * already received and rejects every later chunk, a settled exchange cannot be overwritten by a
 * stale callback, and a re-read transcript reports the stored lifecycle without inventing a live
 * run or a second copy of a message.
 */
class ChatTranscriptStateTest {

    @Test
    fun `a turn starts with the user's words and a waiting assistant`() {
        val state = ChatAgentRunState()

        val token = state.beginTurn("Ask about my notes", atMillis = 10L)

        assertEquals(2, state.messages.size)
        val user = state.messages[0] as ChatTranscriptEntry.User
        val assistant = state.messages[1] as ChatTranscriptEntry.Assistant
        assertEquals("Ask about my notes", user.text)
        assertEquals(ChatAnswerState.WAITING, assistant.state)
        assertTrue(state.isActive(token))
        assertTrue(state.busy)
    }

    @Test
    fun `streamed chunks append to the same assistant turn until it settles`() {
        val state = ChatAgentRunState()
        val token = state.beginTurn("Question")

        state.appendChunk(token, "First ")
        state.appendChunk(token, "second.")
        state.completeTurn(token, "First second.", runId = "run-1")

        val assistant = state.messages.last() as ChatTranscriptEntry.Assistant
        assertEquals("First second.", assistant.text)
        assertEquals(ChatAnswerState.COMPLETE, assistant.state)
        assertEquals("run-1", assistant.runId)
        assertFalse(state.busy)
        assertFalse(state.isActive(token))
    }

    @Test
    fun `stop preserves partial text and rejects every later chunk`() {
        val state = ChatAgentRunState()
        val token = state.beginTurn("Question")
        state.appendChunk(token, "Partial answer")

        assertTrue(state.stopTurn("Stopped.", "Stopped early."))
        assertFalse(state.busy)
        val stopped = state.messages.last() as ChatTranscriptEntry.Assistant
        assertEquals("Partial answer", stopped.text)
        assertEquals(ChatAnswerState.STOPPED, stopped.state)
        assertEquals("Stopped.", stopped.statusLine)

        state.appendChunk(token, " late")
        state.setActivity(token, "late tool")
        val after = state.messages.last() as ChatTranscriptEntry.Assistant
        assertEquals("Partial answer", after.text)
        assertEquals("Stopped.", after.statusLine)
        assertEquals(ChatAnswerState.STOPPED, after.state)
    }

    @Test
    fun `private reasoning shows a phase label only until answer text is visible`() {
        val state = ChatAgentRunState()
        val token = state.beginTurn("Question")

        state.setReasoningActivity(token, "Thinking…")
        val thinking = state.messages.last() as ChatTranscriptEntry.Assistant
        assertEquals("", thinking.text)
        assertEquals(ChatAnswerState.WAITING, thinking.state)
        assertEquals("Thinking…", thinking.statusLine)

        state.appendChunk(token, "Partial answer")
        state.setReasoningActivity(token, "Thinking…")
        val streaming = state.messages.last() as ChatTranscriptEntry.Assistant
        assertEquals("Partial answer", streaming.text)
        assertEquals(ChatAnswerState.STREAMING, streaming.state)
        assertNull(streaming.statusLine)

        state.completeTurn(token, "Partial answer done.", runId = "run-1")
        val settled = state.messages.last() as ChatTranscriptEntry.Assistant
        assertEquals(ChatAnswerState.COMPLETE, settled.state)
        assertNull(settled.statusLine)
    }

    @Test
    fun `a finished tool step clears its label without touching received text`() {
        val state = ChatAgentRunState()
        val token = state.beginTurn("Question")

        state.setActivity(token, "Reading your saved note…")
        val running = state.messages.last() as ChatTranscriptEntry.Assistant
        assertEquals("", running.text)
        assertEquals("Reading your saved note…", running.statusLine)

        state.appendChunk(token, "Answer")
        state.setActivity(token, null)
        val finished = state.messages.last() as ChatTranscriptEntry.Assistant
        assertEquals("Answer", finished.text)
        assertEquals(ChatAnswerState.STREAMING, finished.state)
        assertNull(finished.statusLine)
    }

    @Test
    fun `a stale completion cannot overwrite a stopped turn`() {
        val state = ChatAgentRunState()
        val first = state.beginTurn("Question one")
        state.appendChunk(first, "Partial")
        state.stopTurn("Stopped.", "Stopped early.")
        val second = state.beginTurn("Question two")
        state.appendChunk(second, "Fresh")

        state.completeTurn(first, "Late first answer", runId = "run-1")

        val last = state.messages.last() as ChatTranscriptEntry.Assistant
        assertEquals("Fresh", last.text)
        assertEquals(ChatAnswerState.STREAMING, last.state)
        assertTrue(state.isActive(second))
    }

    @Test
    fun `stopping before the first chunk still leaves an explicit stopped turn`() {
        val state = ChatAgentRunState()
        val token = state.beginTurn("Question")

        state.stopTurn("Stopped.", "Stopped before the reply arrived.")

        val assistant = state.messages.last() as ChatTranscriptEntry.Assistant
        assertEquals("", assistant.text)
        assertEquals(ChatAnswerState.STOPPED, assistant.state)
        assertEquals("Stopped before the reply arrived.", assistant.statusLine)
    }

    @Test
    fun `a durable transcript is re-read without duplicating or resuming a turn`() {
        val run = run(state = "SUCCEEDED", request = "Saved note context (attached by me):\n\"Lunch 65k\"\n\nMy request:\nFind a match")
        val lines = listOf(
            line(run.runId, 0, "USER", "Saved note context (attached by me):\n\"Lunch 65k\"\n\nMy request:\nFind a match"),
            line(run.runId, 1, "STATUS", "Run admitted."),
            line(run.runId, 2, "AGENT", "One possible match."),
        )
        val state = ChatAgentRunState()

        state.restoreConversation(run, lines, "Interrupted.", "Expired.")

        val entries = state.messages
        assertEquals(2, entries.size) // the admission noise is not a visible turn
        assertEquals("Find a match", historyTitleOf(run.requestText))
        val answer = entries[1] as ChatTranscriptEntry.Assistant
        assertEquals("One possible match.", answer.text)
        assertEquals(ChatAnswerState.COMPLETE, answer.state)
        assertFalse(state.busy)
        assertEquals("SUCCEEDED", state.restoredRunState)
    }

    @Test
    fun `a run left running is shown as interrupted rather than live`() {
        val run = run(state = "RUNNING", request = "Ask something")
        val lines = listOf(
            line(run.runId, 0, "USER", "Ask something"),
            line(run.runId, 1, "STATUS", "Run admitted."),
        )
        val state = ChatAgentRunState()

        state.restoreConversation(run, lines, "Interrupted.", "Expired.")

        val last = state.messages.last()
        assertTrue(last is ChatTranscriptEntry.Status)
        assertTrue((last as ChatTranscriptEntry.Status).text.contains("Interrupted"))
        assertFalse(state.busy)
    }

    @Test
    fun `history titles use the user's request instead of the attached-note wrapper`() {
        val attached = "Saved note context (attached by me):\n\"Lunch\"\n\nMy request:\nCompare these two bank drafts"
        val plain = "A plain question"

        assertEquals("Compare these two bank drafts", historyTitleOf(attached))
        assertEquals("A plain question", historyTitleOf(plain))
        val long = "x".repeat(80)
        assertEquals("x".repeat(72) + "…", historyTitleOf(long))
    }

    @Test
    fun `history status never calls a stale running row live`() {
        assertEquals(ChatHistoryStatus.COMPLETED, historyStatusOf("SUCCEEDED", isActive = false))
        assertEquals(ChatHistoryStatus.STOPPED, historyStatusOf("CANCELLED", isActive = false))
        assertEquals(ChatHistoryStatus.FAILED, historyStatusOf("FAILED", isActive = false))
        assertEquals(ChatHistoryStatus.NEEDS_ANSWER, historyStatusOf("CLARIFYING", isActive = false))
        assertEquals(ChatHistoryStatus.INTERRUPTED, historyStatusOf("RUNNING", isActive = false))
        assertEquals(ChatHistoryStatus.IN_PROGRESS, historyStatusOf("RUNNING", isActive = true))
    }

    @Test
    fun `history rows report the title, moment and lifecycle of each durable run`() {
        val runs = listOf(
            run(state = "RUNNING", request = "First question", runId = "run-a", at = 100L),
            run(state = "CANCELLED", request = "Second question", runId = "run-b", at = 200L),
        )

        val rows = historyRowsOf(runs, activeRunId = "run-a", busy = true)

        assertEquals(listOf("First question", "Second question"), rows.map { it.title })
        assertEquals(ChatHistoryStatus.IN_PROGRESS, rows[0].status)
        assertEquals(ChatHistoryStatus.STOPPED, rows[1].status)
    }

    @Test
    fun `relative moments distinguish today and yesterday by calendar day`() {
        val zone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
        val zoneId = ZoneId.of("Asia/Ho_Chi_Minh")
        fun epoch(day: Int, hour: Int, minute: Int): Long = LocalDateTime.of(2026, 11, day, hour, minute)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

        val now = epoch(10, 12, 0)

        assertEquals("Today, 08:05", chatRelativeMoment(epoch(10, 8, 5), AegisLanguage.ENGLISH, now, zone))
        assertEquals("Yesterday, 18:40", chatRelativeMoment(epoch(9, 18, 40), AegisLanguage.ENGLISH, now, zone))
        assertEquals("Hôm qua, 23:59", chatRelativeMoment(epoch(9, 23, 59), AegisLanguage.VIETNAMESE, now, zone))
    }

    private fun run(
        state: String,
        request: String,
        runId: String = "run-1",
        at: Long = 1L,
    ): AgentRunEntity = AgentRunEntity(
        runId = runId,
        scopeId = "scope-1",
        intentNames = "INTERPRET_EVENTS",
        requestFingerprint = "fingerprint-$runId",
        attempt = 1,
        state = state,
        stopReason = null,
        maxOutputTokens = null,
        answerDigest = null,
        requestText = request,
        answerText = null,
        revisionEpochMillis = at,
        createdAtEpochMillis = at,
        updatedAtEpochMillis = at,
    )

    private fun line(
        runId: String,
        sequence: Int,
        role: String,
        text: String,
    ): AgentConversationEntity = AgentConversationEntity(
        runId = runId,
        sequence = sequence,
        role = role,
        text = text,
        createdAtEpochMillis = sequence.toLong(),
    )
}
