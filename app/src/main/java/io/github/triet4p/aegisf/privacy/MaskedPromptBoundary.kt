package io.github.triet4p.aegisf.privacy

import io.github.triet4p.aegisf.data.DraftCandidateQuery
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.NoteEventInterpretation
import io.github.triet4p.aegisf.data.NoteEventTimeAnchor
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * Which outbound content path a masked string feeds. The value names the path only; it carries
 * no content and therefore cannot leak one.
 */
enum class MaskedPath {
    SYSTEM_INSTRUCTION,
    USER_INSTRUCTION,
    CONTEXT,
    HISTORY,
    TOOL_RESULT,
    CONTINUATION,
}

/** The verdict on model-returned text before rehydration. */
sealed interface InboundScan {
    data object Accepted : InboundScan
    data class Refused(val reason: InboundRefusal, val detail: String = "") : InboundScan
}

/** Why inbound text was refused; names a check, never a raw value. */
enum class InboundRefusal {
    FOREIGN_TOKEN,
    UNKNOWN_TOKEN,
    RAW_LITERAL_EGRESS,
}

/**
 * The S8.6 local pre-egress boundary: every user/context/history/tool-result/continuation
 * string the providers could carry is masked through [maskOutbound] before it reaches an
 * adapter, and inbound model text is scanned through [scanInbound] before dispatch.
 *
 * Masking never depends on model cooperation. Detection is deterministic regex over the
 * documented sensitive shapes (grouped/unit amounts, `YYYY-MM-DD` dates, `HH:mm` times,
 * account digit runs, mixed letter-digit bank codes); equality is stable within scope, and
 * ordering/relative-time stay decidable through the vault comparators. There is no external
 * service and no specialist model.
 */
class MaskedPromptBoundary(val vault: SensitiveVault) {

    /** Masks free text for one outbound path. */
    fun maskOutbound(text: String, path: MaskedPath): String = vault.maskText(text)

    /**
     * Masks the structured observation an application tool returned for the next provider turn.
     * Every string inside the JSON is masked; a non-finite number an encoder would render as
     * `NaN` throws [MaskingBlockedException], so the turn fails closed with no request.
     */
    fun maskToolResultJson(outputJson: String): String {
        val root = try {
            JSONObject(outputJson)
        } catch (error: Exception) {
            throw MaskingBlockedException("tool results must be a JSON object to mask")
        }
        return maskJsonValue(root).toString()
    }

    fun scanInbound(text: String): InboundScan {
        if (text.isEmpty()) return InboundScan.Accepted
        val scopeStripped = SensitiveVault.TOKEN_PATTERN.replace(text) { match ->
            if ("-${vault.scopeTag}-" in match.value) " " else match.value
        }
        FOREIGN_TOKEN_PATTERN.find(scopeStripped)?.let {
            return InboundScan.Refused(InboundRefusal.FOREIGN_TOKEN)
        }
        val knownStripped = SensitiveVault.TOKEN_PATTERN.replace(text) { match ->
            if ("-${vault.scopeTag}-" in match.value && vault.isKnownToken(match.value)) {
                " "
            } else {
                match.value
            }
        }
        TOKEN_LIKE_PATTERN.find(knownStripped)?.let {
            return InboundScan.Refused(InboundRefusal.UNKNOWN_TOKEN)
        }
        if (vault.scanLeak(text)) return InboundScan.Refused(InboundRefusal.RAW_LITERAL_EGRESS)
        return InboundScan.Accepted
    }

    private fun maskJsonValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> JSONObject.NULL
        is JSONObject -> JSONObject().also { masked ->
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                masked.put(key, maskJsonValue(value.opt(key)))
            }
        }
        is JSONArray -> JSONArray().also { masked ->
            for (index in 0 until value.length()) masked.put(maskJsonValue(value.opt(index)))
        }
        is String -> vault.maskText(value)
        is Number -> {
            if (value is Double && !value.isFinite() || value is Float && !value.isFinite()) {
                throw MaskingBlockedException("tool results carry a non-finite number")
            }
            value
        }
        is Boolean -> value
        else -> throw MaskingBlockedException("tool results carry an unsupported value")
    }
}

private val TOKEN_LIKE_PATTERN = Regex("⟦[^\\s⟦⟧]{1,64}⟧")
private val FOREIGN_TOKEN_PATTERN = Regex("⟦(?:AMT|DATE|TIME|ACCT|BANK)-[A-Za-z0-9]{4,12}-\\d{4}⟧")

