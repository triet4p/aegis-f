package io.github.triet4p.aegisf.provider

import java.net.URI
import org.json.JSONObject

/**
 * One response value the guard validated as documented technical metadata instead of content.
 * The kind names the exact schema rule that applied; it never carries the value.
 */
enum class HostedResponseMetadataKind {
    /** An opaque provider-assigned id at the protocol's documented root field. */
    PROVIDER_ID,

    /** The configured model echoed at the protocol's documented model field. */
    MODEL_ECHO,

    /** The protocol's own fixed root object discriminator. */
    OBJECT_DISCRIMINATOR,

    /** The root creation timestamp: a nonnegative integer JSON number. */
    TIMESTAMP,

    /** The exact OpenCode Go route accounting extension: a finite nonnegative JSON number. */
    GO_USAGE_AMOUNT,
}

/** A value-free record of one validated technical-metadata location. */
data class HostedResponseValidatedMetadata(
    val kind: HostedResponseMetadataKind,
    val safeLocation: String,
    val nodeType: String,
)

/**
 * The exact technical metadata one validated configuration may receive in a complete response.
 *
 * It is constructed from the adapter's own validated [HostedProviderConfig], so a response is
 * exempted only against the protocol and route that produced the request. There is deliberately
 * no general metadata, object, usage or unknown-field exemption: a field is listed here only when
 * its protocol schema documents it and its value satisfies that schema.
 */
data class HostedResponseMetadataPolicy(
    val protocol: HostedProtocol,
    /** The configured model id; only an exact echo at the documented model field is technical. */
    val configuredModel: String,
    /** The configured endpoint is exactly the OpenCode Go Zen gateway route. */
    val goUsageExtensions: Boolean,
)

/** The metadata policy of one validated configuration; never derived from response content. */
fun HostedProviderConfig.responseMetadataPolicy(): HostedResponseMetadataPolicy =
    HostedResponseMetadataPolicy(
        protocol = protocol,
        configuredModel = model,
        goUsageExtensions = HostedUsageContract.isGoAccountingRoute(protocol, endpoint),
    )

/** The provider-specific usage counters and root metadata documented by each adapter. */
internal object HostedUsageContract {
    private enum class Metric {
        INPUT,
        OUTPUT,
        REASONING,
        CACHED_INPUT,
        UNMAPPED,
    }

    private data class Counter(
        val protocol: HostedProtocol,
        val path: List<String>,
        val metric: Metric,
    )

    private val counters = listOf(
        Counter(HostedProtocol.GEMINI_GENERATE_CONTENT, listOf("usageMetadata", "promptTokenCount"), Metric.INPUT),
        Counter(HostedProtocol.GEMINI_GENERATE_CONTENT, listOf("usageMetadata", "candidatesTokenCount"), Metric.OUTPUT),
        Counter(HostedProtocol.GEMINI_GENERATE_CONTENT, listOf("usageMetadata", "thoughtsTokenCount"), Metric.REASONING),
        Counter(HostedProtocol.GEMINI_GENERATE_CONTENT, listOf("usageMetadata", "cachedContentTokenCount"), Metric.CACHED_INPUT),
        Counter(HostedProtocol.OPENAI_CHAT_COMPLETIONS, listOf("usage", "prompt_tokens"), Metric.INPUT),
        Counter(HostedProtocol.OPENAI_CHAT_COMPLETIONS, listOf("usage", "completion_tokens"), Metric.OUTPUT),
        Counter(HostedProtocol.OPENAI_CHAT_COMPLETIONS, listOf("usage", "total_tokens"), Metric.UNMAPPED),
        Counter(HostedProtocol.OPENAI_CHAT_COMPLETIONS, listOf("usage", "completion_tokens_details", "reasoning_tokens"), Metric.REASONING),
        Counter(HostedProtocol.OPENAI_CHAT_COMPLETIONS, listOf("usage", "prompt_tokens_details", "cached_tokens"), Metric.CACHED_INPUT),
        Counter(HostedProtocol.OPENAI_RESPONSES, listOf("usage", "input_tokens"), Metric.INPUT),
        Counter(HostedProtocol.OPENAI_RESPONSES, listOf("usage", "output_tokens"), Metric.OUTPUT),
        Counter(HostedProtocol.OPENAI_RESPONSES, listOf("usage", "total_tokens"), Metric.UNMAPPED),
        Counter(HostedProtocol.OPENAI_RESPONSES, listOf("usage", "output_tokens_details", "reasoning_tokens"), Metric.REASONING),
        Counter(HostedProtocol.OPENAI_RESPONSES, listOf("usage", "input_tokens_details", "cached_tokens"), Metric.CACHED_INPUT),
    )

    private val objectPaths = listOf(
        HostedProtocol.GEMINI_GENERATE_CONTENT to listOf("usageMetadata"),
        HostedProtocol.OPENAI_CHAT_COMPLETIONS to listOf("usage"),
        HostedProtocol.OPENAI_CHAT_COMPLETIONS to listOf("usage", "completion_tokens_details"),
        HostedProtocol.OPENAI_CHAT_COMPLETIONS to listOf("usage", "prompt_tokens_details"),
        HostedProtocol.OPENAI_RESPONSES to listOf("usage"),
        HostedProtocol.OPENAI_RESPONSES to listOf("usage", "output_tokens_details"),
        HostedProtocol.OPENAI_RESPONSES to listOf("usage", "input_tokens_details"),
    )

