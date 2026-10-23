package io.github.triet4p.aegisf.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryPaletteTest {
    @Test
    fun colorIndexIsNonNegativeForMinimumAndNegativeHashes() {
        listOf(Long.MIN_VALUE, Long.MAX_VALUE, -1L, -2L, 0L, 1L).forEach { categoryId ->
            assertTrue(
                "category $categoryId mapped outside the palette",
                CategoryPalette.colorIndex(categoryId) in 0 until 8,
            )
        }
    }

    @Test
    fun stableIdsFillDistinctPaletteSlotsWithoutNameInput() {
        val firstSlots = (1L..8L).map(CategoryPalette::colorIndex)

        assertEquals(8, firstSlots.toSet().size)
    }
}
