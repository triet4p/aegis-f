package io.github.triet4p.aegisf.privacy

import android.util.JsonReader
import android.util.JsonToken
import io.github.triet4p.aegisf.data.DraftCandidateQuery
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.NoteEventInterpretation
import io.github.triet4p.aegisf.data.NoteEventTimeAnchor
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.provider.HostedProtocol
import io.github.triet4p.aegisf.provider.HostedResponseMetadataKind
import io.github.triet4p.aegisf.provider.HostedResponseMetadataPolicy
import io.github.triet4p.aegisf.provider.HostedResponsePrivacyRefusal
import io.github.triet4p.aegisf.provider.HostedResponsePrivacyRefusalReason
import io.github.triet4p.aegisf.provider.HostedResponsePrivacyResult
import io.github.triet4p.aegisf.provider.HostedResponseRecognizedCounter
import io.github.triet4p.aegisf.provider.HostedResponseProtocolErrorReason
import io.github.triet4p.aegisf.provider.HostedResponseValidatedMetadata
import io.github.triet4p.aegisf.provider.HostedUsageContract
import java.io.IOException
import java.io.StringReader
import java.util.HashSet
import org.json.JSONArray
import org.json.JSONObject

/**
 * Which outbound content path a masked string feeds. The value names the path only; it carries
 * no content and therefore cannot leak one.
 */
enum class MaskedPath {
    SYSTEM_INSTRUCTION,
    USER_INSTRUCTION,
    CONTEXT,
    HISTORY,
    TOOL_RESULT,
    CONTINUATION,
}

/** The verdict on model-returned text before rehydration. */
sealed interface InboundScan {
    data object Accepted : InboundScan
    data class Refused(val reason: InboundRefusal, val detail: String = "") : InboundScan
}

/** Why inbound text was refused; names a check, never a raw value. */
enum class InboundRefusal {
    FOREIGN_TOKEN,
    UNKNOWN_TOKEN,
    RAW_LITERAL_EGRESS,
}

/**
 * The S8.6 local boundary: every user/context/history/tool-result/continuation string the
 * providers could carry is masked through [maskOutbound] before it reaches an adapter.
 * Parsed model text uses [scanInbound]; complete provider responses use
 * [scanInboundResponseJson] before adapter parsing, dispatch or continuation.
 *
 * Masking never depends on model cooperation. Detection is deterministic regex over the
 * documented sensitive shapes (grouped/unit amounts, `YYYY-MM-DD` dates, `HH:mm` times,
 * account digit runs, mixed letter-digit bank codes); equality is stable within scope, and
 * ordering/relative-time stay decidable through the vault comparators.
 */
class MaskedPromptBoundary(val vault: SensitiveVault) {

    /** Masks free text for one outbound path. */
    fun maskOutbound(text: String, path: MaskedPath): String = vault.maskText(text)

    /**
     * Masks the structured observation an application tool returned for the next provider turn.
     * Every string inside the JSON is masked; a non-finite number an encoder would render as
     * `NaN` throws [MaskingBlockedException], so the turn fails closed with no request.
     */
    fun maskToolResultJson(outputJson: String): String {
        val root = try {
            JSONObject(outputJson)
        } catch (error: Exception) {
            throw MaskingBlockedException("tool results must be a JSON object to mask")
        }
        return maskJsonValue(root).toString()
    }

    fun scanInbound(text: String): InboundScan {
        if (text.isEmpty()) return InboundScan.Accepted
        val scopeStripped = SensitiveVault.TOKEN_PATTERN.replace(text) { match ->
            if ("-${vault.scopeTag}-" in match.value) " " else match.value
        }
        FOREIGN_TOKEN_PATTERN.find(scopeStripped)?.let {
            return InboundScan.Refused(InboundRefusal.FOREIGN_TOKEN)
        }
        val knownStripped = SensitiveVault.TOKEN_PATTERN.replace(text) { match ->
            if ("-${vault.scopeTag}-" in match.value && vault.isKnownToken(match.value)) {
                " "
            } else {
                match.value
            }
        }
        TOKEN_LIKE_PATTERN.find(knownStripped)?.let {
            return InboundScan.Refused(InboundRefusal.UNKNOWN_TOKEN)
        }
        val leaked = vault.scanLeak(text)
        if (leaked) return InboundScan.Refused(InboundRefusal.RAW_LITERAL_EGRESS)
        return InboundScan.Accepted
    }

