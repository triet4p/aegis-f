package io.github.triet4p.aegisf.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
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
    private val streamTransport: HttpStreamTransport? = null,
) : HostedGenerationAdapter {
    override val protocol: HostedProtocol = HostedProtocol.GEMINI_GENERATE_CONTENT

    /**
     * Gemini streams on its documented `:streamGenerateContent?alt=sse` route, which is derived
     * only from an endpoint that is exactly the configured `:generateContent` route with no
     * query string. Any other endpoint keeps the complete-body route and reports no streaming.
     */
    private val streamEndpoint: String? =
        config.endpoint
            .takeIf { it.endsWith(POST_SUFFIX) && '?' !in it }
            ?.removeSuffix(POST_SUFFIX)
            ?.plus(STREAM_SUFFIX)
            ?.plus(STREAM_QUERY)

    override val streamingEnabled: Boolean get() = streamTransport != null && streamEndpoint != null

    /** Value-free identity from this adapter's validated config. */
    override fun providerIdentity(): HostedProviderIdentity = config.identity()

    /** Root metadata this validated configuration may receive; Gemini has no Go route. */
    override fun responseMetadataPolicy(): HostedResponseMetadataPolicy =
        config.responseMetadataPolicy()

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
        val stream = streamTransport
        val endpoint = streamEndpoint
        if (stream == null || endpoint == null) {
            return generateWithResponseGuard(conversation, tools, responseGuard)
        }
        val state = requireGeneratable(conversation, tools)
        val request = buildRequest(state, tools)
        val parts = JSONArray()
        var reportedModel: String? = null
        var finishReason = ""
        var usage: JSONObject? = null
        var promptFeedback: JSONObject? = null
        var started: HostedStreamFrame.Start? = null
        var failure: HostedGenerationResult? = null
        try {
            stream.postStream(
                HostedHttpRequest(
                    url = endpoint.replace(HostedProviderConfig.MODEL_PLACEHOLDER, config.model),
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
                        val chunk = ProviderJson.strictObject(payload)
                        if (chunk == null) {
                            failure = HostedGenerationResult.ProviderFailure(
                                HostedProviderFailure.InvalidResponse("stream chunk was not a JSON object"),
                            )
                            return@collect
                        }
                        if (chunk.has("error") && !chunk.isNull("error")) {
                            failure = HostedGenerationResult.ProviderFailure(
                                HostedProviderFailure.ProviderState(
                                    code = ProviderJson.textOrNull(
                                        chunk.optJSONObject("error") ?: JSONObject(),
                                        "status",
                                    ).orEmpty().ifBlank { "error" },
                                    detail = ProviderFailures.detail(payload, config.authentication),
                                ),
                            )
                            return@collect
                        }
                        ProviderJson.textOrNull(chunk, "modelVersion")?.takeIf { it.isNotBlank() }?.let {
                            reportedModel = it
                        }
                        chunk.optJSONObject("usageMetadata")?.let { usage = it }
                        chunk.optJSONObject("promptFeedback")?.let { promptFeedback = it }
                        val candidate = chunk.optJSONArray("candidates")?.optJSONObject(0)
                            ?: return@collect
                        ProviderJson.textOrNull(candidate, "finishReason")?.takeIf { it.isNotBlank() }?.let {
                            finishReason = it
                        }
                        val chunkParts = candidate.optJSONObject("content")?.optJSONArray("parts")
                            ?: return@collect
                        for (index in 0 until chunkParts.length()) {
                            val part = chunkParts.optJSONObject(index) ?: continue
                            val thought = part.optBoolean("thought", false)
                            val text = ProviderJson.textOrNull(part, "text")?.takeIf { it.isNotEmpty() }
                            if (text != null) {
                                appendStreamedTextPart(parts, part, text, thought)
                                if (!thought) {
                                    onProgress(HostedTurnProgress.PublicText(text))
                                }
                                continue
                            }
                            if (part.has("functionCall")) {
                                parts.put(part.deepCopy())
                                if (!thought) {
                                    val name = ProviderJson.textOrNull(
                                        part.optJSONObject("functionCall") ?: JSONObject(),
                                        "name",
                                    ).orEmpty()
                                    if (tools.any { it.name == name }) {
                                        onProgress(HostedTurnProgress.ToolCallStarted(name))
                                        onProgress(HostedTurnProgress.ToolCallCompleted(name))
                                    }
                                }
                                continue
                            }
                            parts.put(part.deepCopy())
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
        reportedModel?.let { assembled.put("modelVersion", it) }
        promptFeedback?.let { assembled.put("promptFeedback", it) }
        if (parts.length() > 0 || finishReason.isNotBlank()) {
            assembled.put(
                "candidates",
                JSONArray().put(
                    JSONObject()
                        .put("content", JSONObject().put("role", "model").put("parts", parts))
                        .put("finishReason", finishReason)
                        .put("index", 0),
                ),
            )
        } else {
            assembled.put("candidates", JSONArray())
        }
        usage?.let { assembled.put("usageMetadata", it) }
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

    /**
     * Appends one streamed text part to the assembled model content.
     *
     * Consecutive plain text parts merge into one part, so the assembled body has the same shape
     * as a complete-body answer. A thought-marked part or a part carrying opaque pairing state
     * stays its own part, and no thought text ever reaches public progress.
     */
    private fun appendStreamedTextPart(
        parts: JSONArray,
        part: JSONObject,
        text: String,
        thought: Boolean,
    ) {
        val plain = part.keys().asSequence().toSet() == setOf("text")
        if (!thought && plain) {
            val last = parts.optJSONObject(parts.length() - 1)
            if (last != null && last.keys().asSequence().toSet() == setOf("text") &&
                !last.has("thought")
            ) {
                last.put("text", last.getString("text") + text)
                return
            }
        }
        parts.put(part.deepCopy())
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
        // The host tail rides in the same user content as the function responses, so the
        // turn alternation stays exactly model -> user and no extra unmatched call appears.
        if (hostNotice != null) {
            if (hostNotice.isBlank()) throw HostedConfigurationException("a host notice must not be blank")
            parts.put(JSONObject().put("text", hostNotice))
        }
        val toolTurn = JSONObject().put("role", "user").put("parts", parts)
        return Conversation(
            systemInstruction = state.systemInstruction,
            contents = state.contents + toolTurn,
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
        val noticeTurn = JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", notice)))
        return Conversation(
            systemInstruction = state.systemInstruction,
            contents = state.contents + noticeTurn,
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
        val generation = JSONObject()
        config.budget.maxOutputTokens?.let { generation.put("maxOutputTokens", it) }
        config.budget.temperature?.let { generation.put("temperature", it) }
        config.budget.reasoningEffort?.let {
            generation.put("thinkingConfig", JSONObject().put("thinkingLevel", it.wireName))
        }
        if (generation.length() > 0) request.put("generationConfig", generation)
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
        val promptBlock = ProviderJson.textOrNull(promptFeedback ?: JSONObject(), "blockReason").orEmpty()
        val reportedModel = ProviderJson.textOrNull(root, "modelVersion").orEmpty().ifBlank { null }
        if (promptBlock.isNotBlank()) {
            return filteredTurn(reportedModel, promptBlock.lowercase(), state)
        }
        if (root.has("error") && !root.isNull("error")) {
            val error = root.optJSONObject("error")
            return HostedGenerationResult.ProviderFailure(
                HostedProviderFailure.ProviderState(
                    code = ProviderJson.textOrNull(error ?: JSONObject(), "status").orEmpty().ifBlank { "error" },
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
        val finishReason = ProviderJson.textOrNull(candidate, "finishReason").orEmpty()
        val content = candidate.optJSONObject("content")
        val parts = mutableListOf<JSONObject>()
        content?.optJSONArray("parts")?.let { array ->
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { parts.add(it) }
            }
        }
        val text = parts.mapNotNull { part ->
            if (part.optBoolean("thought", false)) return@mapNotNull null
            ProviderJson.textOrNull(part, "text")?.takeIf { it.isNotEmpty() }
        }.joinToString(separator = "").ifBlank { null }
        val rawCalls = mutableListOf<RawToolCall>()
        var thoughtCallSeen = false
        for ((index, part) in parts.withIndex()) {
            if (!part.has("functionCall")) continue
            // A thought-marked part is provider reasoning, never tool authority: it contributes
            // no dispatchable call even when it carries schema-valid functionCall bytes. The part
            // stays in the retained model content with its thought flag and thoughtSignature, so
            // the thinking-model continuation keeps its required signature.
            if (part.optBoolean("thought", false)) {
                thoughtCallSeen = true
                continue
            }
            val call = part.optJSONObject("functionCall")
                ?: return HostedGenerationResult.ProviderFailure(
                    HostedProviderFailure.InvalidResponse("functionCall part was not an object"),
                )
            val name = ProviderJson.textOrNull(call, "name").orEmpty()
            if (name.isBlank()) {
                return HostedGenerationResult.ProviderFailure(
                    HostedProviderFailure.InvalidResponse("functionCall part carried no tool name"),
                )
            }
            val wireId = ProviderJson.textOrNull(call, "id").orEmpty().ifBlank { null }
            val arguments: String? = when (val rawArguments = call.opt("args")) {
                null, JSONObject.NULL -> null
                is JSONObject -> rawArguments.toString()
                else -> return HostedGenerationResult.ProviderFailure(
                    HostedProviderFailure.InvalidResponse("functionCall arguments were not an object"),
                )
            }
            rawCalls.add(RawToolCall(wireId ?: "gemini-call-$index", name, arguments, wireId))
        }
        if (thoughtCallSeen && rawCalls.isEmpty() && text == null) {
            return unfinishedTurn(
                reportedModel = reportedModel,
                reason = "thought_only_function_call",
                text = null,
                rawCalls = emptyList(),
                tools = tools,
            )
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
                        contents = state.contents + scrubbedModelContent(content),
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
    /**
     * The retained model content with thought prose removed but protocol structure intact.
     *
     * Thought-marked parts keep their `thought` flag and `thoughtSignature` (a thinking model
     * requires the signature on the next turn) while any `text` they carried is dropped: that
     * prose was reasoning, never the answer, and must not ride the outbound continuation. Parts
     * without `thought: true` pass through byte-identical, so call/result pairing is unchanged.
     */
    private fun scrubbedModelContent(content: JSONObject): JSONObject {
        val copy = content.deepCopy()
        val parts = copy.optJSONArray("parts") ?: return copy
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            if (!part.optBoolean("thought", false)) continue
            part.remove("text")
        }
        return copy
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
        const val POST_SUFFIX = ":generateContent"
        const val STREAM_SUFFIX = ":streamGenerateContent"
        const val STREAM_QUERY = "?alt=sse"
    }
}

