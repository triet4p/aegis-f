package io.github.triet4p.aegisf.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The incremental public-output gate of a streamed turn.
 *
 * These tests are deliberately adversarial about chunk boundaries: a mapped literal, a known
 * token, a foreign token and an unclosed token are each split at plausible points, and the
 * assertion is on what a UI would have displayed (the concatenation of emitted fragments), not
 * on an internal buffer formula. A value the session masked must never become displayable, and
 * a held fragment must never be flushed as a partial token.
 */
class PublicStreamGateTest {

    private class Emitted {
        val fragments = mutableListOf<String>()
        val text: String get() = fragments.joinToString("")
    }

    private fun fixture(vararg secrets: String): Pair<SensitiveVault, MaskedPromptBoundary> {
        val vault = SensitiveVault("pt2-stream-gate")
        val boundary = MaskedPromptBoundary(vault)
        secrets.forEach { vault.maskText(it) }
        return vault to boundary
    }

    @Test
    fun `safe public text streams in order and flushes the held tail`() {
        val (_, boundary) = fixture()
        val out = Emitted()
        val gate = PublicStreamGate(boundary) { out.fragments += it }
        listOf("Khoản chi đã ", "được ghi nhận. ", "Không có ", "thay đổi nào.").forEach(gate::accept)
        gate.finish()
        assertEquals("Khoản chi đã được ghi nhận. Không có thay đổi nào.", out.text)
        assertTrue(out.fragments.size > 1)
    }

    @Test
    fun `a mapped amount split into quarters is never displayed`() {
        val (vault, boundary) = fixture("giá 15,000,000")
        val mapped = vault.issuedTokens().single().token
        val out = Emitted()
        val gate = PublicStreamGate(boundary) { out.fragments += it }
        try {
            // The model cannot know the raw value, but the guard must fail closed if it ever
            // streams the same characters the session mapped, however the chunks are cut.
            listOf("Số tiền là ", "1500", "000", "0").forEach(gate::accept)
            gate.finish()
            fail("a complete mapped value must refuse the stream")
        } catch (expected: PublicStreamRefusal) {
            assertEquals(InboundRefusal.RAW_LITERAL_EGRESS, expected.reason)
        }
        assertFalse(out.text, out.text.contains("15000000"))
        assertFalse(out.text, out.text.contains("15,000,000"))
        // The token itself remains a valid, safe alias.
        assertTrue(boundary.scanInbound(mapped) is InboundScan.Accepted)
    }

    @Test
    fun `a mapped value at the very end refuses without a partial disclosure`() {
        val (_, boundary) = fixture("10000 VND")
        val out = Emitted()
        val gate = PublicStreamGate(boundary) { out.fragments += it }
        try {
            gate.accept("Giá là 100")
            gate.accept("00")
            gate.finish()
            fail("the mapped value must be refused")
        } catch (expected: PublicStreamRefusal) {
            assertEquals(InboundRefusal.RAW_LITERAL_EGRESS, expected.reason)
        }
        assertFalse(out.text, out.text.contains("100"))
        assertFalse(out.text, out.text.contains("10000"))
    }

    @Test
    fun `a surface minted while the gate exists still holds its own tail`() {
        val (vault, boundary) = fixture()
        val out = Emitted()
        val gate = PublicStreamGate(boundary) { out.fragments += it }
        gate.accept("Bắt đầu ")
        // Dynamic issuance after the gate was created: the length is re-read on every flush.
        vault.maskText("chuyển 7,250,000")
        try {
            gate.accept("7,250,000")
            gate.finish()
            fail("a value minted mid-turn must still be refused")
        } catch (expected: PublicStreamRefusal) {
            assertTrue(expected.reason == InboundRefusal.RAW_LITERAL_EGRESS)
        }
        assertFalse(out.text, out.text.contains("7,250,000"))
        assertFalse(out.text, out.text.contains("7250000"))
    }

    @Test
    fun `a known token split across chunks is held until complete then accepted`() {
        val (vault, boundary) = fixture("giá 10000")
        val token = vault.issuedTokens().single().token
        val out = Emitted()
        val gate = PublicStreamGate(boundary) { out.fragments += it }
        gate.accept("Giá trị ")
        gate.accept(token.take(4))
        // Nothing may display a partial delimiter while the token is unterminated.
        assertFalse(out.text, out.text.contains("⟦"))
        gate.accept(token.drop(4))
        gate.finish()
        assertTrue(out.text, out.text.contains(token))
        assertFalse(out.text, out.text.contains("10000"))
    }

    @Test
    fun `a foreign token split across chunks is refused and never displayed`() {
        val (vault, _) = fixture("giá 10000")
        val boundary = MaskedPromptBoundary(vault)
        val foreign = "⟦V-foreign12-0001⟧"
        val out = Emitted()
        val gate = PublicStreamGate(boundary) { out.fragments += it }
        try {
            gate.accept("Tham chiếu ")
            gate.accept(foreign.take(9))
            gate.accept(foreign.drop(9))
            gate.finish()
            fail("a foreign token must be refused")
        } catch (expected: PublicStreamRefusal) {
            assertEquals(InboundRefusal.FOREIGN_TOKEN, expected.reason)
        }
        assertFalse(out.text, out.text.contains("⟦"))
    }

    @Test
    fun `an unclosed token delimiter at the end fails closed`() {
        val (_, boundary) = fixture("giá 10000")
        val out = Emitted()
        val gate = PublicStreamGate(boundary) { out.fragments += it }
        gate.accept("Kết quả: ")
        gate.accept("⟦V-")
        try {
            gate.finish()
            fail("an unclosed delimiter must never be displayed")
        } catch (expected: PublicStreamRefusal) {
            assertEquals(InboundRefusal.UNKNOWN_TOKEN, expected.reason)
        }
        assertFalse(out.text, out.text.contains("⟦"))
    }

    @Test
    fun `a raw mapped date and time split across chunks are refused`() {
        val (_, boundary) = fixture("ngày 02/11", "lúc 10h")
        val dateOut = Emitted()
        val dateGate = PublicStreamGate(boundary) { dateOut.fragments += it }
        try {
            dateGate.accept("Vào ngày 02")
            dateGate.accept("/11")
            dateGate.finish()
            fail("a mapped date must be refused")
        } catch (expected: PublicStreamRefusal) {
            assertTrue(expected.reason == InboundRefusal.RAW_LITERAL_EGRESS)
        }
        assertFalse(dateOut.text, dateOut.text.contains("02/11"))
        assertFalse(dateOut.text, dateOut.text.contains("02"))

        val timeOut = Emitted()
        val timeGate = PublicStreamGate(boundary) { timeOut.fragments += it }
        try {
            timeGate.accept("Khoảng 10")
            timeGate.accept("h")
            timeGate.finish()
            fail("a mapped time must be refused")
        } catch (expected: PublicStreamRefusal) {
            assertTrue(expected.reason == InboundRefusal.RAW_LITERAL_EGRESS)
        }
        assertFalse(timeOut.text, timeOut.text.contains("10h"))
    }
}
