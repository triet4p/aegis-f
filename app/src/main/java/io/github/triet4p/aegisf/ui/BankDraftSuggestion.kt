package io.github.triet4p.aegisf.ui

import io.github.triet4p.aegisf.data.PendingBankDraftEntity

/**
 * Review-only suggestion input for bank drafts (S4.10-A3).
 *
 * English-preferred advisory only. The pinned Laya checkpoint and ONNX export are
 * documented English-first; no multilingual quality is claimed. Any non-blank note or
 * memo text is tried (including short phrases such as `buy`, `lunch`, `buy lunch`, and
 * Vietnamese text such as `an trua`) except genuine safety blocks below. Blank input
 * stays manual, runtime token-budget refusal stays separate, and low-confidence or
 * unknown/no-fit results stay visible for deliberate choice.
 *
 * Genuine safety blocks, kept narrow (never a language allowlist):
 *
 * - One-time-password / security-like text (`OTP_GUARD`, `VIETNAMESE_SECURITY_GUARD`).
 * - Bank reference codes that mix letters and digits in one long run
 *   (`hasMixedLetterDigitCode`), so transaction codes are never forwarded to the model.
 *
 * Two strictly separate inputs, never concatenated (S4.8-A4 correction, kept):
 *
 * - The visible memo text is reused as-is when the note is blank.
 * - An explicit note typed into the separate review note field supplies meaning for
 *   an opaque or unsupported memo. The note starts EMPTY, is evaluated ALONE, and is
 *   never appended to the bank memo. The stored bank fields are never rewritten by this
 *   gate, and the note itself is suggestion-only context: it is not persisted to the
 *   draft and never becomes the saved/confirmed description.
 *
 * This gate never logs, retains, or invents merchant/category meaning. It only decides
 * whether the current input may be offered to the existing remembered-rule lookup and
 * the bounded [io.github.triet4p.aegisf.data.laya.LayaInferenceLifecycle] request. All
 * results still resolve against fresh category rows and change the category field only on
 * explicit user action; confirmation remains mandatory, and the model may still return
 * unknown/no-fit for admitted input that fits no category.
 */

private val ALNUM_TOKEN = Regex("[A-Za-z0-9]+")

private val OTP_GUARD = Regex("\\botp\\b", RegexOption.IGNORE_CASE)

private val VIETNAMESE_SECURITY_GUARD = Regex(
    "m\\u00E3\\s*(?:otp|x\\u00E1c\\s*th\\u1EF1c|x\\u00E1c\\s*nh\\u1EADn|b\\u1EA3o\\s*m\\u1EADt|pin)",
    RegexOption.IGNORE_CASE,
)

/**
 * Whether [text] is blocked for safety and must never become inference input.
 *
 * Narrow by design: only OTP/security-like text and bank reference codes. Language,
 * script, length, or vocabulary never block here; quality of an unsupported language
 * is a review decision (the model may return unknown), not an input filter.
 */
internal fun isBlockedForSuggestionSafety(text: String): Boolean {
    if (OTP_GUARD.containsMatchIn(text)) return true
    if (VIETNAMESE_SECURITY_GUARD.containsMatchIn(text)) return true
    if (hasMixedLetterDigitCode(text)) return true
    return false
}

/**
 * Bank reference codes mix letters and digits in one long run (`382K26A0`,
 * `T5RYKH8N`); plain amounts (`45000`) are digits only and stay neutral, so they never
 * trigger this guard.
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
 * forwarded alongside the note. A present but safety-blocked note yields null rather
 * than falling back to the memo, so a code/OTP in the note field cannot silently reuse
 * the bank text. When the note is blank, the visible memo text is reused as-is unless
 * it is blank or safety-blocked. The stored `draft.memo` is only read by callers for
 * display and is never rewritten here.
 */
internal fun draftSuggestionInput(
    draft: PendingBankDraftEntity,
    memoText: String,
    suggestionNote: String = "",
): String? {
    val note = suggestionNote.trim()
    if (note.isNotEmpty()) {
        if (isBlockedForSuggestionSafety(note)) return null
        return note
    }
    val trimmed = memoText.trim()
    if (trimmed.isEmpty()) return null
    if (isBlockedForSuggestionSafety(trimmed)) return null
    return trimmed
}

