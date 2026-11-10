package io.github.triet4p.aegisf.ui

import io.github.triet4p.aegisf.data.AegisLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The exact amount wording shown under the manual form's hero amount. */
class ManualAmountWordsTest {

    @Test
    fun englishWritesTheDesignExample() {
        assertEquals(
            "One hundred fifty thousand dong",
            amountInWords(150_000, AegisLanguage.ENGLISH),
        )
    }

    @Test
    fun vietnameseWritesTheDesignExample() {
        assertEquals(
            "Một trăm năm mươi nghìn đồng",
            amountInWords(150_000, AegisLanguage.VIETNAMESE),
        )
    }

    @Test
    fun vietnameseStatesAnEmptyHundredsColumn() {
        assertEquals(
            "Một nghìn không trăm lẻ năm đồng",
            amountInWords(1_005, AegisLanguage.VIETNAMESE),
        )
        assertEquals(
            "Một triệu không trăm lẻ năm đồng",
            amountInWords(1_000_005, AegisLanguage.VIETNAMESE),
        )
    }

    @Test
    fun vietnameseUsesTheIrregularUnitForms() {
        assertEquals("Hai mươi mốt đồng", amountInWords(21, AegisLanguage.VIETNAMESE))
        assertEquals("Hai mươi tư đồng", amountInWords(24, AegisLanguage.VIETNAMESE))
        assertEquals("Mười lăm đồng", amountInWords(15, AegisLanguage.VIETNAMESE))
        assertEquals("Một trăm lẻ một đồng", amountInWords(101, AegisLanguage.VIETNAMESE))
    }

    @Test
    fun englishGroupsTheShortScale() {
        assertEquals(
            "One million two hundred thirty-four thousand five hundred sixty-seven dong",
            amountInWords(1_234_567, AegisLanguage.ENGLISH),
        )
        assertEquals(
            "Nine quintillion dong",
            amountInWords(9_000_000_000_000_000_000L, AegisLanguage.ENGLISH),
        )
    }

    @Test
    fun nonPositiveAmountsHaveNoSubtitle() {
        assertNull(amountInWords(0, AegisLanguage.ENGLISH))
        assertNull(amountInWords(-1, AegisLanguage.VIETNAMESE))
    }
}
