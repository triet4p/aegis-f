package io.github.triet4p.aegisf.notification

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification

/**
 * The supported content fields of one permitted bank notification, exactly as posted.
 *
 * These are the fields Android exposes as a notification's visible text: the plain title,
 * the collapsed text, and the expanded big text. A null field was absent or whitespace
 * only; the rest is trimmed and otherwise untouched — redacted lines stay redacted.
 *
 * Nothing here is parsed or interpreted. A permitted notification is a *candidate* for the
 * later intake pipeline, never a transaction, an amount, or a direction.
 */
data class PermittedNotificationContent(
    val title: String?,
    val text: String?,
    val bigText: String?,
)

/** Why a notification from an approved package may not become intake. */
enum class NotificationIntakeRejection {
    /** A one-time password, verification code, login alert, or other security message. */
    SecurityContent,

    /** A group summary: it aggregates other notifications instead of describing one event. */
    GroupSummary,

    /** No supported content field carried any text. */
    NoContent,

    /** Every supported field carried only redaction or placeholder text. */
    RedactedOnly,
}

/**
 * The privacy decision for one posted notification.
 *
 * [NotPermitted] is decided from routing metadata alone, before any content field is
 * touched. [Rejected] names why a permitted package's content is discarded and —
 * deliberately — carries no field value at all, so a rejected body (an OTP, a security
 * alert) cannot be retained, copied, logged, or shown by whoever holds the decision.
 */
sealed interface NotificationIntakeDecision {
    data object NotPermitted : NotificationIntakeDecision

    data class Rejected(val reason: NotificationIntakeRejection) : NotificationIntakeDecision

    data class Permitted(val content: PermittedNotificationContent) : NotificationIntakeDecision
}

/**
 * Decides, in privacy order, whether one posted notification may become intake.
 *
 * The order is the guarantee, not an implementation detail: the package name is routing
 * metadata and is read first, and the notification's content is read only after that
 * package is both a user-approved bank app and covered by granted notification access. A
 * notification from any other app is therefore dropped without its title, text, or big
 * text ever being read — the wrong package is reason enough, and nothing about its body can
 * influence the outcome.
 *
 * Access is not re-queried per post: the system delivers a post only while it has this
 * service bound, and revocation unbinds it, which the listener turns into
 * [ListenerConnection.Disconnected] before any content could be read. Asking the system on
 * every post would put a binder call on the main-thread callback for an answer the binding
 * itself already gives.
 *
 * The result is a filter decision, not a transaction: [NotificationIntakeDecision.Permitted]
 * carries the supported fields unchanged for a later parsing task, and nothing is stored,
 * logged, or counted here.
 */
fun decideNotificationIntake(
    notification: StatusBarNotification,
    allowedPackages: Set<String>,
): NotificationIntakeDecision {
    if (
        !isIntakePermitted(
            accessGranted = true,
            allowedPackages = allowedPackages,
            packageName = notification.packageName,
        )
    ) {
        return NotificationIntakeDecision.NotPermitted
    }
    return screenContent(notification.notification)
}

/**
 * Screens the content of a notification whose package already passed the gate.
 *
 * A group summary is recognised from its summary flag before any text field is read: it
 * describes a bundle of other notifications, so there is no single event to intake. The
 * supported fields are then read once, and the whole payload decides the outcome — a
 * security message is rejected, a payload with no text is rejected, and a payload that is
 * only redaction or placeholder text is rejected. Only a payload with something left to
 * read survives as a candidate.
 *
 * A rejected payload is not carried out of here in any form; only the reason is.
 */
private fun screenContent(notification: Notification): NotificationIntakeDecision {
    if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
        return NotificationIntakeDecision.Rejected(NotificationIntakeRejection.GroupSummary)
    }

    val extras = notification.extras
    val title = postedField(extras, Notification.EXTRA_TITLE)
    val text = postedField(extras, Notification.EXTRA_TEXT)
    val bigText = postedField(extras, Notification.EXTRA_BIG_TEXT)
    val posted = listOfNotNull(title, text, bigText)

    if (posted.isEmpty()) {
        return NotificationIntakeDecision.Rejected(NotificationIntakeRejection.NoContent)
    }
    if (posted.any { field -> SECURITY_PATTERNS.any { it.containsMatchIn(field) } }) {
        return NotificationIntakeDecision.Rejected(NotificationIntakeRejection.SecurityContent)
    }
    if (posted.none(::carriesUsableContent)) {
        return NotificationIntakeDecision.Rejected(NotificationIntakeRejection.RedactedOnly)
    }
    return NotificationIntakeDecision.Permitted(
        PermittedNotificationContent(title = title, text = text, bigText = bigText),
    )
}

