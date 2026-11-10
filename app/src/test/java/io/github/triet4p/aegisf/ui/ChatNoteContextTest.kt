package io.github.triet4p.aegisf.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The explicit note-context boundary between Notes and AI Chat.
 *
 * A handoff attaches one visible, removable note. The note is part of a request only while it is
 * attached, and removing it makes the next request exactly the user's own words again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatNoteContextTest {

    @Test
    fun `without an attachment the request is exactly what the user typed`() {
        assertEquals("Find my lunch", chatRequestText("Find my lunch", null))
    }

    @Test
    fun `an attached note is named in the request beside the typed request`() {
        val context = ChatNoteContext(captureId = 7L, text = "Trưa nay ăn phở Thìn 65k")

        val request = context.toRequestText("Which bank notification matches?")

        assertTrue(request.contains("Trưa nay ăn phở Thìn 65k"))
        assertTrue(request.contains("Which bank notification matches?"))
    }

    @Test
    fun `removing the attachment leaves no note text in the next request`() {
        val state = ChatAgentRunState()
        state.noteContext = ChatNoteContext(captureId = 7L, text = "Mua xăng 80k")
        state.noteContext = null

        assertNull(state.noteContext)
        assertEquals("Next question", chatRequestText("Next question", state.noteContext))
    }

    @Test
    fun `an attached bank draft names its evidence beside the request`() {
        val draft = io.github.triet4p.aegisf.data.PendingBankDraftEntity(
            deliveryId = "s106-chat-context",
            sourcePackage = "synthetic.fixture",
            notificationKey = null,
            postedAtEpochMillis = 1_800_000_000_000L,
            bank = "VIETINBANK",
            parseStatus = io.github.triet4p.aegisf.data.IntakeParseStatus.PARSED,
            amountState = io.github.triet4p.aegisf.data.IntakeFieldState.PRESENT,
            amountVnd = 150_000L,
            amountUnresolvedReason = null,
            direction = io.github.triet4p.aegisf.data.IntakeDirection.DEBIT,
            momentState = io.github.triet4p.aegisf.data.IntakeFieldState.PRESENT,
            statedDate = "2026-11-09",
            statedTime = "14:15",
            momentUnresolvedReason = null,
            memoState = io.github.triet4p.aegisf.data.IntakeFieldState.PRESENT,
            memo = "HIGHLANDS COFFEE",
            memoUnresolvedReason = null,
            createdAtEpochMillis = 1_800_000_000_000L,
        )

        val request = chatRequestText(
            prompt = "Does this match my note?",
            note = null,
            draftEvidence = io.github.triet4p.aegisf.ui.draftContextEvidence(draft),
        )

        assertTrue(request.contains("Bank draft context (attached by me):"))
        assertTrue(request.contains("150,000"))
        assertTrue(request.contains("HIGHLANDS COFFEE"))
        assertTrue(request.contains("Does this match my note?"))
        assertFalse(request.contains("Saved note context"))
    }

    @Test
    fun `a draft context is shown but never stores a confirmation`() {
        val state = ChatAgentRunState()
        state.draftContext = ChatDraftContext(draftId = 12L)

        assertEquals(12L, state.draftContext?.draftId)
        assertEquals("Ask about draft 12", chatRequestText("Ask about draft 12", null))
    }
}
