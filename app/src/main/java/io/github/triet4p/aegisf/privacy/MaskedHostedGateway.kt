package io.github.triet4p.aegisf.privacy

import io.github.triet4p.aegisf.provider.HostedConversation
import io.github.triet4p.aegisf.provider.HostedGenerationAdapter
import io.github.triet4p.aegisf.provider.HostedGenerationResult
import io.github.triet4p.aegisf.provider.HostedProtocol
import io.github.triet4p.aegisf.provider.HostedProviderIdentity
import io.github.triet4p.aegisf.provider.canonicalBudgetString
import io.github.triet4p.aegisf.provider.HostedToolDeclaration
import io.github.triet4p.aegisf.provider.HostedToolResult
import io.github.triet4p.aegisf.provider.HostedTurn
import io.github.triet4p.aegisf.provider.HostedTurnFinish
import io.github.triet4p.aegisf.provider.HostedResponsePrivacyGuard
import io.github.triet4p.aegisf.provider.HostedResponsePrivacyRefusal
import io.github.triet4p.aegisf.provider.HostedResponsePrivacyRefusalReason
import io.github.triet4p.aegisf.provider.HostedResponsePrivacyResult
import io.github.triet4p.aegisf.provider.HostedProviderFailure
import io.github.triet4p.aegisf.provider.HostedTurnProgress

/**
 * The only supported production path from a masked agent session to a provider adapter.
 *
 * [MaskedHostedGateway] wraps one [HostedGenerationAdapter] and the [MaskedPromptBoundary] of
 * the same session. It masks the system instruction, user instruction, tool-result JSON and the
 * host's per-turn token inventory on the way out, keeps the protocol's own required continuation
 * items byte-identical, and scans the decoded provider JSON before an adapter parses it.
 *
 * Guard scope follows the owner-approved channel boundary: provider reasoning/thinking is never
 * guard input, while the parsed assistant answer is checked before publication and the parsed
 * tool-call argument text before any dispatch.
 *
 * Mask or block is the rule: [begin] and [continueWithToolResults] throw
 * [MaskingBlockedException] instead of emitting an unmaskable request. A response that fails the
 * decoded-body scan becomes a privacy-blocked provider result, which the executor maps to an
 * `Incomplete("masking_blocked")` outcome with no dispatch or continuation. There is
 * intentionally no raw passthrough: the wrapped adapter is private and never exposed, so
 * production code cannot bypass the boundary by accident.
 */
