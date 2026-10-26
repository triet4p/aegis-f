package io.github.triet4p.aegisf.data

import java.security.MessageDigest

/**
 * The canonical S8.10 bounded retry contract.
 *
 * One policy object names every bound the executor and the history enforce together:
 * which stop reasons may be retried, how many attempts a run gets, how long a retry
 * waits, how many runs a session keeps, and how a repeated request is recognized.
 * Nothing here carries conversation text, raw values, mappings, or credentials — the
 * fingerprint is a one-way digest of already-masked material, and the attempt key
 * derives from the run identity and the attempt number alone.
 */
object CanonicalAgentRetryPolicy {

    /** How many attempts one run gets, first try included. */
    const val MAX_ATTEMPTS = 3

    /** How many durable runs one session keeps; older runs prune by age. */
    const val MAX_RUNS_PER_SCOPE = 50

    /** Base delay before a retry may run; multiplied by the attempt number. */
    const val RETRY_BASE_DELAY_MILLIS = 1_000L

    /** Upper bound of one retry delay after the attempt multiplier. */
    const val RETRY_MAX_DELAY_MILLIS = 10_000L

    /** Rate-limit retries wait for the provider's own `Retry-After` within this cap. */
    const val RATE_LIMIT_MAX_WAIT_MILLIS = 30_000L

    /** The classification vocabulary of retryable failures. */
    const val RETRY_CODE_TRANSPORT = "transport"
    const val RETRY_CODE_RATE_LIMITED = "rate_limited"
    const val RETRY_CODE_UNAVAILABLE = "unavailable"
    const val RETRY_CODE_STALE_REVISION = "stale_revision"
    const val RETRY_CODE_SCHEMA_REPAIR = "schema_repair"
    const val RETRY_CODE_UNKNOWN_WRITE = "unknown_write"
    const val RETRY_CODE_TIMEOUT = "timeout"
    const val RETRY_CODE_CLARIFY = "clarify"
    const val RETRY_CODE_UNKNOWN = "unknown"
    const val RETRY_CODE_NONE = "none"

    /**
     * Whether the stop reason of a finished attempt may open a bounded retry.
     *
     * Retryable: transport loss, timeouts, rate limits, server failures, stale
     * revisions, repairable schema rejections, and uncertain writes — every one of
     * them reconciled against durable state before any replay. Terminal: truncation,
     * content filtering, masking blocks, caller-scope refusals, user decisions,
     * loops, and malformed failures — retrying those would repeat a decision the
     * application already made.
     */
    fun isRetryableStop(reason: String): Boolean = when (reason) {
        RETRY_CODE_TRANSPORT,
        RETRY_CODE_RATE_LIMITED,
        RETRY_CODE_UNAVAILABLE,
        RETRY_CODE_STALE_REVISION,
        RETRY_CODE_SCHEMA_REPAIR,
        RETRY_CODE_UNKNOWN_WRITE,
        RETRY_CODE_TIMEOUT,
        RETRY_CODE_CLARIFY,
        RETRY_CODE_UNKNOWN,
        "PROVIDER_FAILURE",
        "TOOL_FAILURE",
        "EFFECT_NOT_VERIFIED",
        -> true
        else -> false
    }

    /** How long to wait before attempt [attempt] (1-based) may run. */
    fun retryDelayMillis(attempt: Int, retryAfterMillis: Long? = null): Long {
        val backoff = (RETRY_BASE_DELAY_MILLIS * attempt).coerceAtMost(RETRY_MAX_DELAY_MILLIS)
        return if (retryAfterMillis != null) {
            retryAfterMillis.coerceAtMost(RATE_LIMIT_MAX_WAIT_MILLIS).coerceAtLeast(backoff)
        } else {
            backoff
        }
    }

    /** One-way fingerprint of a trusted request over already-masked material. */
    fun requestFingerprint(
        scopeId: String,
        intents: List<String>,
        maskedRequest: String,
        limitsFingerprint: String,
        vaultScopeTag: String,
    ): String = sha256Hex(
        listOf(
            scopeId,
            intents.sorted().joinToString(","),
            maskedRequest,
            limitsFingerprint,
            vaultScopeTag,
        ).joinToString("\u001F"),
    )

    /** The stable run identity of one fingerprint in one session. */
    fun runIdentity(scopeId: String, fingerprint: String): String =
        "run-${sha256Hex("$scopeId\u001F$fingerprint").take(32)}"

    /** The trusted operation identity of one attempt of one run. */
    fun attemptKey(runId: String, attempt: Int): String =
        "$runId:attempt-$attempt"

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
