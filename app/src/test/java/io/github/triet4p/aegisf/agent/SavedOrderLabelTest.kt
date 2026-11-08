package io.github.triet4p.aegisf.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedOrderLabelTest {
    @Test
    fun uniqueNewestIsTheLatestCapture() {
        val captures = listOf(10L to 100L, 11L to 300L, 12L to 200L)
        assertEquals("newest-in-observed-scope", savedOrderLabel(11L, captures))
        assertEquals("older-in-observed-scope", savedOrderLabel(10L, captures))
        assertEquals("older-in-observed-scope", savedOrderLabel(12L, captures))
    }

    @Test
    fun equalLatestTimestampsNeverClaimUniqueNewest() {
        val captures = listOf(20L to 500L, 21L to 500L, 22L to 400L)
        assertEquals("tied-newest-in-observed-scope", savedOrderLabel(20L, captures))
        assertEquals("tied-newest-in-observed-scope", savedOrderLabel(21L, captures))
        assertEquals("older-in-observed-scope", savedOrderLabel(22L, captures))
    }

    @Test
    fun scopeRelativeOnlyObservedCapturesCount() {
        // The newest globally is not in this observed scope; the label is scope-relative.
        val observed = listOf(30L to 100L, 31L to 200L)
        assertEquals("newest-in-observed-scope", savedOrderLabel(31L, observed))
        assertEquals("older-in-observed-scope", savedOrderLabel(30L, observed))
    }

    @Test
    fun unknownCaptureTimesProduceNoOrder() {
        assertNull(savedOrderLabel(40L, listOf(40L to null, 41L to null)))
        assertNull(savedOrderLabel(40L, emptyList()))
    }
}
