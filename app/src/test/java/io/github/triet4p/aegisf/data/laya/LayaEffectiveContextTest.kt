package io.github.triet4p.aegisf.data.laya

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composed effective input of one classification request.
 *
 * The tokenizer is the pinned one the app ships, so the token counts here are the counts the
 * model actually sees, not an estimate.
 */
class LayaEffectiveContextTest {

    private val tokenizer: LayaTokenizer by lazy { LayaTokenizerLoader.loadFromRepoFile() }

    @Test
    fun `a request without stored readings carries exactly the primary text`() {
        val composed = LayaEffectiveContextComposer.compose(
            tokenizer = tokenizer,
            context = LayaEffectiveContext.of("Monthly rent"),
        )
        assertEquals("Monthly rent", composed.text)
        assertEquals(tokenizer.encode(LayaSequence.scrub("Monthly rent")).size, composed.tokenCount)
        assertTrue(composed.tokenCount > 0)
        assertTrue(composed.includedReadings.isEmpty())
        assertTrue(composed.droppedReadings.isEmpty())
    }

    @Test
    fun `stored readings follow the primary text in the order they were supplied`() {
        val composed = LayaEffectiveContextComposer.compose(
            tokenizer = tokenizer,
            context = LayaEffectiveContext(
                primaryText = "Circle K store",
                eventReadings = listOf(
                    LayaEventReading(7L, "Fuel 50,000"),
                    LayaEventReading(3L, "Circle K snack 20,000"),
                ),
            ),
        )
        assertEquals("Circle K store Fuel 50,000 Circle K snack 20,000", composed.text)
        assertEquals(listOf(7L, 3L), composed.includedReadings.map { it.eventId })
        assertTrue(composed.droppedReadings.isEmpty())
    }

    @Test
    fun `the composed input stays inside the room every candidate set leaves`() {
        val room = LayaEffectiveContextComposer.guaranteedRoomTokens(
            LayaRuntimeContract.MAX_LEN,
            LayaRuntimeContract.HEAD_MAX_LEN,
        )
        assertEquals(316, room)
        val long = "Monthly rent payment for the apartment unit ".repeat(200)
        val composed = LayaEffectiveContextComposer.compose(
            tokenizer = tokenizer,
            context = LayaEffectiveContext(
                primaryText = "Circle K store",
                eventReadings = listOf(LayaEventReading(1L, long)),
            ),
        )
        assertTrue(
            "the composed input must fit the room, was ${composed.tokenCount}",
            composed.tokenCount <= room,
        )
        assertEquals(listOf(1L), composed.droppedReadings.map { it.eventId })
        assertTrue(composed.includedReadings.isEmpty())
        // The primary text is never shortened: only the stored reading was left out.
        assertEquals("Circle K store", composed.text)
    }

    @Test
    fun `a reading that does not fit is left out and reported`() {
        val room = LayaEffectiveContextComposer.guaranteedRoomTokens(
            LayaRuntimeContract.MAX_LEN,
            LayaRuntimeContract.HEAD_MAX_LEN,
        )
        val filler = "word ".repeat(1000)
        val composed = LayaEffectiveContextComposer.compose(
            tokenizer = tokenizer,
            context = LayaEffectiveContext(
                primaryText = "Circle K store",
                eventReadings = listOf(
                    LayaEventReading(1L, filler),
                    LayaEventReading(2L, "Fuel 50,000"),
                ),
            ),
        )
        assertEquals(listOf(2L), composed.includedReadings.map { it.eventId })
        assertEquals(listOf(1L), composed.droppedReadings.map { it.eventId })
        assertTrue(composed.tokenCount <= room)
        assertTrue(composed.text.endsWith("Fuel 50,000"))
    }

    @Test
    fun `a primary text that already fills the room keeps every stored reading out`() {
        val filler = "word ".repeat(1000)
        val composed = LayaEffectiveContextComposer.compose(
            tokenizer = tokenizer,
            context = LayaEffectiveContext(
                primaryText = filler,
                eventReadings = listOf(LayaEventReading(1L, "Fuel 50,000")),
            ),
        )
        assertEquals(filler, composed.text)
        assertTrue(composed.includedReadings.isEmpty())
        assertEquals(listOf(1L), composed.droppedReadings.map { it.eventId })
        // This primary text is larger than the room, so the request may not be asked at all.
        assertEquals(LayaEffectiveInput.Refusal.PRIMARY_EXCEEDS_ROOM, composed.refusal)
    }

    @Test
    fun `a safety-blocked reading is never offered to the model`() {
        val composed = LayaEffectiveContextComposer.compose(
            tokenizer = tokenizer,
            context = LayaEffectiveContext(
                primaryText = "Circle K store",
                eventReadings = listOf(
                    LayaEventReading(1L, "CT DI382K26A0"),
                    LayaEventReading(2L, "Fuel 50,000"),
                ),
            ),
        )
        assertEquals(listOf(2L), composed.includedReadings.map { it.eventId })
        assertEquals(listOf(1L), composed.droppedReadings.map { it.eventId })
        assertTrue(!composed.text.contains("382K26A0"))
    }

