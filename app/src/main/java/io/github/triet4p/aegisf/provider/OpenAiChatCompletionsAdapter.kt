package io.github.triet4p.aegisf.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * OpenAI-compatible Chat Completions with native `tool_calls`.
 *
 * The request carries `messages`, `tools` in the `{type: "function", function: …}` shape,
 * `tool_choice: "auto"`, the configured output budget as `max_completion_tokens`, and only the
 * sampling/reasoning fields the declared capabilities allow. A tool result returns as a
 * `role: "tool"` message whose `tool_call_id` names the call.
 *
 * Continuation retains the assistant message's `content` and `tool_calls` verbatim. The
 * DeepSeek gateway also returns `reasoning_content`; it is deliberately **not** retained, because
 * the live exchange continues correctly without it and hidden reasoning must not become part of
 * the stored conversation. Every pending `tool_call_id` must be answered before the next turn.
 */
class OpenAiChatCompletionsAdapter internal constructor(
    private val config: HostedProviderConfig,
    private val transport: HttpTransport,
    private val streamTransport: HttpStreamTransport? = null,
) : HostedGenerationAdapter {
    override val protocol: HostedProtocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS

    /** Streaming is available only when the production transport exposes the stream capability. */
    override val streamingEnabled: Boolean get() = streamTransport != null

    /** Value-free identity from this adapter's validated config. */
    override fun providerIdentity(): HostedProviderIdentity = config.identity()

    /** Root metadata and Go-route accounting this validated configuration may receive. */
    override fun responseMetadataPolicy(): HostedResponseMetadataPolicy =
        config.responseMetadataPolicy()

    init {
        if (config.protocol != protocol) {
            throw HostedConfigurationException("chat-completions adapter received a ${config.protocol} configuration")
        }
    }

    private data class PendingCall(val callId: String, val name: String)

    private class Conversation(
        val messages: List<JSONObject>,
        val pending: List<PendingCall>,
    ) : HostedConversation {
        override val protocol: HostedProtocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS
        override val pendingCallIds: List<String> = pending.map { it.callId }
    }

    override fun begin(systemInstruction: String, userInstruction: String): HostedConversation {
        if (userInstruction.isBlank()) {
            throw HostedConfigurationException("a generation turn needs a nonblank user instruction")
        }
        val messages = mutableListOf<JSONObject>()
        if (systemInstruction.isNotBlank()) {
            messages.add(JSONObject().put("role", "system").put("content", systemInstruction))
        }
        messages.add(JSONObject().put("role", "user").put("content", userInstruction))
        return Conversation(messages, emptyList())
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
        val text = StringBuilder()
        val calls = LinkedHashMap<Int, StreamCallAcc>()
        var reportedModel: String? = null
        var finishReason = ""
        var usage: JSONObject? = null
        var started: HostedStreamFrame.Start? = null
        var done = false
        var failure: HostedGenerationResult? = null
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
                        if (failure != null || done) return@collect
                        val payload = frame.event.data
                        if (payload.isBlank()) return@collect
                        if (payload.trim() == STREAM_DONE) {
                            done = true
                            return@collect
                        }
                        val chunk = ProviderJson.strictObject(payload)
                        if (chunk == null) {
                            failure = HostedGenerationResult.ProviderFailure(
                                HostedProviderFailure.InvalidResponse("stream chunk was not a JSON object"),
                            )
                            return@collect
                        }
                        if (chunk.has("error") && !chunk.isNull("error")) {
                            val error = chunk.optJSONObject("error")
                            failure = HostedGenerationResult.ProviderFailure(
                                HostedProviderFailure.ProviderState(
                                    code = ProviderJson.textOrNull(error ?: JSONObject(), "code").orEmpty().ifBlank {
                                        ProviderJson.textOrNull(error ?: JSONObject(), "type").orEmpty().ifBlank { "error" }
                                    },
                                    detail = ProviderFailures.detail(payload, config.authentication),
                                ),
                            )
                            return@collect
                        }
                        ProviderJson.textOrNull(chunk, "model")?.takeIf { it.isNotBlank() }?.let { reportedModel = it }
                        chunk.optJSONObject("usage")?.let { usage = it }
                        val choice = chunk.optJSONArray("choices")?.optJSONObject(0)
                        if (choice != null) {
                            ProviderJson.textOrNull(choice, "finish_reason")?.takeIf { it.isNotBlank() }?.let {
                                finishReason = it
                            }
                            val delta = choice.optJSONObject("delta")
                            if (delta != null) {
                                when (val content = delta.opt("content")) {
                                    null, JSONObject.NULL -> Unit
                                    is String -> if (content.isNotEmpty()) {
                                        text.append(content)
                                        onProgress(HostedTurnProgress.PublicText(content))
                                    }

                                    else -> {
                                        failure = HostedGenerationResult.ProviderFailure(
                                            HostedProviderFailure.InvalidResponse(
                                                "assistant delta content was not text",
                                            ),
                                        )
                                        return@collect
                                    }
                                }
                                val toolDeltas = delta.opt("tool_calls")
                                if (toolDeltas is JSONArray) {
                                    for (index in 0 until toolDeltas.length()) {
                                        val entry = toolDeltas.optJSONObject(index) ?: continue
                                        val slot = entry.optInt("index", index)
                                        val acc = calls.getOrPut(slot) { StreamCallAcc() }
                                        ProviderJson.textOrNull(entry, "id")?.takeIf { it.isNotBlank() }?.let {
                                            acc.id = it
                                        }
                                        val function = entry.optJSONObject("function") ?: continue
                                        ProviderJson.textOrNull(function, "name")
                                            ?.takeIf { it.isNotBlank() }
                                            ?.let { acc.name.append(it) }
                                        if (!acc.announced && acc.name.isNotBlank() &&
                                            tools.any { it.name == acc.name.toString() }
                                        ) {
                                            acc.announced = true
                                            onProgress(HostedTurnProgress.ToolCallStarted(acc.name.toString()))
                                        }
                                        ProviderJson.textOrNull(function, "arguments")?.takeIf { it.isNotEmpty() }?.let {
                                            acc.arguments.append(it)
                                            if (!acc.completed && acc.name.isNotBlank() &&
                                                ProviderJson.strictObject(acc.arguments.toString()) != null
                                            ) {
                                                acc.completed = true
                                                onProgress(
                                                    HostedTurnProgress.ToolCallCompleted(acc.name.toString()),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
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
        val assembled = JSONObject()
        reportedModel?.let { assembled.put("model", it) }
        val message = JSONObject().put("role", "assistant")
            .put("content", if (text.isEmpty()) JSONObject.NULL else text.toString())
        if (calls.isNotEmpty()) {
            message.put(
                "tool_calls",
                JSONArray().also { array ->
                    calls.values.forEach { acc ->
                        // Some OpenAI-compatible routes omit the call id entirely on a streamed
                        // turn. The id only pairs this turn's result with its call in the next
                        // request; minting a stable, unique local id keeps that pairing valid
                        // instead of failing the whole exchange.
                        val callId = acc.id.ifBlank {
                            "call_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
                        }
                        array.put(
                            JSONObject()
                                .put("id", callId)
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", acc.name.toString())
                                        .put("arguments", acc.arguments.toString()),
                                ),
                        )
                    }
                },
            )
        }
        assembled.put(
            "choices",
            JSONArray().put(
                JSONObject()
                    .put("index", 0)
                    .put("message", message)
                    .put("finish_reason", finishReason),
            ),
        )
        usage?.let { assembled.put("usage", it) }
        val body = assembled.toString()
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

    private class StreamCallAcc {
        var id: String = ""
        val name = StringBuilder()
        val arguments = StringBuilder()
        var announced = false
        var completed = false
    }

    override fun continueWithToolResults(
        conversation: HostedConversation,
        results: List<HostedToolResult>,
        hostNotice: String?,
    ): HostedConversation {
        val state = conversation as? Conversation
            ?: throw HostedConfigurationException("conversation belongs to another protocol")
        val answers = ToolCallAssembly.answerEveryPendingCall(state.pending.map { it.callId }, results)
        val messages = state.messages + state.pending.map { pending ->
            JSONObject()
                .put("role", "tool")
                .put("tool_call_id", pending.callId)
                .put("content", answers.getValue(pending.callId).outputJson)
        } + hostNoticeMessages(hostNotice)
        return Conversation(messages, emptyList())
    }

    /** The host tail is one ordinary user message after the tool outputs. */
    private fun hostNoticeMessages(hostNotice: String?): List<JSONObject> {
        if (hostNotice == null) return emptyList()
        if (hostNotice.isBlank()) throw HostedConfigurationException("a host notice must not be blank")
        return listOf(JSONObject().put("role", "user").put("content", hostNotice))
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
        val messages = state.messages +
            JSONObject().put("role", "user").put("content", notice)
        return Conversation(messages, emptyList())
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

    private fun buildRequest(
        state: Conversation,
        tools: List<HostedToolDeclaration>,
    ): JSONObject {
        val request = JSONObject()
            .put("model", config.model)
            .put(
                "messages",
                JSONArray().also { messages ->
                    state.messages.forEach { messages.put(it.deepCopy()) }
                },
            )
        if (tools.isNotEmpty()) {
            request.put(
                "tools",
                JSONArray().also { declarations ->
                    tools.forEach { tool ->
                        declarations.put(
                            JSONObject()
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", tool.name)
                                        .put("description", tool.description)
                                        .put("parameters", ProviderJson.openAiSchema(tool.parameters)),
                                ),
                        )
                    }
                },
            )
            request.put("tool_choice", AUTO_TOOL_CHOICE)
        }
        request.put("max_completion_tokens", config.budget.maxOutputTokens)
        config.budget.temperature?.let { request.put("temperature", it) }
        config.budget.reasoningEffort?.let { request.put("reasoning_effort", it.wireName) }
        if (tools.isNotEmpty()) config.budget.parallelToolCalls?.let { request.put("parallel_tool_calls", it) }
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
        if (root.has("error") && !root.isNull("error")) {
            val error = root.optJSONObject("error")
            return HostedGenerationResult.ProviderFailure(
                HostedProviderFailure.ProviderState(
                    code = ProviderJson.textOrNull(error ?: JSONObject(), "code").orEmpty().ifBlank {
                        ProviderJson.textOrNull(error ?: JSONObject(), "type").orEmpty().ifBlank { "error" }
                    },
                    detail = ProviderFailures.detail(body, config.authentication),
                ),
            )
        }
        val reportedModel = ProviderJson.textOrNull(root, "model").orEmpty().ifBlank { null }
        ProviderFailures.identityProblem(config.model, reportedModel)?.let {
            return HostedGenerationResult.ProviderFailure(it)
        }
        val choices = root.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            return HostedGenerationResult.ProviderFailure(
                HostedProviderFailure.InvalidResponse("response carried no choice"),
            )
        }
        val choice = choices.optJSONObject(0)
            ?: return HostedGenerationResult.ProviderFailure(
                HostedProviderFailure.InvalidResponse("choice was not an object"),
            )
        val message = choice.optJSONObject("message")
            ?: return HostedGenerationResult.ProviderFailure(
                HostedProviderFailure.InvalidResponse("choice carried no assistant message"),
            )
        val text = when (val content = message.opt("content")) {
            null, JSONObject.NULL -> null
            is String -> content.ifBlank { null }
            else -> return HostedGenerationResult.ProviderFailure(
                HostedProviderFailure.InvalidResponse("assistant content was not text"),
            )
        }
        val rawCalls = mutableListOf<RawToolCall>()
        var toolCallsArray: JSONArray? = null
        val toolCalls = message.opt("tool_calls")
        if (toolCalls != null && toolCalls !== JSONObject.NULL) {
            if (toolCalls !is JSONArray) {
                return HostedGenerationResult.ProviderFailure(
                    HostedProviderFailure.InvalidResponse("tool_calls was not an array"),
                )
            }
            toolCallsArray = toolCalls
            for (index in 0 until toolCalls.length()) {
                val call = toolCalls.optJSONObject(index)
                    ?: return HostedGenerationResult.ProviderFailure(
                        HostedProviderFailure.InvalidResponse("tool call was not an object"),
                    )
                val callId = ProviderJson.textOrNull(call, "id").orEmpty()
                if (callId.isBlank()) {
                    return HostedGenerationResult.ProviderFailure(
                        HostedProviderFailure.InvalidResponse("tool call carried no id"),
                    )
                }
                val type = ProviderJson.textOrNull(call, "type").orEmpty()
                if (type.isNotBlank() && type != "function") {
                    return HostedGenerationResult.ProviderFailure(
                        HostedProviderFailure.InvalidResponse("unsupported tool call type"),
                    )
                }
                val function = call.optJSONObject("function")
                    ?: return HostedGenerationResult.ProviderFailure(
                        HostedProviderFailure.InvalidResponse("tool call carried no function"),
                    )
                val name = ProviderJson.textOrNull(function, "name").orEmpty()
                if (name.isBlank()) {
                    return HostedGenerationResult.ProviderFailure(
                        HostedProviderFailure.InvalidResponse("tool call carried no name"),
                    )
                }
                val arguments = when (val rawArguments = function.opt("arguments")) {
                    null, JSONObject.NULL -> null
                    is String -> rawArguments.ifBlank { null }
                    else -> return HostedGenerationResult.ProviderFailure(
                        HostedProviderFailure.InvalidResponse("tool arguments were not a JSON string"),
                    )
                }
                rawCalls.add(RawToolCall(callId, name, arguments, callId))
            }
        }
        if (ToolCallAssembly.duplicateCallIds(rawCalls).isNotEmpty()) {
            return HostedGenerationResult.ProviderFailure(
                HostedProviderFailure.InvalidResponse("response repeated a tool call id"),
            )
        }
        val finishReason = ProviderJson.textOrNull(choice, "finish_reason").orEmpty()
        return when (val signal = classifyFinish(finishReason)) {
            is FinishSignal.Failed -> HostedGenerationResult.ProviderFailure(signal.failure)
            is FinishSignal.Filtered -> HostedGenerationResult.Turn(
                HostedTurn(
                    protocol = protocol,
                    requestedModel = config.model,
                    reportedModel = reportedModel,
                    assistantText = null,
                    toolCalls = ToolCallAssembly.assemble(
                        rawCalls,
                        tools,
                        ToolCallDisposition.FILTERED_TURN,
                    ),
                    finish = HostedTurnFinish.ContentFiltered(signal.reason),
                    usage = usageOf(root),
                    conversation = null,
                ),
            )

            is FinishSignal.Incomplete -> HostedGenerationResult.Turn(
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
                    finish = HostedTurnFinish.Incomplete(signal.reason),
                    usage = usageOf(root),
                    conversation = null,
                ),
            )

            FinishSignal.Complete -> {
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
                    val assistantMessage = JSONObject()
                        .put("role", "assistant")
                        .put("content", message.opt("content") ?: JSONObject.NULL)
                        .put("tool_calls", ProviderJson.copyArray(toolCallsArray ?: JSONArray()))
                    Conversation(
                        messages = state.messages + assistantMessage,
                        pending = rawCalls.map { PendingCall(it.callId, it.name) },
                    )
                } else {
                    null
                }
                HostedGenerationResult.Turn(
                    HostedTurn(
                        protocol = protocol,
                        requestedModel = config.model,
                        reportedModel = reportedModel,
                        assistantText = text,
                        toolCalls = calls,
                        finish = if (rawCalls.isEmpty()) {
                            HostedTurnFinish.Complete
                        } else {
                            HostedTurnFinish.ToolCalls
                        },
                        usage = usageOf(root),
                        conversation = nextConversation,
                    ),
                )
            }
        }
    }

    private fun usageOf(root: JSONObject): HostedUsage? =
        HostedUsageContract.parse(protocol, root)

    private sealed interface FinishSignal {
        data object Complete : FinishSignal

        data class Incomplete(val reason: String) : FinishSignal

        data class Filtered(val reason: String?) : FinishSignal

        data class Failed(val failure: HostedProviderFailure) : FinishSignal
    }

    private fun classifyFinish(reason: String): FinishSignal = when (reason) {
        "stop", "tool_calls", "function_call" -> FinishSignal.Complete
        "length" -> FinishSignal.Incomplete("length")
        "content_filter" -> FinishSignal.Filtered("content_filter")
        "" -> FinishSignal.Failed(
            HostedProviderFailure.InvalidResponse("choice carried no finish reason"),
        )

        else -> FinishSignal.Failed(
            HostedProviderFailure.ProviderState(code = reason, detail = null),
        )
    }

    private companion object {
        const val AUTO_TOOL_CHOICE = "auto"
        const val EMPTY_ANSWER_REASON = "empty_answer"
        const val MAX_TRANSPORT_DETAIL = 200
        const val STREAM_DONE = "[DONE]"
    }
}
