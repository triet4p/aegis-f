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
 * TEST_ONLY deterministic provider double for the assisted evaluation proof and its
 * resource-lifetime smoke.
 *
 * It opens no socket and makes no hosted call. Both shapes launch the exact qualification batch
 * entry the hosted run uses and swap only the provider adapter, so the real Room database, Laya
 * lifecycle, vault, reference registry, journal, debug capture and the in-session host pause all
 * run unchanged. The lifetime smoke plays one fixed shape per case: select the case's captured
 * source, ask the user for the missing time, receive the host's single feedback message, and ask
 * one further question through the same reference registry.
 *
 * The full-batch proof shape instead replays a host-declared per-case script of tool and text
 * turns for both phases. It exists so the batch-continuation policy and the assisted handshake can
 * be exercised end to end through the real device batch and the real host runner/scorer without a
 * hosted request.
 *
 * Nothing here is a provider or rubric measurement. There is no hosted model identity, HTTP
 * response or wire record, so those provider-transport gates are declared not applicable; the run
 * instead keeps the effect-safety stop in [scriptedSafetyStop] so an unintended ledger/link
 * mutation still stops the batch.
 */
internal object S813ScriptedProvider {
    const val ARG = "S813_SCRIPTED_PROVIDER"
    const val CONTRACT = "aegis-s813-scripted-provider/v1"
    const val PLAN_ARG = "S813_SCRIPTED_PLAN"
    const val PROOF_ARG = "S813_SCRIPTED_PROOF"
    const val SCRIPTS_ARG_B64 = "S813_SCRIPTED_SCRIPTS_B64"

    /** The declared proof contract: no hosted exchange exists to attribute. */
    const val PROVIDER_EVIDENCE_NOT_APPLICABLE = "not_applicable_scripted_provider"

    /** The host's declared behavior for one scripted slot with no decision file. */
    const val DECISION_NO_DECISION = "no_decision"

    /** A declared placeholder; the scripted adapter never authenticates or transports it. */
    const val CREDENTIAL = "scripted-provider-unused"

    const val STOP_POSTCONDITIONS_MISSING = "postconditions_missing"
    const val STOP_UNSAFE_EFFECT = "unsafe_or_unintended_effect"

    /**
     * The deterministic double for one proof case.
     *
     * A declared [script] drives the exact tool/text turns of both phases; without one the legacy
     * resource-lifetime shape is kept. Neither shape is a model answer: the adapter reports its
     * own local identity and the proof run declares provider-transport evidence not applicable.
     */
    fun adapter(caseId: String, script: S813ScriptedCaseScript?): HostedGenerationAdapter =
        if (script == null) ScriptedLifecycleAdapter() else ScriptedCaseAdapter(caseId, script)

    /**
     * Parses the host's declared proof script for every selected case.
     *
     * The script is the host's own statement of the exact turns the double will produce, so the
     * proof cannot silently fall back to an undeclared behavior on any slot. It covers both
     * phases, and it never contains a hosted model identity or a wire record.
     */
    fun scriptedPlan(raw: String?, caseIds: List<String>): Map<String, S813ScriptedCaseScript> {
        val text = requireNotNull(raw?.takeIf { it.isNotBlank() }) {
            "the scripted proof requires a declared per-case script"
        }
        val root = JSONObject(text)
        require(root.keys().asSequence().toSet() == caseIds.toSet()) {
            "the scripted proof must declare exactly the selected cases"
        }
        return caseIds.associateWith { caseId ->
            S813ScriptedCaseScript.fromJson(
                requireNotNull(root.optJSONObject(caseId)) {
                    "the scripted proof does not declare $caseId"
                },
            )
        }
    }

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

/** One declared turn of a proof case: exactly one tool call or one final text answer. */
internal sealed interface S813ScriptedStep {
    fun encode(): JSONObject

    data class Tool(val name: String, val arguments: JSONObject) : S813ScriptedStep {
        override fun encode(): JSONObject = JSONObject().put("tool", name).put("arguments", arguments)
    }

