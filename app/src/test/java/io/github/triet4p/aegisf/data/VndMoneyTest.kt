package io.github.triet4p.aegisf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VndMoneyTest {
    @Test
    fun `manual parser accepts the smallest and largest whole dong values exactly`() {
        assertEquals(1L, VndMoney.parseManualAmount("1"))
        assertEquals(Long.MAX_VALUE, VndMoney.parseManualAmount("9223372036854775807"))
        assertEquals(1L, VndMoney.parseManualAmount("0001"))
    }

    @Test
    fun `manual parser rejects empty zero signed fractional spaced and separated input`() {
        val invalidAmounts = listOf(
            "",
            "0",
            "000",
            "-1",
            "+1",
            "1.5",
            "1,000",
            "1.000",
            "1 000",
            " 1",
            "1 ",
            "\t1",
            "1\n",
        )

        invalidAmounts.forEach { input ->
            assertThrows(LedgerValidationException::class.java) {
                VndMoney.parseManualAmount(input)
            }
        }
    }

    @Test
    fun `manual parser refuses values above the signed long ceiling`() {
        assertThrows(LedgerValidationException::class.java) {
            VndMoney.parseManualAmount("9223372036854775808")
        }
        assertThrows(LedgerValidationException::class.java) {
            VndMoney.parseManualAmount("-9223372036854775808")
        }
    }

    @Test
    fun `display groups whole dong deterministically with an explicit currency code`() {
        assertEquals("0 VND", VndMoney.formatAmount(0L))
        assertEquals("1 VND", VndMoney.formatAmount(1L))
        assertEquals("1,000 VND", VndMoney.formatAmount(1_000L))
        assertEquals("9,223,372,036,854,775,807 VND", VndMoney.formatAmount(Long.MAX_VALUE))
    }

    @Test
    fun `display refuses negative values without taking an absolute value`() {
        assertThrows(IllegalArgumentException::class.java) {
            VndMoney.formatAmount(Long.MIN_VALUE)
        }
    }

    @Test
    fun `checked total preserves the ceiling and rejects a crossing addition`() {
        assertEquals(Long.MAX_VALUE, VndMoney.checkedAddToTotal(Long.MAX_VALUE - 1L, 1L))
        assertEquals(Long.MAX_VALUE, VndMoney.checkedAddToTotal(0L, Long.MAX_VALUE))
        assertThrows(ArithmeticException::class.java) {
            VndMoney.checkedAddToTotal(Long.MAX_VALUE, 1L)
        }
    }
}
