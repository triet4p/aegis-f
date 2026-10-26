package io.github.triet4p.aegisf.provider

import kotlinx.coroutines.CancellationException
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
) : HostedGenerationAdapter {
    override val protocol: HostedProtocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS

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
        if (response.status !in 200..299) {
            return HostedGenerationResult.ProviderFailure(
                ProviderFailures.classify(
                    status = response.status,
                    body = response.body,
                    headers = response.headers,
                    authentication = config.authentication,
                ),
            )
        }
        return parseTurn(response.body, state, tools)
    }

    override fun continueWithToolResults(
        conversation: HostedConversation,
        results: List<HostedToolResult>,
    ): HostedConversation {
        val state = conversation as? Conversation
            ?: throw HostedConfigurationException("conversation belongs to another protocol")
        val answers = ToolCallAssembly.answerEveryPendingCall(state.pending.map { it.callId }, results)
        val messages = state.messages + state.pending.map { pending ->
            JSONObject()
                .put("role", "tool")
                .put("tool_call_id", pending.callId)
                .put("content", answers.getValue(pending.callId).outputJson)
        }
        return Conversation(messages, emptyList())
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
        config.budget.parallelToolCalls?.let { request.put("parallel_tool_calls", it) }
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
                    code = error?.optString("code").orEmpty().ifBlank {
                        error?.optString("type").orEmpty().ifBlank { "error" }
                    },
                    detail = ProviderFailures.detail(body, config.authentication),
                ),
            )
        }
        val reportedModel = root.optString("model").ifBlank { null }
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
                val callId = call.optString("id")
                if (callId.isBlank()) {
                    return HostedGenerationResult.ProviderFailure(
                        HostedProviderFailure.InvalidResponse("tool call carried no id"),
                    )
                }
                val type = call.optString("type")
                if (type.isNotBlank() && type != "function") {
                    return HostedGenerationResult.ProviderFailure(
                        HostedProviderFailure.InvalidResponse("unsupported tool call type"),
                    )
                }
                val function = call.optJSONObject("function")
                    ?: return HostedGenerationResult.ProviderFailure(
                        HostedProviderFailure.InvalidResponse("tool call carried no function"),
                    )
                val name = function.optString("name")
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
        val finishReason = choice.optString("finish_reason")
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

    private fun usageOf(root: JSONObject): HostedUsage? {
        val usage = root.optJSONObject("usage") ?: return null
        val reasoning = usage.optJSONObject("completion_tokens_details")
        val cached = usage.optJSONObject("prompt_tokens_details")
        return HostedUsage(
            inputTokens = usage.positiveInt("prompt_tokens"),
            outputTokens = usage.positiveInt("completion_tokens"),
            reasoningTokens = reasoning?.positiveInt("reasoning_tokens"),
            cachedInputTokens = cached?.positiveInt("cached_tokens"),
        )
    }

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
    }
}