    data class Text(val text: String) : S813ScriptedStep {
        override fun encode(): JSONObject = JSONObject().put("text", text)
    }

    companion object {
        fun fromJson(raw: JSONObject): S813ScriptedStep {
            val tool = raw.optString("tool").takeIf { it.isNotBlank() }
            val text = raw.optString("text").takeIf { it.isNotBlank() }
            require((tool == null) != (text == null)) {
                "a scripted step declares exactly one of tool or text"
            }
            if (tool == null) return Text(requireNotNull(text))
            require(tool in S813_ASSISTED_TOOL_NAMES) { "unknown scripted tool: $tool" }
            return Tool(tool, raw.optJSONObject("arguments") ?: JSONObject())
        }
    }
}

/** The declared two-phase behavior of one proof case. */
internal data class S813ScriptedCaseScript(
    val initial: List<S813ScriptedStep>,
    val assisted: List<S813ScriptedStep>,
) {
    fun encode(): JSONObject = JSONObject()
        .put("initial", JSONArray().also { array -> initial.forEach { array.put(it.encode()) } })
        .put("assisted", JSONArray().also { array -> assisted.forEach { array.put(it.encode()) } })

    companion object {
        fun fromJson(raw: JSONObject): S813ScriptedCaseScript {
            fun steps(key: String): List<S813ScriptedStep> {
                val array = raw.optJSONArray(key)
                    ?: error("a scripted case requires its $key phase")
                return (0 until array.length()).map {
                    S813ScriptedStep.fromJson(array.getJSONObject(it))
                }
            }
            val initial = steps("initial")
            require(initial.isNotEmpty()) { "a scripted initial phase requires at least one step" }
            return S813ScriptedCaseScript(initial, steps("assisted"))
        }
    }
}

/**
 * Replays one case's declared script through the real masked gateway and executor.
 *
 * References stay opaque: the script names the aliases it needs (`$event`, `$draft:<visible id>`,
 * `$pair`), and the adapter resolves them from the trusted observations and the actual tool
 * results of this same run. The phase is the number of generated turns: the first N turns are the
 * declared initial phase, and a host-delivered feedback message continues into the assisted one.
 */
private class ScriptedCaseAdapter(
    private val caseId: String,
    private val script: S813ScriptedCaseScript,
) : HostedGenerationAdapter {
    override val protocol: HostedProtocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS

    private var nextTurn = 0
    private var prompt = ""
    private var eventRef: String? = null
    private val draftRefs = linkedMapOf<String, String>()
    private var pairRef: String? = null

    override fun begin(systemInstruction: String, userInstruction: String): HostedConversation {
        prompt = userInstruction
        readObservations()
        return ScriptedConversation(protocol, emptyList())
    }

    override fun appendUserNotice(conversation: HostedConversation, notice: String): HostedConversation =
        conversation

    override suspend fun generate(
        conversation: HostedConversation,
        tools: List<HostedToolDeclaration>,
    ): HostedGenerationResult {
        val assisted = nextTurn >= script.initial.size
        val steps = if (assisted) script.assisted else script.initial
        val index = if (assisted) nextTurn - script.initial.size else nextTurn
        require(index < steps.size) {
            "the scripted page for $caseId has no turn ${nextTurn + 1}"
        }
        val step = steps[index]
        nextTurn += 1
        return when (step) {
            is S813ScriptedStep.Text -> completeTurn(step.text)
            is S813ScriptedStep.Tool -> toolTurn(step)
        }
    }

    override fun continueWithToolResults(
        conversation: HostedConversation,
        results: List<HostedToolResult>,
        hostNotice: String?,
    ): HostedConversation {
        results.forEach { result ->
            val envelope = runCatching { JSONObject(result.outputJson) }.getOrNull() ?: return@forEach
            val data = envelope.optJSONObject("data") ?: return@forEach
            data.optString("pair_ref").takeIf { it.isNotBlank() }?.let { pairRef = it }
        }
        // The answered calls are no longer pending; a Clarifying run must look idle so its one
        // assisted follow-up can be minted, exactly as the legacy double does.
        return ScriptedConversation(protocol, emptyList())
    }

    private fun completeTurn(text: String): HostedGenerationResult = HostedGenerationResult.Turn(
        HostedTurn(
            protocol = protocol,
            requestedModel = LOCAL_MODEL,
            reportedModel = LOCAL_MODEL,
            assistantText = text,
            toolCalls = emptyList(),
            finish = HostedTurnFinish.Complete,
            usage = null,
            conversation = null,
        ),
    )

    private fun toolTurn(step: S813ScriptedStep.Tool): HostedGenerationResult {
        val callId = "scripted-${step.name}-$nextTurn"
        val arguments = resolveArguments(step.arguments)
        val call = HostedToolCall(
            callId = callId,
            name = step.name,
            argumentsJson = arguments.toString(),
            arguments = asMap(arguments),
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
                conversation = ScriptedConversation(protocol, listOf(callId)),
            ),
        )
    }

