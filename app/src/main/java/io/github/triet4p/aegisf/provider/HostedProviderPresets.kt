package io.github.triet4p.aegisf.provider

/** Presets only use protocol/model routes exercised by the project's prior live adapter probes. */
data class HostedProviderPreset(
    val id: String,
    val label: String,
    val protocol: HostedProtocol,
    val endpoint: String,
    val model: String,
    val maxOutputTokens: Int,
    val reasoningEffort: HostedReasoningEffort?,
    val capabilities: HostedProviderCapabilities,
) {
    fun profile(
        name: String,
        credential: String,
        maxOutputTokens: Int = this.maxOutputTokens,
        reasoningEffort: HostedReasoningEffort? = this.reasoningEffort,
        parallelToolCalls: Boolean? = if (capabilities.supportsParallelToolCalls) true else null,
    ): SavedHostedProvider = SavedHostedProvider(
        id = id,
        name = name,
        protocol = protocol,
        endpoint = endpoint,
        model = model,
        credential = credential,
        maxOutputTokens = maxOutputTokens,
        reasoningEffort = reasoningEffort,
        temperature = null,
        parallelToolCalls = parallelToolCalls,
        capabilities = capabilities,
    ).also { it.validatedConfig() }
}

object HostedProviderPresets {
    private val toolModel = HostedProviderCapabilities(
        supportsToolCalls = true,
        supportsParallelToolCalls = true,
        supportsReasoningBudget = false,
        supportsTemperature = false,
        maxOutputTokensLimit = null,
    )

    val all: List<HostedProviderPreset> = listOf(
        HostedProviderPreset(
            id = "google-gemma-4-26b-a4b-it",
            label = "Gemini API · Gemma 4 26B",
            protocol = HostedProtocol.GEMINI_GENERATE_CONTENT,
            endpoint = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
            model = "gemma-4-26b-a4b-it",
            maxOutputTokens = 8192,
            reasoningEffort = null,
            capabilities = toolModel.copy(
                supportsParallelToolCalls = false,
                maxOutputTokensLimit = 32768,
            ),
        ),
        HostedProviderPreset(
            id = "google-gemini-flash-lite",
            label = "Gemini API · Gemini 3.5 Flash Lite",
            protocol = HostedProtocol.GEMINI_GENERATE_CONTENT,
            endpoint = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
            model = "gemini-3.5-flash-lite",
            maxOutputTokens = 8192,
            reasoningEffort = null,
            capabilities = toolModel.copy(
                supportsParallelToolCalls = false,
                supportsReasoningBudget = true,
                maxOutputTokensLimit = 65536,
            ),
        ),
        HostedProviderPreset(
            id = "opencode-go-deepseek",
            label = "OpenCode Go · DeepSeek V4.1 Flash",
            protocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            endpoint = "https://opencode.ai/zen/go/v1/chat/completions",
            model = "deepseek-v4.1-flash",
            maxOutputTokens = 8192,
            reasoningEffort = null,
            capabilities = toolModel,
        ),
        HostedProviderPreset(
            id = "opencode-go-responses",
            label = "OpenCode Go · GPT-6 Luna (Responses)",
            protocol = HostedProtocol.OPENAI_RESPONSES,
            endpoint = "https://opencode.ai/zen/go/v1/responses",
            model = "gpt-6-luna",
            maxOutputTokens = 4096,
            reasoningEffort = HostedReasoningEffort.MEDIUM,
            capabilities = toolModel.copy(supportsReasoningBudget = true, supportsParallelToolCalls = false),
        ),
    )

    fun byId(id: String): HostedProviderPreset = all.single { it.id == id }
}