    /** A path is recognized only when it exactly matches the selected provider protocol. */
    fun isCounterPath(protocol: HostedProtocol, path: List<String>): Boolean =
        counters.any { it.protocol == protocol && it.path == path }

    /** Whether a decoded structural path is beneath a provider usage container. */
    fun isUsageDiagnosticPath(path: List<String>): Boolean =
        path.firstOrNull() == "usage" || path.firstOrNull() == "usageMetadata"

    /** Exact path comparison used only to classify a sanitized cross-protocol diagnostic. */
    fun isCounterPathForOtherProtocol(protocol: HostedProtocol, path: List<String>): Boolean =
        !isCounterPath(protocol, path) && counters.any { it.protocol != protocol && it.path == path }

    /** Known usage containers must keep their protocol-defined object shape. */
    fun isObjectPath(protocol: HostedProtocol, path: List<String>): Boolean =
        objectPaths.any { (pathProtocol, objectPath) -> pathProtocol == protocol && objectPath == path }

    /**
     * The documented root fields of each protocol response that carry technical metadata rather
     * than content. Everything else at the root — and every nested `id`/`model`/`usage` field —
     * stays inside the full decoded privacy scan, except the explicitly listed provider-assigned
     * tool-call identifiers below, which are validated by [isOpaqueProviderId] and never treated
     * as content.
     */
    private val rootMetadataFields = mapOf(
        HostedProtocol.OPENAI_CHAT_COMPLETIONS to mapOf(
            "id" to HostedResponseMetadataKind.PROVIDER_ID,
            "object" to HostedResponseMetadataKind.OBJECT_DISCRIMINATOR,
            "created" to HostedResponseMetadataKind.TIMESTAMP,
            "model" to HostedResponseMetadataKind.MODEL_ECHO,
        ),
        HostedProtocol.OPENAI_RESPONSES to mapOf(
            "id" to HostedResponseMetadataKind.PROVIDER_ID,
            "object" to HostedResponseMetadataKind.OBJECT_DISCRIMINATOR,
            "created_at" to HostedResponseMetadataKind.TIMESTAMP,
            "model" to HostedResponseMetadataKind.MODEL_ECHO,
        ),
        HostedProtocol.GEMINI_GENERATE_CONTENT to mapOf(
            "responseId" to HostedResponseMetadataKind.PROVIDER_ID,
            "modelVersion" to HostedResponseMetadataKind.MODEL_ECHO,
        ),
    )

    /** The protocol's own fixed root discriminator; Gemini documents none. */
    private val objectDiscriminators = mapOf(
        HostedProtocol.OPENAI_CHAT_COMPLETIONS to "chat.completion",
        HostedProtocol.OPENAI_RESPONSES to "response",
    )

    /**
     * The two USD accounting extensions the OpenCode Go gateway adds to the OpenAI-style usage
     * object. They are not token counters and are never part of [counters].
     */
    private val goUsageExtensionPaths = listOf(
        listOf("usage", "opencode", "allowance"),
        listOf("usage", "opencode", "list_cost"),
    )

    private const val GO_ACCOUNTING_HOST = "opencode.ai"
    private val goAccountingPaths = mapOf(
        HostedProtocol.OPENAI_CHAT_COMPLETIONS to "/zen/go/v1/chat/completions",
        HostedProtocol.OPENAI_RESPONSES to "/zen/go/v1/responses",
    )

    /** Provider-assigned opaque ids: bounded, nonempty, printable ASCII; no assumed prefix. */
    fun isOpaqueProviderId(value: String): Boolean =
        value.length in 1..MAX_METADATA_STRING_LENGTH && value.all { it in ' '..'~' }

    /** A Unix creation time is a nonnegative integer JSON number, never a fraction or string. */
    fun isTimestampNumber(text: String): Boolean = isNonnegativeIntegerNumber(text)

    /** The fixed discriminator for this protocol, when it documents one. */
    fun objectDiscriminator(protocol: HostedProtocol): String? = objectDiscriminators[protocol]

    /** The metadata rule for a root field of the selected protocol, or null for content fields. */
    fun rootMetadataKind(protocol: HostedProtocol, name: String): HostedResponseMetadataKind? =
        rootMetadataFields[protocol]?.get(name)

    /**
     * The only nested fields whose value is a provider-assigned opaque identifier rather than
     * content: the Chat Completions tool-call id the next tool result must echo, and the
     * Responses function-call item id / call id. Each is documented by its protocol schema.
     *
     * This is a narrow, policy-owned exemption. It is not a general "looks like an identifier"
     * allowance: a mapped sensitive value glued into ordinary content (`10000VND`, `10000k`,
     * `acc12345678`) is still a leak, and a value that exactly equals a mapped surface is still
     * refused even in one of these fields.
     */
    private val nestedProviderIdPaths = mapOf(
        HostedProtocol.OPENAI_CHAT_COMPLETIONS to listOf(
            listOf("choices", "[]", "message", "tool_calls", "[]", "id"),
        ),
        HostedProtocol.OPENAI_RESPONSES to listOf(
            listOf("output", "[]", "id"),
            listOf("output", "[]", "call_id"),
        ),
    )

