package io.github.triet4p.aegisf.provider

import org.json.JSONObject
import java.net.URI
import java.net.URISyntaxException

/**
 * The provider-neutral hosted-generation contract.
 *
 * One agent runtime talks to Gemini, OpenAI Chat Completions and OpenAI Responses through
 * this surface only; the three adapters own their wire encodings. The contract is deliberately
 * explicit about the things a hosted agent must not guess:
 *
 * - **Identity.** [HostedProviderConfig] states the protocol, the absolute HTTPS endpoint, the
 *   model id, the credential and the model's capabilities. Nothing is defaulted, derived from a
 *   model name, or replaced when a request fails. An adapter sends the model id verbatim, uses
 *   the endpoint verbatim (Gemini's `{model}` placeholder is the one documented substitution),
 *   and compares the identity the provider echoes back with the configured one.
 * - **Budget.** [HostedGenerationBudget.maxOutputTokens] is required and per model; there is no
 *   shared cap. Reasoning effort and temperature are only sent when the declared capabilities
 *   include them, and a request for an undeclared feature fails as a configuration error instead
 *   of being silently dropped.
 * - **Dispatch eligibility.** A tool call is only [HostedToolCall.dispatchable] after its
 *   arguments are a complete JSON object that satisfies the declared argument schema. Partial
 *   or truncated arguments, unknown tools and schema violations are surfaced with their own
 *   [ToolCallDisposition] and can never be executed through this contract.
 * - **Continuation.** A turn that asks for tool calls returns a [HostedConversation] holding the
 *   protocol's required continuation items. [HostedGenerationAdapter.continueWithToolResults]
 *   refuses to build the next turn unless every pending call is answered exactly once, so a
 *   protocol-required item cannot be dropped silently.
 * - **Failures.** Transport, authentication, account/billing, rate limit, provider server,
 *   request rejection, endpoint redirect, provider-reported failure and schema-invalid payloads
 *   are distinct [HostedProviderFailure] values; conversation parsing problems are model
 *   failures reported per call, never provider errors.
 */
enum class HostedProtocol {
    /** Gemini API `models/{model}:generateContent` with `functionCall` / `functionResponse`. */
    GEMINI_GENERATE_CONTENT,

    /** OpenAI-compatible `chat/completions` with `tools` and `tool_calls`. */
    OPENAI_CHAT_COMPLETIONS,

    /** OpenAI Responses with typed `function_call` items and `function_call_output` results. */
    OPENAI_RESPONSES,
}

/** How the configured credential is presented; the token never appears in [toString]. */
sealed interface HostedAuthentication {
    val headerName: String

    /** The exact header value. Adapters use it only to build an authorization header. */
    fun headerValue(): String

    /** Removes this credential from provider text before it is retained as a failure detail. */
    fun redact(text: String): String

    class Bearer(private val token: String) : HostedAuthentication {
        init {
            requireToken(token, "bearer token")
        }

        override val headerName: String = "Authorization"

        override fun headerValue(): String = "Bearer $token"

        override fun redact(text: String): String = text.replace(token, REDACTED)

        override fun toString(): String = "HostedAuthentication.Bearer(redacted)"
    }

    class GoogleApiKey(private val token: String) : HostedAuthentication {
        init {
            requireToken(token, "Google API key")
        }

        override val headerName: String = "x-goog-api-key"

        override fun headerValue(): String = token

        override fun redact(text: String): String = text.replace(token, REDACTED)

        override fun toString(): String = "HostedAuthentication.GoogleApiKey(redacted)"
    }

    private companion object {
        const val REDACTED = "[redacted-credential]"

        fun requireToken(token: String, label: String) {
            if (token.isBlank()) throw HostedConfigurationException("$label must not be blank")
            if (token.any { it == '\r' || it == '\n' || it.code < 0x20 }) {
                throw HostedConfigurationException("$label must not contain control characters")
            }
        }
    }
}

/**
 * What the configured model is known to accept.
 *
 * These are declarations, not guesses from a model name: a model whose support was not
 * discovered keeps the feature off, and requesting it is a configuration error.
 */
data class HostedProviderCapabilities(
    /** The model accepts function/tool declarations and returns native calls. */
    val supportsToolCalls: Boolean,
    /** The model accepts a parallel-tool-call setting for a turn. */
    val supportsParallelToolCalls: Boolean,
    /** The model accepts its protocol's reasoning/thinking budget field. */
    val supportsReasoningBudget: Boolean,
    /** The model accepts a temperature setting. */
    val supportsTemperature: Boolean,
    /** The provider-published output limit, when one is known. */
    val maxOutputTokensLimit: Int? = null,
) {
    init {
        if (maxOutputTokensLimit != null && maxOutputTokensLimit < 1) {
            throw HostedConfigurationException("maxOutputTokensLimit must be positive")
        }
    }
}

