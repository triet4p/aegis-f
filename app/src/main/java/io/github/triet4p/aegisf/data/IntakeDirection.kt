package io.github.triet4p.aegisf.data

/**
 * The movement the bank stated for one pending draft's amount, in the bank's own
 * vocabulary — not the ledger's `TransactionType`.
 *
 * A [DEBIT] is money the bank says left the account and is **not** automatically an
 * expense; a [CREDIT] is not automatically income. Turning either into a ledger type is
 * the user's explicit confirmation in review, never intake's guess. [UNKNOWN] records
 * that the bank did not state a sign.
 */
enum class IntakeDirection {
    DEBIT,
    CREDIT,
    UNKNOWN,
}
