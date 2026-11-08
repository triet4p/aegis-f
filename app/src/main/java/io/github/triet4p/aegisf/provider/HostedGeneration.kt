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
 * - **Budget.** [HostedGenerationBudget.maxOutputTokens] is optional and `null` by default: the
 *   request then carries no output-limit field at all and the provider applies the selected
 *   model's own default. A configured value is per model and sent verbatim; there is no shared
 *   cap, and the app's own presets never impose one. Reasoning effort and temperature are only
 *   sent when the declared capabilities include them, and a request for an undeclared feature
 *   fails as a configuration error instead of being silently dropped.
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
 * The canonical cache-identity spelling of a budget that requests no explicit output ceiling.
 * The wire request simply omits the protocol's limit field in that case.
 */
const val PROVIDER_DEFAULT_OUTPUT_TOKENS: String = "provider-default"

/**
 * One model's generation budget.
 *
 * [maxOutputTokens] is the optional explicit output ceiling and `null` by default. `null` omits
 * the protocol's limit field entirely so the provider applies the selected model's own default:
 * that is the only setting that both lets a reasoning model spend its own thinking budget before
 * the first tool call and stays inside whatever that model actually supports. A non-null value
 * is sent verbatim and is bounded only by the declared model limit. Truncation is still reported
 * as an incomplete turn, never as a complete answer.
 */
