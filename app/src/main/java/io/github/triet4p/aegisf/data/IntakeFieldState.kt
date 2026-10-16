package io.github.triet4p.aegisf.data

/**
 * How one labelled bank field was read into a pending draft.
 *
 * This mirrors the parser's [io.github.triet4p.aegisf.notification.NotificationField] in
 * storage form: [PRESENT] carries a value in the draft's matching column, while
 * [UNRESOLVED] and [MISSING] leave that column null so a missing field is never silently
 * equal to one the bank stated but could not be read. The reason for [UNRESOLVED] lives
 * in the draft's matching `*UnresolvedReason` column.
 */
enum class IntakeFieldState {
    PRESENT,
    UNRESOLVED,
    MISSING,
}
