package io.github.triet4p.aegisf.notification

import io.github.triet4p.aegisf.data.LedgerValidationException
import io.github.triet4p.aegisf.data.VndMoney
import io.github.triet4p.aegisf.ui.ManualDateTime

/**
 * Which bank's observable notification format a permitted message matched.
 *
 * One bank is supported because one format was actually supplied —
 * `artifacts/sprint-3/handoffs/vietinbank-notification-context.md` is the user's own redacted
 * VietinBank sample. A message that does not carry that structure is
 * [BankNotificationParse.UnsupportedFormat]: it is never bent into a VietinBank transaction.
 */
enum class BankSource {
    VIETINBANK,
}

/**
 * The direction the bank stated for one transaction amount, read from that amount's own sign.
 *
 * This is the bank statement's own vocabulary, not the ledger's [io.github.triet4p.aegisf.data.TransactionType]:
 * a [DEBIT] is money the bank says left the account, and it is **not** automatically an expense
 * — it may be an internal transfer or something else. A [CREDIT] is not automatically income.
 * Turning one of these into an expense, an income, or a transfer is the user's explicit
 * confirmation in review, never a guess made here.
 *
 * [UNKNOWN] is not a third kind of movement: it records that the bank did not state a sign, so
 * the amount may be readable while its financial direction is not.
 */
enum class BankDirection {
    DEBIT,
    CREDIT,
    UNKNOWN,
}

/**
 * One wall-clock moment exactly as a bank notification stated it, with the time zone the bank
 * did not supply.
 *
 * `VietinBank: 16/10/2026 07:38` prints a local calendar date and a clock time; the bank
 * supplied no zone or offset, and the only format evidence — the user's own redacted sample —
 * supplies none either. A wall clock without a zone is not an instant, so this type carries no
 * epoch millis and names no zone: picking one here would assert a moment the bank never stated
 * and make the app's own convention look like bank data. The two fields are the app's own
 * manual-entry grammars (`YYYY-MM-DD`, `HH:mm`), so the step that needs an instant for a
 * user-confirmed ledger record converts exactly these with
 * [io.github.triet4p.aegisf.ui.ManualDateTime.toEpochMillis] in the device zone that step
 * already documents. That conversion is the app's policy under the user's confirmation; it is
 * never presented as the bank's time zone.
 */
data class BankStatedMoment(
    /** The stated calendar date in the manual-entry grammar `YYYY-MM-DD`. */
    val date: String,

    /** The stated clock time in the manual-entry grammar `HH:mm`, on a 24-hour clock. */
    val time: String,
)

/** Why a labelled field that the bank did state could not be read into a value. */
enum class UnresolvedReason {
    /**
     * The label was present with a value, but the value is not in the supported shape for
     * that field (a masked amount, a non-VND currency, a date that is not `dd/MM/yyyy HH:mm`).
     */
    Unreadable,

    /**
     * The label was present but carried only masking or placeholder text — the sample's
     * `ND: ........` is the observed case. That is an unresolved field, never a literal memo.
     */
    Redacted,

    /**
     * The same label appeared more than once with different values, so no single value is
     * the bank's statement. A repeated *identical* line is not ambiguous: the same text
     * arriving in more than one supported field is one statement, not two.
     */
    Ambiguous,
}

/**
 * The outcome of reading one labelled field of a supported notification.
 *
 * The three states are kept distinct so a missing field is never silently equal to one the
 * bank stated and could not be read, and neither is confused with a conflict. A consumer such
 * as the review inbox shows all three as visibly unresolved, while [Present] is the only state
 * that carries a value.
 */
sealed interface NotificationField<out T> {
    /** The bank stated this field and it was read into [value]. */
    data class Present<T>(val value: T) : NotificationField<T>

    /** The bank stated this field, but it was not read into a value; [reason] says why. */
    data class Unresolved(val reason: UnresolvedReason) : NotificationField<Nothing>

    /** The bank did not state this field at all. */
    data object Missing : NotificationField<Nothing>
}

/**
 * The result of parsing one permitted notification.
 *
 * [UnsupportedFormat] means the message does not carry the supported VietinBank transaction
 * structure — a different bank, a promotion, a balance-only alert, or any other payload — and
 * is deliberately not turned into a transaction. [Parsed] carries only fields the bank stated
 * or their explicit unresolved states, all of them read from the labelled lines the observed
 * format uses, and never a value that was guessed to fill a gap.
 */
sealed interface BankNotificationParse {
    data object UnsupportedFormat : BankNotificationParse