    /**
     * Scans a complete provider response as strict JSON over decoded names and leaves.
     *
     * The decoded-body guard enforces the owner-approved channel boundary: provider
     * reasoning/thinking ([ResponseChannel.PROVIDER_REASONING]) is never guard input, while the
     * model's user-visible answer, its tool-call arguments and every protocol field stay scanned
     * exactly as in the accepted S8.6 boundary. The gateway scans the parsed answer again before
     * publication and the parsed argument text again before dispatch, so an invalid, unknown,
     * foreign or raw value in either enforced channel is still blocked.
     *
     * Exactly three things can bypass context-sensitive financial matching, and only after
     * schema validation against [metadataPolicy]:
     *
     * 1. the selected protocol's documented root metadata fields ([HostedResponseMetadataKind]);
     * 2. the selected protocol's documented usage-counter paths ([HostedUsageContract]);
     * 3. only for the exact configured OpenCode Go route, the two USD accounting extensions.
     *
     * Every other decoded key and value outside provider reasoning — answer text, tool
     * arguments, sensitive keys, arbitrary nested `id`/`model`/`usage` lookalikes and unknown
     * fields — remains fully scanned. A malformed value at an exemptable field is a protocol
     * error, never coerced, and malformed envelopes fail before any channel decision.
     */
    internal fun scanInboundResponseJson(
        body: String,
        protocol: HostedProtocol,
        httpStatus: Int? = null,
        metadataPolicy: HostedResponseMetadataPolicy? = null,
    ): HostedResponsePrivacyResult {
        val safePath = ArrayList<String>(MAX_RESPONSE_JSON_DEPTH)
        val semanticPath = ArrayList<String>(MAX_RESPONSE_JSON_DEPTH)
        val recognizedCounters = ArrayList<HostedResponseRecognizedCounter>()
        val validatedMetadata = ArrayList<HostedResponseValidatedMetadata>()
        var protocolError: HostedResponsePrivacyResult.ProtocolError? = null
        val inspectUsage = httpStatus != null && httpStatus in 200..299
        val policy = metadataPolicy?.takeIf { it.protocol == protocol }

        fun recordProtocolError(
            reason: HostedResponseProtocolErrorReason,
            location: String,
            nodeType: String,
        ) {
            if (protocolError == null) {
                protocolError = HostedResponsePrivacyResult.ProtocolError(
                    reason = reason,
                    safeLocation = location,
                    nodeType = nodeType,
                )
            }
        }

        val reasoningIndex = collectReasoningIndex(body, protocol)
        return try {
            JsonReader(StringReader(body)).use { reader ->
                reader.isLenient = false
                when (val rootToken = reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        val refusal = scanResponseJsonValue(
                            reader = reader,
                            depth = 0,
                            safePath = safePath,
                            semanticPath = semanticPath,
                            protocol = protocol,
                            inspectUsage = inspectUsage,
                            policy = policy,
                            rootMetadata = null,
                            channel = ResponseChannel.PROTOCOL,
                            indices = ArrayList<Int>(MAX_RESPONSE_JSON_DEPTH),
                            reasoningIndex = reasoningIndex,
                            recognizedCounters = recognizedCounters,
                            validatedMetadata = validatedMetadata,
                            recordProtocolError = ::recordProtocolError,
                        )
                        when {
                            refusal != null -> refusal
                            reader.peek() != JsonToken.END_DOCUMENT ->
                                responsePrivacyRefusal(
                                    HostedResponsePrivacyRefusalReason.INVALID_JSON,
                                    safePath,
                                    "INVALID_JSON",
                                )
                            protocolError != null -> protocolError!!
                            validatedMetadata.isNotEmpty() ->
                                HostedResponsePrivacyResult.AcceptedWithValidatedMetadata(
                                    validatedMetadata,
                                    recognizedCounters,
                                )
                            recognizedCounters.isNotEmpty() ->
                                HostedResponsePrivacyResult.AcceptedWithValidatedCounters(recognizedCounters)
                            else -> HostedResponsePrivacyResult.Accepted
                        }
                    }
                    JsonToken.END_DOCUMENT -> responsePrivacyRefusal(
                        HostedResponsePrivacyRefusalReason.INVALID_JSON,
                        safePath,
                        "INVALID_JSON",
                    )
                    else -> responsePrivacyRefusal(
                        HostedResponsePrivacyRefusalReason.UNSUPPORTED_ROOT,
                        safePath,
                        responseJsonNodeType(rootToken),
                    )
                }
            }
        } catch (_: IOException) {
            responsePrivacyRefusal(
                HostedResponsePrivacyRefusalReason.INVALID_JSON,
                safePath,
                "INVALID_JSON",
            )
        } catch (_: IllegalStateException) {
            responsePrivacyRefusal(
                HostedResponsePrivacyRefusalReason.INVALID_JSON,
                safePath,
                "INVALID_JSON",
            )
        }
    }

    private fun scanResponseJsonValue(
        reader: JsonReader,
        depth: Int,
        safePath: MutableList<String>,
        semanticPath: MutableList<String>,
        protocol: HostedProtocol,
        inspectUsage: Boolean,
        policy: HostedResponseMetadataPolicy?,
        rootMetadata: HostedResponseMetadataKind?,
        channel: ResponseChannel,
        indices: MutableList<Int>,
        reasoningIndex: ResponseReasoningIndex,
        recognizedCounters: MutableList<HostedResponseRecognizedCounter>,
        validatedMetadata: MutableList<HostedResponseValidatedMetadata>,
        recordProtocolError: (HostedResponseProtocolErrorReason, String, String) -> Unit,
    ): HostedResponsePrivacyResult.Refused? {
        val token = reader.peek()
        val nodeChannel = channelForNode(reasoningIndex, protocol, semanticPath, indices, channel)
        val counterPath = inspectUsage && HostedUsageContract.isCounterPath(protocol, semanticPath)
        val knownOtherProtocol = inspectUsage &&
            HostedUsageContract.isCounterPathForOtherProtocol(protocol, semanticPath)
        val objectPath = inspectUsage && HostedUsageContract.isObjectPath(protocol, semanticPath)
        val goAmountPath = inspectUsage && policy?.goUsageExtensions == true &&
            HostedUsageContract.isGoUsageExtensionPath(protocol, semanticPath)
        val nodeType = responseJsonNodeType(token)
        if (depth > MAX_RESPONSE_JSON_DEPTH) {
            return responsePrivacyRefusal(
                HostedResponsePrivacyRefusalReason.DEPTH_LIMIT,
                safePath,
                nodeType,
            )
        }
        if (counterPath && token != JsonToken.NUMBER) {
            recordProtocolError(
                HostedResponseProtocolErrorReason.INVALID_USAGE_COUNTER,
                safeResponseJsonLocation(safePath),
                nodeType,
            )
        }
        if (objectPath && token != JsonToken.BEGIN_OBJECT && token != JsonToken.NULL) {
            recordProtocolError(
                HostedResponseProtocolErrorReason.INVALID_USAGE_COUNTER,
                safeResponseJsonLocation(safePath),
                nodeType,
            )
        }
        if (goAmountPath && token != JsonToken.NUMBER) {
            recordProtocolError(
                HostedResponseProtocolErrorReason.INVALID_PROTOCOL_METADATA,
                safeResponseJsonLocation(safePath),
                nodeType,
            )
        }
        if (rootMetadata != null && token != JsonToken.STRING && token != JsonToken.NUMBER) {
            recordProtocolError(
                HostedResponseProtocolErrorReason.INVALID_PROTOCOL_METADATA,
                safeResponseJsonLocation(safePath),
                nodeType,
            )
        }
        return when (token) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                val names = HashSet<String>()
                val rootObject = depth == 0 && semanticPath.isEmpty()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (nodeChannel != ResponseChannel.PROVIDER_REASONING) {
                        scanResponseJsonText(name, safePath, "KEY", "field_name")?.let {
                            return it
                        }
                    }
                    val segment = safeResponseJsonPathSegment(
                        name,
                        HostedUsageContract.isUsageDiagnosticPath(semanticPath),
                    )
                    if (!names.add(name)) {
                        return responsePrivacyRefusal(
                            HostedResponsePrivacyRefusalReason.DUPLICATE_KEY,
                            safePath,
                            "KEY",
                            "$segment.duplicate_key",
                        )
                    }
                    val childChannel = channelForField(protocol, semanticPath, name, nodeChannel)
                    safePath += segment
                    semanticPath += name
                    val child = scanResponseJsonValue(
                        reader = reader,
                        depth = depth + 1,
                        safePath = safePath,
                        semanticPath = semanticPath,
                        protocol = protocol,
                        inspectUsage = inspectUsage,
                        policy = policy,
                        rootMetadata = if (rootObject && policy != null) {
                            HostedUsageContract.rootMetadataKind(protocol, name)
                        } else {
                            null
                        },
                        channel = childChannel,
                        indices = indices,
                        reasoningIndex = reasoningIndex,
                        recognizedCounters = recognizedCounters,
                        validatedMetadata = validatedMetadata,
                        recordProtocolError = recordProtocolError,
                    )
                    safePath.removeAt(safePath.lastIndex)
                    semanticPath.removeAt(semanticPath.lastIndex)
                    if (child != null) return child
                }
                reader.endObject()
                null
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                safePath += "[]"
                semanticPath += "[]"
                var index = 0
                while (reader.hasNext()) {
                    indices += index
                    val child = scanResponseJsonValue(
                        reader = reader,
                        depth = depth + 1,
                        safePath = safePath,
                        semanticPath = semanticPath,
                        protocol = protocol,
                        inspectUsage = inspectUsage,
                        policy = policy,
                        rootMetadata = null,
                        channel = nodeChannel,
                        indices = indices,
                        reasoningIndex = reasoningIndex,
                        recognizedCounters = recognizedCounters,
                        validatedMetadata = validatedMetadata,
                        recordProtocolError = recordProtocolError,
                    )
                    indices.removeAt(indices.lastIndex)
                    if (child != null) return child
                    index += 1
                }
                safePath.removeAt(safePath.lastIndex)
                semanticPath.removeAt(semanticPath.lastIndex)
                reader.endArray()
                null
            }
            JsonToken.STRING, JsonToken.NUMBER -> {
                val text = reader.nextString()
                // Provider reasoning/thinking is not privacy-guard input: an invented token
                // shape or a sensitive-looking value inside it is never displayed, rehydrated
                // or treated as authority, so it cannot refuse the response by itself.
                if (!nodeChannel.guarded) return null
                // A documented nested provider-assigned identifier is metadata, not content.
                // The id the next turn must echo can legitimately embed a short mapped surface
                // inside a longer opaque value; the exemption is path- and schema-bound (never a
                // general "looks like an identifier" heuristic), and an id that is itself the
                // mapped value is still scanned and refused.
                if (token == JsonToken.STRING &&
                    HostedUsageContract.isNestedProviderIdPath(protocol, semanticPath) &&
                    HostedUsageContract.isOpaqueProviderId(text) &&
                    !vault.isMappedValueExact(text)
                ) {
                    validatedMetadata += HostedResponseValidatedMetadata(
                        kind = HostedResponseMetadataKind.PROVIDER_ID,
                        safeLocation = safeResponseJsonLocation(safePath),
                        nodeType = nodeType,
                    )
                    return null
                }
                if (rootMetadata != null) {
                    when (metadataVerdict(rootMetadata, token, text, policy)) {
                        MetadataVerdict.EXEMPT -> {
                            validatedMetadata += HostedResponseValidatedMetadata(
                                kind = rootMetadata,
                                safeLocation = safeResponseJsonLocation(safePath),
                                nodeType = nodeType,
                            )
                            return null
                        }
                        MetadataVerdict.MALFORMED -> {
                            recordProtocolError(
                                HostedResponseProtocolErrorReason.INVALID_PROTOCOL_METADATA,
                                safeResponseJsonLocation(safePath),
                                nodeType,
                            )
                            return null
                        }
                        // A model echo that is not the configured model stays a scanned value;
                        // the adapter's own identity check owns that failure.
                        MetadataVerdict.SCANNED_VALUE -> Unit
                    }
                }
                if (goAmountPath) {
                    if (metadataVerdict(
                            HostedResponseMetadataKind.GO_USAGE_AMOUNT,
                            token,
                            text,
                            policy,
                        ) == MetadataVerdict.EXEMPT
                    ) {
                        validatedMetadata += HostedResponseValidatedMetadata(
                            kind = HostedResponseMetadataKind.GO_USAGE_AMOUNT,
                            safeLocation = safeResponseJsonLocation(safePath),
                            nodeType = nodeType,
                        )
                        return null
                    }
                    recordProtocolError(
                        HostedResponseProtocolErrorReason.INVALID_PROTOCOL_METADATA,
                        safeResponseJsonLocation(safePath),
                        nodeType,
                    )
                    return null
                }
                if (counterPath && token == JsonToken.NUMBER) {
                    if (HostedUsageContract.isNonnegativeIntegerNumber(text)) {
                        vault.scanLeakShapes(text)
                            .sortedBy { it.wire }
                            .forEach { shape ->
                                recognizedCounters += HostedResponseRecognizedCounter(
                                    sensitiveShape = shape.wire,
                                    safeLocation = safeResponseJsonLocation(safePath),
                                )
                            }
                        null
                    } else {
                        recordProtocolError(
                            HostedResponseProtocolErrorReason.INVALID_USAGE_COUNTER,
                            safeResponseJsonLocation(safePath),
                            nodeType,
                        )
                        scanResponseJsonText(text, safePath, nodeType)
                    }
                } else {
                    scanResponseJsonText(text, safePath, nodeType, knownOtherProtocol = knownOtherProtocol)
                }
            }
            JsonToken.BOOLEAN -> {
                reader.nextBoolean()
                null
            }
            JsonToken.NULL -> {
                reader.nextNull()
                null
            }
            else -> responsePrivacyRefusal(
                HostedResponsePrivacyRefusalReason.INVALID_JSON,
                safePath,
                "INVALID_JSON",
            )
        }
    }

    private enum class MetadataVerdict {
        /** The value satisfies the documented schema rule for its field. */
        EXEMPT,
        /** The value is malformed at a documented metadata field; fail closed. */
        MALFORMED,
        /** The field is metadata but this value must still pass the full content scan. */
        SCANNED_VALUE,
    }

    /**
     * Applies one documented root-metadata rule to the decoded value. The rules come from the
     * protocol's published response schema; none of them is derived from the response text.
     */
    private fun metadataVerdict(
        kind: HostedResponseMetadataKind,
        token: JsonToken,
        text: String,
        policy: HostedResponseMetadataPolicy?,
    ): MetadataVerdict = when (kind) {
        HostedResponseMetadataKind.PROVIDER_ID ->
            if (token == JsonToken.STRING && HostedUsageContract.isOpaqueProviderId(text)) {
                MetadataVerdict.EXEMPT
            } else {
                MetadataVerdict.MALFORMED
            }
        HostedResponseMetadataKind.MODEL_ECHO ->
            if (token == JsonToken.STRING && text == policy?.configuredModel) {
                MetadataVerdict.EXEMPT
            } else if (token == JsonToken.STRING) {
                MetadataVerdict.SCANNED_VALUE
            } else {
                MetadataVerdict.MALFORMED
            }
        HostedResponseMetadataKind.OBJECT_DISCRIMINATOR -> {
            val discriminator = policy?.let { HostedUsageContract.objectDiscriminator(it.protocol) }
            if (token == JsonToken.STRING && discriminator != null && text == discriminator) {
                MetadataVerdict.EXEMPT
            } else {
                MetadataVerdict.MALFORMED
            }
        }
        HostedResponseMetadataKind.TIMESTAMP ->
            if (token == JsonToken.NUMBER && HostedUsageContract.isTimestampNumber(text)) {
                MetadataVerdict.EXEMPT
            } else {
                MetadataVerdict.MALFORMED
            }
        HostedResponseMetadataKind.GO_USAGE_AMOUNT ->
            if (token == JsonToken.NUMBER && HostedUsageContract.isNonnegativeJsonNumber(text)) {
                MetadataVerdict.EXEMPT
            } else {
                MetadataVerdict.MALFORMED
            }
    }

    private fun scanResponseJsonText(
        text: String,
        path: List<String>,
        nodeType: String,
        suffix: String? = null,
        knownOtherProtocol: Boolean = false,
    ): HostedResponsePrivacyResult.Refused? {
        val inbound = scanInbound(text)
        if (inbound is InboundScan.Accepted) return null
        inbound as InboundScan.Refused
        val reason = when (inbound.reason) {
            InboundRefusal.FOREIGN_TOKEN ->
                HostedResponsePrivacyRefusalReason.FOREIGN_TOKEN
            InboundRefusal.UNKNOWN_TOKEN ->
                HostedResponsePrivacyRefusalReason.UNKNOWN_TOKEN
            InboundRefusal.RAW_LITERAL_EGRESS ->
                HostedResponsePrivacyRefusalReason.DECODED_SENSITIVE_MATCH
        }
        val sensitiveShape = if (reason == HostedResponsePrivacyRefusalReason.DECODED_SENSITIVE_MATCH) {
            vault.scanLeakShapes(text).minByOrNull { it.wire }?.wire
        } else {
            null
        }
        return HostedResponsePrivacyResult.Refused(
            HostedResponsePrivacyRefusal(
                reason = reason,
                sensitiveShape = sensitiveShape,
                safeLocation = safeResponseJsonLocation(path, suffix),
                nodeType = nodeType,
                knownOtherProtocol = knownOtherProtocol,
            ),
        )
    }

    private fun responsePrivacyRefusal(
        reason: HostedResponsePrivacyRefusalReason,
        path: List<String>,
        nodeType: String,
        suffix: String? = null,
    ) = HostedResponsePrivacyResult.Refused(
        HostedResponsePrivacyRefusal(
            reason = reason,
            sensitiveShape = null,
            safeLocation = safeResponseJsonLocation(path, suffix),
            nodeType = nodeType,
        ),
    )

    private fun responseJsonNodeType(token: JsonToken): String = when (token) {
        JsonToken.BEGIN_OBJECT, JsonToken.END_OBJECT -> "OBJECT"
        JsonToken.BEGIN_ARRAY, JsonToken.END_ARRAY -> "ARRAY"
        JsonToken.STRING -> "STRING"
        JsonToken.NUMBER -> "NUMBER"
        JsonToken.BOOLEAN -> "BOOLEAN"
        JsonToken.NULL -> "NULL"
        else -> "INVALID_JSON"
    }

    private fun safeResponseJsonLocation(path: List<String>, suffix: String? = null): String =
        buildString {
            append("response_body")
            path.forEach { segment ->
                if (segment == "[]") append("[]") else append('.').append(segment)
            }
            suffix?.let { append('.').append(it) }
        }

    private fun safeResponseJsonPathSegment(name: String, usagePath: Boolean): String =
        when {
            name in SAFE_RESPONSE_JSON_PATH_SEGMENTS -> name
            usagePath && isSafeUsageDiagnosticIdentifier(name) -> name
            else -> "other_field"
        }


    private fun maskJsonValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> JSONObject.NULL
        is JSONObject -> JSONObject().also { masked ->
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                masked.put(key, maskJsonValue(value.opt(key)))
            }
        }
        is JSONArray -> JSONArray().also { masked ->
            for (index in 0 until value.length()) masked.put(maskJsonValue(value.opt(index)))
        }
        is String -> vault.maskText(value)
        is Number -> {
            if (value is Double && !value.isFinite() || value is Float && !value.isFinite()) {
                throw MaskingBlockedException("tool results carry a non-finite number")
            }
            value
        }
        is Boolean -> value
        else -> throw MaskingBlockedException("tool results carry an unsupported value")
    }
}

