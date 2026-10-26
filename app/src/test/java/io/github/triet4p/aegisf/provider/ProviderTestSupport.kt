package io.github.triet4p.aegisf.provider

import org.json.JSONObject

/** A transport that hands back queued answers and records the exact requests it saw. */
internal class FakeTransport(vararg responses: HostedHttpResponse) : HttpTransport {
    private val queued = ArrayDeque(responses.toList())
    val requests: MutableList<HostedHttpRequest> = mutableListOf()

    override suspend fun post(request: HostedHttpRequest): HostedHttpResponse {
        requests += request
        return queued.removeFirstOrNull()
            ?: throw IllegalStateException("fake transport received more requests than it has answers")
    }

    fun requestJson(index: Int = requests.lastIndex): JSONObject = JSONObject(requests[index].body)
}

internal object ProviderFixtures {
    const val TOKEN = "synthetic-test-credential"

    val echoTool = HostedToolDeclaration(
        name = "probe_echo",
        description = "Echo one probe value back to the caller.",
        parameters = JSONObject(
            """
            {"type":"object","properties":{"value":{"type":"string","minLength":1}},
             "required":["value"],"additionalProperties":false}
            """.trimIndent(),
        ),
    )

    val secondTool = HostedToolDeclaration(
        name = "probe_count",
        description = "Report one bounded count.",
        parameters = JSONObject(
            """
            {"type":"object","properties":{"count":{"type":"integer","minimum":1,"maximum":500}},
             "required":["count"],"additionalProperties":false}
            """.trimIndent(),
        ),
    )

    val referencedTool = HostedToolDeclaration(
        name = "probe_page",
        description = "Ask for one bounded page.",
        parameters = JSONObject(
            """
            {"type":"object","properties":{"bounds":{"${'$'}ref":"#/${'$'}defs/page"}},
             "required":["bounds"],"additionalProperties":false,
             "${'$'}defs":{"page":{"type":"object","properties":{
               "limit":{"type":"integer","minimum":1,"maximum":500},
               "offset":{"type":"integer","minimum":0}},
               "required":["limit","offset"],"additionalProperties":false}}}
            """.trimIndent(),
        ),
    )

    fun geminiConfig(
        budget: HostedGenerationBudget = HostedGenerationBudget(
            maxOutputTokens = 8_192,
            reasoningEffort = HostedReasoningEffort.MINIMAL,
        ),
        capabilities: HostedProviderCapabilities = HostedProviderCapabilities(
            supportsToolCalls = true,
            supportsParallelToolCalls = false,
            supportsReasoningBudget = true,
            supportsTemperature = true,
            maxOutputTokensLimit = 65_536,
        ),
    ): HostedProviderConfig = HostedProviderConfig(
        protocol = HostedProtocol.GEMINI_GENERATE_CONTENT,
        endpoint = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
        model = "gemini-3.5-flash-lite",
        authentication = HostedAuthentication.GoogleApiKey(TOKEN),
        budget = budget,
        capabilities = capabilities,
    )

    fun chatConfig(
        budget: HostedGenerationBudget = HostedGenerationBudget(
            maxOutputTokens = 8_192,
            parallelToolCalls = false,
        ),
        capabilities: HostedProviderCapabilities = HostedProviderCapabilities(
            supportsToolCalls = true,
            supportsParallelToolCalls = true,
            supportsReasoningBudget = false,
            supportsTemperature = true,
        ),
        clientHeaders: Map<String, String> = mapOf(
            "x-opencode-session" to "synthetic-session",
            "User-Agent" to "aegis-f-test/1.0",
        ),
    ): HostedProviderConfig = HostedProviderConfig(
        protocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
        endpoint = "https://opencode.ai/zen/go/v1/chat/completions",
        model = "deepseek-v4.1-flash",
        authentication = HostedAuthentication.Bearer(TOKEN),
        budget = budget,
        capabilities = capabilities,
        clientHeaders = clientHeaders,
    )

    fun responsesConfig(
        budget: HostedGenerationBudget = HostedGenerationBudget(
            maxOutputTokens = 4_096,
            reasoningEffort = HostedReasoningEffort.MEDIUM,
            parallelToolCalls = false,
        ),
        capabilities: HostedProviderCapabilities = HostedProviderCapabilities(
            supportsToolCalls = true,
            supportsParallelToolCalls = true,
            supportsReasoningBudget = true,
            supportsTemperature = false,
        ),
    ): HostedProviderConfig = HostedProviderConfig(
        protocol = HostedProtocol.OPENAI_RESPONSES,
        endpoint = "https://opencode.ai/zen/go/v1/responses",
        model = "gpt-6-luna",
        authentication = HostedAuthentication.Bearer(TOKEN),
        budget = budget,
        capabilities = capabilities,
    )

    fun turn(result: HostedGenerationResult): HostedTurn =
        (result as? HostedGenerationResult.Turn)?.turn
            ?: throw AssertionError("expected a parsed turn, got $result")

    fun failure(result: HostedGenerationResult): HostedProviderFailure =
        (result as? HostedGenerationResult.ProviderFailure)?.failure
            ?: throw AssertionError("expected a provider failure, got $result")

    fun reply(
        body: String,
        status: Int = 200,
        headers: Map<String, String> = emptyMap(),
    ): HostedHttpResponse = HostedHttpResponse(status = status, headers = headers, body = body)
}