    data class Parsed(
        /** Which bank's format matched; also the notification's source attribution. */
        val bank: BankSource,

        /**
         * The transaction magnitude in whole dong, read from the transaction line (`GD:`) and
         * from nowhere else. Never negative: the sign belongs to [direction], and the balance
         * (masked or not) and the account line are never read as an amount.
         */
        val amountVnd: NotificationField<Long>,

        /** The direction the amount's sign stated, or [BankDirection.UNKNOWN]. */
        val direction: BankDirection,

        /**
         * The bank's own wall-clock moment for the transaction, or an explicit unresolved
         * state. This is deliberately not an instant: the bank states a local date and clock
         * time and no time zone, so no epoch millis and no zone are claimed on its behalf.
         * Converting it into a stored moment is a later step's documented policy under the
         * user's confirmation — never the bank's time zone and never a guessed UTC instant.
         */
        val occurredAt: NotificationField<BankStatedMoment>,

        /**
         * The transaction description (`ND:`), the only field that can become a memo. The
         * account line and the balance carry no memo, and a value that is only masking or
         * placeholders is [UnresolvedReason.Redacted] rather than a description.
         */
        val memo: NotificationField<String>,
    ) : BankNotificationParse
}

/**
 * Parses one permitted notification into the supported VietinBank transaction fields.
 *
 * The input is [PermittedNotificationContent] — the output of the intake boundary — so this
 * runs strictly after the package is approved and the security, group-summary, empty, and
 * redaction-only payloads are already discarded. The three visible fields are read together,
 * because which of title, text, or big text the bank actually uses is device evidence that was
 * not supplied; a line is therefore found wherever it was posted, and the same line in more
 * than one field counts once.
 *
 * The observed grammar is matched deterministically and nothing else is accepted as a
 * VietinBank transaction: a line beginning `VietinBank` gives the bank attribution and the
 * timestamp after it, and the labelled lines `TK:`, `GD:`, `SDC:`, and `ND:` give the account,
 * the transaction amount, the balance, and the description. `TK:` and `SDC:` are recognised
 * only so they can be excluded — the account identifier is never extracted, and the masked
 * balance is neither reconstructed nor mistaken for the amount.
 *
 * Every field the bank did not state, or stated in a shape this format does not support, stays
 * an explicit unresolved state; nothing is inferred from a debit alone, and no time zone or
 * transaction reference is invented.
 *
 * The bank's moment is kept as the wall clock it printed: the message states no time zone, so
 * nothing here picks one or produces an instant, and a caller that needs one converts the
 * stated [BankStatedMoment] under its own documented policy where a user confirms the record.
 */
fun parseBankNotification(
    content: PermittedNotificationContent,
): BankNotificationParse {
    val lines = content.postedLines()
    if (lines.none(::isBankAttribution) || lines.none(::isTransactionLine)) {
        return BankNotificationParse.UnsupportedFormat
    }

    val (amountVnd, direction) = resolveAmount(labelledValues(lines, TRANSACTION_LABEL))
    return BankNotificationParse.Parsed(
        bank = BankSource.VIETINBANK,
        amountVnd = amountVnd,
        direction = direction,
        occurredAt = resolveOccurredAt(lines),
        memo = resolveMemo(labelledValues(lines, MEMO_LABEL)),
    )
}

/**
 * The non-empty lines of the three supported fields, trimmed, in posting order, without exact
 * duplicates.
 *
 * Reading the fields as one line pool is what keeps the parser independent of the field
 * mapping: the bank's lines are found whether they arrived in the title, the text, or the big
 * text, and which one they used is not assumed. Duplicates are dropped because Android posts
 * the same text in more than one field — that is one statement, and only genuinely different
 * values are a conflict.
 */
private fun PermittedNotificationContent.postedLines(): List<String> =
    listOfNotNull(title, text, bigText)
        .flatMap { field -> field.split('\n') }
        .map { line -> line.trim() }
        .filter { line -> line.isNotEmpty() }
        .distinct()

/** The observed bank attribution line, for example `VietinBank: 16/10/2026 07:38`. */
private val BANK_ATTRIBUTION = Regex("^VietinBank\\b(.*)$", RegexOption.IGNORE_CASE)

/** The observed transaction-amount label; `GD` is the transaction line of the sample. */
private const val TRANSACTION_LABEL = "GD"

/** The observed description label; `ND` is the memo line of the sample. */
private const val MEMO_LABEL = "ND"

/** A short label followed by a colon, matching `TK:`, `GD:`, `SDC:`, and `ND:`. */
private val LABELLED_LINE = Regex("^([A-Za-z]{1,4})\\s*:\\s*(.*)$")

private fun isBankAttribution(line: String): Boolean = BANK_ATTRIBUTION.containsMatchIn(line)

private fun isTransactionLine(line: String): Boolean = labelOf(line) == TRANSACTION_LABEL

private fun labelOf(line: String): String? =
    LABELLED_LINE.matchEntire(line)?.groupValues?.get(1)?.uppercase()

private fun labelledValues(lines: List<String>, label: String): List<String> =
    lines.filter { line -> labelOf(line) == label }.mapNotNull { line ->
        LABELLED_LINE.matchEntire(line)?.groupValues?.get(2)?.trim()
    }

/**
 * The amount the bank stated, and the direction its sign stated.
 *
 * Values are compared as whole statements — direction together with magnitude — so two
 * identical lines agree while a differing amount or a differing sign is a conflict. A conflict
 * leaves the amount and the direction unresolved rather than picking one.
 */
