package io.github.triet4p.aegisf.provider

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * OpenAI Responses with native typed items.
 *
 * `input` carries the conversation items, `tools` carries flat function declarations, and the
 * answer is a list of typed `output` items: `message` items hold `output_text`, `function_call`
 * items hold `call_id`, `name` and a JSON-string `arguments`, and reasoning items are preserved
 * as opaque items. A result returns as a `function_call_output` item whose `call_id` names the
 * call.
 *
 * This adapter chains **statelessly**: the retained items are resent with the next request
 * instead of naming a server-side `previous_response_id`. The full `output` list (including
 * reasoning items) is retained verbatim because a reasoning item belongs to the continuation;
 * the trade-off is explicit and recorded in `docs/sprint-8-provider-adapters.md`.
 *
 * A `function_call` item whose status is `incomplete`, or any call from an `incomplete` response,
 * is reported as [ToolCallDisposition.TRUNCATED_TURN] and can never be executed.
 */
class OpenAiResponsesAdapter internal constructor(
    private val config: HostedProviderConfig,
    private val transport: HttpTransport,
) : HostedGenerationAdapter {
    override val protocol: HostedProtocol = HostedProtocol.OPENAI_RESPONSES
    /** Value-free identity from this adapter's validated config. */
    override fun providerIdentity(): HostedProviderIdentity = config.identity()

    /** Root metadata and Go-route accounting this validated configuration may receive. */
    override fun responseMetadataPolicy(): HostedResponseMetadataPolicy =
        config.responseMetadataPolicy()


    init {
        if (config.protocol != protocol) {
            throw HostedConfigurationException("Responses adapter received a ${config.protocol} configuration")
        }
    }

    private data class PendingCall(val callId: String, val name: String)

    private class Conversation(
        val instructions: String,
        val input: List<JSONObject>,
        val pending: List<PendingCall>,
    ) : HostedConversation {
        override val protocol: HostedProtocol = HostedProtocol.OPENAI_RESPONSES
        override val pendingCallIds: List<String> = pending.map { it.callId }
    }

    override fun begin(systemInstruction: String, userInstruction: String): HostedConversation {
        if (userInstruction.isBlank()) {
            throw HostedConfigurationException("a generation turn needs a nonblank user instruction")
        }
        val input = listOf(
            JSONObject()
                .put("role", "user")
                .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", userInstruction))),
        )
        return Conversation(systemInstruction, input, emptyList())
    }

    override suspend fun generate(
        conversation: HostedConversation,
        tools: List<HostedToolDeclaration>,
    ): HostedGenerationResult = generateWithResponseGuard(conversation, tools, null)

    override suspend fun generateGuarded(
        conversation: HostedConversation,
        tools: List<HostedToolDeclaration>,
        responseGuard: HostedResponsePrivacyGuard,
    ): HostedGenerationResult = generateWithResponseGuard(conversation, tools, responseGuard)

    private suspend fun generateWithResponseGuard(
        conversation: HostedConversation,
        tools: List<HostedToolDeclaration>,
        responseGuard: HostedResponsePrivacyGuard?,
    ): HostedGenerationResult {
        val state = conversation as? Conversation
            ?: throw HostedConfigurationException("conversation belongs to another protocol")
        if (state.protocol != protocol) {
            throw HostedConfigurationException("conversation belongs to another protocol")
        }
        if (state.pending.isNotEmpty()) {
            throw HostedConfigurationException(
                "a turn cannot start while ${state.pending.size} tool result(s) are still pending",
            )
        }
        if (tools.isNotEmpty() && !config.capabilities.supportsToolCalls) {
            throw HostedConfigurationException("tools were offered but the model declares no tool support")
        }
        val request = buildRequest(state, tools)
        val response = try {
            transport.post(
                HostedHttpRequest(
                    url = config.endpoint,
                    headers = config.requestHeaders(),
                    body = request.toString(),
                ),
            )
        } catch (exception: HostedResponseTooLargeException) {
            return HostedGenerationResult.ProviderFailure(
                HostedProviderFailure.InvalidResponse(exception.message),
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: IOException) {
            return HostedGenerationResult.ProviderFailure(
                HostedProviderFailure.Transport(exception.message?.take(MAX_TRANSPORT_DETAIL)),
            )
        }
        if (responseGuard != null) {
            when (val scan = responseGuard.scan(response.body, response.status)) {
                HostedResponsePrivacyResult.Accepted,
                is HostedResponsePrivacyResult.AcceptedWithValidatedCounters,
                is HostedResponsePrivacyResult.AcceptedWithValidatedMetadata -> Unit
                is HostedResponsePrivacyResult.Refused ->
                    return HostedGenerationResult.ProviderFailure(
                        HostedProviderFailure.PrivacyBlocked(scan.refusal),
                    )
                is HostedResponsePrivacyResult.ProtocolError ->
                    return HostedGenerationResult.ProviderFailure(
                        HostedProviderFailure.InvalidResponse(scan.reason.safeDetail()),
                    )
            }
        }
        val result = if (response.status !in 200..299) {
            HostedGenerationResult.ProviderFailure(
                ProviderFailures.classify(
                    status = response.status,
                    body = response.body,
                    headers = response.headers,
                    authentication = config.authentication,
                ),
            )
        } else {
            try {
                parseTurn(response.body, state, tools)
            } catch (_: InvalidProviderUsageException) {
                HostedGenerationResult.ProviderFailure(
                    HostedProviderFailure.InvalidResponse("invalid provider usage counter"),
                )
            }
        }
        return if (responseGuard == null) {
            result.withRawResponseForPrivacyScan(response.body)
        } else {
            result
        }
    }

    override fun continueWithToolResults(
        conversation: HostedConversation,
        results: List<HostedToolResult>,
        hostNotice: String?,
    ): HostedConversation {
        val state = conversation as? Conversation
            ?: throw HostedConfigurationException("conversation belongs to another protocol")
        val answers = ToolCallAssembly.answerEveryPendingCall(state.pending.map { it.callId }, results)
        val outputs = state.pending.map { pending ->
            JSONObject()
                .put("type", "function_call_output")
                .put("call_id", pending.callId)
                .put("output", answers.getValue(pending.callId).outputJson)
        }
        // The host tail follows the function outputs as one native user message item.
        val tail = hostNotice?.let { notice ->
            if (notice.isBlank()) throw HostedConfigurationException("a host notice must not be blank")
            listOf(
                JSONObject()
                    .put("role", "user")
                    .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", notice))),
            )
        } ?: emptyList()
        return Conversation(
            instructions = state.instructions,
            input = state.input + outputs + tail,
            pending = emptyList(),
        )
    }

    override fun appendUserNotice(
        conversation: HostedConversation,
        notice: String,
    ): HostedConversation {
        val state = conversation as? Conversation
            ?: throw HostedConfigurationException("conversation belongs to another protocol")
        if (state.pending.isNotEmpty()) {
            throw HostedConfigurationException(
                "a notice cannot be appended while ${state.pending.size} tool result(s) are pending",
            )
        }
        if (notice.isBlank()) throw HostedConfigurationException("a notice must not be blank")
        val message = JSONObject()
            .put("role", "user")
            .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", notice)))
        return Conversation(
            instructions = state.instructions,
            input = state.input + message,
            pending = emptyList(),
        )
    }

    private fun buildRequest(
        state: Conversation,
        tools: List<HostedToolDeclaration>,
    ): JSONObject {
        val request = JSONObject()
            .put("model", config.model)
            .put(
                "input",
                JSONArray().also { input ->
                    state.input.forEach { input.put(it.deepCopy()) }
                },
            )
        if (state.instructions.isNotBlank()) request.put("instructions", state.instructions)
        if (tools.isNotEmpty()) {
            request.put(
                "tools",
                JSONArray().also { declarations ->
                    tools.forEach { tool ->
                        declarations.put(
                            JSONObject()
                                .put("type", "function")
                                .put("name", tool.name)
                                .put("description", tool.description)
                                .put("parameters", ProviderJson.openAiSchema(tool.parameters)),
                        )
                    }
                },
            )
            request.put("tool_choice", AUTO_TOOL_CHOICE)
        }
        request.put("max_output_tokens", config.budget.maxOutputTokens)
        config.budget.temperature?.let { request.put("temperature", it) }
        config.budget.reasoningEffort?.let {
            request.put("reasoning", JSONObject().put("effort", it.wireName))
        }
        if (tools.isNotEmpty()) {
            config.budget.parallelToolCalls?.let { request.put("parallel_tool_calls", it) }
        }
        return request
    }

    private fun parseTurn(
        body: String,
        state: Conversation,
        tools: List<HostedToolDeclaration>,
    ): HostedGenerationResult {
        val root = ProviderJson.strictObject(body)
            ?: return HostedGenerationResult.ProviderFailure(
                HostedProviderFailure.InvalidResponse("response was not a JSON object"),
            )
        val reportedModel = root.optString("model").ifBlank { null }
        val status = root.optString("status")
        val error = root.optJSONObject("error")
        when (status) {
            "failed" -> return HostedGenerationResult.ProviderFailure(
                HostedProviderFailure.ProviderState(
                    code = error?.optString("code").orEmpty().ifBlank { "failed" },
                    detail = error?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: ProviderFailures.detail(body, config.authentication),
                ),
            )

            "cancelled" -> return HostedGenerationResult.ProviderFailure(
                HostedProviderFailure.ProviderState(code = "cancelled", detail = null),
            )

            "queued", "in_progress" -> return HostedGenerationResult.ProviderFailure(
                HostedProviderFailure.ProviderState(code = status, detail = "turn did not reach a terminal state"),
            )

            "" -> return HostedGenerationResult.ProviderFailure(
                HostedProviderFailure.InvalidResponse("response carried no status"),
            )

            "completed", "incomplete" -> Unit
            else -> return HostedGenerationResult.ProviderFailure(
                HostedProviderFailure.ProviderState(code = status, detail = null),
            )
        }
        ProviderFailures.identityProblem(config.model, reportedModel)?.let {
            return HostedGenerationResult.ProviderFailure(it)
        }
        val output = root.optJSONArray("output")
        if (output == null) {
            return HostedGenerationResult.ProviderFailure(
                HostedProviderFailure.InvalidResponse("response carried no output list"),
            )
        }
        val textSegments = mutableListOf<String>()
        val rawCalls = mutableListOf<RawToolCall>()
        for (index in 0 until output.length()) {
            val item = output.optJSONObject(index)
                ?: return HostedGenerationResult.ProviderFailure(
                    HostedProviderFailure.InvalidResponse("output item was not an object"),
                )
            when (item.optString("type")) {
                "message" -> {
                    val content = item.opt("content")
                    if (content is JSONArray) {
                        for (partIndex in 0 until content.length()) {
                            val part = content.optJSONObject(partIndex) ?: continue
                            if (part.optString("type") != "output_text") continue
                            part.optString("text").takeIf { it.isNotEmpty() }?.let(textSegments::add)
                        }
                    }
                }

                "function_call" -> {
                    val callId = item.optString("call_id")
                    if (callId.isBlank()) {
                        return HostedGenerationResult.ProviderFailure(
                            HostedProviderFailure.InvalidResponse("function_call item carried no call_id"),
                        )
                    }
                    val name = item.optString("name")
                    if (name.isBlank()) {
                        return HostedGenerationResult.ProviderFailure(
                            HostedProviderFailure.InvalidResponse("function_call item carried no name"),
                        )
                    }
                    val arguments = when (val rawArguments = item.opt("arguments")) {
                        null, JSONObject.NULL -> null
                        is String -> rawArguments.ifBlank { null }
                        else -> return HostedGenerationResult.ProviderFailure(
                            HostedProviderFailure.InvalidResponse("function_call arguments were not text"),
                        )
                    }
                    rawCalls.add(
                        RawToolCall(
                            callId = callId,
                            name = name,
                            argumentsJson = arguments,
                            wireId = callId,
                            forcedDisposition = if (item.optString("status") == "incomplete") {
                                ToolCallDisposition.TRUNCATED_TURN
                            } else {
                                null
                            },
                        ),
                    )
                }

                else -> Unit
            }
        }
        if (ToolCallAssembly.duplicateCallIds(rawCalls).isNotEmpty()) {
            return HostedGenerationResult.ProviderFailure(
                HostedProviderFailure.InvalidResponse("response repeated a call_id"),
            )
        }
        val text = textSegments.joinToString(separator = "\n").ifBlank { null }
        if (status == "incomplete") {
            val reason = root.optJSONObject("incomplete_details")?.optString("reason").orEmpty()
            val finish = if (reason == "content_filter") {
                HostedTurnFinish.ContentFiltered(reason)
            } else {
                HostedTurnFinish.Incomplete(reason.ifBlank { "incomplete" })
            }
            return HostedGenerationResult.Turn(
                HostedTurn(
                    protocol = protocol,
                    requestedModel = config.model,
                    reportedModel = reportedModel,
                    assistantText = text,
                    toolCalls = ToolCallAssembly.assemble(
                        rawCalls,
                        tools,
                        ToolCallDisposition.TRUNCATED_TURN,
                    ),
                    finish = finish,
                    usage = usageOf(root),
                    conversation = null,
                ),
            )
        }
        val truncatedCall = rawCalls.any { it.forcedDisposition == ToolCallDisposition.TRUNCATED_TURN }
        if (truncatedCall) {
            return HostedGenerationResult.Turn(
                HostedTurn(
                    protocol = protocol,
                    requestedModel = config.model,
                    reportedModel = reportedModel,
                    assistantText = text,
                    toolCalls = ToolCallAssembly.assemble(
                        rawCalls,
                        tools,
                        ToolCallDisposition.TRUNCATED_TURN,
                    ),
                    finish = HostedTurnFinish.Incomplete("truncated_function_call"),
                    usage = usageOf(root),
                    conversation = null,
                ),
            )
        }
        if (rawCalls.isEmpty() && text == null) {
            return HostedGenerationResult.Turn(
                HostedTurn(
                    protocol = protocol,
                    requestedModel = config.model,
                    reportedModel = reportedModel,
                    assistantText = null,
                    toolCalls = emptyList(),
                    finish = HostedTurnFinish.Incomplete(EMPTY_ANSWER_REASON),
                    usage = usageOf(root),
                    conversation = null,
                ),
            )
        }
        val calls = ToolCallAssembly.assemble(rawCalls, tools)
        val nextConversation = if (rawCalls.isNotEmpty()) {
            Conversation(
                instructions = state.instructions,
                input = state.input + scrubbedOutputItems(output),
                pending = rawCalls.map { PendingCall(it.callId, it.name) },
            )
        } else {
            null
        }
        return HostedGenerationResult.Turn(
            HostedTurn(
                protocol = protocol,
                requestedModel = config.model,
                reportedModel = reportedModel,
                assistantText = text,
                toolCalls = calls,
                finish = if (rawCalls.isEmpty()) HostedTurnFinish.Complete else HostedTurnFinish.ToolCalls,
                usage = usageOf(root),
                conversation = nextConversation,
            ),
        )
    }

    /**
     * The retained output items with reasoning prose removed but protocol structure intact.
     *
     * Responses continuation is stateless: the resent items must keep every `type`, `id`,
     * `call_id`, `status` and pairing field byte-identical so call/result association survives.
     * A `type: "reasoning"` item keeps its identity and status fields plus the opaque
     * `encrypted_content` blob verbatim: that blob is provider continuation state, never
     * plaintext reasoning, and the S8.5 live capture shows continuations must keep their
     * required items while the Chat live capture proves reasoning prose needs no retention.
     * Only plaintext reasoning prose (`summary`, `content`, `text`) is dropped so a mapped
     * raw literal inside reasoning never rides the outbound continuation. Message and
     * function_call items pass through byte-identical.
     */
    private fun scrubbedOutputItems(output: JSONArray): List<JSONObject> =
        (0 until output.length()).mapNotNull { index ->
            val copy = output.optJSONObject(index)?.deepCopy() ?: return@mapNotNull null
            if (copy.optString("type") != "reasoning") return@mapNotNull copy
            for (key in listOf("summary", "content", "text")) copy.remove(key)
            copy
        }

    private fun usageOf(root: JSONObject): HostedUsage? =
        HostedUsageContract.parse(protocol, root)

    private companion object {
        const val AUTO_TOOL_CHOICE = "auto"
        const val EMPTY_ANSWER_REASON = "empty_answer"
        const val MAX_TRANSPORT_DETAIL = 200
    }
}
