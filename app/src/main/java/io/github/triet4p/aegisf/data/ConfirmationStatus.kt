package io.github.triet4p.aegisf.data

/**
 * Whether a record is ready to be treated as a real transaction.
 *
 * A record saved by the user is [CONFIRMED] immediately. A record captured without the
 * user's approval — for example from a bank notification — starts as [DRAFT] and only
 * enters ledger totals after the user confirms it.
 */
enum class ConfirmationStatus {
    CONFIRMED,
    DRAFT,
}
