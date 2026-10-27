package io.github.triet4p.aegisf.provider

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Gemini API `models/{model}:generateContent` with native function calls.
 *
 * Wire behavior this adapter pins (verified against the live endpoint):
 *
 * - Declarations travel as `tools[].functionDeclarations[]` with the provider's own OpenAPI
 *   subset; keywords it rejects (`additionalProperties`, `$defs`) are lowered, not invented.
 * - A model turn carries `functionCall` parts, optionally with `thoughtSignature`; the whole
 *   part object is retained verbatim for continuation, because the signature is required for
 *   the next model turn on thinking models.
 * - A tool result travels as a `functionResponse` part in a **user** role turn. The frozen
 *   S8.4 draft said `tool`, but the live `generateContent` endpoint rejects that role
 *   (`Role 'tool' is not supported`), so the implemented encoding is the accepted one and the
 *   correction is recorded in `docs/sprint-8-provider-adapters.md`.
 * - `finishReason: STOP` accompanies a function-call-only turn; the pending calls, not the
 *   finish string, decide that the turn is awaiting results.
 *
 * Nothing here executes a tool call. A malformed, partial, unknown or truncated call is
 * returned with its [ToolCallDisposition] and is never [HostedToolCall.dispatchable].
 */
class GeminiGenerateContentAdapter internal constructor(
    private val config: HostedProviderConfig,
    private val transport: HttpTransport,
) : HostedGenerationAdapter {
    override val protocol: HostedProtocol = HostedProtocol.GEMINI_GENERATE_CONTENT

    /** Value-free identity from this adapter's validated config. */
    override fun providerIdentity(): HostedProviderIdentity = config.identity()

    init {
        if (config.protocol != protocol) {
            throw HostedConfigurationException("Gemini adapter received a ${config.protocol} configuration")
        }
        if (config.budget.parallelToolCalls != null) {
            throw HostedConfigurationException(
                "generateContent has no parallel-tool-call field; do not configure one for Gemini",
            )
        }
    }

    private data class PendingCall(
        val callId: String,
        val name: String,
        val wireId: String?,
    )

    private class Conversation(
        val systemInstruction: String,
        val contents: List<JSONObject>,
        val pending: List<PendingCall>,
    ) : HostedConversation {
        override val protocol: HostedProtocol = HostedProtocol.GEMINI_GENERATE_CONTENT
        override val pendingCallIds: List<String> = pending.map { it.callId }
    }

    override fun begin(systemInstruction: String, userInstruction: String): HostedConversation {
        if (userInstruction.isBlank()) {
            throw HostedConfigurationException("a generation turn needs a nonblank user instruction")
        }
        val contents = listOf(
            JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", userInstruction))),
        )
        return Conversation(systemInstruction, contents, emptyList())
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
                    url = config.endpointForModel(),
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
        val parts = JSONArray()
        state.pending.forEach { pending ->
            val result = answers.getValue(pending.callId)
            val response = ProviderJson.strictObject(result.outputJson)
                ?: throw HostedConfigurationException(
                    "Gemini functionResponse needs a JSON object observation for ${pending.name}",
                )
            val part = JSONObject().put("response", response).put("name", pending.name)
            pending.wireId?.let { part.put("id", it) }
            parts.put(JSONObject().put("functionResponse", part))
        }
        val toolTurn = JSONObject().put("role", "user").put("parts", parts)
        return Conversation(
            systemInstruction = state.systemInstruction,
            contents = state.contents + toolTurn,
            pending = emptyList(),
        )
    }

    private fun buildRequest(
        state: Conversation,
        tools: List<HostedToolDeclaration>,
    ): JSONObject {
        val request = JSONObject()
        if (state.systemInstruction.isNotBlank()) {
            request.put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", state.systemInstruction))),
            )
        }
        request.put(
            "contents",
            JSONArray().also { contents ->
                state.contents.forEach { contents.put(it.deepCopy()) }
            },
        )
        if (tools.isNotEmpty()) {
            val declarations = JSONArray()
            tools.forEach { tool ->
                declarations.put(
                    JSONObject()
                        .put("name", tool.name)
                        .put("description", tool.description)
                        .put("parameters", ProviderJson.geminiSchema(tool.parameters)),
                )
            }
            request.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", declarations)))
            request.put(
                "toolConfig",
                JSONObject().put(
                    "functionCallingConfig",
                    JSONObject().put("mode", AUTO_TOOL_MODE),
                ),
            )
        }
        val generation = JSONObject().put("maxOutputTokens", config.budget.maxOutputTokens)
        config.budget.temperature?.let { generation.put("temperature", it) }
        config.budget.reasoningEffort?.let {
            generation.put("thinkingConfig", JSONObject().put("thinkingLevel", it.wireName))
        }
        request.put("generationConfig", generation)
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
        val promptFeedback = root.optJSONObject("promptFeedback")
        val promptBlock = promptFeedback?.optString("blockReason").orEmpty()
        val reportedModel = root.optString("modelVersion").ifBlank { null }
        if (promptBlock.isNotBlank()) {
            return filteredTurn(reportedModel, promptBlock.lowercase(), state)
        }
        if (root.has("error") && !root.isNull("error")) {
            val error = root.optJSONObject("error")
            return HostedGenerationResult.ProviderFailure(
                HostedProviderFailure.ProviderState(
                    code = error?.optString("status").orEmpty().ifBlank { "error" },
                    detail = ProviderFailures.detail(body, config.authentication),
                ),
            )
        }
        ProviderFailures.identityProblem(config.model, reportedModel)?.let {
            return HostedGenerationResult.ProviderFailure(it)
        }
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            return HostedGenerationResult.ProviderFailure(
                HostedProviderFailure.InvalidResponse("response carried no candidate"),
            )
        }
        val candidate = candidates.optJSONObject(0)
            ?: return HostedGenerationResult.ProviderFailure(
                HostedProviderFailure.InvalidResponse("candidate was not an object"),
            )
        val finishReason = candidate.optString("finishReason")
        val content = candidate.optJSONObject("content")
        val parts = mutableListOf<JSONObject>()
        content?.optJSONArray("parts")?.let { array ->
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { parts.add(it) }
            }
        }
        val text = parts.mapNotNull { part ->
            if (part.optBoolean("thought", false)) return@mapNotNull null
            part.optString("text").takeIf { it.isNotEmpty() }
        }.joinToString(separator = "").ifBlank { null }

        val rawCalls = mutableListOf<RawToolCall>()
        for ((index, part) in parts.withIndex()) {
            if (!part.has("functionCall")) continue
            val call = part.optJSONObject("functionCall")
                ?: return HostedGenerationResult.ProviderFailure(
                    HostedProviderFailure.InvalidResponse("functionCall part was not an object"),
                )
            val name = call.optString("name")
            if (name.isBlank()) {
                return HostedGenerationResult.ProviderFailure(
                    HostedProviderFailure.InvalidResponse("functionCall part carried no tool name"),
                )
            }
            val wireId = call.optString("id").ifBlank { null }
            val arguments: String? = when (val rawArguments = call.opt("args")) {
                null, JSONObject.NULL -> null
                is JSONObject -> rawArguments.toString()
                else -> return HostedGenerationResult.ProviderFailure(
                    HostedProviderFailure.InvalidResponse("functionCall arguments were not an object"),
                )
            }
            rawCalls.add(RawToolCall(wireId ?: "gemini-call-$index", name, arguments, wireId))
        }
        if (ToolCallAssembly.duplicateCallIds(rawCalls).isNotEmpty()) {
            return HostedGenerationResult.ProviderFailure(
                HostedProviderFailure.InvalidResponse("response repeated a function call id"),
            )
        }

        return when (val signal = classifyFinish(finishReason)) {
            is FinishSignal.Failed -> HostedGenerationResult.ProviderFailure(signal.failure)
            is FinishSignal.Filtered -> filteredTurn(reportedModel, signal.reason, state)
            is FinishSignal.Incomplete -> unfinishedTurn(
                reportedModel = reportedModel,
                reason = signal.reason,
                text = text,
                rawCalls = rawCalls,
                tools = tools,
            )

            FinishSignal.Complete -> {
                if (rawCalls.isEmpty() && text == null) {
                    return unfinishedTurn(
                        reportedModel = reportedModel,
                        reason = EMPTY_ANSWER_REASON,
                        text = null,
                        rawCalls = emptyList(),
                        tools = tools,
                    )
                }
                val calls = ToolCallAssembly.assemble(rawCalls, tools)
                val pending = rawCalls.map { call ->
                    PendingCall(callId = call.callId, name = call.name, wireId = call.wireId)
                }
                val nextConversation = if (rawCalls.isNotEmpty() && content != null) {
                    Conversation(
                        systemInstruction = state.systemInstruction,
                        contents = state.contents + content.deepCopy(),
                        pending = pending,
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

    private fun filteredTurn(
        reportedModel: String?,
        reason: String?,
        state: Conversation,
    ): HostedGenerationResult {
        val mismatch = reportedModel?.let { ProviderFailures.identityProblem(config.model, it) }
        if (mismatch != null) return HostedGenerationResult.ProviderFailure(mismatch)
        return HostedGenerationResult.Turn(
            HostedTurn(
                protocol = protocol,
                requestedModel = config.model,
                reportedModel = reportedModel,
                assistantText = null,
                toolCalls = emptyList(),
                finish = HostedTurnFinish.ContentFiltered(reason),
                usage = null,
                conversation = null,
            ),
        )
    }

    private fun unfinishedTurn(
        reportedModel: String?,
        reason: String,
        text: String?,
        rawCalls: List<RawToolCall>,
        tools: List<HostedToolDeclaration>,
    ): HostedGenerationResult = HostedGenerationResult.Turn(
        HostedTurn(
            protocol = protocol,
            requestedModel = config.model,
            reportedModel = reportedModel,
            assistantText = text,
            toolCalls = ToolCallAssembly.assemble(
                calls = rawCalls,
                tools = tools,
                forcedDisposition = ToolCallDisposition.TRUNCATED_TURN,
            ),
            finish = HostedTurnFinish.Incomplete(reason),
            usage = null,
            conversation = null,
        ),
    )

    private fun usageOf(root: JSONObject): HostedUsage? {
        val usage = root.optJSONObject("usageMetadata") ?: return null
        return HostedUsage(
            inputTokens = usage.positiveInt("promptTokenCount"),
            outputTokens = usage.positiveInt("candidatesTokenCount"),
            reasoningTokens = usage.positiveInt("thoughtsTokenCount"),
            cachedInputTokens = usage.positiveInt("cachedContentTokenCount"),
        )
    }

    private sealed interface FinishSignal {
        data object Complete : FinishSignal

        data class Incomplete(val reason: String) : FinishSignal

        data class Filtered(val reason: String?) : FinishSignal

        data class Failed(val failure: HostedProviderFailure) : FinishSignal
    }

    private fun classifyFinish(reason: String): FinishSignal = when (reason) {
        "STOP", "FINISH_REASON_UNSPECIFIED" -> FinishSignal.Complete
        "MAX_TOKENS" -> FinishSignal.Incomplete("max_tokens")
        "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY", "LANGUAGE" ->
            FinishSignal.Filtered(reason.lowercase())

        "MALFORMED_FUNCTION_CALL" -> FinishSignal.Incomplete("malformed_function_call")
        "" -> FinishSignal.Failed(
            HostedProviderFailure.InvalidResponse("candidate carried no finish reason"),
        )

        else -> FinishSignal.Failed(
            HostedProviderFailure.ProviderState(code = reason, detail = null),
        )
    }

    private companion object {
        const val AUTO_TOOL_MODE = "AUTO"
        const val EMPTY_ANSWER_REASON = "empty_answer"
        const val MAX_TRANSPORT_DETAIL = 200
    }
}

/** Reads a nonnegative token count, treating a missing field as unknown rather than zero. */
internal fun JSONObject.positiveInt(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    val value = optInt(name, -1)
    return if (value >= 0) value else null
}
