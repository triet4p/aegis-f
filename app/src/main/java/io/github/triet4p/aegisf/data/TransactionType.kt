package io.github.triet4p.aegisf.data

/**
 * The kind of ledger record.
 *
 * The type, not a sign, determines the financial direction of [TransactionEntity.amountVnd]:
 * an [EXPENSE] is money leaving the user, an [INCOME] is money arriving, and a [TRANSFER]
 * moves money between the user's own holdings and therefore changes neither.
 */
enum class TransactionType {
    EXPENSE,
    INCOME,
    TRANSFER,
}
