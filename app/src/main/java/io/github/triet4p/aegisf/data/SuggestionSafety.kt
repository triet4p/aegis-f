package io.github.triet4p.aegisf.data

/**
 * The narrow safety guard that decides which text may be offered to the local model.
 *
 * It lives in the data layer because every source of model input passes through it: the memo
 * and the explicit note of a bank draft review, and the stored event readings that S5.4 adds
 * to the effective context. A guard that only the review form applied would let the same
 * text reach the model through another door.
 *
 * The guard is deliberately narrow: only OTP/security-like text and bank reference codes are
 * refused. Language, script, length, or vocabulary never block here — the quality of an
 * unsupported language is a review decision (the model may return unknown), not an input
 * filter.
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
fun isBlockedForSuggestionSafety(text: String): Boolean {
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
