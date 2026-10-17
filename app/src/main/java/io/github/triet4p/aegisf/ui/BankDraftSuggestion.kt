package io.github.triet4p.aegisf.ui

import io.github.triet4p.aegisf.data.PendingBankDraftEntity

/**
 * Review-only suggestion eligibility for bank drafts (S4.8).
 *
 * The English-first Laya model only sees an explicit, meaningful English description.
 * Anything else stays manual: a blank memo, an unresolved/missing bank memo without an
 * explicit user note, an opaque or identifier-only value, bank reference codes, non-ASCII
 * text (including Vietnamese diacritics) or other non-ASCII scripts, and any OTP/security-like
 * text never become inference input.
 *
 * Two strictly separate inputs, never concatenated (S4.8-A4 correction):
 *
 * - The stored bank memo (`draft.memo`, shown in the review Description field) is reused
 *   as-is when it is itself meaningful English. Opaque, code-heavy, or non-English memos
 *   stay manual; typing is never required to change them.
 * - An explicit English note typed into the separate review note field supplies meaning for
 *   an opaque or unsupported memo. The note starts EMPTY, is evaluated ALONE by the same
 *   gate below, and is never appended to the bank memo. The stored bank fields are never
 *   rewritten by this gate, and the note itself is suggestion-only context: it is not
 *   persisted to the draft and never becomes the saved/confirmed description.
 *
 * This gate never logs, retains, or invents merchant/category meaning. It only decides
 * whether the current input may be offered to the existing remembered-rule lookup and
 * the bounded [io.github.triet4p.aegisf.data.laya.LayaInferenceLifecycle] request. All
 * results still resolve against fresh category rows and change the category field only on
 * explicit user action; confirmation remains mandatory, and the model may still return
 * unknown/no-fit for admitted English that fits no category.
 */

private val ASCII_WORD = Regex("[A-Za-z]+")

private val ALNUM_TOKEN = Regex("[A-Za-z0-9]+")

private val OTP_GUARD = Regex("\\botp\\b", RegexOption.IGNORE_CASE)

private val VIETNAMESE_SECURITY_GUARD = Regex(
    "m\\u00E3\\s*(?:otp|x\\u00E1c\\s*th\\u1EF1c|x\\u00E1c\\s*nh\\u1EADn|b\\u1EA3o\\s*m\\u1EADt|pin)",
    RegexOption.IGNORE_CASE,
)

/**
 * Currency codes carry no language evidence either way: amounts such as `45000 VND`
 * accompany both English notes and bank memos, so they are excluded from the
 * recognized-proportion denominator instead of counting as unknown words.
 */
private val CURRENCY_TOKENS = setOf("vnd", "dong", "usd")

/**
 * Short English function words that may join a recognized content word, as `to` does in
 * `Payment to Alex` and `Flight to Hanoi`. Single letters and `an` are deliberately
 * excluded: `a`/`an` collide with frequent Vietnamese syllables (`an trua`), so they
 * carry no evidence here. A short function word alone never admits anything; it only
 * supports one recognized content word in a two-content-token phrase.
 */
private val SHORT_ENGLISH_FUNCTION_WORDS = setOf("at", "by", "in", "of", "on", "or", "to")

/**
 * General everyday-expense English vocabulary (S4.8-A4 correction).
 *
 * Deliberately NOT scraped from `tools/laya/expense_examples.json`, category descriptors,
 * or project tests: it is a curated set of ordinary English words across bills, food,
 * transport, health, shopping, and time, plus standard function words. Overlap with the
 * labeled examples (lunch, rent, payment) is coincidental — they are common English —
 * and narrow example-specific words (standup, pastries, colleagues, celebration,
 * downtown, lake, outings) are deliberately absent: a note using only such words stays
 * manual, which is safe and reviewable, never silently confirmed.
 *
 * Deliberately absent as well:
 *
 * - Ambiguous short tokens that double as Vietnamese syllables or codes (an, don, hang,
 *   nap, shop, tin, son, ban): excluding them keeps one loanword (cafe, payment, bill)
 *   from admitting a whole Vietnamese memo.
 * - No Vietnamese word or phrase appears anywhere in this gate: rejection of
 *   Vietnamese memos comes from the proportion rule below (too few recognized English
 *   words), never from matching Vietnamese markers.
 *
 * Tradeoff: mixed-language text with a strong English backbone may still pass (for
 * example `coffee for anh`), and terse but genuine English outside this set stays manual.
 * Both outcomes are contained downstream: an admitted phrase may still resolve to
 * unknown/no-fit, results resolve against current categories, and nothing changes the
 * category field or ledger without explicit user action.
 */
