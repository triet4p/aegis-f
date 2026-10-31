package io.github.triet4p.aegisf.agent

import io.github.triet4p.aegisf.provider.HostedResponsePrivacyRefusalReason

/**
 * The one bounded repair opportunity for a pre-dispatch masking refusal.
 *
 * The owner approved exactly this exception (S8.13-replan-a21): when the complete response
 * body scan refuses with [HostedResponsePrivacyRefusalReason.UNKNOWN_TOKEN] *before* any call
 * in that response was dispatched, the host may re-issue the same turn once with the fixed,
 * versioned notice below. Acceptance is unchanged:
 *
 * - the refused response and every call it carried are discarded completely (no effects);
 * - the replacement response is scanned by the same boundary in full;
 * - a second `UNKNOWN_TOKEN`, or any `FOREIGN_TOKEN`, `RAW_LITERAL_EGRESS`, decoded-sensitive
 *   match, duplicate key, invalid JSON, depth or root problem stays terminal;
 * - no other masking failure is retried, and there is no spelling, raw-value, endpoint,
 *   protocol or model fallback: the provider identity never changes.
 *
 * The notice is fixed text. It never contains the refused text, the offending token, a raw
 * value, reasoning, a foreign identifier or any reconstruction of them: the guard returns on
 * the first failing branch, so a refused string can itself be a raw known surface or another
 * session's token, and echoing it back would leak it.
 *
 * The notice is appended as the last, dynamic prompt element. The stable versioned prefix
 * stays byte-identical, so provider prefix caching is unaffected.
 */
object MaskingRepairPolicy {
    /** Version of the fixed repair notice; any wording change must mint a new version. */
    const val VERSION = "s8-agent-masking-repair/v1"

    /**
     * ASCII text with no bracket-delimited token shape: the model must not be handed a new
     * token-shaped example it could copy into its next response.
     */
    const val NOTICE: String =
        "Host notice (fixed, $VERSION): the previous response contained a masked-value form that " +
            "this session did not issue, so it was discarded and nothing in it was used. Write a " +
            "masked value only as an exact byte-for-byte copy of one complete value shown in a " +
            "tool result or observation. Never abbreviate, reformat, renumber or invent one; when " +
            "no shown value fits, use plain words instead."

    /**
     * Only an unissued token shape is repairable. Every other refusal category is a privacy,
     * protocol or metadata failure that a re-issue cannot repair and must stay terminal.
     */
    fun eligible(reason: HostedResponsePrivacyRefusalReason): Boolean =
        reason == HostedResponsePrivacyRefusalReason.UNKNOWN_TOKEN
}