    /** Whether [path] is a documented nested provider-assigned identifier field. */
    fun isNestedProviderIdPath(protocol: HostedProtocol, path: List<String>): Boolean =
        nestedProviderIdPaths[protocol]?.any { it == path } == true

    /** Exact route match: the configured endpoint is the OpenCode Go Zen gateway for this protocol. */
    fun isGoAccountingRoute(protocol: HostedProtocol, endpoint: String): Boolean {
        val expectedPath = goAccountingPaths[protocol] ?: return false
        val uri = try {
            URI(endpoint)
        } catch (_: Exception) {
            return false
        }
        return uri.scheme.equals("https", true) &&
            GO_ACCOUNTING_HOST.equals(uri.host, true) &&
            uri.path == expectedPath &&
            uri.query == null
    }

    /** Exact path match for the Go accounting extension of the OpenAI-style usage object. */
    fun isGoUsageExtensionPath(protocol: HostedProtocol, path: List<String>): Boolean {
        if (protocol == HostedProtocol.GEMINI_GENERATE_CONTENT) return false
        return goUsageExtensionPaths.any { it == path }
    }

    /**
     * A JSON number lexeme that represents a finite, nonnegative amount. Strings, booleans and
     * non-finite/negative numbers are protocol errors at an accounting path, never coerced.
     */
    fun isNonnegativeJsonNumber(text: String): Boolean {
        if (text.isEmpty() || text.any { it.isDigit().not() && it !in ".eE+-" }) return false
        if (text.none { it.isDigit() }) return false
        val value = text.toDoubleOrNull() ?: return false
        return value.isFinite() && value >= 0.0
    }

    private const val MAX_METADATA_STRING_LENGTH = 200

    /**
     * Parses only the fields in [counters]. Present counters must be JSON numbers represented as
     * nonnegative base-10 integers within the adapter's `Int` accounting range; strings, nulls,
     * fractions, exponents, negatives and overflow are protocol errors, never coerced.
     */
    fun parse(protocol: HostedProtocol, root: JSONObject): HostedUsage? {
        val usageRoot = when (protocol) {
            HostedProtocol.GEMINI_GENERATE_CONTENT -> "usageMetadata"
            HostedProtocol.OPENAI_CHAT_COMPLETIONS, HostedProtocol.OPENAI_RESPONSES -> "usage"
        }
        if (!root.has(usageRoot) || root.isNull(usageRoot)) return null
        if (root.opt(usageRoot) !is JSONObject) throw InvalidProviderUsageException

        var inputTokens: Int? = null
        var outputTokens: Int? = null
        var reasoningTokens: Int? = null
        var cachedInputTokens: Int? = null
        for (counter in counters) {
            if (counter.protocol != protocol) continue
            val value = valueAt(root, counter.path) ?: continue
            val parsed = parseNonnegativeInt(value) ?: throw InvalidProviderUsageException
            when (counter.metric) {
                Metric.INPUT -> inputTokens = parsed
                Metric.OUTPUT -> outputTokens = parsed
                Metric.REASONING -> reasoningTokens = parsed
                Metric.CACHED_INPUT -> cachedInputTokens = parsed
                Metric.UNMAPPED -> Unit
            }
        }
        for ((pathProtocol, objectPath) in objectPaths) {
            if (pathProtocol != protocol || objectPath.size == 1) continue
            val value = valueAt(root, objectPath) ?: continue
            if (value != JSONObject.NULL && value !is JSONObject) throw InvalidProviderUsageException
        }
        return HostedUsage(inputTokens, outputTokens, reasoningTokens, cachedInputTokens)
    }

    private fun valueAt(root: JSONObject, path: List<String>): Any? {
        var value: Any? = root
        for (index in path.indices) {
            val objectValue = value as? JSONObject ?: return null
            val field = path[index]
            if (!objectValue.has(field)) return null
            value = objectValue.opt(field)
            if (index != path.lastIndex && value == JSONObject.NULL) return null
        }
        return value
    }

    private fun parseNonnegativeInt(value: Any): Int? =
        (value as? Number)?.let { parseCounterNumber(it.toString()) }

    /** Checks an integer JSON number lexeme independently from the adapter's narrower Int storage. */
    fun isNonnegativeIntegerNumber(text: String): Boolean =
        text.isNotEmpty() &&
            text.all { it in '0'..'9' } &&
            (text == "0" || text[0] != '0')

    fun parseCounterNumber(text: String): Int? {
        if (!isNonnegativeIntegerNumber(text)) return null
        val parsed = text.toLongOrNull() ?: return null
        if (parsed > Int.MAX_VALUE) return null
        return parsed.toInt()
    }
}

/** Safe failure marker; provider values and parser exception text are intentionally absent. */
internal data object InvalidProviderUsageException : RuntimeException()