class MaskedHostedGateway(
    private val adapter: HostedGenerationAdapter,
    val boundary: MaskedPromptBoundary,
) : HostedResponsePrivacyGuard {
    val protocol = adapter.protocol
    override fun scan(rawResponse: String, httpStatus: Int?): HostedResponsePrivacyResult =
        boundary.scanInboundResponseJson(
            body = rawResponse,
            protocol = protocol,
            httpStatus = httpStatus,
            metadataPolicy = adapter.responseMetadataPolicy(),
        )

    /**
     * The wrapped adapter's value-free provider identity (protocol/host/model/budget),
     * or null when it cannot attribute its configuration. The adapter stays private:
     * this exposes identities only, never the transport, the credential, or raw text.
     */
    fun providerIdentity(): HostedProviderIdentity? =
        try {
            adapter.providerIdentity()
        } catch (_: Exception) {
            null
        }

    /**
     * Builds this run's stable cache identity from the wrapped adapter's value-free
     * provider identity plus the host's canonical tool-schema, selected skills and
     * privacy-context tag. Returns null when the adapter cannot attribute its
     * configuration; the run then reports usage honestly without a cache identity.
     * Never carries prompt text, raw values, mappings, keys, or credentials, and never
     * leaks the wrapped adapter: production code still cannot bypass the boundary.
     */
    fun cacheKeyFor(
        declarations: List<io.github.triet4p.aegisf.provider.HostedToolDeclaration>,
        skills: List<io.github.triet4p.aegisf.agent.WorkflowSkill>,
        privacyScopeTag: String,
    ): io.github.triet4p.aegisf.agent.AgentCacheKey? {
        val identity = providerIdentity() ?: return null
        return io.github.triet4p.aegisf.agent.AgentContextManager.cacheKey(
            protocol = identity.protocol,
            endpointHost = identity.endpointHost,
            model = identity.model,
            generationBudget = identity.budget.canonicalBudgetString(),
            declarations = declarations,
            skills = skills,
            privacyScopeTag = privacyScopeTag,
        )
    }

    /**
     * Identity material for the local cache key. The value carries the protocol only; the
     * host supplies provider, model, config, tool-schema, skill-schema and privacy-context
     * identities. It never carries prompt text, raw values, mappings, or credentials, and it
     * never leaks the wrapped adapter: production code still cannot bypass the boundary.
     */
    fun cacheIdentities(): MaskedGatewayIdentities = MaskedGatewayIdentities(protocol)

    fun begin(systemInstruction: String, userInstruction: String): MaskedConversation =
        MaskedConversation(
            adapter.begin(maskOrThrow(systemInstruction), maskOrThrow(userInstruction)),
            boundary,
        )

    suspend fun generate(
        conversation: MaskedConversation,
        tools: List<HostedToolDeclaration>,
    ): HostedGenerationResult {
        conversation.requireBoundary(boundary)
        val result = adapter.generateGuarded(conversation.inner, tools, this)
        val turn = (result as? HostedGenerationResult.Turn)?.turn ?: return result
        return HostedGenerationResult.Turn(maskTurnOrBlock(turn))
    }

    /**
     * Generates one turn while reporting incremental, already-guarded public progress.
     *
     * When [progress] is null this is exactly [generate]. When the validated route has no honest
     * stream, the caller receives [HostedTurnProgress.StreamingUnavailable] and the ordinary
     * complete-body turn runs: no text is ever synthesized to look like streaming.
     *
     * Incremental answer text crosses [PublicStreamGate] before it is reported, so a value the
     * session masked, a foreign/unknown token, or an unclosed token delimiter can never be shown
     * while it is still incomplete. The assembled response is still scanned by [scan] and the
     * parsed turn by [maskTurnOrBlock] before publication and dispatch, exactly like a
     * complete-body turn; a gate refusal becomes the same `masking_blocked` provider result.
     */
    suspend fun generateStreaming(
        conversation: MaskedConversation,
        tools: List<HostedToolDeclaration>,
        progress: ((HostedTurnProgress) -> Unit)?,
    ): HostedGenerationResult {
        conversation.requireBoundary(boundary)
        if (progress == null) return generate(conversation, tools)
        if (!adapter.streamingEnabled) {
            progress(HostedTurnProgress.StreamingUnavailable("no_streaming_transport_or_route"))
            return generate(conversation, tools)
        }
        val gate = PublicStreamGate(boundary) { safe ->
            progress(HostedTurnProgress.PublicText(safe))
        }
        val result = try {
            adapter.streamGuarded(conversation.inner, tools, this) { item ->
                when (item) {
                    is HostedTurnProgress.PublicText -> gate.accept(item.text)
                    is HostedTurnProgress.ToolCallStarted,
                    is HostedTurnProgress.ToolCallCompleted,
                    is HostedTurnProgress.StreamingUnavailable,
                    -> progress(item)
                }
            }
        } catch (refusal: PublicStreamRefusal) {
            return HostedGenerationResult.ProviderFailure(
                HostedProviderFailure.PrivacyBlocked(
                    HostedResponsePrivacyRefusal(
                        reason = refusal.reason.toPrivacyReason(),
                        sensitiveShape = null,
                        safeLocation = "assistant_text",
                        nodeType = "STRING",
                    ),
                ),
            )
        }
        val turn = (result as? HostedGenerationResult.Turn)?.turn
        if (turn != null) {
            gate.finish()
            return HostedGenerationResult.Turn(maskTurnOrBlock(turn))
        }
        // A failed or refused exchange publishes no flushed tail: only text the gate already
        // accepted was shown, and the caller replaces it with the truthful outcome.
        return result
    }

    fun continueWithToolResults(
        conversation: MaskedConversation,
        results: List<HostedToolResult>,
        hostNotice: String? = null,
    ): MaskedConversation {
        conversation.requireBoundary(boundary)
        val masked = results.map { result ->
            HostedToolResult(result.callId, boundary.maskToolResultJson(result.outputJson))
        }
        val maskedNotice = hostNotice?.let { maskOrThrow(it) }
        return MaskedConversation(
            adapter.continueWithToolResults(conversation.inner, masked, maskedNotice),
            boundary,
        )
    }

    /**
     * Masks free context (a capture excerpt, draft text, history line) before it joins the next
     * prompt. Anything that cannot be masked throws before any request exists.
     */
    fun maskContext(text: String, path: MaskedPath = MaskedPath.CONTEXT): String = maskOrThrow(text)

    /**
     * Appends the fixed, host-authored masking-repair notice as the next native user turn.
     *
     * The notice is masked outbound like any other prompt text (it carries no value, so the
     * call normally passes unchanged) and the returned conversation is bound to this gateway.
     * No pending call may exist: the refused response was discarded whole, so nothing needs an
     * answer and no unmatched tool-call continuation can be produced.
     */
    fun withRepairNotice(conversation: MaskedConversation, notice: String): MaskedConversation {
        conversation.requireBoundary(boundary)
        val masked = maskOrThrow(notice)
        return MaskedConversation(adapter.appendUserNotice(conversation.inner, masked), boundary)
    }

    /**
     * Appends one user follow-up message as the next native user turn on the same conversation.
     *
     * This is the assisted-follow-up seam: the message is masked outbound exactly like any other
     * prompt text, every prior item (assistant text, tool calls and their actual results) is kept
     * byte-for-byte, and the conversation must have no pending call. It is not a clarification
     * answer, so the caller never registers it as clarification evidence.
     */
    fun appendUserMessage(conversation: MaskedConversation, message: String): MaskedConversation {
        conversation.requireBoundary(boundary)
        require(conversation.pendingCallIds.isEmpty()) {
            "A user follow-up cannot be appended while tool calls are unanswered."
        }
        val masked = maskOrThrow(message)
        return MaskedConversation(adapter.appendUserNotice(conversation.inner, masked), boundary)
    }

    private fun maskOrThrow(text: String): String = boundary.maskOutbound(text, MaskedPath.CONTEXT)

    private fun maskTurnOrBlock(turn: HostedTurn): HostedTurn {
        // The two enforced model-output points. Provider reasoning never reaches this function:
        // adapters skip thought-marked calls and scrub plaintext reasoning prose while keeping
        // opaque pairing state (Gemini thoughtSignature, Responses encrypted_content), and
        // reasoning is never returned as answer text nor as a call argument, so it cannot block
        // a turn, be published, or dispatch an effect.
        turn.assistantText?.let { text ->
            if (boundary.scanInbound(text) is InboundScan.Refused) return blockedTurn(turn)
        }
        for (call in turn.toolCalls) {
            val wire = call.argumentsJson ?: continue
            if (boundary.scanInbound(wire) is InboundScan.Refused) return blockedTurn(turn)
        }
        return turn
    }

    private fun blockedTurn(turn: HostedTurn): HostedTurn = turn.copy(
        finish = HostedTurnFinish.Incomplete("masking_blocked"),
        toolCalls = emptyList(),
        conversation = null,
    )
}