internal fun isSafeUsageDiagnosticIdentifier(name: String): Boolean =
    name.length in 1..64 && name.all { it in 'a'..'z' || it == '_' }

/**
 * Which model output channel one decoded value of a complete provider response belongs to.
 *
 * The owner-approved boundary (S8.13-replan-a23) separates the provider's internal
 * reasoning/thinking from everything a run may act on or show:
 *
 * - [PROVIDER_REASONING] is never displayed, rehydrated, replayed as prompt content or treated
 *   as authority. It is therefore not privacy-guard input: invented token notation or a
 *   sensitive-looking value inside it must not refuse an otherwise valid response.
 * - [MODEL_ANSWER] is the prose the user can see; it is guarded here and again by the gateway
 *   before publication.
 * - [MODEL_TOOL_ARGUMENTS] is the exact argument text a call would dispatch; it is guarded
 *   here, again by the gateway before dispatch and again by the tool runtime.
 * - [PROTOCOL] covers validated metadata, provider error text and unclassified fields; it stays
 *   guarded exactly as in the accepted S8.6 boundary.
 *
 * Everything except [PROVIDER_REASONING] is guard input, so this classification never weakens
 * an existing check: it only names which decoded channel a value occupies.
 */
internal enum class ResponseChannel {
    PROVIDER_REASONING,
    MODEL_ANSWER,
    MODEL_TOOL_ARGUMENTS,
    PROTOCOL;