private val GENERAL_ENGLISH_WORDS = setOf(
    "after", "afternoon", "airport", "and", "annual", "antibiotics", "apartment",
    "bakery", "barber", "beauty", "bill", "bills", "breakfast", "broken", "brunch",
    "bus", "cafe", "cake", "candy", "car", "cash", "change", "checkup", "chocolate",
    "clinic", "clothes", "clothing", "coffee", "cousin", "daily", "dental", "dentist",
    "doctor", "drink", "drinks", "drugstore", "due", "electric", "electricity",
    "evening", "event", "expense", "expenses", "extra", "family", "fare", "fee", "fees",
    "flight", "flu", "food", "for", "friend", "friends", "from", "fruit", "fuel",
    "garage", "gas", "gift", "groceries", "grocery", "gym", "haircut", "health",
    "home", "hospital", "hotel", "house", "housing", "internet", "laundry", "live",
    "lunch", "maintenance", "mall", "market", "meal", "meals", "medical", "medicine",
    "membership", "metro", "mobile", "monthly", "morning", "music", "near", "new",
    "night", "office", "order", "our", "paid", "parking", "payment", "personal",
    "pharmacy", "phone", "plus", "purchase", "rent", "repair", "restaurant", "ride",
    "road", "salon", "school", "service", "shop", "shopping", "side", "snack", "snacks",
    "store", "subscription", "take", "taxi", "team", "tea", "that", "the", "these",
    "this", "those", "ticket", "toll", "train", "transport", "treatment", "trip",
    "upkeep", "utilities", "vegetable", "vegetables", "vehicle", "visit", "water",
    "weekend", "weekly", "wedding", "whole", "with", "work", "yearly", "your",
)

/**
 * Whether [text] is meaningful English that may be offered to the English-first model.
 *
 * Conservative by design: very short fragments, identifier-only values, non-ASCII text,
 * OTP/security-like text, and bank reference codes stay manual. Beyond that, at least
 * half of the content words must be recognized general English (a single loanword such as
 * `payment`, `cafe`, or `rent` cannot admit a Vietnamese memo on its own), except that
 * one recognized content word joined by a short English function word (`Payment to
 * Alex`, `Flight to Hanoi`) is enough evidence for a short phrase. A lone recognized
 * content word of four or more letters (`Rent`, `Haircut`) passes; `REF` stays manual.
 */
internal fun isMeaningfulEnglishForSuggestion(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.length < 3) return false
    if (trimmed.any { it.code > 127 }) return false
    if (OTP_GUARD.containsMatchIn(trimmed)) return false
    if (VIETNAMESE_SECURITY_GUARD.containsMatchIn(trimmed)) return false
    if (hasMixedLetterDigitCode(trimmed)) return false
    val words = ASCII_WORD.findAll(trimmed).map { it.value.lowercase() }.toList()
    if (words.isEmpty()) return false
    val small = words.filter { it.length < 3 }
    val content = words.filter { it.length >= 3 && it !in CURRENCY_TOKENS }
    if (content.isEmpty()) return false
    val recognized = content.count { it in GENERAL_ENGLISH_WORDS }
    if (content.size == 1) {
        return recognized == 1 && content[0].length >= 4
    }
    if (recognized >= 2) {
        return recognized * 2 >= content.size
    }
    return recognized == 1 && small.any { it in SHORT_ENGLISH_FUNCTION_WORDS }
}

/**
 * Bank reference codes mix letters and digits in one long run (`382K26A0`,
 * `T5RYKH8N`); plain amounts (`45000`) are digits only and stay neutral, so they never
 * trigger this guard. English notes with an amount (`Lunch at a cafe, 45000 VND`) pass
 * through; memos carrying transaction codes stay manual however much English surrounds
 * them, because the codes would otherwise be forwarded to the model as description.
 */
private fun hasMixedLetterDigitCode(text: String): Boolean =
    ALNUM_TOKEN.findAll(text).any { match ->
        val token = match.value
        token.length >= 5 && token.any { it.isLetter() } && token.any { it.isDigit() }
    }

/**
 * The effective suggestion input for one draft review, or null when no suggestion applies.
 *
 * The explicit note and the visible memo text are strictly separate inputs, never
 * concatenated: a non-blank note is evaluated ALONE, so keyboard behavior inside the
 * note field cannot poison the bank memo and transaction codes from the memo are never
 * forwarded alongside the note. A present but non-meaningful note yields null rather
 * than falling back to the memo, so junk in the note field cannot silently reuse the
 * bank text. When the note is blank, a parsed present bank memo is reused as-is; an
 * unresolved, missing, or unsupported-format bank memo has no stored value, so a
 * meaningful memo text there is already explicit user input. The stored `draft.memo`
 * is only read by callers for display and is never rewritten here.
 */
internal fun draftSuggestionInput(
    draft: PendingBankDraftEntity,
    memoText: String,
    suggestionNote: String = "",
): String? {
    val note = suggestionNote.trim()
    if (note.isNotEmpty()) {
        if (!isMeaningfulEnglishForSuggestion(note)) return null
        return note
    }
    val trimmed = memoText.trim()
    if (trimmed.isEmpty()) return null
    if (!isMeaningfulEnglishForSuggestion(trimmed)) return null
    return trimmed
}