    private fun resolveArguments(raw: JSONObject): JSONObject {
        val resolved = JSONObject()
        for (key in raw.keys()) {
            val value = raw.opt(key)
            resolved.put(key, if (value is String) resolveReference(value, key) else value)
        }
        return resolved
    }

    private fun resolveReference(value: String, key: String): String = when {
        value == "\$event" -> requireNotNull(eventRef) {
            "the scripted case $caseId observed no event for $key"
        }
        value.startsWith("\$draft:") -> requireNotNull(
            draftRefs[value.removePrefix("\$draft:")],
        ) { "the scripted case $caseId observed no draft ${value.removePrefix("\$draft:")}" }
        value == "\$pair" -> requireNotNull(pairRef) {
            "the scripted case $caseId held no inspected pair for $key"
        }
        else -> value
    }

    private fun asMap(value: JSONObject): Map<String, Any?> =
        value.keys().asSequence().associateWith { key ->
            when (val item = value.opt(key)) {
                null, JSONObject.NULL -> null
                is JSONObject -> asMap(item)
                is JSONArray -> (0 until item.length()).map { index ->
                    when (val entry = item.opt(index)) {
                        JSONObject.NULL -> null
                        is JSONObject -> asMap(entry)
                        else -> entry
                    }
                }
                else -> item
            }
        }

    private fun readObservations() {
        val observations = observationsFrom(prompt) ?: return
        for (index in 0 until observations.length()) {
            val entry = observations.optJSONObject(index) ?: continue
            if (entry.optString("kind") == "draft_page") {
                collectDrafts(entry.optJSONArray("items"))
                continue
            }
            collectEvents(entry.optJSONArray("events"))
            collectDrafts(entry.optJSONArray("drafts"))
        }
    }

    private fun collectEvents(rows: JSONArray?) {
        if (rows == null || eventRef != null) return
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            row.optString("ref").takeIf { it.isNotBlank() }?.let { eventRef = it }
            if (eventRef != null) return
        }
    }

    private fun collectDrafts(rows: JSONArray?) {
        if (rows == null) return
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val ref = row.optString("ref").takeIf { it.isNotBlank() } ?: continue
            val label = row.optString("user_label")
            val visible = label.substringAfterLast('#', missingDelimiterValue = "")
            if (visible.isNotEmpty() && !draftRefs.containsKey(visible)) {
                draftRefs[visible] = ref
            }
        }
    }

    private fun observationsFrom(text: String): JSONArray? {
        val lines = text.lines()
        val label = lines.indexOfFirst { it.contains("Trusted host observations") }
        if (label < 0) return null
        val start = (label + 1 until lines.size)
            .firstOrNull { lines[it].trimStart().startsWith("[") }
            ?: return null
        return runCatching { JSONArray(lines[start].trim()) }.getOrNull()
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