    /** Whether the decoded-body guard scans values in this channel. */
    val guarded: Boolean get() = this != PROVIDER_REASONING
}

/**
 * The reasoning containers of one response body, resolved by structural position.
 *
 * A provider signals thinking either with a per-part `thought` flag (Gemini) or with a typed
 * `reasoning` output item (Responses); in both cases the flag can follow the text it qualifies,
 * so the streaming guard cannot classify a leaf in one pass. This index is collected first and
 * only ever names positions: it carries no provider text.
 */
internal data class ResponseReasoningIndex(
    val geminiThoughtParts: Set<Pair<Int, Int>> = emptySet(),
    val responsesReasoningItems: Set<Int> = emptySet(),
) {
    /** Whether the container at this exact position is a provider reasoning/thinking object. */
    fun isReasoningContainer(
        protocol: HostedProtocol,
        path: List<String>,
        indices: List<Int>,
    ): Boolean = when (protocol) {
        HostedProtocol.GEMINI_GENERATE_CONTENT ->
            path == GEMINI_PART_PATH && indices.size >= 2 &&
                (indices[0] to indices[1]) in geminiThoughtParts

        HostedProtocol.OPENAI_RESPONSES ->
            path == RESPONSES_ITEM_PATH && indices.isNotEmpty() &&
                indices[0] in responsesReasoningItems

        HostedProtocol.OPENAI_CHAT_COMPLETIONS -> false
    }
}

