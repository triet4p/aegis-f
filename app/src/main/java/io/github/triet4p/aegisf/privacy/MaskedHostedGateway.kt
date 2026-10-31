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
import io.github.triet4p.aegisf.provider.HostedResponsePrivacyResult

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