/** Neutral reasoning efforts; adapters map them to their protocol's own vocabulary. */
enum class HostedReasoningEffort(val wireName: String) {
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
}

/**
 * One model's frozen generation budget.
 *
 * [maxOutputTokens] is required: a large reasoning model must be given room to reason and
 * answer, and a single shared cap (the old 2048-token value) is exactly what this contract
 * forbids. Truncation is reported as an incomplete turn, never as a complete answer.
 */
data class HostedGenerationBudget(
    val maxOutputTokens: Int,
    val reasoningEffort: HostedReasoningEffort? = null,
    val temperature: Double? = null,
    /** `null` leaves the provider's own parallel-call default in place. */
    val parallelToolCalls: Boolean? = null,
) {
    init {
        if (maxOutputTokens < 1) {
            throw HostedConfigurationException("maxOutputTokens must be positive")
        }
        if (temperature != null && temperature !in 0.0..2.0) {
            throw HostedConfigurationException("temperature must be within 0.0..2.0")
        }
    }
}

/**
 * The explicit hosted provider configuration.
 *
 * Construction is the validation point: a non-HTTPS endpoint, a model id that cannot travel in
 * the endpoint path, an authentication kind the protocol cannot use, a capability that does not
 * cover a requested feature, or a credential-carrying extra header are all refused before any
 * request exists.
 */
data class HostedProviderConfig(
    val protocol: HostedProtocol,
    /** Absolute HTTPS endpoint; Gemini requires the one `{model}` placeholder. */
    val endpoint: String,
    /** The model id sent verbatim to the configured endpoint. */
    val model: String,
    val authentication: HostedAuthentication,
    val budget: HostedGenerationBudget,
    val capabilities: HostedProviderCapabilities,
    /**
     * Extra client headers the endpoint documents (for example a stable session id and a
     * client user agent). Credential and framing headers are reserved and refused here.
     */
    val clientHeaders: Map<String, String> = emptyMap(),
) {
    init {
        val uri = endpointUri(endpoint.replace(MODEL_PLACEHOLDER, "model"))
        if (!endpoint.startsWith("https://")) {
            throw HostedConfigurationException("endpoint must use https")
        }
        if (uri.host.isNullOrBlank()) throw HostedConfigurationException("endpoint must name a host")
        if (uri.userInfo != null) throw HostedConfigurationException("endpoint must not carry user info")
        if (uri.fragment != null) throw HostedConfigurationException("endpoint must not carry a fragment")
        if (model.isBlank() || model.length > 200) {
            throw HostedConfigurationException("model id must be a nonblank bounded value")
        }
        if (model.any { it.isWhitespace() || it == '/' || it == '?' || it == '#' || it.code < 0x20 }) {
            throw HostedConfigurationException("model id must travel in a request path unchanged")
        }
        when (protocol) {
            HostedProtocol.GEMINI_GENERATE_CONTENT -> {
                if (endpoint.count { it == '{' } != 1 || !endpoint.contains(MODEL_PLACEHOLDER)) {
                    throw HostedConfigurationException("Gemini endpoint must carry one {model} placeholder")
                }
                if (authentication !is HostedAuthentication.GoogleApiKey) {
                    throw HostedConfigurationException("Gemini requires a Google API key credential")
                }
            }

            HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            HostedProtocol.OPENAI_RESPONSES,
            -> {
                if (endpoint.contains(MODEL_PLACEHOLDER)) {
                    throw HostedConfigurationException("OpenAI-style endpoint must not carry {model}")
                }
                if (authentication !is HostedAuthentication.Bearer) {
                    throw HostedConfigurationException("OpenAI-style protocol requires a bearer credential")
                }
            }
        }
        val limit = capabilities.maxOutputTokensLimit
        if (limit != null && budget.maxOutputTokens > limit) {
            throw HostedConfigurationException(
                "maxOutputTokens ${budget.maxOutputTokens} exceeds the declared model limit $limit",
            )
        }
        if (budget.reasoningEffort != null && !capabilities.supportsReasoningBudget) {
            throw HostedConfigurationException("reasoning budget was requested but the model declares no support")
        }
        if (budget.temperature != null && !capabilities.supportsTemperature) {
            throw HostedConfigurationException("temperature was requested but the model declares no support")
        }
        if (budget.parallelToolCalls != null && !capabilities.supportsParallelToolCalls) {
            throw HostedConfigurationException(
                "parallel tool calls were configured but the model declares no support",
            )
        }
        validateClientHeaders(clientHeaders)
    }

    /** The endpoint with this configuration's model substituted into its one placeholder. */
    fun endpointForModel(): String = endpoint.replace(MODEL_PLACEHOLDER, model)

    companion object {
        const val MODEL_PLACEHOLDER: String = "{model}"

        private val RESERVED_HEADERS = setOf(
            "authorization",
            "proxy-authorization",
            "x-goog-api-key",
            "content-type",
            "content-length",
            "host",
            "connection",
            "transfer-encoding",
        )
        private val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

        private fun endpointUri(endpoint: String): URI = try {
            URI(endpoint)
        } catch (exception: URISyntaxException) {
            throw HostedConfigurationException("endpoint is not a valid absolute URL: ${exception.reason}")
        }

        private fun validateClientHeaders(headers: Map<String, String>) {
            val seen = mutableSetOf<String>()
            for ((name, value) in headers) {
                if (!HEADER_NAME.matches(name)) {
                    throw HostedConfigurationException("client header name is not a token: $name")
                }
                if (name.lowercase() in RESERVED_HEADERS) {
                    throw HostedConfigurationException("client header $name is owned by the adapter")
                }
                if (!seen.add(name.lowercase())) {
                    throw HostedConfigurationException("client header $name was supplied twice")
                }
                if (value.isBlank() || value.length > 1_024) {
                    throw HostedConfigurationException("client header $name has an unusable value")
                }
                if (value.any { it == '\r' || it == '\n' || it.code < 0x20 }) {
                    throw HostedConfigurationException("client header $name contains control characters")
                }
            }
        }
    }
}