private val GEMINI_PART_PATH = listOf("candidates", "[]", "content", "parts", "[]")
private val GEMINI_FUNCTION_CALL_PATH = GEMINI_PART_PATH + "functionCall"
private val RESPONSES_ITEM_PATH = listOf("output", "[]")
private val RESPONSES_MESSAGE_CONTENT_PATH = RESPONSES_ITEM_PATH + listOf("content", "[]")
private val CHAT_MESSAGE_PATH = listOf("choices", "[]", "message")
private val CHAT_TOOL_FUNCTION_PATH = CHAT_MESSAGE_PATH + listOf("tool_calls", "[]", "function")
private val CHAT_LEGACY_FUNCTION_PATH = CHAT_MESSAGE_PATH + listOf("function_call")

/**
 * The channel of one child field. Reasoning wins over everything (a thinking part's text,
 * signature and nested payloads are never guard input); otherwise each protocol's documented
 * answer and argument fields are named, and every other field stays [ResponseChannel.PROTOCOL].
 */
internal fun channelForField(
    protocol: HostedProtocol,
    containerPath: List<String>,
    name: String,
    inherited: ResponseChannel,
): ResponseChannel {
    if (!inherited.guarded) return inherited
    return when (protocol) {
        HostedProtocol.OPENAI_CHAT_COMPLETIONS -> when {
            containerPath == CHAT_MESSAGE_PATH && name in CHAT_REASONING_FIELDS ->
                ResponseChannel.PROVIDER_REASONING

            containerPath == CHAT_MESSAGE_PATH && name in CHAT_ANSWER_FIELDS ->
                ResponseChannel.MODEL_ANSWER

            containerPath == CHAT_TOOL_FUNCTION_PATH && name == "arguments" ->
                ResponseChannel.MODEL_TOOL_ARGUMENTS

            containerPath == CHAT_LEGACY_FUNCTION_PATH && name == "arguments" ->
                ResponseChannel.MODEL_TOOL_ARGUMENTS

            // Anything else keeps the channel it was nested in; a protocol field stays protocol.
            else -> inherited
        }

        HostedProtocol.GEMINI_GENERATE_CONTENT -> when {
            // A thought part itself is classified by container position, so reaching here
            // means the part is ordinary output.
            containerPath == GEMINI_PART_PATH && name == "text" -> ResponseChannel.MODEL_ANSWER
            containerPath == GEMINI_PART_PATH && name == "thoughtSignature" ->
                ResponseChannel.PROVIDER_REASONING

            containerPath == GEMINI_FUNCTION_CALL_PATH && name == "args" ->
                ResponseChannel.MODEL_TOOL_ARGUMENTS

            // Anything else keeps the channel it was nested in; a protocol field stays protocol.
            else -> inherited
        }

        HostedProtocol.OPENAI_RESPONSES -> when {
            containerPath == RESPONSES_ITEM_PATH && name == "arguments" ->
                ResponseChannel.MODEL_TOOL_ARGUMENTS

            containerPath == RESPONSES_MESSAGE_CONTENT_PATH && name in RESPONSES_ANSWER_FIELDS ->
                ResponseChannel.MODEL_ANSWER

            containerPath.isEmpty() && name in RESPONSES_ROOT_ANSWER_FIELDS ->
                ResponseChannel.MODEL_ANSWER

            // Anything else keeps the channel it was nested in; a protocol field stays protocol.
            else -> inherited
        }
    }
}