/** One supported field, trimmed; null when it was absent or whitespace only. */
private fun postedField(extras: Bundle, key: String): String? =
    extras.getCharSequence(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Whether one field still carries something readable once redaction is discounted.
 *
 * Banks mask identifiers and memos in place — the user's own sample shows `TK: Redacted`
 * and `ND: ........` beside a readable `GD: -50,000 VND` — so redaction is judged per
 * fragment rather than for the whole payload. Mask glyph runs, redaction words, and bare
 * field labels are removed; the field counts as usable when a digit or a word of two or
 * more letters survives. That is what keeps a partly redacted bank notification a
 * candidate while a payload that is nothing but `........`, `Redacted`, or `TK: Redacted`
 * with `ND: ........` is rejected instead of being treated as a transaction.
 *
 * This never decides whether the surviving text is an amount, a balance, or a date: the
 * parser makes those field-level decisions, and a masked balance is left unresolved rather
 * than being treated as an amount.
 *
 * It is `internal` rather than private because the parser asks the same question of one
 * labelled value — a memo that arrived as `........` is unresolved, not a description —
 * and sharing this one judgement keeps a single redaction rule instead of a second one.
 */
internal fun carriesUsableContent(value: String): Boolean {
    val withoutLabels = FIELD_LABEL.replace(value, " ")
    val withoutRedaction = REDACTION_TOKEN.replace(withoutLabels, " ")
    return USABLE_TEXT.containsMatchIn(withoutRedaction)
}

/**
 * Messages that must never become ledger data: credentials, one-time codes, and the
 * security alerts banks send with them, in the English and Vietnamese wording such
 * messages use. Deliberately narrow — a pattern only joins this list when it cannot
 * describe an ordinary transaction notification, so a real debit message stays a
 * candidate instead of being discarded as a security message.
 */
private val SECURITY_PATTERNS: List<Regex> = listOf(
    // One-time passwords and verification codes.
    Regex("\\botp\\b", RegexOption.IGNORE_CASE),
    Regex("one[\\s-]?time\\s+(?:password|code)", RegexOption.IGNORE_CASE),
    Regex(
        "\\b(?:verification|security|authentication|access|login|confirmation)\\s+code\\b",
        RegexOption.IGNORE_CASE,
    ),
    Regex(
        "mã\\s*(?:otp|xác\\s*thực|xác\\s*nhận|bảo\\s*mật|pin)",
        RegexOption.IGNORE_CASE,
    ),
    // Credentials, and the instruction not to share them.
    Regex("\\b(?:password|passcode)\\b", RegexOption.IGNORE_CASE),
    Regex("mật\\s*khẩu", RegexOption.IGNORE_CASE),
    Regex("\\bdo not share\\b|\\bnever share\\b", RegexOption.IGNORE_CASE),
    Regex("không\\s*(?:chia\\s*sẻ|cung\\s*cấp|tiết\\s*lộ)", RegexOption.IGNORE_CASE),
    // Login and security alerts that are not transactions at all.
    Regex("cảnh\\s*báo\\s*(?:bảo\\s*mật|an\\s*toàn)", RegexOption.IGNORE_CASE),
    Regex("\\b(?:two|2)[\\s-]?factor\\b", RegexOption.IGNORE_CASE),
    Regex("thiết\\s*bị\\s*(?:mới|lạ)", RegexOption.IGNORE_CASE),
    Regex("\\bnew device\\b", RegexOption.IGNORE_CASE),
)

/** Leading `GD:`, `ND:`, `TK:` style labels hold no value of their own. */
private val FIELD_LABEL = Regex("(?m)^\\s*\\p{L}{1,4}\\s*:")

/** Masked values and redaction words, removed before deciding whether anything is left. */
private val REDACTION_TOKEN = Regex(
    "\\b(?:redacted|redaction|không\\s*hiển\\s*thị)\\b|[•·*]{2,}|\\.{2,}|_{2,}|x{2,}|…",
    RegexOption.IGNORE_CASE,
)

/** A digit or a word: the smallest sign of real content rather than redaction. */
private val USABLE_TEXT = Regex("\\d|\\p{L}{2,}")