/**
 * Tokens the model may reference when arguing about an event reading. Each token is minted in
 * this session's vault, so it is scoped, typed, and previously seen outbound; the raw literal
 * never appears in masked model text. Only the fields the model actually needs are tokens;
 * every other validated field crosses as plain text.
 */
data class MaskedInterpretation(
    val normalizedText: String,
    val amountToken: String?,
    val dateToken: String?,
    val timeToken: String?,
    val spanSourceStart: Int,
    val spanSourceEnd: Int,
)

/**
 * Rehydrates validated masked arguments into the typed application commands the services own.
 *
 * Trust rules, enforced in order:
 *
 * 1. Scope: [scope] must be the session that owns [captureId]; anything outside is refused
 *    before anything is read.
 * 2. Tokens: every token must be well-formed, minted in this session's vault, and typed for
 *    its field (an account token in an amount field is refused, never converted).
 * 3. Spans: the source slice is re-masked and its token structure compared against what the
 *    model saw; unknown/ambiguous values fail closed instead of minting under a refused write.
 * 4. Shapes: integer VND range, `YYYY-MM-DD`/`HH:mm` grammars, span bounds, and the scope's
 *    capture/draft sets are rechecked locally; the application services own the final check.
 *
 * Tokens are never arbitrary text authority: model text that carries raw sensitive literals,
 * cross-session tokens, or unknown tokens never reaches a command.
 */
object MaskedApplicationEffect {

    /**
     * Maps model offsets in the masked source view to exact offsets in the stored source.
     *
     * Sensitive tokens have different lengths from their source values. The model's copied
     * span is therefore checked against the current masked source before its boundaries are
     * translated locally; a boundary inside a token or an unmapped sensitive literal refuses.
     */
    fun sourceOffsetsForMaskedSpan(
        scope: WorkflowCallerScope.AgentSession,
        boundary: MaskedPromptBoundary,
        captureId: Long,
        captureText: String,
        maskedStart: Int,
        maskedEnd: Int,
        maskedSpanText: String,
    ): Pair<Int, Int> {
        if (captureId !in scope.captureIds) {
            throw TokenValidationException("the capture is outside this session's scope")
        }
        val vault = boundary.vault
        val maskedSource = vault.maskedPreview(captureText)
        if (SensitiveVault.UNMAPPED_SENTINEL in maskedSource) {
            throw TokenValidationException("the source has an unmapped sensitive value")
        }
        if (maskedStart < 0 || maskedEnd <= maskedStart || maskedEnd > maskedSource.length) {
            throw TokenValidationException("the masked source span is not usable")
        }
        val splitsToken = SensitiveVault.TOKEN_PATTERN.findAll(maskedSource).any { token ->
            (maskedStart > token.range.first && maskedStart <= token.range.last) ||
                (maskedEnd > token.range.first && maskedEnd <= token.range.last)
        }
        if (splitsToken) throw TokenValidationException("the masked span splits a protected value")
        if (maskedSource.substring(maskedStart, maskedEnd) != maskedSpanText) {
            throw TokenValidationException("the masked span does not match the source text")
        }
        val sourceStart = vault.rehydrateText(maskedSource.substring(0, maskedStart)).length
        val sourceEnd = vault.rehydrateText(maskedSource.substring(0, maskedEnd)).length
        if (sourceEnd <= sourceStart) {
            throw TokenValidationException("the translated source span is not usable")
        }
        return sourceStart to sourceEnd
    }

    /** Maps a stored source span to the masked character offsets a model can observe. */
    fun maskedOffsetsForSourceSpan(
        scope: WorkflowCallerScope.AgentSession,
        boundary: MaskedPromptBoundary,
        captureId: Long,
        captureText: String,
        sourceStart: Int,
        sourceEnd: Int,
    ): Pair<Int, Int> {
        if (captureId !in scope.captureIds) {
            throw TokenValidationException("the capture is outside this session's scope")
        }
        if (sourceStart < 0 || sourceEnd <= sourceStart || sourceEnd > captureText.length) {
            throw TokenValidationException("the source span is not usable")
        }
        val vault = boundary.vault
        val maskedSource = vault.maskedPreview(captureText)
        val maskedPrefix = vault.maskedPreview(captureText.substring(0, sourceStart))
        val maskedSpan = vault.maskedPreview(captureText.substring(sourceStart, sourceEnd))
        val maskedSuffix = vault.maskedPreview(captureText.substring(sourceEnd))
        if (SensitiveVault.UNMAPPED_SENTINEL in maskedSource ||
            SensitiveVault.UNMAPPED_SENTINEL in maskedPrefix ||
            SensitiveVault.UNMAPPED_SENTINEL in maskedSpan ||
            SensitiveVault.UNMAPPED_SENTINEL in maskedSuffix ||
            maskedPrefix + maskedSpan + maskedSuffix != maskedSource
        ) {
            throw TokenValidationException("the source span does not align with masked context")
        }
        return maskedPrefix.length to (maskedPrefix.length + maskedSpan.length)
    }