    @Test
    fun `the digest names the composed text and changes with it`() {
        val first = LayaEffectiveContextComposer.compose(
            tokenizer = tokenizer,
            context = LayaEffectiveContext(
                primaryText = "Circle K store",
                eventReadings = listOf(LayaEventReading(1L, "Fuel 50,000")),
            ),
        )
        val second = LayaEffectiveContextComposer.compose(
            tokenizer = tokenizer,
            context = LayaEffectiveContext(
                primaryText = "Circle K store",
                eventReadings = listOf(LayaEventReading(1L, "Fuel 60,000")),
            ),
        )
        assertEquals(io.github.triet4p.aegisf.data.effectiveInputDigest(first.text), first.digest)
        assertNotEquals(first.digest, second.digest)
    }

    @Test
    fun `the composed input is measured with the pinned tokenizer`() {
        val composed = LayaEffectiveContextComposer.compose(
            tokenizer = tokenizer,
            context = LayaEffectiveContext.of("Monthly rent"),
        )
        assertEquals(
            tokenizer.encode(LayaSequence.scrub("Monthly rent")).size,
            composed.tokenCount,
        )
    }

    @Test
    fun `a primary text larger than the room is refused and never shortened`() {
        val room = room()
        val oversized = textOfTokenCount(tokenizer, room + 1)
        val composed = LayaEffectiveContextComposer.compose(
            tokenizer = tokenizer,
            context = LayaEffectiveContext(
                primaryText = oversized,
                eventReadings = listOf(LayaEventReading(1L, "Fuel 50,000")),
            ),
        )
        assertEquals(LayaEffectiveInput.Refusal.PRIMARY_EXCEEDS_ROOM, composed.refusal)
        assertFalse(composed.fitsRoom)
        assertEquals(room + 1, composed.tokenCount)
        assertTrue(composed.tokenCount > composed.roomTokens)
        // The text is kept exactly as it was given: it is not shortened to fit.
        assertEquals(oversized, composed.text)
        assertEquals(io.github.triet4p.aegisf.data.effectiveInputDigest(oversized), composed.digest)
        assertTrue(composed.includedReadings.isEmpty())
        assertEquals(listOf(1L), composed.droppedReadings.map { it.eventId })
    }

    @Test
    fun `a primary text that exactly fills the room is offered as it is`() {
        val room = room()
        val exact = textOfTokenCount(tokenizer, room)
        val composed = LayaEffectiveContextComposer.compose(
            tokenizer = tokenizer,
            context = LayaEffectiveContext(
                primaryText = exact,
                eventReadings = listOf(LayaEventReading(1L, "Fuel 50,000")),
            ),
        )
        assertNull(composed.refusal)
        assertTrue(composed.fitsRoom)
        assertEquals(room, composed.tokenCount)
        assertEquals(room, composed.roomTokens)
        assertEquals(exact, composed.text)
        // No reading fits any more, so none is added and the omission is reported.
        assertTrue(composed.includedReadings.isEmpty())
        assertEquals(listOf(1L), composed.droppedReadings.map { it.eventId })
    }

    @Test
    fun `the budget of multibyte text follows the pinned tokenizer and not the character count`() {
        val room = room()
        // A text whose character count is below the room but whose size in the pinned
        // tokenizer is above it: a character count would have hidden the overflow.
        val units = listOf("食", "é", "đ")
        val repeats = listOf(110, 150, 200, 250, 300)
        var oversized: String? = null
        for (unit in units) {
            for (count in repeats) {
                val candidate = unit.repeat(count)
                val tokens = tokenizer.encode(LayaSequence.scrub(candidate)).size
                if (tokens > room && candidate.length < room) {
                    oversized = candidate
                    break
                }
            }
            if (oversized != null) break
        }
        val over = checkNotNull(oversized) {
            "the pinned tokenizer must count the multibyte text above the room"
        }
        val composed = LayaEffectiveContextComposer.compose(
            tokenizer = tokenizer,
            context = LayaEffectiveContext.of(over),
        )
        assertEquals(tokenizer.encode(LayaSequence.scrub(over)).size, composed.tokenCount)
        assertTrue(composed.tokenCount > room)
        assertEquals(LayaEffectiveInput.Refusal.PRIMARY_EXCEEDS_ROOM, composed.refusal)
        // The text has fewer characters than the room has tokens, and it is still over the
        // model's input budget.
        assertTrue(over.length < room)
        assertEquals(over, composed.text)
    }

    private fun room(): Int = LayaEffectiveContextComposer.guaranteedRoomTokens(
        LayaRuntimeContract.MAX_LEN,
        LayaRuntimeContract.HEAD_MAX_LEN,
    )

    /**
     * A text whose size in the pinned tokenizer is exactly [target] tokens.
     *
     * The size is measured, never estimated, so the boundary cases above are the model's own
     * boundary and not a guess about how the tokenizer counts.
     */
    private fun textOfTokenCount(
        tokenizer: LayaTokenizer,
        target: Int,
    ): String {
        val fillers = listOf(" word", " food", " rent", " x", " abc")
        var text = ""
        var count = 0
        var index = 0
        while (count < target) {
            val candidate = text + fillers[index % fillers.size]
            val next = tokenizer.encode(LayaSequence.scrub(candidate)).size
            if (next == count + 1) {
                text = candidate
                count = next
            } else {
                index++
                require(index < 10000) { "could not reach $target tokens" }
            }
        }
        return text
    }
}
