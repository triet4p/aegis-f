package io.github.triet4p.aegisf.data

/**
 * Whether one permitted notification carried the supported bank transaction structure.
 *
 * [PARSED] means the parser matched the supported format and read its labelled fields
 * (each field keeping its own explicit [IntakeFieldState]). [UNSUPPORTED_FORMAT] means
 * the payload passed the privacy filter but is not the supported transaction format —
 * another bank, a promotion, a balance-only alert — and is deliberately kept as
 * reviewable intake with no financial content rather than being turned into a
 * transaction or silently dropped.
 */
enum class IntakeParseStatus {
    PARSED,
    UNSUPPORTED_FORMAT,
}
