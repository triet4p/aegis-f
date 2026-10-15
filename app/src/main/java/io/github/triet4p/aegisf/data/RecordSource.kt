package io.github.triet4p.aegisf.data

/**
 * Where a stored record came from.
 *
 * Provenance is kept so that a later review can tell a record the user typed in
 * ([MANUAL]) apart from one the app captured on its own ([BANK_NOTIFICATION]).
 */
enum class RecordSource {
    MANUAL,
    BANK_NOTIFICATION,
}
