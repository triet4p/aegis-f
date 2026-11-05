package io.github.triet4p.aegisf.privacy

/**
 * The lexical shape of a value this vault has masked: a hint about how the characters are
 * written, never a claim about what the value means.
 *
 * A numeric shape says "this is written with digits", not "this is money". The model assigns
 * the role by choosing which value alias to pass into a role-specific tool argument; the host
 * then parses and validates that value locally for that argument. A numeric account number and
 * a numeric amount are the same shape; nothing here makes one the other.
 */
enum class IssuedValueShape(val wire: String) {
    NUMERIC("numeric"),
    TEXT("text"),
    ALPHANUMERIC("alphanumeric"),
}

/**
 * One value this vault has already issued: the opaque token and its lexical shape hint.
 *
 * It deliberately carries no canonical value, no first surface and no position, so a list of
 * these can be handed back to the model without rehydrating anything, and the hint cannot be
 * used as an eligibility gate for a semantic field.
 */
data class IssuedMaskToken(val token: String, val shape: IssuedValueShape)

/** Masking refused to produce outbound text; no request was emitted. The reason names no raw value. */
class MaskingBlockedException(message: String) : IllegalStateException(message)

/** An inbound token reference was refused; nothing was rehydrated and nothing was dispatched. */
class TokenValidationException(message: String) : IllegalArgumentException(message)

/**
 * The scoped reversible mapping behind the S8.6 pre-egress boundary.
 *
 * One vault serves one agent session. Every sensitive literal is replaced by a single neutral,
 * scope-bound, unforgeable token (`⟦V-<scope>-<sequence>⟧`). The token states an identity and
 * nothing else: no amount/date/account namespace, no semantic kind and no field authority.
 * The model learns the value's *lexical shape* from the observation and inventory, chooses the
 * meaning that fits the sentence and the tool argument, and the host parses and validates the
 * underlying value locally for that argument. A token is therefore usable as an amount, a date
 * or a time only through the runtime's argument-specific parse, never by its spelling.
 *
 * Equality stays decidable: equal values mint one token (grouping variants of a number,
 * spellings of the same date, and the same wall-clock time share a canonical form). Distinct
 * values never share a token. Mappings never leave memory: [toString] is redacted, nothing is
 * logged, and there is no serialization hook.
 *
 * Cross-session isolation is structural: the scope tag baked into every token binds it to this
 * vault, and a token from another scope is unknown here even when the raw value coincides.
 * Capacity is bounded ([maxEntries]); exceeding it fails closed with [MaskingBlockedException]
 * instead of reusing or dropping a mapping.
 */
