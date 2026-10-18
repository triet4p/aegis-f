package io.github.triet4p.aegisf.data

/**
 * A ledger input or attempted write violated a data rule: a malformed or non-positive
 * amount, an unknown or archived category, a category on an internal transfer, a blank or
 * duplicated active category name, or deleting a category that is not stored.
 *
 * Validation happens before a ledger write, so a rejected write does not mutate stored data.
 */
class LedgerValidationException(message: String) : IllegalArgumentException(message)