data class HostedGenerationBudget(
    val maxOutputTokens: Int? = null,
    val reasoningEffort: HostedReasoningEffort? = null,
    val temperature: Double? = null,
    /** `null` leaves the provider's own parallel-call default in place. */
    val parallelToolCalls: Boolean? = null,
) {
    init {
        if (maxOutputTokens != null && maxOutputTokens < 1) {
            throw HostedConfigurationException("maxOutputTokens must be positive when it is set")
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
        val requestedOutputTokens = budget.maxOutputTokens
        if (limit != null && requestedOutputTokens != null && requestedOutputTokens > limit) {
            throw HostedConfigurationException(
                "maxOutputTokens $requestedOutputTokens exceeds the declared model limit $limit",
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
    /**
     * The canonical response body a streamed turn was assembled into, for the local production
     * trace only. It is the provider's own delta content in the protocol's ordinary response shape:
     * it is never displayed, rehydrated, exported or read as authority, and a complete-body turn
     * leaves it null because the transport already recorded the exact body.
     */
    val assembledResponseBody: String? = null,
)

/**
 * An ephemeral provider body carried only so a masking boundary can inspect unparsed fields.
 * `toString` is redacted; callers must not persist or display [body].
 */
class HostedRawResponse internal constructor(internal val body: String) {
    override fun toString(): String = "HostedRawResponse(redacted)"
}

enum class HostedResponsePrivacyRefusalReason {
    DECODED_SENSITIVE_MATCH,
    DUPLICATE_KEY,
    INVALID_JSON,
    UNSUPPORTED_ROOT,
    DEPTH_LIMIT,
    /** A bracket-delimited value that is not a token this session issued. */
    UNKNOWN_TOKEN,
    /** A well-formed token minted for another session; never rehydrated or shown. */
    FOREIGN_TOKEN,
    ;

    companion object {
        /**
         * Version of the safe refusal categories written into diagnostics.
         *
         * v2 splits the former coarse token category into the precise [UNKNOWN_TOKEN] and
         * [FOREIGN_TOKEN] reasons. No live producer emits the retired coarse name; retained
         * pre-v2 artifacts are read by their own historical evidence contract, never rewritten.
         *
         * v3 replaces the semantic `sensitiveKind` diagnostic with `sensitiveShape`, the lexical
         * shape hint (`numeric`/`text`/`alphanumeric`) of the matched value. Tokens are neutral
         * identities now, so no refusal can claim a semantic kind.
         */
        const val CONTRACT_VERSION = "aegis-response-refusal/v3"
    }
}

/** Value-free response refusal metadata; locations are produced only from the boundary allowlist. */
data class HostedResponsePrivacyRefusal(
    val reason: HostedResponsePrivacyRefusalReason,
    /**
     * The lexical shape hint of the matched value, when one hit: `numeric`, `text` or
     * `alphanumeric`. It is never a semantic type and never authority; null when the refusal
     * concerned a token shape rather than a raw value.
     */
    val sensitiveShape: String?,
    val safeLocation: String,
    val nodeType: String,
    val recognizedCounter: Boolean = false,
    val knownOtherProtocol: Boolean = false,
)

data class HostedResponseRecognizedCounter(
    /** The lexical shape hint of the recognized value, never a semantic kind. */
    val sensitiveShape: String,
    val safeLocation: String,
    val nodeType: String = "NUMBER",
    val recognizedCounter: Boolean = true,
    val knownOtherProtocol: Boolean = false,
)

enum class HostedResponseProtocolErrorReason {
    INVALID_USAGE_COUNTER,
    INVALID_PROTOCOL_METADATA,
}

/** The sanitized adapter detail for one protocol error; carries no provider value. */
internal fun HostedResponseProtocolErrorReason.safeDetail(): String = when (this) {
    HostedResponseProtocolErrorReason.INVALID_USAGE_COUNTER -> "invalid provider usage counter"
    HostedResponseProtocolErrorReason.INVALID_PROTOCOL_METADATA -> "invalid provider protocol metadata"
}

sealed interface HostedResponsePrivacyResult {
    data object Accepted : HostedResponsePrivacyResult
    data class AcceptedWithValidatedCounters(
        val matches: List<HostedResponseRecognizedCounter>,
    ) : HostedResponsePrivacyResult

    /**
     * The response carried only schema-validated technical metadata at the protocol's documented
     * root fields; [counters] repeats any recognized usage counters. [metadata] names the exact
     * locations and rules so evidence can prove what was exempted without carrying values.
     */
    data class AcceptedWithValidatedMetadata(
        val metadata: List<HostedResponseValidatedMetadata>,
        val counters: List<HostedResponseRecognizedCounter> = emptyList(),
    ) : HostedResponsePrivacyResult
    data class Refused(val refusal: HostedResponsePrivacyRefusal) : HostedResponsePrivacyResult
    data class ProtocolError(
        val reason: HostedResponseProtocolErrorReason,
        val safeLocation: String,
        val nodeType: String,
        val recognizedCounter: Boolean = false,
        val knownOtherProtocol: Boolean = false,
    ) : HostedResponsePrivacyResult
}

/** Scans one complete provider JSON response before status classification or protocol parsing. */
interface HostedResponsePrivacyGuard {
    fun scan(rawResponse: String, httpStatus: Int?): HostedResponsePrivacyResult
}

/** One provider turn or failure with an optional ephemeral body for full-response privacy scanning. */
sealed interface HostedGenerationResult {
    val rawResponseForPrivacyScan: HostedRawResponse?

    data class Turn(
        val turn: HostedTurn,
        override val rawResponseForPrivacyScan: HostedRawResponse? = null,
    ) : HostedGenerationResult

    data class ProviderFailure(
        val failure: HostedProviderFailure,
        override val rawResponseForPrivacyScan: HostedRawResponse? = null,
    ) : HostedGenerationResult
}

internal fun HostedGenerationResult.withRawResponseForPrivacyScan(body: String): HostedGenerationResult {
    val rawResponse = HostedRawResponse(body)
    return when (this) {
        is HostedGenerationResult.Turn -> copy(rawResponseForPrivacyScan = rawResponse)
        is HostedGenerationResult.ProviderFailure -> copy(rawResponseForPrivacyScan = rawResponse)
    }
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

    /** A complete response was refused by the local inbound privacy boundary. */
    data class PrivacyBlocked(val refusal: HostedResponsePrivacyRefusal) : HostedProviderFailure

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

    /**
     * Whether this adapter's validated configuration can stream public progress. False is an
     * honest capability statement, not a fallback: a caller that requested streaming must
     * report the limitation instead of simulating typed text.
     */
    val streamingEnabled: Boolean get() = false

    /**
     * Generates one turn and reports incremental public progress.
     *
     * The adapter owns protocol event parsing and delta assembly; [onProgress] receives only
     * already-classified progress (never private reasoning, never partial tool arguments). The
     * returned turn is assembled exactly like a complete-body turn, and a turn assembled from
     * a stream carries [HostedTurn.assembledResponseBody] for the local trace. The default
     * implementation performs no streaming at all, so an adapter that has not implemented the
     * protocol's delta grammar cannot pretend to stream.
     */
    suspend fun streamGuarded(
        conversation: HostedConversation,
        tools: List<HostedToolDeclaration>,
        responseGuard: HostedResponsePrivacyGuard,
        onProgress: (HostedTurnProgress) -> Unit,
    ): HostedGenerationResult = generateGuarded(conversation, tools, responseGuard)

    /**
     * The value-free provider identity (protocol/host/model/frozen budget) of this
     * adapter's validated configuration, for one run's stable cache key. Null when
     * the adapter cannot attribute its configuration; the run then reports usage
     * honestly without a cache identity instead of inventing one.
     */
    fun providerIdentity(): HostedProviderIdentity? = null

    /**
     * The exact technical metadata this adapter's validated configuration may receive. A
     * response guard exempts nothing when this is null, so an unattributable adapter stays
     * fully scanned instead of guessing a protocol or route.
     */
    fun responseMetadataPolicy(): HostedResponseMetadataPolicy? = null

    fun begin(systemInstruction: String, userInstruction: String): HostedConversation

    suspend fun generate(
        conversation: HostedConversation,
        tools: List<HostedToolDeclaration> = emptyList(),
    ): HostedGenerationResult

    /**
     * Generates one turn while scanning its complete raw response. Production adapters override
     * this to run [responseGuard] before parsing; the fallback supports decorators and controlled
     * adapters that expose a [HostedRawResponse] on their result.
     */
    suspend fun generateGuarded(
        conversation: HostedConversation,
        tools: List<HostedToolDeclaration>,
        responseGuard: HostedResponsePrivacyGuard,
    ): HostedGenerationResult {
        val result = generate(conversation, tools)

        val rawResponse = result.rawResponseForPrivacyScan ?: return result
        when (val scan = responseGuard.scan(rawResponse.body, null)) {
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
        return when (result) {
            is HostedGenerationResult.Turn -> result.copy(rawResponseForPrivacyScan = null)
            is HostedGenerationResult.ProviderFailure -> result.copy(rawResponseForPrivacyScan = null)
        }
    }

    /**
     * Answers every pending call with its observation and returns the next conversation.
     *
     * [hostNotice] is the host-authored tail of that same continuation: the run's authoritative
     * issued-token inventory, already masked. An adapter lowers it natively immediately after
     * the tool outputs (never dropping it and never creating an unmatched call), because the
     * model must see the current inventory on every turn, including after new observations. The
     * gateway masks it like any other prompt text; null means no tail is added.
     */
    fun continueWithToolResults(
        conversation: HostedConversation,
        results: List<HostedToolResult>,
        hostNotice: String? = null,
    ): HostedConversation

    /**
     * Appends one host-authored notice as the next native user turn.
     *
     * Used only by the bounded masking repair: the refused response left no pending call, so
     * the conversation is continued without answering anything, and the protocol's own
     * required items stay byte-identical. An adapter must refuse when a call is still pending,
     * because appending content there would produce an unmatched tool-call continuation.
     *
     * The default fails closed: an adapter that does not implement native lowering cannot be
     * used for a repair, and the executor then ends the run terminally instead of silently
     * dropping the notice or the protocol's continuation items.
     */
    fun appendUserNotice(conversation: HostedConversation, notice: String): HostedConversation =
        throw HostedConfigurationException("adapter ${protocol.name} cannot append a host notice")
}

/** Creates the adapter for the configured protocol; there is no fallback between protocols. */
object HostedAdapters {
    fun create(config: HostedProviderConfig, transport: HttpTransport): HostedGenerationAdapter {
        val streaming = transport as? HttpStreamTransport
        return when (config.protocol) {
            HostedProtocol.GEMINI_GENERATE_CONTENT ->
                GeminiGenerateContentAdapter(config, transport, streaming)

            HostedProtocol.OPENAI_CHAT_COMPLETIONS ->
                OpenAiChatCompletionsAdapter(config, transport, streaming)

            HostedProtocol.OPENAI_RESPONSES ->
                OpenAiResponsesAdapter(config, transport, streaming)
        }
    }
}
