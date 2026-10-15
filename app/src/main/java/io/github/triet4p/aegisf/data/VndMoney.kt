package io.github.triet4p.aegisf.data

/** Exact whole-dong boundaries shared by manual input, storage validation, display, and totals. */
object VndMoney {
    /** Smallest storable transaction magnitude; direction remains a separate transaction type. */
    const val MIN_AMOUNT_VND = 1L

    /**
     * Parses manual amounts written as one or more ASCII digits, without whitespace or grouping.
     * Zero, signs, decimal fractions, separators, and values above [Long.MAX_VALUE] are refused.
     */
    fun parseManualAmount(input: String): Long {
        if (input.isEmpty()) {
            throw LedgerValidationException("Amount is required.")
        }

        var amountVnd = 0L
        for (character in input) {
            val digit = character.code - '0'.code
            if (digit !in 0..9) {
                throw LedgerValidationException(
                    "Amount must contain ASCII digits only, without signs, spaces, or separators.",
                )
            }
            val digitVnd = digit.toLong()
            if (amountVnd > (Long.MAX_VALUE - digitVnd) / 10L) {
                throw LedgerValidationException("Amount exceeds the maximum whole-dong value.")
            }
            amountVnd = amountVnd * 10L + digitVnd
        }

        requirePositiveAmount(amountVnd)
        return amountVnd
    }

    /** Enforces the positive-magnitude rule for values passed directly to the ledger. */
    fun requirePositiveAmount(amountVnd: Long) {
        if (amountVnd < MIN_AMOUNT_VND) {
            throw LedgerValidationException(
                "Amount must be at least $MIN_AMOUNT_VND whole dong; got $amountVnd.",
            )
        }
    }

    /** Formats a non-negative whole-dong amount with deterministic grouping and an explicit code. */
    fun formatAmount(amountVnd: Long): String {
        require(amountVnd >= 0L) { "A VND amount to display cannot be negative." }
        val digits = amountVnd.toString()
        val separators = (digits.length - 1) / 3
        return buildString(digits.length + separators + VND_SUFFIX.length) {
            digits.forEachIndexed { index, digit ->
                if (index > 0 && (digits.length - index) % 3 == 0) {
                    append(',')
                }
                append(digit)
            }
            append(VND_SUFFIX)
        }
    }

    /** Adds one positive transaction magnitude to a non-negative total without signed wraparound. */
    fun checkedAddToTotal(totalVnd: Long, amountVnd: Long): Long {
        require(totalVnd >= 0L) { "A VND total cannot be negative." }
        requirePositiveAmount(amountVnd)
        if (amountVnd > Long.MAX_VALUE - totalVnd) {
            throw ArithmeticException("VND total exceeds the maximum whole-dong value.")
        }
        return totalVnd + amountVnd
    }

    private const val VND_SUFFIX = " VND"
}