/** Value-free gateway identity for the local cache key: protocol only, never content. */
data class MaskedGatewayIdentities(val protocol: HostedProtocol)

/**
 * A provider conversation whose free text is masked and whose continuation items are intact.
 *
 * The inner conversation carries the protocol's own required items (per-call ids, reasoning
 * items, thought signatures) verbatim — dropping them would corrupt the continuation — but it
 * is only constructible through [MaskedHostedGateway]. The session binding is identity-based:
 * mixing conversations across gateways refuses loudly instead of rehydrating under the wrong
 * vault.
 */
class MaskedConversation internal constructor(
    internal val inner: HostedConversation,
    private val boundary: MaskedPromptBoundary,
) {
    val pendingCallIds: List<String> get() = inner.pendingCallIds
    val protocol get() = inner.protocol

    internal fun requireBoundary(expected: MaskedPromptBoundary) {
        if (boundary !== expected) {
            throw MaskingBlockedException("the conversation belongs to another masking session")
        }
    }
}

/**
 * A streamed public fragment was refused by the incremental gate; nothing further is displayed.
 *
 * It carries the same refusal category the complete-response scan uses, so the executor maps it
 * to the same `masking_blocked` outcome and the same bounded repair policy.
 */
internal class PublicStreamRefusal(val reason: InboundRefusal) : Exception("streamed public text was refused")

internal fun InboundRefusal.toPrivacyReason(): HostedResponsePrivacyRefusalReason = when (this) {
    InboundRefusal.FOREIGN_TOKEN -> HostedResponsePrivacyRefusalReason.FOREIGN_TOKEN
    InboundRefusal.UNKNOWN_TOKEN -> HostedResponsePrivacyRefusalReason.UNKNOWN_TOKEN
    InboundRefusal.RAW_LITERAL_EGRESS -> HostedResponsePrivacyRefusalReason.DECODED_SENSITIVE_MATCH
}