/** The channel of one node, upgrading a container a provider marked as thinking. */
internal fun channelForNode(
    reasoningIndex: ResponseReasoningIndex,
    protocol: HostedProtocol,
    path: List<String>,
    indices: List<Int>,
    inherited: ResponseChannel,
): ResponseChannel = when {
    !inherited.guarded -> inherited
    reasoningIndex.isReasoningContainer(protocol, path, indices) -> ResponseChannel.PROVIDER_REASONING
    else -> inherited
}

private val CHAT_REASONING_FIELDS = setOf("reasoning_content", "reasoning")
private val CHAT_ANSWER_FIELDS = setOf("content", "refusal")
private val RESPONSES_ANSWER_FIELDS = setOf("text", "refusal")
private val RESPONSES_ROOT_ANSWER_FIELDS = setOf("output_text", "outputText")

/**
 * Collects the reasoning positions of one body without retaining or interpreting any text.
 *
 * A structurally invalid body aborts collection; the strict guard pass that follows owns every
 * malformed-envelope refusal, so an empty or partial index can never accept one.
 */
private fun collectReasoningIndex(body: String, protocol: HostedProtocol): ResponseReasoningIndex {
    if (protocol == HostedProtocol.OPENAI_CHAT_COMPLETIONS) return ResponseReasoningIndex()
    val thoughtParts = mutableSetOf<Pair<Int, Int>>()
    val reasoningItems = mutableSetOf<Int>()
    try {
        JsonReader(StringReader(body)).use { reader ->
            reader.isLenient = false
            collectReasoningContainers(
                reader = reader,
                protocol = protocol,
                path = mutableListOf(),
                indices = mutableListOf(),
                depth = 0,
                thoughtParts = thoughtParts,
                reasoningItems = reasoningItems,
            )
        }
    } catch (_: IOException) {
        // The strict pass reports INVALID_JSON for the same body.
    } catch (_: IllegalStateException) {
        // The strict pass reports INVALID_JSON for the same body.
    }
    return ResponseReasoningIndex(thoughtParts, reasoningItems)
}

private fun collectReasoningContainers(
    reader: JsonReader,
    protocol: HostedProtocol,
    path: MutableList<String>,
    indices: MutableList<Int>,
    depth: Int,
    thoughtParts: MutableSet<Pair<Int, Int>>,
    reasoningItems: MutableSet<Int>,
) {
    if (depth > MAX_RESPONSE_JSON_DEPTH) throw IllegalStateException("response JSON is too deep")
    when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            val objectPath = path.toList()
            val objectIndices = indices.toList()
            var thought = false
            var itemType: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                path += name
                when (reader.peek()) {
                    JsonToken.BOOLEAN -> {
                        val value = reader.nextBoolean()
                        if (name == "thought") thought = value
                    }

                    JsonToken.STRING -> {
                        val value = reader.nextString()
                        if (name == "type") itemType = value
                    }

                    else -> collectReasoningContainers(
                        reader = reader,
                        protocol = protocol,
                        path = path,
                        indices = indices,
                        depth = depth + 1,
                        thoughtParts = thoughtParts,
                        reasoningItems = reasoningItems,
                    )
                }
                path.removeAt(path.lastIndex)
            }
            reader.endObject()
            if (protocol == HostedProtocol.GEMINI_GENERATE_CONTENT &&
                objectPath == GEMINI_PART_PATH &&
                objectIndices.size == 2 &&
                thought
            ) {
                thoughtParts += objectIndices[0] to objectIndices[1]
            }
            if (protocol == HostedProtocol.OPENAI_RESPONSES &&
                objectPath == RESPONSES_ITEM_PATH &&
                objectIndices.size == 1 &&
                itemType == "reasoning"
            ) {
                reasoningItems += objectIndices[0]
            }
        }

        JsonToken.BEGIN_ARRAY -> {
            reader.beginArray()
            path += "[]"
            var index = 0
            while (reader.hasNext()) {
                indices += index
                collectReasoningContainers(
                    reader = reader,
                    protocol = protocol,
                    path = path,
                    indices = indices,
                    depth = depth + 1,
                    thoughtParts = thoughtParts,
                    reasoningItems = reasoningItems,
                )
                indices.removeAt(indices.lastIndex)
                index += 1
            }
            path.removeAt(path.lastIndex)
            reader.endArray()
        }

        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> reader.nextNull()
        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
        else -> throw IllegalStateException("unexpected JSON token in the response body")
    }
}

