package io.github.triet4p.aegisf.data

/**
 * Source of the timestamps stored with ledger records.
 *
 * Timestamps are epoch milliseconds. They are read through this interface so a test can
 * pass a deterministic clock, and so the time source stays a single, replaceable
 * decision instead of scattered `System.currentTimeMillis()` calls.
 */
fun interface LedgerClock {
    fun nowEpochMillis(): Long
}

/** Production clock: the device wall clock. */
object SystemLedgerClock : LedgerClock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}
