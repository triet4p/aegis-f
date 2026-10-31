package io.github.triet4p.aegisf.privacy

/**
 * The kinds of sensitive literal the local boundary minimizes.
 *
 * Amounts, calendar dates, wall-clock times, account digit runs and bank reference codes each
 * mint their own token namespace ([SensitiveKind.prefix]), so a token is only authority for the
 * field type it was minted for. A date token in an amount field is refused, never converted.
 */
enum class SensitiveKind(val prefix: String) {
    AMOUNT("AMT"),
    DATE("DATE"),
    TIME("TIME"),
    ACCOUNT("ACCT"),
    BANK("BANK"),
}

/**
 * One value this vault has already issued: the opaque token and the kind of value it stands for.
 *
 * It deliberately carries no canonical value, no first surface and no position, so a list of
 * these can be handed back to the model without rehydrating anything.
 */
data class IssuedMaskToken(val token: String, val kind: SensitiveKind)

/** Masking refused to produce outbound text; no request was emitted. The reason names no raw value. */
class MaskingBlockedException(message: String) : IllegalStateException(message)

/** An inbound token reference was refused; nothing was rehydrated and nothing was dispatched. */
class TokenValidationException(message: String) : IllegalArgumentException(message)

/**
 * The scoped reversible mapping behind the S8.6 pre-egress boundary.
 *
 * One vault serves one agent session: every distinct raw value of a kind mints one stable token
 * within the scope (equal values mask equally, so equality stays decidable in masked text),
 * distinct values never share a token, and ordering/relative-time stay decidable through the
 * local comparators without exporting the underlying literals. Mappings never leave memory:
 * [toString] is redacted, nothing is logged, and there is no serialization hook.
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
        val kind: SensitiveKind,
        val canonical: String,
        val firstSurface: String,
        /** Mint order inside this scope, so an issued-token list is stable and complete. */
        val order: Int,
    )

    private val lock = Any()
    private var sequence = 0
    private val byRaw = mutableMapOf<Pair<SensitiveKind, String>, String>()
    private val byToken = mutableMapOf<String, Entry>()

    /** How many distinct raw values are currently mapped; exposed for capacity tests. */
    fun entryCount(): Int = synchronized(lock) { byToken.size }

    /**
     * Every token this vault has issued, in mint order, with only its kind.
     *
     * This is the authoritative inventory the host hands the model on every turn: it is
     * complete for this run (never truncated, never filtered), carries no canonical value or
     * first surface, and cannot be used to rehydrate anything. A token minted after this call
     * appears in the next inventory; a token from another vault never appears at all.
     */
    fun issuedTokens(): List<IssuedMaskToken> = synchronized(lock) {
        byToken.entries
            .sortedBy { it.value.order }
            .map { IssuedMaskToken(it.key, it.value.kind) }
    }

    /**
     * Masks every sensitive literal in free text, longest match first, without overlap.
     *
     * Already-minted tokens are protected spans: masking is idempotent and never
     * double-masks, so masked history and continuations pass through unchanged.
     * Supported detection, in priority order:
     *
     * - `YYYY-MM-DD` calendar dates and `HH:mm` wall-clock times;
     * - grouped amounts (`15,000,000`) and digit runs adjacent to a VND unit
     *   (`28000 VND`, `28,000 dong`); the unit word itself stays in cleartext;
     * - digit runs named by an account hint (`account ending 4821`);
     * - other standalone digit runs of four or more digits (account-like);
     * - mixed letter-digit runs of five or more characters (bank reference codes).
     *
     * Bare digit runs under four digits (ids, revisions, spans, counts) are never masked.
     * A bare ungrouped run without a VND unit is typed account-like even when it names an
     * amount: without a unit or grouping the two are indistinguishable, and guessing would
     * let a token mean two things. Amount spellings that differ only by grouping
     * (`15,000,000` vs `15000000`) share one token.
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
            validatedEntry(match.value, kindOf(match)).firstSurface
        }

    /** The exact whole-dong digits behind an amount token; range is checked by the caller. */
    fun rehydrateAmount(token: String): Long {
        val canonical = validatedEntry(token, SensitiveKind.AMOUNT).canonical
        return canonical.toLongOrNull()
            ?: throw TokenValidationException("the amount reference is not a valid whole-dong value")
    }

    /** The exact `YYYY-MM-DD` string behind a date token. */
    fun rehydrateDate(token: String): String = validatedEntry(token, SensitiveKind.DATE).canonical

    /** The exact `HH:mm` string behind a time token. */
    fun rehydrateTime(token: String): String = validatedEntry(token, SensitiveKind.TIME).canonical

    /** The exact digit run behind an account token. */
    fun rehydrateAccount(token: String): String = validatedEntry(token, SensitiveKind.ACCOUNT).canonical

    /** The exact reference code behind a bank token. */
    fun rehydrateBank(token: String): String = validatedEntry(token, SensitiveKind.BANK).canonical

    /** Whether [token] was minted by this vault; used to tell unknown tokens from foreign ones. */
    fun isKnownToken(token: String): Boolean = synchronized(lock) { byToken.containsKey(token) }

    /** The token already minted for these whole-dong digits, or null when unmapped. */
    fun existingAmountToken(digits: String): String? = synchronized(lock) {
        byRaw[SensitiveKind.AMOUNT to digits]
    }

    /** The token already minted for this digit run, or null when unmapped. */
    fun existingAccountToken(digits: String): String? = synchronized(lock) {
        byRaw[SensitiveKind.ACCOUNT to digits]
    }

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
     * The kinds whose mapped surfaces occur in [text], without returning the surfaces,
     * canonical values, tokens, or match lengths. Intended for safe boundary diagnostics.
     */
    fun scanLeakKinds(text: String): Set<SensitiveKind> {
        if (text.isEmpty()) return emptySet()
        val stripped = TOKEN_PATTERN.replace(text, " ")
        synchronized(lock) {
            if (byToken.isEmpty()) return emptySet()
            val kinds = mutableSetOf<SensitiveKind>()
            for (entry in byToken.values) {
                if (entry.firstSurface in stripped || entry.canonical in stripped) {
                    kinds.add(entry.kind)
                }
            }
            return kinds
        }
    }

    /**
     * Local ordering over two amount tokens of this scope; negative/zero/positive like
     * [Comparable.compareTo]. Powers masked-text reasoning that needs order without literals.
     */
    fun compareAmounts(first: String, second: String): Int =
        rehydrateAmount(first).compareTo(rehydrateAmount(second))

    /** Local chronological ordering over two date tokens of this scope. */
    fun compareDates(first: String, second: String): Int =
        rehydrateDate(first).compareTo(rehydrateDate(second))

    /** Local chronological ordering over two time tokens of this scope. */
    fun compareTimes(first: String, second: String): Int =
        rehydrateTime(first).compareTo(rehydrateTime(second))

    override fun toString(): String = "SensitiveVault(scopeTag=$scopeTag,entries=${entryCount()})"

    private data class Hit(val start: Int, val end: Int, val kind: SensitiveKind, val surface: String)

    private fun applyMask(text: String, commit: Boolean): String {
        if (text.isEmpty()) return text
        val protected = TOKEN_PATTERN.findAll(text).map { it.range.first to it.range.last + 1 }.toList()
        val accepted = protected.toMutableList()
        val hits = mutableListOf<Hit>()
        fun collect(pattern: Regex, kind: SensitiveKind, surfaceOf: (MatchResult) -> Pair<IntRange, String>?) {
            for (match in pattern.findAll(text)) {
                val (range, surface) = surfaceOf(match) ?: continue
                hits.add(Hit(range.first, range.last + 1, kind, surface))
            }
        }
        collect(DATE_PATTERN, SensitiveKind.DATE) { it.range to it.value }
        collect(TIME_PATTERN, SensitiveKind.TIME) { it.range to it.value }
        collect(GROUPED_AMOUNT_PATTERN, SensitiveKind.AMOUNT) { it.range to it.value }
        collect(UNIT_AMOUNT_PATTERN, SensitiveKind.AMOUNT) { match ->
            val group = match.groups[1] ?: return@collect null
            group.range to group.value
        }
        collect(ACCOUNT_HINT_PATTERN, SensitiveKind.ACCOUNT) { match ->
            val group = match.groups[1] ?: return@collect null
            group.range to group.value
        }
        collect(BARE_DIGITS_PATTERN, SensitiveKind.ACCOUNT) { it.range to it.value }
        for (match in BANK_CODE_PATTERN.findAll(text)) {
            val surface = match.value
            if (surface.any { it.isLetter() } && surface.any { it.isDigit() }) {
                hits.add(Hit(match.range.first, match.range.last + 1, SensitiveKind.BANK, surface))
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
        val tokens = ascending.map { hit -> tokenFor(hit.kind, hit.surface, commit) }
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

    private fun canonicalOf(kind: SensitiveKind, surface: String): String =
        if (kind == SensitiveKind.AMOUNT) surface.replace(",", "") else surface

    /**
     * The token for a raw surface: the stable mapping when it exists, a fresh mint when
     * [commit] allows it, [UNMAPPED_SENTINEL] for preview rendering of unknown values.
     */
    private fun tokenFor(kind: SensitiveKind, surface: String, commit: Boolean): String {
        val canonical = canonicalOf(kind, surface)
        synchronized(lock) {
            byRaw[kind to canonical]?.let { return it }
            if (!commit) return UNMAPPED_SENTINEL
            if (byToken.size >= maxEntries) {
                throw MaskingBlockedException("the masking scope is full; refusing to drop a mapping")
            }
            sequence += 1
            val token = "⟦${kind.prefix}-$scopeTag-%04d⟧".format(sequence)
            byRaw[kind to canonical] = token
            byToken[token] = Entry(kind, canonical, surface, sequence)
            return token
        }
    }

    private fun kindOf(match: MatchResult): SensitiveKind =
        SensitiveKind.entries.first { it.prefix == match.groups[1]?.value }

    private fun validatedEntry(token: String, expected: SensitiveKind): Entry {
        val match = TOKEN_FULL.matchEntire(token)
            ?: throw TokenValidationException("the reference is not a well-formed masked value")
        val kind = SensitiveKind.entries.firstOrNull { it.prefix == match.groups[1]?.value }
            ?: throw TokenValidationException("the reference is not a well-formed masked value")
        if (kind != expected) {
            throw TokenValidationException("the masked value is not valid for this field")
        }
        if (match.groups[2]?.value != scopeTag) {
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
        val TOKEN_PATTERN = Regex("⟦(AMT|DATE|TIME|ACCT|BANK)-([A-Za-z0-9]{4,12})-(\\d{4})⟧")
        private val TOKEN_FULL = Regex("^⟦(AMT|DATE|TIME|ACCT|BANK)-([A-Za-z0-9]{4,12})-(\\d{4})⟧$")
        private val DATE_PATTERN = Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b")
        private val TIME_PATTERN = Regex("\\b(?:[01][0-9]|2[0-3]):[0-5][0-9]\\b")
        private val GROUPED_AMOUNT_PATTERN = Regex("\\b\\d{1,3}(?:,\\d{3})+\\b")
        private val UNIT_AMOUNT_PATTERN = Regex("(\\d[\\d,]*)\\s*(?:VND|₫|[Dd]ong)")
        private val ACCOUNT_HINT_PATTERN =
            Regex("(?i)(?:\\baccount(?:\\s+ending)?|\\bending)\\s*:?\\s*#?\\s*(\\d{4,})")
        private val BARE_DIGITS_PATTERN = Regex("\\b\\d{4,}\\b")
        private val BANK_CODE_PATTERN = Regex("\\b[A-Za-z0-9]{5,}\\b")

        internal fun scopeTagOf(scopeId: String): String {
            val alnum = scopeId.filter { it.isLetterOrDigit() }.take(8)
            val hash = "%04x".format(scopeId.hashCode() and 0xffff)
            return (if (alnum.isEmpty()) "s" else alnum) + hash
        }
    }
}