private fun resolveAmount(values: List<String>): Pair<NotificationField<Long>, BankDirection> {
    val stated = values.filter { value -> value.isNotEmpty() }.mapNotNull(::statedAmount)
    if (stated.isEmpty()) {
        return NotificationField.Unresolved(UnresolvedReason.Unreadable) to BankDirection.UNKNOWN
    }
    val distinct = stated.distinct()
    if (distinct.size > 1) {
        return NotificationField.Unresolved(UnresolvedReason.Ambiguous) to BankDirection.UNKNOWN
    }
    val single = distinct.single()
    return NotificationField.Present(single.magnitudeVnd) to single.direction
}

/** One amount as the bank stated it: the direction from its sign, and its whole-dong magnitude. */
private data class StatedAmount(val direction: BankDirection, val magnitudeVnd: Long)

/** An optional sign, grouped or plain digits, and an optional dong indicator. */
private val STATED_AMOUNT = Regex("^([+-])?\\s*(\\d{1,3}(?:,\\d{3})+|\\d+)\\s*(\\S*)$")

/** The only suffixes this VND-only app accepts after the digits. */
private val DONG_SUFFIXES = setOf("", "VND", "VNĐ", "Đ", "₫")

private fun statedAmount(value: String): StatedAmount? {
    val match = STATED_AMOUNT.matchEntire(value) ?: return null
    if (match.groupValues[3].uppercase() !in DONG_SUFFIXES) {
        return null
    }
    val magnitude = try {
        VndMoney.parseManualAmount(match.groupValues[2].replace(",", ""))
    } catch (rejection: LedgerValidationException) {
        return null
    }
    val direction = when (match.groupValues[1]) {
        "-" -> BankDirection.DEBIT
        "+" -> BankDirection.CREDIT
        else -> BankDirection.UNKNOWN
    }
    return StatedAmount(direction = direction, magnitudeVnd = magnitude)
}

/**
 * The wall-clock moment the bank stated on its attribution line.
 *
 * The bank sends `dd/MM/yyyy HH:mm` and no time zone, so the stated date and time are kept in
 * the app's manual-entry grammars without being converted: nothing here picks a zone or
 * produces an instant. The same non-lenient refusal of an impossible moment the manual entry
 * form applies (`30/02/2026` is never silently rolled over) decides whether the stated shape
 * can be read at all; anything else stays unresolved rather than being guessed.
 */
private fun resolveOccurredAt(lines: List<String>): NotificationField<BankStatedMoment> {
    val stated = lines.mapNotNull { line ->
        BANK_ATTRIBUTION.matchEntire(line)?.groupValues?.get(1)?.trim()?.removePrefix(":")?.trim()
    }.filter { value -> value.isNotEmpty() }
    if (stated.isEmpty()) {
        return NotificationField.Missing
    }

    val moments = stated.mapNotNull(::bankStatedMoment)
    if (moments.isEmpty()) {
        return NotificationField.Unresolved(UnresolvedReason.Unreadable)
    }
    val distinct = moments.distinct()
    if (distinct.size > 1) {
        return NotificationField.Unresolved(UnresolvedReason.Ambiguous)
    }
    return NotificationField.Present(distinct.single())
}

/** The observed bank timestamp shape: `dd/MM/yyyy HH:mm`, without a time zone. */
private val BANK_LOCAL_TIME = Regex("^(\\d{2})/(\\d{2})/(\\d{4})\\s+(\\d{2}):(\\d{2})$")

private fun bankStatedMoment(value: String): BankStatedMoment? {
    val match = BANK_LOCAL_TIME.matchEntire(value) ?: return null
    val dateText = "${match.groupValues[3]}-${match.groupValues[2]}-${match.groupValues[1]}"
    val timeText = "${match.groupValues[4]}:${match.groupValues[5]}"
    if (ManualDateTime.dateError(dateText) != null || ManualDateTime.timeError(timeText) != null) {
        return null
    }
    return BankStatedMoment(date = dateText, time = timeText)
}

/**
 * The description the bank stated, when it is something other than masking.
 *
 * The same redaction judgement the intake boundary uses decides whether the text is a memo at
 * all, so `ND: ........` and `ND: Redacted` are unresolved exactly as they are in the payload
 * filter. Two different descriptions are a conflict rather than a choice.
 */
private fun resolveMemo(values: List<String>): NotificationField<String> {
    val stated = values.filter { value -> value.isNotEmpty() }
    if (stated.isEmpty()) {
        return NotificationField.Missing
    }
    val meaningful = stated.filter(::carriesUsableContent).distinct()
    if (meaningful.isEmpty()) {
        return NotificationField.Unresolved(UnresolvedReason.Redacted)
    }
    if (meaningful.size > 1) {
        return NotificationField.Unresolved(UnresolvedReason.Ambiguous)
    }
    return NotificationField.Present(meaningful.single())
}