/** One declared tool, identical across transports apart from the wire encoding. */
data class HostedToolDeclaration(
    val name: String,
    val description: String,
    /** A JSON Schema object describing the complete argument object. */
    val parameters: JSONObject,
) {
    init {
        if (!TOOL_NAME.matches(name)) {
            throw HostedConfigurationException("tool name is not portable across the adapters: $name")
        }
        if (description.isBlank()) throw HostedConfigurationException("tool $name has no description")
    }

    companion object {
        private val TOOL_NAME = Regex("^[A-Za-z_][A-Za-z0-9_-]{0,63}$")
    }
}

/**
 * The actual observation of one executed call, fed back to the provider as its native result.
 *
 * [outputJson] is the structured observation the application returned. Chat Completions and
 * Responses carry it as their string result; Gemini carries it as the `functionResponse`
 * `response` object, so that adapter requires a JSON object.
 */
data class HostedToolResult(
    val callId: String,
    val outputJson: String,
) {
    init {
        if (callId.isBlank()) throw HostedConfigurationException("tool result call id must not be blank")
        if (outputJson.isBlank()) throw HostedConfigurationException("tool result output must not be blank")
    }
}

/**
 * A provider conversation that retains its own native continuation.
 *
 * The adapter that produced it is the only code that reads the protocol-specific part; callers
 * pass it back unchanged. [pendingCallIds] lists every call the next turn must answer, including
 * calls whose arguments were malformed: the protocol still requires a result item for each one,
 * and the executor answers those with an error observation without executing anything.
 */
interface HostedConversation {
    val protocol: HostedProtocol
    val pendingCallIds: List<String>
}

/** Why a call may or may not be executed. Only [READY] is executable. */
enum class ToolCallDisposition {
    /** Complete arguments satisfying the declared schema for a known tool. */
    READY,

    /** The name is not one of the declarations offered for this turn. */
    UNKNOWN_TOOL,

    /** Arguments were absent, cut off, or not a complete JSON object of the declared shape. */
    MALFORMED_ARGUMENTS,

    /** Arguments are a complete object but violate the declared schema. */
    SCHEMA_INVALID,

    /** The turn itself was truncated, so no call in it may be executed. */
    TRUNCATED_TURN,

    /** The provider refused the turn on safety grounds, so no call in it may be executed. */
    FILTERED_TURN,
}

/**
 * One native tool call after assembly and validation.
 *
 * [argumentsJson] is the exact wire text where the wire carries text (`null` when the provider
 * omitted it). [arguments] is present only for a complete, schema-valid object. [disposition]
 * is the only authority for dispatch: a caller that checks [dispatchable] can never execute a
 * partial call.
 */
data class HostedToolCall(
    val callId: String,
    val name: String,
    val argumentsJson: String?,
    val arguments: Map<String, Any?>?,
    val disposition: ToolCallDisposition,
    /** A bounded, value-free reason for a non-ready disposition. */
    val detail: String? = null,
) {
    val dispatchable: Boolean get() = disposition == ToolCallDisposition.READY
}

/** Token accounting as reported by the provider; absent fields stay null rather than guessed. */
data class HostedUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cachedInputTokens: Int? = null,
)

/** The classified end of one provider turn. */
sealed interface HostedTurnFinish {
    /** A complete answer, verified as such by the protocol's own finish state. */
    data object Complete : HostedTurnFinish