    /**
     * Rehydrates a masked event-reading argument into [NoteEventInterpretation], or refuses.
     *
     * [captureText] is the current capture text the model read; [spanStart]/[spanEnd] are the
     * translated offsets into that stored source. [maskedSpanText] is the exact copied range
     * from the masked source view, mapped locally before this call. Unknown/ambiguous
     * rehydration fails closed with [TokenValidationException].
     */
    fun rehydrateInterpretation(
        scope: WorkflowCallerScope.AgentSession,
        boundary: MaskedPromptBoundary,
        captureId: Long,
        captureText: String,
        spanStart: Int,
        spanEnd: Int,
        maskedSpanText: String,
        masked: MaskedInterpretation,
    ): NoteEventInterpretation {
        if (captureId !in scope.captureIds) {
            throw TokenValidationException("the capture is outside this session's scope")
        }
        val vault = boundary.vault
        if (spanStart < 0 || spanEnd <= spanStart || spanEnd > captureText.length) {
            throw TokenValidationException("the source span is not usable")
        }
        verifySpanAlignment(vault, captureText, spanStart, spanEnd, maskedSpanText)
        val amount = masked.amountToken?.let { vault.rehydrateAmount(it) }
        val date = masked.dateToken?.let { vault.rehydrateDate(it) }
        val time = masked.timeToken?.let { vault.rehydrateTime(it) }
        return NoteEventInterpretation(
            spanStart = spanStart,
            spanEnd = spanEnd,
            normalizedText = rehydrateScopedText(vault, masked.normalizedText),
            amountVnd = amount,
            timeAnchor = if (date == null && time == null) {
                null
            } else {
                NoteEventTimeAnchor(date = date, time = time, timezoneId = null)
            },
        )
    }

    /**
     * Rehydrates a masked draft-candidate page read into [DraftCandidateQuery].
     * Limits/offsets cross verbatim (never sensitive); the review status comes from the
     * allowlisted vocabulary, never from model text.
     */
    fun rehydrateDraftQuery(
        scope: WorkflowCallerScope.AgentSession,
        limit: Int,
        offset: Int,
        onlyPossibleDuplicates: Boolean,
        reviewStatus: IntakeReviewStatus?,
    ): DraftCandidateQuery {
        if (limit !in 1..500 || offset < 0) {
            throw TokenValidationException("the page bounds are not usable")
        }
        return DraftCandidateQuery(
            reviewStatus = reviewStatus,
            onlyPossibleDuplicates = onlyPossibleDuplicates,
            bounds = QueryPageRequest(limit = limit, offset = offset),
        )
    }

    /**
     * Validates a masked link pair before the application policy decides.
     * Event/draft ids cross as integers (never sensitive); they are rechecked against the
     * session's granted sets here so a swapped or cross-scope id refuses before any write.
     */
    fun validatedLinkPair(scope: WorkflowCallerScope.AgentSession, eventId: Long, draftId: Long) {
        if (eventId <= 0 || draftId <= 0) {
            throw TokenValidationException("the link pair is not usable")
        }
        if (draftId !in scope.draftIds) {
            throw TokenValidationException("the draft is outside this session's scope")
        }
    }

    private fun verifySpanAlignment(
        vault: SensitiveVault,
        captureText: String,
        spanStart: Int,
        spanEnd: Int,
        maskedSpanText: String,
    ) {
        val sourceSlice = captureText.substring(spanStart, spanEnd)
        val preview = vault.maskedPreview(sourceSlice)
        val expected = SensitiveVault.TOKEN_PATTERN.replace(preview, " ")
        val observed = SensitiveVault.TOKEN_PATTERN.replace(maskedSpanText, " ")
        if (expected != observed || SensitiveVault.UNMAPPED_SENTINEL in preview) {
            throw TokenValidationException("the masked span does not match the source text")
        }
    }

    private fun rehydrateScopedText(vault: SensitiveVault, text: String): String {
        if (MaskedPromptBoundary(vault).scanInbound(text) is InboundScan.Refused) {
            throw TokenValidationException("the model text carries an unmasked value or foreign token")
        }
        return vault.rehydrateText(text)
    }
}
