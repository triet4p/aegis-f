package io.github.triet4p.aegisf.data

/**
 * Where one pending bank draft stands in explicit user review.
 *
 * Every draft stored by intake starts as [PENDING]: it is persisted evidence, never a
 * ledger record, and it enters no summary. Only the user's explicit review (a later
 * task) moves it to [CONFIRMED] or [DISMISSED]; nothing in intake writes those states.
 */
enum class IntakeReviewStatus {
    PENDING,
    CONFIRMED,
    DISMISSED,
}
