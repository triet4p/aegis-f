package io.github.triet4p.aegisf.ui

import org.junit.Assert.assertEquals
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
}
