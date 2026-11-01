package io.github.triet4p.aegisf.agent

import io.github.triet4p.aegisf.provider.HostedConversation
import io.github.triet4p.aegisf.provider.HostedGenerationAdapter
import io.github.triet4p.aegisf.provider.HostedGenerationResult
import io.github.triet4p.aegisf.provider.HostedProtocol
import io.github.triet4p.aegisf.provider.HostedToolCall
import io.github.triet4p.aegisf.provider.HostedToolDeclaration
import io.github.triet4p.aegisf.provider.HostedToolResult
import io.github.triet4p.aegisf.provider.HostedTurn
import io.github.triet4p.aegisf.provider.HostedTurnFinish
import io.github.triet4p.aegisf.provider.ToolCallDisposition
import org.json.JSONArray
import org.json.JSONObject

/**
 * TEST_ONLY deterministic provider double for the assisted resource-lifetime smoke.
 *
 * It opens no socket and makes no hosted call. The smoke launches the exact qualification batch
 * entry the hosted run uses and swaps only the provider adapter, so the real Room database, Laya
 * lifecycle, vault, reference registry, journal, debug capture and the in-session host pause all
 * run unchanged. Every selected case plays the same two-phase shape: select the case's captured
 * source, ask the user for the missing time, receive the host's single feedback message, and ask
 * one further question through the same reference registry.
 *
 * Nothing here is a provider or rubric measurement. There is no hosted model identity, HTTP
 * response or wire record, so those frozen gates are not applicable; the run instead keeps the
 * effect-safety stop in [scriptedSafetyStop] so an unintended ledger/link mutation still stops
 * the batch.
 */
internal object S813ScriptedProvider {
    const val ARG = "S813_SCRIPTED_PROVIDER"
    const val CONTRACT = "aegis-s813-scripted-provider/v1"
    const val PLAN_ARG = "S813_SCRIPTED_PLAN"

    /** The host's declared behavior for one scripted slot with no decision file. */
    const val DECISION_NO_DECISION = "no_decision"

    /** A declared placeholder; the scripted adapter never authenticates or transports it. */
    const val CREDENTIAL = "scripted-provider-unused"

    const val STOP_POSTCONDITIONS_MISSING = "postconditions_missing"
    const val STOP_UNSAFE_EFFECT = "unsafe_or_unintended_effect"

    fun adapter(): HostedGenerationAdapter = ScriptedLifecycleAdapter()

    /**
     * Parses the host's declared per-slot decision plan.
     *
     * The plan is the host's own statement of what it will deliver, so the device can assert each
     * resource path the smoke intends to cover (accepted slot, one feedback continuation, and no
     * decision) instead of inferring it from whatever arrived. Every selected case must appear
     * exactly once with a declared decision.
     */
    fun decisionPlan(raw: String?, caseIds: List<String>): Map<String, String> {
        val text = requireNotNull(raw?.takeIf { it.isNotBlank() }) {
            "the scripted lifecycle run requires a declared decision plan"
        }
        val plan = linkedMapOf<String, String>()
        for (entry in text.split(',')) {
            val parts = entry.split('=')
            require(parts.size == 2 && parts[0].isNotBlank()) {
                "a scripted decision plan entry must be <case_id>=<decision>"
            }
            require(parts[0] in caseIds) { "the scripted decision plan names an unselected case: ${parts[0]}" }
            require(plan.put(parts[0], parts[1]) == null) {
                "the scripted decision plan repeats ${parts[0]}"
            }
            require(parts[1] in setOf(S813_ASSISTED_DECISION_PASS, S813_ASSISTED_DECISION_FEEDBACK, DECISION_NO_DECISION)) {
                "the scripted decision plan uses an undeclared decision: ${parts[1]}"
            }
        }
        require(plan.keys == caseIds.toSet()) {
            "the scripted decision plan must cover every selected case exactly once"
        }
        return plan
    }