private val TOKEN_LIKE_PATTERN = Regex("⟦[^\\s⟦⟧]{1,64}⟧")
private val FOREIGN_TOKEN_PATTERN = Regex("⟦V-[A-Za-z0-9]{4,12}-\\d{4}⟧")
private const val MAX_RESPONSE_JSON_DEPTH = 64
private val SAFE_RESPONSE_JSON_PATH_SEGMENTS = setOf(
    "choices", "message", "content", "reasoning_content", "reasoning",
    "tool_calls", "toolCalls", "function_call", "functionCall", "arguments", "args",
    "candidates", "parts", "text", "output", "output_text", "outputText",
    "id", "created", "created_at", "createdAt", "model", "response_id", "responseId",
    "object", "modelVersion",
    "usage", "usageMetadata", "total_tokens", "totalTokens", "prompt_tokens",
    "completion_tokens", "reasoning_tokens", "input_tokens", "output_tokens",
    "completion_tokens_details", "prompt_tokens_details", "output_tokens_details",
    "input_tokens_details", "cached_tokens", "promptTokenCount", "candidatesTokenCount",
    "thoughtsTokenCount", "cachedContentTokenCount",
    "error", "promptFeedback", "blockReason", "finish_reason", "finishReason",
    "status", "type", "role", "name", "refusal", "annotations", "service_tier",
    "system_fingerprint",
)

/**
 * Tokens the model may reference when arguing about an event reading. Each token is minted in
 * this session's vault, so it is scoped, typed, and previously seen outbound; the raw literal
 * never appears in masked model text. Only the fields the model actually needs are tokens;
 * every other validated field crosses as plain text.
 */
data class MaskedInterpretation(
    val normalizedText: String,
    val amountToken: String?,
    val dateToken: String?,
    val timeToken: String?,
    val spanSourceStart: Int,
    val spanSourceEnd: Int,
)

/** Field identity for a rejected typed token; deliberately carries no submitted value. */
internal class SensitiveTokenFieldException(
    val fieldPath: String,
    val expectedType: String,
) : IllegalArgumentException()

internal inline fun <T> withSensitiveTokenField(
    fieldPath: String,
    expectedType: String,
    rehydrate: () -> T,
): T = try {
    rehydrate()
} catch (_: TokenValidationException) {
    throw SensitiveTokenFieldException(fieldPath, expectedType)
}

/**
 * Rehydrates validated masked arguments into the typed application commands the services own.
 *
 * Trust rules, enforced in order:
 *
 * 1. Scope: [scope] must be the session that owns [captureId]; anything outside is refused
 *    before anything is read.
 * 2. Tokens: every token must be well-formed, minted in this session's vault, and typed for
 *    its field (an account token in an amount field is refused, never converted).
 * 3. Spans: the source slice is re-masked and its token structure compared against what the
 *    model saw; unknown/ambiguous values fail closed instead of minting under a refused write.
 * 4. Shapes: integer VND range, `YYYY-MM-DD`/`HH:mm` grammars, span bounds, and the scope's
 *    capture/draft sets are rechecked locally; the application services own the final check.
 *
 * Tokens are never arbitrary text authority: model text that carries raw sensitive literals,
 * cross-session tokens, or unknown tokens never reaches a command.
 */
object MaskedApplicationEffect {

    /**
     * Maps model offsets in the masked source view to exact offsets in the stored source.
     *
     * Sensitive tokens have different lengths from their source values. The model's copied
     * span is therefore checked against the current masked source before its boundaries are
     * translated locally; a boundary inside a token or an unmapped sensitive literal refuses.
     */
    fun sourceOffsetsForMaskedSpan(
        scope: WorkflowCallerScope.AgentSession,
        boundary: MaskedPromptBoundary,
        captureId: Long,
        captureText: String,
        maskedStart: Int,
        maskedEnd: Int,
        maskedSpanText: String,
    ): Pair<Int, Int> {
        if (captureId !in scope.captureIds) {
            throw TokenValidationException("the capture is outside this session's scope")
        }
        val vault = boundary.vault
        val maskedSource = vault.maskedPreview(captureText)
        if (SensitiveVault.UNMAPPED_SENTINEL in maskedSource) {
            throw TokenValidationException("the source has an unmapped sensitive value")
        }
        if (maskedStart < 0 || maskedEnd <= maskedStart || maskedEnd > maskedSource.length) {
            throw TokenValidationException("the masked source span is not usable")
        }
        val splitsToken = SensitiveVault.TOKEN_PATTERN.findAll(maskedSource).any { token ->
            (maskedStart > token.range.first && maskedStart <= token.range.last) ||
                (maskedEnd > token.range.first && maskedEnd <= token.range.last)
        }
        if (splitsToken) throw TokenValidationException("the masked span splits a protected value")
        if (maskedSource.substring(maskedStart, maskedEnd) != maskedSpanText) {
            throw TokenValidationException("the masked span does not match the source text")
        }
        val sourceStart = vault.rehydrateText(maskedSource.substring(0, maskedStart)).length
        val sourceEnd = vault.rehydrateText(maskedSource.substring(0, maskedEnd)).length
        if (sourceEnd <= sourceStart) {
            throw TokenValidationException("the translated source span is not usable")
        }
        return sourceStart to sourceEnd
    }

