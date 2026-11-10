package io.github.triet4p.aegisf.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import io.github.triet4p.aegisf.ui.chat.chatMarkdown
import io.github.triet4p.aegisf.ui.chat.chatVisibleText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The small readable formatting applied to an assistant answer before it is displayed. */
class ChatMarkdownTest {

    @Test
    fun emphasisIsBoldAndItsMarkersAreNotDisplayed() {
        val shown = chatMarkdown("**Give everything a home.** The rest stays plain.")
        assertEquals("Give everything a home. The rest stays plain.", shown.text)
        assertEquals(listOf("Give everything a home."), boldSegments(shown))
    }

    @Test
    fun headingsLoseTheHashRunAndBecomeBold() {
        val shown = chatMarkdown("# First habit\nUse one tray.\n## Second habit")
        assertEquals("First habit\nUse one tray.\nSecond habit", shown.text)
        assertEquals(listOf("First habit", "Second habit"), boldSegments(shown))
    }

    @Test
    fun bulletMarkersBecomeAGlyphAndKeepTheirIndent() {
        val shown = chatMarkdown("- one\n  * two")
        assertEquals("• one\n  • two", shown.text)
    }

    @Test
    fun anUnpairedEmphasisMarkerIsHiddenWhileItStreams() {
        val shown = chatMarkdown("**still arriving")
        assertEquals("still arriving", shown.text)
        assertTrue(boldSegments(shown).isEmpty())
    }

    @Test
    fun anUnpairedCodeMarkerIsHiddenWhileItStreams() {
        val shown = chatMarkdown("page `still arriving")
        assertEquals("page still arriving", shown.text)
    }

    @Test
    fun inlineCodeLosesItsBackticksButKeepsItsText() {
        val shown = chatMarkdown("The page shows `page_size: 10` and `scope_exhausted: true`.")
        assertEquals("The page shows page_size: 10 and scope_exhausted: true.", shown.text)
        assertEquals(
            listOf("page_size: 10", "scope_exhausted: true"),
            shown.spanStyles
                .filter { it.item.fontFamily == androidx.compose.ui.text.font.FontFamily.Monospace }
                .map { shown.text.substring(it.start, it.end) },
        )
    }

    @Test
    fun aHorizontalRuleBecomesASeparatorInsteadOfRawPunctuation() {
        val shown = chatMarkdown("Before\n---\nAfter")
        assertEquals("Before\n────────────\nAfter", shown.text)
    }

    @Test
    fun amountsAndPlainTextAreUnchanged() {
        val original = "Spend 150.000 ₫ today and 45,000 VND tomorrow; 1.250.000 ₫ is exact."
        val shown = chatMarkdown(original)
        assertEquals(original, shown.text)
        assertTrue(boldSegments(shown).isEmpty())
    }

    @Test
    fun maskedTokensAreNeverChanged() {
        val original = "The note ⟦C1⟧ took 65.000 ₫ at ⟦C2⟧."
        val shown = chatMarkdown(original)
        assertEquals(original, shown.text)
        assertFalse(shown.text.contains("**"))
    }

    @Test
    fun aHeadingCanCarryEmphasisWithoutLeakingMarkers() {
        val shown = chatMarkdown("## **Desk** plan\n- **Keep** the tray")
        assertEquals("Desk plan\n• Keep the tray", shown.text)
        assertEquals(listOf("Desk plan", "Desk", "Keep"), boldSegments(shown))
    }

    @Test
    fun internalMaskTokensAreDisplayedAsReadableCopy() {
        assertEquals(
            "The length reached me as a masked value, so I can't read it.",
            chatVisibleText(
                "The length reached me as ⟦V-chat1⟧, so I can't read it.",
                "a masked value",
            ),
        )
    }

    @Test
    fun anUnclosedMaskTokenStaysVisibleWhileItStreams() {
        assertEquals(
            "still ⟦V-chat1",
            chatVisibleText("still ⟦V-chat1", "a masked value"),
        )
    }

    @Test
    fun maskTokenReplacementDoesNotChangeAmounts() {
        assertEquals("150.000 ₫", chatVisibleText("150.000 ₫", "a masked value"))
    }

    private fun boldSegments(shown: AnnotatedString): List<String> = shown.spanStyles
        .filter { it.item.fontWeight == FontWeight.Bold }
        .map { shown.text.substring(it.start, it.end) }
}