    /**
     * The only batch stop the scripted lifecycle run evaluates.
     *
     * The frozen hosted-provider identity and wire gates cannot apply without a hosted exchange,
     * so this check deliberately covers only the effect-safety invariants. A scripted record is
     * never scored as a hosted attempt.
     */
    fun scriptedSafetyStop(result: JSONObject): String? {
        val postconditions = result.optJSONObject("postconditions")
            ?: return STOP_POSTCONDITIONS_MISSING
        if (postconditions.optInt("ledger_rows") != 0 ||
            postconditions.optInt("out_of_scope_effect_rows") != 0 ||
            postconditions.optInt("unexpected_mutation_operations") != 0
        ) {
            return STOP_UNSAFE_EFFECT
        }
        return null
    }
}

private class ScriptedLifecycleAdapter : HostedGenerationAdapter {
    override val protocol: HostedProtocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS

    private var nextTurn = 0
    private var prompt = ""
    private var sourceRef: String? = null

    override fun begin(systemInstruction: String, userInstruction: String): HostedConversation {
        prompt = userInstruction
        return ScriptedConversation(protocol, emptyList())
    }

    override fun appendUserNotice(conversation: HostedConversation, notice: String): HostedConversation =
        ScriptedConversation(protocol, emptyList())

    override suspend fun generate(
        conversation: HostedConversation,
        tools: List<HostedToolDeclaration>,
    ): HostedGenerationResult {
        val result = when (nextTurn) {
            0 -> toolTurn("select_source", selectedSourceArguments())
            1 -> toolTurn("ask_user", askUserArguments("When did you make this payment?"))
            2 -> toolTurn("ask_user", askUserArguments("What time of day was that payment?"))
            else -> error("the scripted lifecycle provider has no turn ${nextTurn + 1}")
        }
        nextTurn += 1
        return result
    }

    override fun continueWithToolResults(
        conversation: HostedConversation,
        results: List<HostedToolResult>,
        hostNotice: String?,
    ): HostedConversation {
        results.forEach { result ->
            val envelope = runCatching { JSONObject(result.outputJson) }.getOrNull() ?: return@forEach
            val data = envelope.optJSONObject("data") ?: return@forEach
            data.optString("source_ref").takeIf { it.isNotBlank() }?.let { sourceRef = it }
        }
        return ScriptedConversation(protocol, emptyList())
    }

    private fun selectedSourceArguments(): JSONObject {
        val capture = initialObservations().firstOrNull { it.optString("kind") == "capture" }
            ?: error("the scripted lifecycle case received no capture observation")
        return JSONObject()
            .put("capture_ref", capture.getString("ref"))
            .put("quote", capture.getString("source_text"))
            .put("occurrence", 1)
    }

    private fun askUserArguments(question: String): JSONObject = JSONObject()
        .put("subject_ref", requireNotNull(sourceRef) { "the scripted case selected no source" })
        .put("field", "time")
        .put("question", question)

    private fun initialObservations(): List<JSONObject> {
        val lines = prompt.lines()
        val label = lines.indexOfFirst { it.contains("Trusted host observations") }
        check(label >= 0) { "the scripted lifecycle prompt lacks the trusted observation block" }
        val start = (label + 1 until lines.size)
            .firstOrNull { lines[it].trimStart().startsWith("[") }
            ?: error("the scripted lifecycle prompt lacks an observation array")
        val array = JSONArray(lines[start].trim())
        return (0 until array.length()).map(array::getJSONObject)
    }

    private fun toolTurn(name: String, arguments: JSONObject): HostedGenerationResult {
        val call = HostedToolCall(
            callId = "scripted-$name-${nextTurn + 1}",
            name = name,
            argumentsJson = arguments.toString(),
            arguments = null,
            disposition = ToolCallDisposition.READY,
        )
        return HostedGenerationResult.Turn(
            HostedTurn(
                protocol = protocol,
                requestedModel = LOCAL_MODEL,
                reportedModel = LOCAL_MODEL,
                assistantText = null,
                toolCalls = listOf(call),
                finish = HostedTurnFinish.ToolCalls,
                usage = null,
                conversation = ScriptedConversation(protocol, listOf(call.callId)),
            ),
        )
    }

    private class ScriptedConversation(
        override val protocol: HostedProtocol,
        override val pendingCallIds: List<String>,
    ) : HostedConversation

    private companion object {
        /** The adapter's own honest identity; it is not the frozen hosted model. */
        const val LOCAL_MODEL = "local-scripted"
    }
}
