package io.github.triet4p.aegisf.agent

import io.github.triet4p.aegisf.data.CanonicalAgentRetryPolicy
import io.github.triet4p.aegisf.provider.HostedProviderFailure

/**
 * The S8.10 bounded classified failure vocabulary of one agent run.
 *
 * Each failure class names what went wrong and whether the run may retry it — never
 * raw content, credentials, or wire detail. Transport loss, rate limits, server
 * failures, stale revisions, repairable schema rejections, and uncertain writes
 * reconcile against durable state before any replay, within the declared attempt and
 * delay bounds; budget, truncation, filtering, masking, scope, user-decision, loop,
 * and malformed failures are terminal and never retried. Cancelling never claims
 * rollback: a stored effect stays stored and stays verified, and the key of a
 * cancelled attempt is freed only for a fresh corrected request.
 */
sealed interface AgentRunFailure {

    /** The run may retry this failure within the bounded retry policy. */
    val retryable: Boolean

    /** The privacy-safe code stored on the durable attempt row. */
    val code: String

    /** The provider request itself never completed; nothing was applied. */
    data class Transport(val detail: String? = null) : AgentRunFailure {
        override val retryable: Boolean = true
        override val code: String = CanonicalAgentRetryPolicy.RETRY_CODE_TRANSPORT
    }

    /** The run timed out waiting; the write behind it may still have applied. */
    data class Timeout(val detail: String? = null) : AgentRunFailure {
        override val retryable: Boolean = true
        override val code: String = CanonicalAgentRetryPolicy.RETRY_CODE_TIMEOUT
    }

    /** The endpoint rate-limited the run; honors the provider's `Retry-After`. */
    data class RateLimited(val retryAfterMillis: Long? = null) : AgentRunFailure {
        override val retryable: Boolean = true
        override val code: String = CanonicalAgentRetryPolicy.RETRY_CODE_RATE_LIMITED
    }

    /** The endpoint failed while serving the run (5xx class). */
    data class Unavailable(val detail: String? = null) : AgentRunFailure {
        override val retryable: Boolean = true
        override val code: String = CanonicalAgentRetryPolicy.RETRY_CODE_UNAVAILABLE
    }

    /** The application state moved on under the run; revalidate revisions first. */
    data class StaleRevision(val detail: String? = null) : AgentRunFailure {
        override val retryable: Boolean = true
        override val code: String = CanonicalAgentRetryPolicy.RETRY_CODE_STALE_REVISION
    }

    /** The model output failed schema validation but is repairable; retry corrected. */
    data class SchemaRepair(val detail: String? = null) : AgentRunFailure {
        override val retryable: Boolean = true
        override val code: String = CanonicalAgentRetryPolicy.RETRY_CODE_SCHEMA_REPAIR
    }

    /**
     * The run was interrupted with the write outcome unknown (process death, timeout
     * between effect and response, late reply). Never replayed blindly: reconcile
     * the journal and the live rows first.
     */
    data class UnknownWrite(val detail: String? = null) : AgentRunFailure {
        override val retryable: Boolean = true
        override val code: String = CanonicalAgentRetryPolicy.RETRY_CODE_UNKNOWN_WRITE
    }

    /** Terminal: the budget, truncation, filter, masking, scope, or user decision. */
    data class Terminal(override val code: String, val detail: String? = null) : AgentRunFailure {
        override val retryable: Boolean = false
    }

    companion object {

        /** Classifies one provider failure into the bounded retry vocabulary. */
        fun ofProvider(failure: HostedProviderFailure): AgentRunFailure = when (failure) {
            is HostedProviderFailure.Transport -> Transport(failure.detail)
            is HostedProviderFailure.RateLimited -> RateLimited(
                failure.retryAfterSeconds?.let { it * 1_000L },
            )
            is HostedProviderFailure.Unavailable -> Unavailable(failure.detail)
            is HostedProviderFailure.ProviderState ->
                if (failure.code.contains("timeout", ignoreCase = true)) {
                    Timeout(failure.detail)
                } else {
                    Unavailable(failure.detail)
                }
            is HostedProviderFailure.Authentication ->
                Terminal("authentication:${failure.status}", failure.detail)
            is HostedProviderFailure.Account ->
                Terminal("account:${failure.status}", failure.detail)
            is HostedProviderFailure.Rejected ->
                Terminal("rejected:${failure.status}", failure.detail)
            is HostedProviderFailure.Redirected ->
                Terminal("redirected:${failure.status}", failure.detail)
            is HostedProviderFailure.ModelMismatch -> Terminal("model_mismatch")
            is HostedProviderFailure.PrivacyBlocked -> Terminal("masking_blocked")
            is HostedProviderFailure.InvalidResponse -> SchemaRepair(failure.detail)
        }

        /** Classifies one executor stop reason into the bounded retry vocabulary. */
        fun ofStop(reason: WorkflowRunStopReason, detail: String?): AgentRunFailure = when (reason) {
            WorkflowRunStopReason.PROVIDER_FAILURE -> when {
                detail == null -> UnknownWrite(null)
                detail.startsWith("transport") -> Transport(detail)
                detail.startsWith("rate_limited") -> RateLimited()
                detail.startsWith("unavailable") -> Unavailable(detail)
                detail.startsWith("rejected") || detail.startsWith("authentication") ||
                    detail.startsWith("account") || detail.startsWith("redirected") ||
                    detail.startsWith("model_mismatch") -> Terminal(detail)
                else -> UnknownWrite(detail)
            }
            WorkflowRunStopReason.TOOL_FAILURE -> UnknownWrite(detail)
            WorkflowRunStopReason.EFFECT_NOT_VERIFIED -> UnknownWrite(detail)
            WorkflowRunStopReason.TRUNCATED_TURN -> Terminal("truncated_turn", detail)
            WorkflowRunStopReason.CONTENT_FILTERED -> Terminal("content_filtered", detail)
            WorkflowRunStopReason.MASKING_BLOCKED -> Terminal("masking_blocked", detail)
            WorkflowRunStopReason.MALFORMED_TURN -> Terminal("malformed_turn", detail)
            WorkflowRunStopReason.TURN_BUDGET -> Terminal("turn_budget", detail)
            WorkflowRunStopReason.TOOL_CALL_BUDGET -> Terminal("tool_budget", detail)
            WorkflowRunStopReason.CONTEXT_BUDGET -> Terminal("context_budget", detail)
            WorkflowRunStopReason.TIME_BUDGET -> Timeout(detail)
            WorkflowRunStopReason.LOOP_DETECTED -> Terminal("loop_detected", detail)
            WorkflowRunStopReason.REQUIRED_EFFECT_MISSING -> Terminal("required_effect_missing", detail)
            WorkflowRunStopReason.EMPTY_ANSWER -> SchemaRepair(detail)
        }

        /** Classifies one terminal run outcome into the bounded retry vocabulary. */
        fun ofOutcome(outcome: WorkflowRunOutcome): AgentRunFailure? = when (outcome) {
            is WorkflowRunOutcome.Completed -> null
            is WorkflowRunOutcome.Clarifying ->
                Terminal(CanonicalAgentRetryPolicy.RETRY_CODE_CLARIFY, outcome.field)
            is WorkflowRunOutcome.Refused -> Terminal(outcome.reason, outcome.answer?.take(80))
            is WorkflowRunOutcome.Incomplete -> ofStop(outcome.reason, outcome.detail)
        }
    }
}