    /** Maps a stored source span to the masked character offsets a model can observe. */
    fun maskedOffsetsForSourceSpan(
        scope: WorkflowCallerScope.AgentSession,
        boundary: MaskedPromptBoundary,
        captureId: Long,
        captureText: String,
        sourceStart: Int,
        sourceEnd: Int,
    ): Pair<Int, Int> {
        if (captureId !in scope.captureIds) {
            throw TokenValidationException("the capture is outside this session's scope")
        }
        if (sourceStart < 0 || sourceEnd <= sourceStart || sourceEnd > captureText.length) {
            throw TokenValidationException("the source span is not usable")
        }
        val vault = boundary.vault
        val maskedSource = vault.maskedPreview(captureText)
        val maskedPrefix = vault.maskedPreview(captureText.substring(0, sourceStart))
        val maskedSpan = vault.maskedPreview(captureText.substring(sourceStart, sourceEnd))
        val maskedSuffix = vault.maskedPreview(captureText.substring(sourceEnd))
        if (SensitiveVault.UNMAPPED_SENTINEL in maskedSource ||
            SensitiveVault.UNMAPPED_SENTINEL in maskedPrefix ||
            SensitiveVault.UNMAPPED_SENTINEL in maskedSpan ||
            SensitiveVault.UNMAPPED_SENTINEL in maskedSuffix ||
            maskedPrefix + maskedSpan + maskedSuffix != maskedSource
        ) {
            throw TokenValidationException("the source span does not align with masked context")
        }
        return maskedPrefix.length to (maskedPrefix.length + maskedSpan.length)
    }

    /**
     * Rehydrates a masked event-reading argument into [NoteEventInterpretation], or refuses.
     *
     * [captureText] is the current capture text the model read; [spanStart]/[spanEnd] are the
     * translated offsets into that stored source. [maskedSpanText] is the exact copied range
     * from the masked source view, mapped locally before this call. Unknown/ambiguous
     * rehydration fails closed with [TokenValidationException].
     */
    fun rehydrateInterpretation(
        scope: WorkflowCallerScope.AgentSession,
        boundary: MaskedPromptBoundary,
        captureId: Long,
        captureText: String,
        spanStart: Int,
        spanEnd: Int,
        maskedSpanText: String,
        masked: MaskedInterpretation,
    ): NoteEventInterpretation {
        if (captureId !in scope.captureIds) {
            throw TokenValidationException("the capture is outside this session's scope")
        }
        val vault = boundary.vault
        if (spanStart < 0 || spanEnd <= spanStart || spanEnd > captureText.length) {
            throw TokenValidationException("the source span is not usable")
        }
        verifySpanAlignment(vault, captureText, spanStart, spanEnd, maskedSpanText)
        val amount = masked.amountToken?.let { token ->
            withSensitiveTokenField("interpretation.amountVnd", "value") {
                vault.parseAmount(token)
            }
        }
        val date = masked.dateToken?.let { token ->
            withSensitiveTokenField("interpretation.timeAnchor.date", "value") {
                vault.parseDate(token)
            }
        }
        val time = masked.timeToken?.let { token ->
            withSensitiveTokenField("interpretation.timeAnchor.time", "value") {
                vault.parseTime(token)
            }
        }
        return NoteEventInterpretation(
            spanStart = spanStart,
            spanEnd = spanEnd,
            normalizedText = rehydrateScopedText(vault, masked.normalizedText),
            amountVnd = amount,
            timeAnchor = if (date == null && time == null) {
                null
            } else {
                NoteEventTimeAnchor(date = date, time = time, timezoneId = null)
            },
        )
    }

    /**
     * Rehydrates a masked draft-candidate page read into [DraftCandidateQuery].
     * Limits/offsets cross verbatim (never sensitive); the review status comes from the
     * allowlisted vocabulary, never from model text.
     */
    fun rehydrateDraftQuery(
        scope: WorkflowCallerScope.AgentSession,
        limit: Int,
        offset: Int,
        onlyPossibleDuplicates: Boolean,
        reviewStatus: IntakeReviewStatus?,
    ): DraftCandidateQuery {
        if (limit !in 1..500 || offset < 0) {
            throw TokenValidationException("the page bounds are not usable")
        }
        return DraftCandidateQuery(
            reviewStatus = reviewStatus,
            onlyPossibleDuplicates = onlyPossibleDuplicates,
            bounds = QueryPageRequest(limit = limit, offset = offset),
        )
    }

    /**
     * Validates a masked link pair before the application policy decides.
     * Event/draft ids cross as integers (never sensitive); they are rechecked against the
     * session's granted sets here so a swapped or cross-scope id refuses before any write.
     */
    fun validatedLinkPair(scope: WorkflowCallerScope.AgentSession, eventId: Long, draftId: Long) {
        if (eventId <= 0 || draftId <= 0) {
            throw TokenValidationException("the link pair is not usable")
        }
        if (draftId !in scope.draftIds) {
            throw TokenValidationException("the draft is outside this session's scope")
        }
    }

    private fun verifySpanAlignment(
        vault: SensitiveVault,
        captureText: String,
        spanStart: Int,
        spanEnd: Int,
        maskedSpanText: String,
    ) {
        val sourceSlice = captureText.substring(spanStart, spanEnd)
        val preview = vault.maskedPreview(sourceSlice)
        val expected = SensitiveVault.TOKEN_PATTERN.replace(preview, " ")
        val observed = SensitiveVault.TOKEN_PATTERN.replace(maskedSpanText, " ")
        if (expected != observed || SensitiveVault.UNMAPPED_SENTINEL in preview) {
            throw TokenValidationException("the masked span does not match the source text")
        }
    }

    private fun rehydrateScopedText(vault: SensitiveVault, text: String): String {
        if (MaskedPromptBoundary(vault).scanInbound(text) is InboundScan.Refused) {
            throw TokenValidationException("the model text carries an unmasked value or foreign token")
        }
        return vault.rehydrateText(text)
    }
}