    /** Complete model output that asks for tool results before it can continue. */
    data object ToolCalls : HostedTurnFinish

    /** The provider stopped on an output budget (for example `MAX_TOKENS` or `length`). */
    data class Incomplete(val reason: String) : HostedTurnFinish

    /** The provider refused the turn on safety/policy grounds; there is no answer to report. */
    data class ContentFiltered(val reason: String?) : HostedTurnFinish
}

/**
 * One parsed provider turn.
 *
 * [reportedModel] is the provider's own echo of the model it served. The adapter only returns a
 * turn after it matches the configured model; a mismatch is a [HostedProviderFailure].
 */
data class HostedTurn(
    val protocol: HostedProtocol,
    val requestedModel: String,
    /**
     * The provider's own echo of the model it served, when it reported one. A completed,
     * truncated or tool-call turn always carries a verified echo equal to the configured
     * model; a refused turn may carry no echo because there is no model answer to identify.
     */
    val reportedModel: String?,
    val assistantText: String?,
    val toolCalls: List<HostedToolCall>,
    val finish: HostedTurnFinish,
    val usage: HostedUsage?,
    /** Present only when the turn asks for tool results and can therefore continue. */
    val conversation: HostedConversation?,
)

/** The outcome of one generation request: either a parsed turn or a classified provider failure. */
sealed interface HostedGenerationResult {
    data class Turn(val turn: HostedTurn) : HostedGenerationResult

    data class ProviderFailure(val failure: HostedProviderFailure) : HostedGenerationResult
}

/**
 * Provider-side failures, kept distinct from model failures (per-call dispositions) and from
 * tool failures (the application's own outcome vocabulary in S8.7).
 *
 * [detail] is already sanitized of the configured credential and bounded; it is diagnostic text
 * from the endpoint, not model content.
 */
sealed interface HostedProviderFailure {
    /** The request did not complete: no connection, DNS, TLS or read timeout. */
    data class Transport(val detail: String?) : HostedProviderFailure

    /** The endpoint rejected the credential (401/403). */
    data class Authentication(val status: Int, val detail: String?) : HostedProviderFailure

    /** The account cannot pay for the request (402 or a billing-class rejection). */
    data class Account(val status: Int, val detail: String?) : HostedProviderFailure

    /** The endpoint rate-limited the request (429). */
    data class RateLimited(val status: Int, val retryAfterSeconds: Long?, val detail: String?) :
        HostedProviderFailure

    /** The endpoint rejected the request itself (400/404/409/422 and similar). */
    data class Rejected(val status: Int, val detail: String?) : HostedProviderFailure

    /** The endpoint failed while serving the request (5xx). */
    data class Unavailable(val status: Int, val detail: String?) : HostedProviderFailure

    /** The endpoint redirected; following it could forward the credential to another host. */
    data class Redirected(val status: Int, val detail: String?) : HostedProviderFailure

    /** A 2xx payload reports a failed/cancelled/non-terminal provider state. */
    data class ProviderState(val code: String, val detail: String?) : HostedProviderFailure

    /** The provider echoed a model id other than the configured one (hidden substitution). */
    data class ModelMismatch(val configuredModel: String, val reportedModel: String) :
        HostedProviderFailure

    /** A 2xx payload that does not satisfy the protocol's response schema. */
    data class InvalidResponse(val detail: String?) : HostedProviderFailure
}

/** A caller/programming error in the provider contract, never a provider outcome. */
class HostedConfigurationException(message: String) : IllegalArgumentException(message)

/**
 * The neutral generation surface.
 *
 * [begin] starts a conversation; [generate] performs one provider turn; and
 * [continueWithToolResults] returns the next conversation carrying the protocol's own
 * continuation items and exactly the supplied observations. Implementations never dispatch a
 * tool call themselves.
 */
interface HostedGenerationAdapter {
    val protocol: HostedProtocol

    fun begin(systemInstruction: String, userInstruction: String): HostedConversation

    suspend fun generate(
        conversation: HostedConversation,
        tools: List<HostedToolDeclaration> = emptyList(),
    ): HostedGenerationResult

    fun continueWithToolResults(
        conversation: HostedConversation,
        results: List<HostedToolResult>,
    ): HostedConversation
}

/** Creates the adapter for the configured protocol; there is no fallback between protocols. */
object HostedAdapters {
    fun create(config: HostedProviderConfig, transport: HttpTransport): HostedGenerationAdapter =
        when (config.protocol) {
            HostedProtocol.GEMINI_GENERATE_CONTENT -> GeminiGenerateContentAdapter(config, transport)
            HostedProtocol.OPENAI_CHAT_COMPLETIONS -> OpenAiChatCompletionsAdapter(config, transport)
            HostedProtocol.OPENAI_RESPONSES -> OpenAiResponsesAdapter(config, transport)
        }
}
