package io.github.triet4p.aegisf.provider

import org.json.JSONObject

/**
 * Support shared by the three adapters: assembling native calls into executable or explicitly
 * non-executable values, pairing tool results with the calls they answer, and classifying HTTP
 * and payload failures.
 */
internal data class RawToolCall(
    val callId: String,
    val name: String,
    /** The wire argument text when the protocol carries one; `null` when it was absent. */
    val argumentsJson: String?,
    /** The provider's own id when it supplied one, echoed only where the protocol requires it. */
    val wireId: String? = null,
    /**
     * A provider-declared truncation of this one call (Responses item status `incomplete`);
     * it is never executable regardless of its arguments.
     */
    val forcedDisposition: ToolCallDisposition? = null,
)

internal object ToolCallAssembly {
    /**
     * Assembles every call of one turn.
     *
     * A turn-level [forcedDisposition] (truncation or a provider filter) wins over the call's own
     * shape: nothing from such a turn is executable. Otherwise a call is ready only with a
     * declared tool name and a complete argument object that satisfies that declaration.
     */
    fun assemble(
        calls: List<RawToolCall>,
        tools: List<HostedToolDeclaration>,
        forcedDisposition: ToolCallDisposition? = null,
    ): List<HostedToolCall> {
        val declarations = tools.associateBy { it.name }
        return calls.map { call ->
            val forced = forcedDisposition ?: call.forcedDisposition
            if (forced != null) {
                return@map HostedToolCall(
                    callId = call.callId,
                    name = call.name,
                    argumentsJson = call.argumentsJson,
                    arguments = null,
                    disposition = forced,
                    detail = when (forced) {
                        ToolCallDisposition.TRUNCATED_TURN -> "turn output was truncated"
                        ToolCallDisposition.FILTERED_TURN -> "turn was refused by the provider"
                        else -> null
                    },
                )
            }
            val declaration = declarations[call.name]
            if (declaration == null) {
                return@map HostedToolCall(
                    callId = call.callId,
                    name = call.name,
                    argumentsJson = call.argumentsJson,
                    arguments = null,
                    disposition = ToolCallDisposition.UNKNOWN_TOOL,
                    detail = "tool is not declared for this turn",
                )
            }
            val arguments = ProviderJson.strictObject(call.argumentsJson)
            if (arguments == null) {
                return@map HostedToolCall(
                    callId = call.callId,
                    name = call.name,
                    argumentsJson = call.argumentsJson,
                    arguments = null,
                    disposition = ToolCallDisposition.MALFORMED_ARGUMENTS,
                    detail = "arguments are absent, partial, or not a complete JSON object",
                )
            }
            when (val assessment = ProviderJson.validateArguments(declaration.parameters, arguments)) {
                is ToolArgumentAssessment.Complete -> HostedToolCall(
                    callId = call.callId,
                    name = call.name,
                    argumentsJson = call.argumentsJson,
                    arguments = assessment.arguments,
                    disposition = ToolCallDisposition.READY,
                )

                is ToolArgumentAssessment.Invalid -> HostedToolCall(
                    callId = call.callId,
                    name = call.name,
                    argumentsJson = call.argumentsJson,
                    arguments = null,
                    disposition = ToolCallDisposition.SCHEMA_INVALID,
                    detail = assessment.reason,
                )
            }
        }
    }

    /** The repeated call ids in one turn, if any; duplicates cannot be answered unambiguously. */
    fun duplicateCallIds(calls: List<RawToolCall>): List<String> {
        val seen = mutableSetOf<String>()
        val duplicates = linkedSetOf<String>()
        for (call in calls) {
            if (!seen.add(call.callId)) duplicates.add(call.callId)
        }
        return duplicates.toList()
    }

    /** Pairs results with pending calls, refusing a missing, extra, or repeated answer. */
    fun answerEveryPendingCall(
        pendingCallIds: List<String>,
        results: List<HostedToolResult>,
    ): Map<String, HostedToolResult> {
        val expected = pendingCallIds
        require(expected.size == expected.toSet().size) { "pending calls must have unique ids" }
        require(results.size == expected.size) {
            "every pending call must be answered exactly once (expected ${expected.size}, got ${results.size})"
        }
        val byId = results.associateBy { it.callId }
        require(byId.size == results.size) { "tool results repeat a call id" }
        require(byId.keys == expected.toSet()) {
            "tool results must answer exactly the pending call ids"
        }
        return byId
    }
}

internal object ProviderFailures {
    private const val MAX_DETAIL_LENGTH = 400

    /** Classifies a non-2xx answer without retaining the credential or unbounded text. */
    fun classify(
        status: Int,
        body: String,
        headers: Map<String, String>,
        authentication: HostedAuthentication,
    ): HostedProviderFailure {
        val detail = detail(body, authentication)
        return when {
            status == 401 || status == 403 ->
                HostedProviderFailure.Authentication(status, detail)

            status == 402 -> HostedProviderFailure.Account(status, detail)
            status == 429 -> HostedProviderFailure.RateLimited(
                status = status,
                retryAfterSeconds = headers["retry-after"]?.trim()?.toLongOrNull(),
                detail = detail,
            )

            status in 300..399 -> HostedProviderFailure.Redirected(status, detail)
            status in 500..599 -> HostedProviderFailure.Unavailable(status, detail)
            else -> HostedProviderFailure.Rejected(status, detail)
        }
    }

    /**
     * A bound, credential-free diagnostic message. Provider messages sometimes echo a request
     * field or a prompt; the adapter keeps only a short excerpt and never the credential.
     */
    fun detail(body: String, authentication: HostedAuthentication): String? {
        val root = ProviderJson.strictObject(body)
        val errorObject = root?.optJSONObject("error")
        val message = errorObject?.optString("message")?.takeIf { it.isNotBlank() }
            ?: root?.optString("message")?.takeIf { it.isNotBlank() }
            ?: (root?.opt("error") as? String)?.takeIf { it.isNotBlank() }
            ?: root?.optString("detail")?.takeIf { it.isNotBlank() }
            ?: body.takeIf { it.isNotBlank() }
            ?: return null
        val sanitized = authentication.redact(message)
            .replace('\r', ' ')
            .replace('\n', ' ')
            .filter { it.code >= 0x20 || it == '\t' }
            .trim()
        if (sanitized.isEmpty()) return null
        return if (sanitized.length <= MAX_DETAIL_LENGTH) sanitized else sanitized.take(MAX_DETAIL_LENGTH) + "…"
    }

    /**
     * The provider must echo the configured model id. A missing echo cannot establish identity;
     * a different echo is the hidden substitution this contract forbids.
     */
    fun identityProblem(
        configuredModel: String,
        reportedModel: String?,
    ): HostedProviderFailure? = when {
        reportedModel.isNullOrBlank() ->
            HostedProviderFailure.InvalidResponse("the endpoint did not report the served model")

        reportedModel != configuredModel ->
            HostedProviderFailure.ModelMismatch(configuredModel, reportedModel)

        else -> null
    }
}

/** The exact headers every adapter sends: framing, the one credential, then declared extras. */
internal fun HostedProviderConfig.requestHeaders(): Map<String, String> = buildMap {
    put("Content-Type", "application/json")
    put("Accept", "application/json")
    put(authentication.headerName, authentication.headerValue())
    putAll(clientHeaders)
}

/** Deep-copies a parsed wire object so a retained continuation cannot be mutated by a caller. */
internal fun JSONObject.deepCopy(): JSONObject = JSONObject(toString())