class SensitiveVault(
    scopeId: String,
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    init {
        require(maxEntries in 1..MAX_SEQUENCE) {
            "vault capacity must stay inside the token sequence space"
        }
    }

    /** The tag baked into every token this vault mints; inbound tokens must carry it. */
    val scopeTag: String = scopeTagOf(scopeId)

    private data class Entry(
        val canonical: String,
        val firstSurface: String,
        val shape: IssuedValueShape,
        /** Mint order inside this scope, so an issued-token list is stable and complete. */
        val order: Int,
    )

    private val lock = Any()
    private var sequence = 0
    private val byCanonical = mutableMapOf<String, String>()
    private val byToken = mutableMapOf<String, Entry>()

    /** How many distinct values are currently mapped; exposed for capacity tests. */
    fun entryCount(): Int = synchronized(lock) { byToken.size }

    /**
     * Every token this vault has issued, in mint order, with only its lexical shape hint.
     *
     * This is the authoritative inventory the host hands the model on every turn: it is
     * complete for this run (never truncated, never filtered), carries no canonical value or
     * first surface, and cannot be used to rehydrate anything. A token minted after this call
     * appears in the next inventory; a token from another vault never appears at all.
     */
    fun issuedTokens(): List<IssuedMaskToken> = synchronized(lock) {
        byToken.entries
            .sortedBy { it.value.order }
            .map { IssuedMaskToken(it.key, it.value.shape) }
    }

    /** The lexical shape hint for a known token, or null when it was not minted here. */
    fun shapeOf(token: String): IssuedValueShape? = synchronized(lock) { byToken[token]?.shape }

    /**
     * Whether [text] is a strict prefix of any raw surface this vault has mapped.
     *
     * The incremental public gate uses this to hold back a trailing fragment that could still
     * become a complete mapped value once more streamed characters arrive. It answers a
     * yes/no about shape only and never returns the surface itself.
     */
    fun isMappedSurfacePrefix(text: String): Boolean {
        if (text.isEmpty()) return false
        synchronized(lock) {
            return byToken.values.any { entry ->
                (entry.firstSurface.length > text.length && entry.firstSurface.startsWith(text)) ||
                    (entry.canonical.length > text.length && entry.canonical.startsWith(text))
            }
        }
    }

    /**
     * The longest raw surface this vault has mapped, in characters, or 0 when nothing is mapped.
     *
     * The incremental public-output gate holds back this many trailing characters before it
     * displays streamed model text, so a complete mapped value can never straddle the boundary
     * between displayed and not-yet-checked text. It names a length only: no value, no token.
     */
    fun maxMappedSurfaceLength(): Int = synchronized(lock) {
        byToken.values.maxOfOrNull { maxOf(it.firstSurface.length, it.canonical.length) } ?: 0
    }

    /**
     * Masks every sensitive literal in free text, longest match first, without overlap.
     *
     * Already-minted tokens are protected spans: masking is idempotent and never
     * double-masks, so masked history and continuations pass through unchanged.
     *
     * Detection (precision only; it never assigns a semantic kind) covers `YYYY-MM-DD` and
     * `D/M`/`D/M/YYYY` calendar dates, `HH:mm`/`Hh`/`HhMM` wall-clock times, grouped numbers
     * (`15,000,000`, `15.000.000`), digit runs adjacent to a VND unit, digit runs named by an
     * account hint, other standalone digit runs of four or more digits, and mixed letter-digit
     * reference codes. A year-less day/month literal is detected when a cue word introduces it,
     * when it is the whole answer, or when both components are two-digit, so a ratio-like
     * `1/2` is not mistaken for a date. All matched values become the same neutral token.
     */
    fun maskText(text: String): String = applyMask(text, commit = true)

    /**
     * Renders [text] with existing mappings only: known values mask to their tokens, unknown
     * values render as [UNMAPPED_SENTINEL]. Never mints, so span verification can compare
     * structure without polluting the vault on a refused path.
     */
    internal fun maskedPreview(text: String): String = applyMask(text, commit = false)

    /**
     * Replaces every known token in free text with the exact surface it was first seen as,
     * so a faithfully copied masked span rehydrates to the byte-identical source slice.
     * Unknown tokens refuse with [TokenValidationException].
     */
    fun rehydrateText(text: String): String =
        TOKEN_PATTERN.replace(text) { match ->
            validatedEntry(match.value).firstSurface
        }

    /** The exact first surface behind one neutral token. */
    fun rehydrateValue(token: String): String = validatedEntry(token).firstSurface

    /**
     * The whole-dong amount a token names, or a refusal when its value is not an integer.
     *
     * The caller has already chosen this value for an amount argument; this parse is the local
     * validation of that choice. A date or time token is not an integer and refuses here. A
     * numeric value that also looks like an account number parses: the model's selection plus
     * the source/clarification binding carry the meaning, and the application's own amount
     * validation still runs. The parse alone never promotes a value semantically.
     */
    fun parseAmount(token: String): Long {
        val canonical = validatedEntry(token).canonical
        if (!INTEGER_FULL.matches(canonical)) {
            throw TokenValidationException("the selected value is not a whole-dong amount")
        }
        return canonical.toLongOrNull()
            ?: throw TokenValidationException("the selected value is outside the whole-dong range")
    }

    /**
     * The stored `YYYY-MM-DD` a token names.
     *
     * A value that states its year normalizes exactly. A day/month value that states no year
     * resolves only against [referenceYear] — the trusted local calendar year of the capture
     * the value was read from. Without that reference the call refuses instead of inventing a
     * year, so a genuinely missing year becomes a clarification, not a guess.
     */
    fun parseDate(token: String, referenceYear: Int? = null): String =
        resolveDateCanonical(validatedEntry(token).canonical, referenceYear)

    /** The `HH:mm` wall-clock time a token names, or a refusal when it is not a time value. */
    fun parseTime(token: String): String {
        val canonical = validatedEntry(token).canonical
        if (!TIME_FULL.matches(canonical)) {
            throw TokenValidationException("the selected value is not a wall-clock time")
        }
        return canonical
    }

    /** Whether [token] was minted by this vault; used to tell unknown tokens from foreign ones. */
    fun isKnownToken(token: String): Boolean = synchronized(lock) { byToken.containsKey(token) }

    /**
     * Whether any raw surface mapped in this scope appears outside token spans in [text].
     * Scans outbound wire text (must be false) and inbound model text (a hit means the model
     * emitted an unmasked sensitive literal, which fails closed).
     */
    fun scanLeak(text: String): Boolean {
        if (text.isEmpty()) return false
        val stripped = TOKEN_PATTERN.replace(text, " ")
        synchronized(lock) {
            if (byToken.isEmpty()) return false
            return byToken.values.any { entry ->
                entry.firstSurface in stripped || entry.canonical in stripped
            }
        }
    }

    /**
     * The lexical shapes whose mapped surfaces occur in [text], without returning the surfaces,
     * canonical values, tokens, or match lengths. Intended for safe boundary diagnostics.
     */
    fun scanLeakShapes(text: String): Set<IssuedValueShape> {
        if (text.isEmpty()) return emptySet()
        val stripped = TOKEN_PATTERN.replace(text, " ")
        synchronized(lock) {
            if (byToken.isEmpty()) return emptySet()
            val shapes = mutableSetOf<IssuedValueShape>()
            for (entry in byToken.values) {
                if (entry.firstSurface in stripped || entry.canonical in stripped) {
                    shapes.add(entry.shape)
                }
            }
            return shapes
        }
    }

    /**
     * Whether [text] is exactly one mapped surface or canonical value (token spans stripped).
     *
     * The decoded response guard uses this only for the narrow documented provider-identifier
     * metadata fields. A provider-assigned tool-call id may contain a short mapped surface as
     * part of a longer opaque id (`call_…sk11h6ax0`) and is not content; but an identifier that
     * *is* the value (`10000`) is a real leak and is still refused.
     */
    fun isMappedValueExact(text: String): Boolean {
        if (text.isEmpty()) return false
        val stripped = TOKEN_PATTERN.replace(text, " ")
        synchronized(lock) {
            return byToken.values.any {
                it.firstSurface == stripped || it.canonical == stripped
            }
        }
    }

    /** Local ordering over two values selected as amounts; refuses when one is not an integer. */
    fun compareAmounts(first: String, second: String): Int =
        parseAmount(first).compareTo(parseAmount(second))

    /** Local chronological ordering over two values selected as dates. */
    fun compareDates(first: String, second: String, referenceYear: Int? = null): Int =
        parseDate(first, referenceYear).compareTo(parseDate(second, referenceYear))

    /** Local chronological ordering over two values selected as times. */
    fun compareTimes(first: String, second: String): Int =
        parseTime(first).compareTo(parseTime(second))

    override fun toString(): String = "SensitiveVault(scopeTag=$scopeTag,entries=${entryCount()})"

    private data class Hit(
        val start: Int,
        val end: Int,
        val surface: String,
        val canonical: String,
        val shape: IssuedValueShape,
    )

    private fun applyMask(text: String, commit: Boolean): String {
        if (text.isEmpty()) return text
        val protected = TOKEN_PATTERN.findAll(text).map { it.range.first to it.range.last + 1 }.toList()
        val accepted = protected.toMutableList()
        val hits = mutableListOf<Hit>()
        fun add(range: IntRange, surface: String, canonical: String, shape: IssuedValueShape) {
            hits.add(Hit(range.first, range.last + 1, surface, canonical, shape))
        }
        for (match in ISO_DATE_PATTERN.findAll(text)) {
            add(match.range, match.value, match.value, IssuedValueShape.NUMERIC)
        }
        for (match in SLASH_DATE_PATTERN.findAll(text)) {
            val dayText = match.groups[1]?.value ?: continue
            val monthText = match.groups[2]?.value ?: continue
            val day = dayText.toIntOrNull() ?: continue
            val month = monthText.toIntOrNull() ?: continue
            if (day !in 1..31 || month !in 1..12) continue
            if (match.groups[3] == null &&
                !hasDateCueBefore(text, match.range.first) &&
                !isStandalone(text, match.range) &&
                !(dayText.length == 2 && monthText.length == 2)
            ) {
                continue
            }
            add(match.range, match.value, canonicalDate(match.value), IssuedValueShape.NUMERIC)
        }
        for (match in TIME_PATTERN.findAll(text)) {
            add(match.range, match.value, match.value, IssuedValueShape.NUMERIC)
        }
        for (match in INFORMAL_TIME_PATTERN.findAll(text)) {
            val hour = match.groups[1]?.value?.toIntOrNull() ?: continue
            val minute = match.groups[2]?.value?.toIntOrNull() ?: 0
            if (hour !in 0..23 || minute !in 0..59) continue
            add(match.range, match.value, canonicalTime(match.value), IssuedValueShape.NUMERIC)
        }
        for (match in GROUPED_NUMBER_PATTERN.findAll(text)) {
            add(match.range, match.value, match.value.filter { it.isDigit() }, IssuedValueShape.NUMERIC)
        }
        for (match in UNIT_AMOUNT_PATTERN.findAll(text)) {
            val group = match.groups[1] ?: continue
            add(group.range, group.value, group.value.filter { it.isDigit() }, IssuedValueShape.NUMERIC)
        }
        // The bare "d"/"D" dong shorthand an English note uses ("50000d", "50,000d"). The whole
        // surface is masked so the unit cannot remain beside a token, and the canonical is the
        // whole-dong digits so the amount role resolves it locally. A bare form needs four digits
        // so a duration such as "3d" is not reclassified as money; the grouped form is already
        // unambiguous.
        for (match in DONG_D_SUFFIX_PATTERN.findAll(text)) {
            add(match.range, match.value, match.value.filter { it.isDigit() }, IssuedValueShape.NUMERIC)
        }
        for (match in ACCOUNT_HINT_PATTERN.findAll(text)) {
            val group = match.groups[1] ?: continue
            add(group.range, group.value, group.value, IssuedValueShape.NUMERIC)
        }
        for (match in BARE_DIGITS_PATTERN.findAll(text)) {
            add(match.range, match.value, match.value, IssuedValueShape.NUMERIC)
        }
        for (match in BANK_CODE_PATTERN.findAll(text)) {
            val surface = match.value
            if (surface.any { it.isLetter() } && surface.any { it.isDigit() }) {
                add(match.range, surface, surface, IssuedValueShape.ALPHANUMERIC)
            }
        }
        val ordered = hits.sortedWith(compareBy({ it.start }, { -(it.end - it.start) }))
        val chosen = mutableListOf<Hit>()
        for (hit in ordered) {
            val span = hit.start to hit.end
            if (accepted.none { other -> span.first < other.second && other.first < span.second }) {
                accepted.add(span)
                chosen.add(hit)
            }
        }
        // Mint in ascending order so each token is created once, then apply left to right
        // while tracking the shift masked offsets accumulate.
        val ascending = chosen.sortedBy { it.start }
        val tokens = ascending.map { hit -> tokenFor(hit, commit) }
        var masked = text
        var delta = 0
        for ((index, hit) in ascending.withIndex()) {
            val token = tokens[index]
            val applyAt = hit.start + delta
            masked = masked.replaceRange(applyAt, applyAt + (hit.end - hit.start), token)
            delta += token.length - (hit.end - hit.start)
        }
        return masked
    }

    private fun canonicalDate(surface: String): String {
        SLASH_DATE_FULL.matchEntire(surface)?.let { match ->
            val day = match.groups[1]!!.value.padStart(2, '0')
            val month = match.groups[2]!!.value.padStart(2, '0')
            return "${match.groups[3]!!.value}-$month-$day"
        }
        SLASH_DATE_PARTIAL.matchEntire(surface)?.let { match ->
            val day = match.groups[1]!!.value.padStart(2, '0')
            val month = match.groups[2]!!.value.padStart(2, '0')
            return "$UNKNOWN_YEAR-$month-$day"
        }
        return surface
    }

    private fun canonicalTime(surface: String): String {
        INFORMAL_TIME_FULL.matchEntire(surface)?.let { match ->
            val hour = match.groups[1]!!.value.toInt()
            val minute = match.groups[2]?.value?.toInt() ?: 0
            return "%02d:%02d".format(hour, minute)
        }
        return surface
    }

    /** The stored `YYYY-MM-DD` behind a canonical value, resolving a year-less date locally. */
    private fun resolveDateCanonical(canonical: String, referenceYear: Int?): String {
        if (ISO_DATE_FULL.matches(canonical)) return canonical
        YEARLESS_DATE_FULL.matchEntire(canonical)?.let { match ->
            val year = referenceYear
                ?: throw TokenValidationException(
                    "the masked date states no year and no local reference year was supplied",
                )
            return "%04d-%s-%s".format(year, match.groups[1]!!.value, match.groups[2]!!.value)
        }
        throw TokenValidationException("the selected value is not a calendar date")
    }

    /** Whether a date cue word ends within a short window before [index]. */
    private fun hasDateCueBefore(text: String, index: Int): Boolean {
        val from = (index - DATE_CUE_WINDOW).coerceAtLeast(0)
        return DATE_CUE_BEFORE.containsMatchIn(text.substring(from, index))
    }

    /** Whether the matched literal is the whole trimmed text (a bare clarification answer). */
    private fun isStandalone(text: String, range: IntRange): Boolean =
        text.trim().length == (range.last - range.first + 1) && text.trim() == text.substring(range)

    /**
     * The neutral token for one detected value: the stable mapping when the canonical form is
     * already known, a fresh mint when [commit] allows it, [UNMAPPED_SENTINEL] in a preview.
     */
    private fun tokenFor(hit: Hit, commit: Boolean): String = synchronized(lock) {
        byCanonical[hit.canonical]?.let { return it }
        if (!commit) return UNMAPPED_SENTINEL
        if (byToken.size >= maxEntries) {
            throw MaskingBlockedException("the masking scope is full; refusing to drop a mapping")
        }
        sequence += 1
        val token = "⟦V-$scopeTag-%04d⟧".format(sequence)
        byCanonical[hit.canonical] = token
        byToken[token] = Entry(hit.canonical, hit.surface, hit.shape, sequence)
        return token
    }

    private fun validatedEntry(token: String): Entry {
        val match = TOKEN_FULL.matchEntire(token)
            ?: throw TokenValidationException("the reference is not a well-formed masked value")
        if (match.groups[1]?.value != scopeTag) {
            throw TokenValidationException("the masked value belongs to another session")
        }
        synchronized(lock) {
            return byToken[token]
                ?: throw TokenValidationException("the masked value is unknown in this session")
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 256
        const val MAX_SEQUENCE = 9_999

        /** Renders in previews where a value has no mapping; never a valid token. */
        internal const val UNMAPPED_SENTINEL = "⟦UNMAPPED⟧"

        /** Token spans inside outbound text; also used to strip tokens before leak scans. */
        val TOKEN_PATTERN = Regex("⟦V-([A-Za-z0-9]{4,12})-(\\d{4})⟧")
        private val TOKEN_FULL = Regex("^⟦V-([A-Za-z0-9]{4,12})-(\\d{4})⟧$")

        private val INTEGER_FULL = Regex("^\\d{1,18}$")
        private val TIME_FULL = Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$")
        private val ISO_DATE_PATTERN = Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b")
        private val SLASH_DATE_PATTERN = Regex("(?<![\\d/])(\\d{1,2})/(\\d{1,2})(?:/(\\d{4}))?(?![\\d/])")
        private val SLASH_DATE_FULL = Regex("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$")
        private val SLASH_DATE_PARTIAL = Regex("^(\\d{1,2})/(\\d{1,2})$")
        private val ISO_DATE_FULL = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        private val YEARLESS_DATE_FULL = Regex("^\\?{4}-(\\d{2})-(\\d{2})$")
        private val DATE_CUE_BEFORE =
            Regex("(?i)(?:ngày|ngay|hôm|hom|date|on)\\s*:?\\s*$")
        private val TIME_PATTERN = Regex("\\b(?:[01][0-9]|2[0-3]):[0-5][0-9]\\b")
        private val INFORMAL_TIME_PATTERN = Regex("(?<![\\d:])([01]?\\d|2[0-3])h([0-5]\\d)?(?![\\p{L}\\d])")
        private val INFORMAL_TIME_FULL = Regex("(\\d{1,2})h(\\d{2})?")
        private val GROUPED_NUMBER_PATTERN = Regex("\\b\\d{1,3}(?:[.,]\\d{3})+\\b")
        private val UNIT_AMOUNT_PATTERN =
            Regex("(?i)(\\d{1,3}(?:[.,]\\d{3})+|\\d+)(?![.,]\\d)\\s*(?:VND|₫|đồng|dong|đ)(?![\\p{L}])")
        private val DONG_D_SUFFIX_PATTERN =
            Regex("(?i)(?<![\\p{L}\\d])(\\d{1,3}(?:[.,]\\d{3})+|\\d{4,})\\s*d(?![\\p{L}\\d])")
        private val ACCOUNT_HINT_PATTERN =
            Regex("(?i)(?:\\baccount(?:\\s+ending)?|\\bending)\\s*:?\\s*#?\\s*(\\d{4,})")
        private val BARE_DIGITS_PATTERN = Regex("\\b\\d{4,}\\b")
        private val BANK_CODE_PATTERN = Regex("\\b[A-Za-z0-9]{5,}\\b")

        /** The canonical date component of a day/month surface that states no year. */
        private const val UNKNOWN_YEAR = "????"

        /** How far before a date literal a date cue word may sit. */
        private const val DATE_CUE_WINDOW = 16

        internal fun scopeTagOf(scopeId: String): String {
            val alnum = scopeId.filter { it.isLetterOrDigit() }.take(8)
            val hash = "%04x".format(scopeId.hashCode() and 0xffff)
            return (if (alnum.isEmpty()) "s" else alnum) + hash
        }
    }
}