/**
 * The incremental public-output gate of one streamed turn.
 *
 * Every emitted fragment is a prefix of the final answer that has already passed the same
 * [MaskedPromptBoundary.scanInbound] check the complete answer must pass: no unknown or foreign
 * token, no complete raw value this session mapped, and no unclosed token delimiter.
 *
 * A fragment is only emitted once two boundaries are decided:
 *
 * - **Token boundary.** Text from an unmatched `⟦` is held until its `⟧` arrives, so a token is
 *   never displayed half-formatted or validated against a partial shape.
 * - **Raw-value boundary.** The trailing `maxMappedSurfaceLength() - 1` characters are held back.
 *   Held-back text is never displayed unscanned, so a complete mapped value (an amount, date,
 *   time, account or bank code issued at any point in this session) can never straddle the
 *   boundary between scanned and unscanned text. The length is re-read on every flush, so a
 *   value minted after the gate was created still holds its own tail back.
 *
 * [finish] flushes the remaining text through the same scan. Both a refusal at any point and an
 * unclosed delimiter at the end throw [PublicStreamRefusal]; the caller fails closed and no
 * further text is shown.
 */
internal class PublicStreamGate(
    private val boundary: MaskedPromptBoundary,
    private val emit: (String) -> Unit,
) {
    private val pending = StringBuilder()

    fun accept(delta: String) {
        if (delta.isEmpty()) return
        pending.append(delta)
        flush(final = false)
    }

    fun finish() {
        if (pending.isEmpty()) return
        val lastOpen = pending.lastIndexOf(TOKEN_OPEN)
        if (lastOpen >= 0 && pending.indexOf(TOKEN_CLOSE, lastOpen) < 0) {
            // A delimiter that never closed is not displayable text; refuse instead of showing
            // a partial token that the complete-answer guard would have rejected.
            throw PublicStreamRefusal(InboundRefusal.UNKNOWN_TOKEN)
        }
        flush(final = true)
    }

    private fun flush(final: Boolean) {
        if (pending.isEmpty()) return
        val cut = if (final) pending.length else safeCut()
        if (cut <= 0) return
        val candidate = pending.substring(0, cut)
        when (val scan = boundary.scanInbound(candidate)) {
            InboundScan.Accepted -> Unit
            is InboundScan.Refused -> throw PublicStreamRefusal(scan.reason)
        }
        emit(candidate)
        pending.delete(0, cut)
    }

    private fun safeCut(): Int {
        var cut = pending.length
        val tail = (boundary.vault.maxMappedSurfaceLength() - 1).coerceAtLeast(0)
        while (true) {
            cut = minOf(cut, pending.length - tail)
            if (cut <= 0) return 0
            val tokenAdjusted = tokenSafeCut(cut)
            if (tokenAdjusted != cut) {
                cut = tokenAdjusted
                continue
            }
            val prefixStart = trailingPrefixStart(cut)
            if (prefixStart != cut) {
                cut = prefixStart
                continue
            }
            return cut
        }
    }

    /**
     * Moves a cut out of any bracket-delimited span. A span from `⟦` to its `⟧` is atomic: a
     * cut inside it would display half a token (or skip the scan that refuses a foreign one),
     * and a cut after an unclosed `⟦` would release a partial token.
     */
    private fun tokenSafeCut(cut: Int): Int {
        var adjusted = cut
        var open = pending.indexOf(TOKEN_OPEN)
        while (open >= 0) {
            val close = pending.indexOf(TOKEN_CLOSE, open + 1)
            if (close < 0) return minOf(adjusted, open)
            val end = close + 1
            if (adjusted > open && adjusted < end) adjusted = open
            open = pending.indexOf(TOKEN_OPEN, end)
        }
        return adjusted
    }

    /**
     * The latest position from which the pending suffix is a strict prefix of a mapped surface;
     * emitting past it could display the beginning of a value the session masks.
     */
    private fun trailingPrefixStart(cut: Int): Int {
        var prefixStart = cut
        var start = cut - 1
        val floor = maxOf(0, cut - MAX_PREFIX_PROBE)
        while (start >= floor) {
            if (boundary.vault.isMappedSurfacePrefix(pending.substring(start, cut))) {
                prefixStart = start
            }
            start -= 1
        }
        return prefixStart
    }

    private companion object {
        const val TOKEN_OPEN = "⟦"
        const val TOKEN_CLOSE = "⟧"

        /** How far back the prefix probe looks; bounded by the longest mapped surface. */
        const val MAX_PREFIX_PROBE = 256
    }
}
