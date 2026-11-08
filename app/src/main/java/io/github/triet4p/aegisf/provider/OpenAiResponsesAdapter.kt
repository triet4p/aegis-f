package io.github.triet4p.aegisf.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
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
 * instead of naming a server-side `previous_response_id`. Message and function-call items retain
 * their protocol fields; reasoning items preserve any opaque encrypted state with a required empty
 * summary, while plaintext reasoning content is omitted. The trade-off is explicit and recorded
 * in `docs/sprint-8-provider-adapters.md`.
 *
 * A `function_call` item whose status is `incomplete`, or any call from an `incomplete` response,
 * is reported as [ToolCallDisposition.TRUNCATED_TURN] and can never be executed.
 */
class OpenAiResponsesAdapter internal constructor(
    private val config: HostedProviderConfig,
    private val transport: HttpTransport,
    private val streamTransport: HttpStreamTransport? = null,
) : HostedGenerationAdapter {
    override val protocol: HostedProtocol = HostedProtocol.OPENAI_RESPONSES

    /** Streaming is available only when the production transport exposes the stream capability. */
    override val streamingEnabled: Boolean get() = streamTransport != null
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
        val state = requireGeneratable(conversation, tools)
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

    override suspend fun streamGuarded(
        conversation: HostedConversation,
        tools: List<HostedToolDeclaration>,
        responseGuard: HostedResponsePrivacyGuard,
        onProgress: (HostedTurnProgress) -> Unit,
    ): HostedGenerationResult {
        val stream = streamTransport ?: return generateWithResponseGuard(conversation, tools, responseGuard)
        val state = requireGeneratable(conversation, tools)
        val request = buildRequest(state, tools).put("stream", true)
        var started: HostedStreamFrame.Start? = null
        var finalResponse: JSONObject? = null
        var failure: HostedGenerationResult? = null
        val calls = LinkedHashMap<String, ResponseCallAcc>()
        try {
            stream.postStream(
                HostedHttpRequest(
                    url = config.endpoint,
                    headers = config.requestHeaders(),
                    body = request.toString(),
                ),
            ).collect { frame ->
                when (frame) {
                    is HostedStreamFrame.Start -> started = frame
                    is HostedStreamFrame.Event -> {
                        if (failure != null) return@collect
                        val payload = frame.event.data
                        if (payload.isBlank()) return@collect
                        val event = ProviderJson.strictObject(payload)
                        if (event == null) {
                            failure = HostedGenerationResult.ProviderFailure(
                                HostedProviderFailure.InvalidResponse("stream event was not a JSON object"),
                            )
                            return@collect
                        }
                        val type = frame.event.event ?: ProviderJson.textOrNull(event, "type").orEmpty()
                        when (type) {
                            "response.output_item.added" -> {
                                val item = event.optJSONObject("item") ?: return@collect
                                if (ProviderJson.textOrNull(item, "type") != FUNCTION_CALL_ITEM) return@collect
                                val key = ProviderJson.textOrNull(item, "id").orEmpty()
                                    .ifBlank { ProviderJson.textOrNull(item, "call_id").orEmpty() }
                                val name = ProviderJson.textOrNull(item, "name").orEmpty()
                                val acc = calls.getOrPut(key) { ResponseCallAcc(name) }
                                if (name.isNotBlank() && !acc.announced &&
                                    tools.any { it.name == name }
                                ) {
                                    acc.announced = true
                                    onProgress(HostedTurnProgress.ToolCallStarted(name))
                                }
                            }

                            "response.function_call_arguments.delta" -> {
                                val key = ProviderJson.textOrNull(event, "item_id").orEmpty()
                                val acc = calls[key] ?: return@collect
                                ProviderJson.textOrNull(event, "delta")?.let { acc.arguments.append(it) }
                                reportResponseCallComplete(acc, tools, onProgress)
                            }

                            "response.function_call_arguments.done" -> {
                                val key = ProviderJson.textOrNull(event, "item_id").orEmpty()
                                val acc = calls[key] ?: return@collect
                                ProviderJson.textOrNull(event, "arguments")?.takeIf { it.isNotEmpty() }?.let {
                                    acc.arguments.setLength(0)
                                    acc.arguments.append(it)
                                }
                                reportResponseCallComplete(acc, tools, onProgress)
                            }

                            "response.output_text.delta" -> {
                                val delta = event.opt("delta")
                                if (delta is String && delta.isNotEmpty()) {
                                    onProgress(HostedTurnProgress.PublicText(delta))
                                }
                            }

                            "response.completed", "response.incomplete", "response.failed" ->
                                event.optJSONObject("response")?.let { finalResponse = it }

                            "error" -> {
                                val error = event.optJSONObject("error")
                                failure = HostedGenerationResult.ProviderFailure(
                                    HostedProviderFailure.ProviderState(
                                        code = ProviderJson.textOrNull(error ?: JSONObject(), "code")
                                            .orEmpty().ifBlank { "error" },
                                        detail = ProviderJson.textOrNull(error ?: JSONObject(), "message")
                                            ?.takeIf { it.isNotBlank() }
                                            ?: ProviderFailures.detail(payload, config.authentication),
                                    ),
                                )
                            }

                            else -> Unit
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (tooLarge: HostedResponseTooLargeException) {
            return HostedGenerationResult.ProviderFailure(
                HostedProviderFailure.InvalidResponse(tooLarge.message),
            )
        } catch (thrown: java.io.IOException) {
            return HostedGenerationResult.ProviderFailure(
                HostedProviderFailure.Transport(thrown.message?.take(MAX_TRANSPORT_DETAIL)),
            )
        }
        failure?.let { return it }
        val start = started
            ?: return HostedGenerationResult.ProviderFailure(
                HostedProviderFailure.Transport("the stream did not deliver a response head"),
            )
        if (start.errorBody != null) {
            return classifyStreamHead(start.status, start.errorBody, start.headers, responseGuard)
        }
        val body = finalResponse
            ?.toString()
            ?: return HostedGenerationResult.ProviderFailure(
                HostedProviderFailure.InvalidResponse(
                    "the streamed response ended before its terminal event",
                ),
            )
        when (val scan = responseGuard.scan(body, start.status)) {
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
        return try {
            when (val parsed = parseTurn(body, state, tools)) {
                is HostedGenerationResult.Turn -> HostedGenerationResult.Turn(
                    parsed.turn.copy(assembledResponseBody = body),
                )
                else -> parsed
            }
        } catch (_: InvalidProviderUsageException) {
            HostedGenerationResult.ProviderFailure(
                HostedProviderFailure.InvalidResponse("invalid provider usage counter"),
            )
        }
    }

    private fun reportResponseCallComplete(
        acc: ResponseCallAcc,
        tools: List<HostedToolDeclaration>,
        onProgress: (HostedTurnProgress) -> Unit,
    ) {
        if (acc.completed || acc.name.isBlank()) return
        if (ProviderJson.strictObject(acc.arguments.toString()) == null) return
        acc.completed = true
        if (tools.any { it.name == acc.name }) {
            onProgress(HostedTurnProgress.ToolCallCompleted(acc.name))
        }
    }

    private fun classifyStreamHead(
        status: Int,
        body: String,
        headers: Map<String, String>,
        guard: HostedResponsePrivacyGuard,
    ): HostedGenerationResult {
        when (val scan = guard.scan(body, status)) {
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
        return HostedGenerationResult.ProviderFailure(
            ProviderFailures.classify(
                status = status,
                body = body,
                headers = headers,
                authentication = config.authentication,
            ),
        )
    }

    private class ResponseCallAcc(val name: String) {
        val arguments = StringBuilder()
        var announced = false
        var completed = false
    }

    private fun requireGeneratable(
        conversation: HostedConversation,
        tools: List<HostedToolDeclaration>,
    ): Conversation {
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
        return state
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
        config.budget.maxOutputTokens?.let { request.put("max_output_tokens", it) }
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
        val reportedModel = ProviderJson.textOrNull(root, "model").orEmpty().ifBlank { null }
        val status = ProviderJson.textOrNull(root, "status").orEmpty()
        val error = root.optJSONObject("error")
        when (status) {
            "failed" -> return HostedGenerationResult.ProviderFailure(
                HostedProviderFailure.ProviderState(
                    code = ProviderJson.textOrNull(error ?: JSONObject(), "code").orEmpty().ifBlank { "failed" },
                    detail = ProviderJson.textOrNull(error ?: JSONObject(), "message")?.takeIf { it.isNotBlank() }
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
            when (ProviderJson.textOrNull(item, "type").orEmpty()) {
                "message" -> {
                    val content = item.opt("content")
                    if (content is JSONArray) {
                        for (partIndex in 0 until content.length()) {
                            val part = content.optJSONObject(partIndex) ?: continue
                            if (ProviderJson.textOrNull(part, "type") != "output_text") continue
                            ProviderJson.textOrNull(part, "text")?.takeIf { it.isNotEmpty() }?.let(textSegments::add)
                        }
                    }
                }

                "function_call" -> {
                    val callId = ProviderJson.textOrNull(item, "call_id").orEmpty()
                    if (callId.isBlank()) {
                        return HostedGenerationResult.ProviderFailure(
                            HostedProviderFailure.InvalidResponse("function_call item carried no call_id"),
                        )
                    }
                    val name = ProviderJson.textOrNull(item, "name").orEmpty()
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
                            forcedDisposition = if (ProviderJson.textOrNull(item, "status") == "incomplete") {
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
            val reason = ProviderJson.textOrNull(root.optJSONObject("incomplete_details") ?: JSONObject(), "reason").orEmpty()
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
     * Retained output items with plaintext reasoning removed and required protocol structure intact.
     *
     * Responses continuation is stateless: resent items keep their identity, status, call pairing and
     * opaque `encrypted_content`. A reasoning item's `summary` array is required by the endpoint even
     * when empty, so retain that field as an empty array; `content` and `text` are removed. No
     * plaintext reasoning is sent on the continuation. Message and function_call items pass through
     * byte-identical.
     */
    private fun scrubbedOutputItems(output: JSONArray): List<JSONObject> =
        (0 until output.length()).mapNotNull { index ->
            val copy = output.optJSONObject(index)?.deepCopy() ?: return@mapNotNull null
            if (ProviderJson.textOrNull(copy, "type") != "reasoning") return@mapNotNull copy
            copy.put("summary", JSONArray())
            copy.remove("content")
            copy.remove("text")
            copy
        }

    private fun usageOf(root: JSONObject): HostedUsage? =
        HostedUsageContract.parse(protocol, root)

    private companion object {
        const val AUTO_TOOL_CHOICE = "auto"
        const val EMPTY_ANSWER_REASON = "empty_answer"
        const val MAX_TRANSPORT_DETAIL = 200
        const val FUNCTION_CALL_ITEM = "function_call"
    }
}
