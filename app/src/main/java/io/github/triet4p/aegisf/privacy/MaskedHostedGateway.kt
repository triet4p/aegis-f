package io.github.triet4p.aegisf.privacy

import io.github.triet4p.aegisf.provider.HostedConversation
import io.github.triet4p.aegisf.provider.HostedGenerationAdapter
import io.github.triet4p.aegisf.provider.HostedGenerationResult
import io.github.triet4p.aegisf.provider.HostedToolDeclaration
import io.github.triet4p.aegisf.provider.HostedToolResult
import io.github.triet4p.aegisf.provider.HostedTurn
import io.github.triet4p.aegisf.provider.HostedTurnFinish

/**
 * The only supported production path from a masked agent session to a provider adapter.
 *
 * [MaskedHostedGateway] wraps one [HostedGenerationAdapter] and the [MaskedPromptBoundary] of
 * the same session. It masks the system instruction, user instruction and tool-result JSON on
 * the way out, keeps the protocol's own required continuation items byte-identical, and blocks
 * inbound model text that carries raw literals or foreign tokens before anything may act on it.
 *
 * Mask or block is the rule: [begin] and [continueWithToolResults] throw
 * [MaskingBlockedException] instead of emitting an unmaskable request, and an inbound turn
 * that fails [MaskedPromptBoundary.scanInbound] returns as an `Incomplete("masking_blocked")`
 * turn with no dispatchable calls and no continuation. There is intentionally no raw
 * passthrough: the wrapped adapter is private and never exposed, so production code cannot
 * bypass the boundary by accident.
 */
class MaskedHostedGateway(
    private val adapter: HostedGenerationAdapter,
    val boundary: MaskedPromptBoundary,
) {
    val protocol = adapter.protocol

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
        val result = adapter.generate(conversation.inner, tools)
        val turn = (result as? HostedGenerationResult.Turn)?.turn ?: return result
        return HostedGenerationResult.Turn(maskTurnOrBlock(turn))
    }

    fun continueWithToolResults(
        conversation: MaskedConversation,
        results: List<HostedToolResult>,
    ): MaskedConversation {
        conversation.requireBoundary(boundary)
        val masked = results.map { result ->
            HostedToolResult(result.callId, boundary.maskToolResultJson(result.outputJson))
        }
        return MaskedConversation(adapter.continueWithToolResults(conversation.inner, masked), boundary)
    }

    /**
     * Masks free context (a capture excerpt, draft text, history line) before it joins the next
     * prompt. Anything that cannot be masked throws before any request exists.
     */
    fun maskContext(text: String, path: MaskedPath = MaskedPath.CONTEXT): String = maskOrThrow(text)

    private fun maskOrThrow(text: String): String = boundary.maskOutbound(text, MaskedPath.CONTEXT)

    private fun maskTurnOrBlock(turn: HostedTurn): HostedTurn {
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
