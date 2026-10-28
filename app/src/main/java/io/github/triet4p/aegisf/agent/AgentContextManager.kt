package io.github.triet4p.aegisf.agent

import io.github.triet4p.aegisf.provider.HostedProtocol
import io.github.triet4p.aegisf.provider.HostedToolDeclaration

/**
 * The S8.11 local context manager: stable prompt-cache identities, bounded task history,
 * and minimal dynamic observations.
 *
 * What this module owns:
 *
 * - Stable versioned instruction/tool/skill prefix construction: one canonical system text
 *   and one canonical tool-schema text per run, so prefix bytes stay stable across turns
 *   and provide the advertised provider caching with the identical-prefix discipline
 *   (static versioned prefix first, dynamic masked observations only at the end).
 * - A value-free cache identity ([AgentCacheKey]): provider protocol/host/model/config,
 *   tool schema, skill catalog, and privacy-context (scope + vault tag) versions and ids.
 *   The identity carries no prompt text, no raw values, no mappings, no keys, and no
 *   credential — it is a local routing/attribution record only. Any stored prefix cache
 *   is provider-owned; this code never creates an explicit cache resource and never
 *   assumes one mechanism across providers.
 * - Actual usage accounting ([AgentRunUsage]): per-turn input/output/reasoning/cached
 *   tokens exactly as each provider reported them, absent fields stay null. No estimated
 *   savings are ever computed or reported as fact.
 * - Bounded local compaction for over-budget runs: verified effects and re-read fresh
 *   state are preserved; only older masked tool-result payloads are dropped from the
 *   new prompt. Compaction changes no dispatch, no revalidation, and no completion rule:
 *   mutable state is always re-read fresh (never trusted from the compacted summary),
 *   summaries never authorize an effect, and the protocol's own required continuation
 *   items stay byte-identical (compaction only restarts chains with no pending calls).
 * - Cache failure fallback: a provider that reports no usage, or a cache miss/expiry,
 *   is recorded honestly ([AgentCacheSupport]) and the run continues with the full
 *   stable prefix — behavior and isolation unchanged.
 */
object AgentContextManager {

    /** Version of the S8.11 runtime block appended to every system instruction. */
    const val CONTEXT_BLOCK_VERSION = "s8-agent-context/v3"

    /** Version of the tool-schema material included in the stable cache identity. */
    const val TOOL_SCHEMA_VERSION = AgentInterface.TOOL_SCHEMA

    /** Version of the skill-catalog material included in the stable cache identity. */
    const val SKILL_CATALOG_VERSION = WorkflowSkillCatalog.SCHEMA

    /** How many prior dispatch entries a carried summary may hold; older entries drop. */
    const val MAX_HISTORY_ENTRIES = 32

    /**
     * The stable runtime block: one canonical text for every run. Versioned so a
     * contract/skill/catalog change visibly changes the cache identity.
     */
    fun stableRuntimeBlock(): String = buildString {
        appendLine("context_block_version: $CONTEXT_BLOCK_VERSION")
        appendLine("provider_cache_discipline: stable versioned instruction/tool/skill prefix first;")
        appendLine("dynamic masked task observations only at the end of the prompt.")
        appendLine("mutable_state_rule: mutable query results are authoritative only when re-read")
        appendLine("by scope/revision in this run; summaries never authorize state or effects.")
        appendLine("privacy_rule: the stable prefix carries versioned instructions and tool/skill")
        appendLine("schemas only — never raw values, mappings, keys, or credentials.")

        appendLine("reference_rule: references are opaque host-issued aliases bound to one observation's revisions; never parse, construct or reuse one across runs, and re-observe instead of refreshing a stale binding.")
        appendLine("mutation_identity_rule: mutation identities and the recorded operation receipt are host-owned; never supply or request one.")
        appendLine("replay_evidence_rule: exact replay is the recorded operation alias only; report the journal no_change result and current effect verification separately.")
        appendLine("repair_rule: repair only errors whose control permits it, using fresh authoritative masked observations. Terminal errors stop hosted work; privacy refusals are never retried with another spelling or fallback.")
    }

    /**
     * The canonical tool-schema material for the stable cache identity: schema version,
     * then every tool name, description, and parameter schema in sorted-name order.
     * Exact bytes stay stable for identical inputs so prefix-cache attribution holds.
     */
    fun canonicalToolSchema(declarations: List<HostedToolDeclaration>): String =
        buildString {
            append("tool_schema_version: ").appendLine(TOOL_SCHEMA_VERSION)
            val sorted = declarations.sortedBy { it.name }
            sorted.forEachIndexed { index, tool ->
                if (index > 0) appendLine("---")
                append("tool_name: ").appendLine(tool.name)
                append("tool_description: ").appendLine(tool.description)
                append("tool_parameters: ").appendLine(tool.parameters.toString())
            }
        }

    /**
     * The canonical skill material for one run's cache identity: catalog schema plus
     * the selected skill ids and versions in sorted order. Different intents select
     * different skills, so the identity is per-run, never shared across workflows.
     */
    fun canonicalSkills(skills: List<WorkflowSkill>): String =
        skills.sortedBy { it.id.wireId }
            .joinToString(separator = ";") { "${it.id.wireId}/${it.version}" }
            .let { "skill_catalog: $SKILL_CATALOG_VERSION skills: [$it]" }

    /**
     * Builds the stable cache identity for one run. Every input is a version/identity
     * string already pinned by construction: the protocol/host/model/budget come from
     * the validated provider config, the tool schema is canonicalized above, the skill
     * material names the selected versioned skills, and the privacy context is the
     * vault scope tag (a one-way session tag, never a mapping or a value).
     */
    fun cacheKey(
        protocol: HostedProtocol,
        endpointHost: String,
        model: String,
        generationBudget: String,
        declarations: List<HostedToolDeclaration>,
        skills: List<WorkflowSkill>,
        privacyScopeTag: String,
    ): AgentCacheKey = AgentCacheKey(
        protocol = protocol,
        endpointHost = endpointHost,
        model = model,
        generationBudget = generationBudget,
        interfaceContract = AgentInterface.CONTRACT,
        toolSchema = TOOL_SCHEMA_VERSION + ":sha256:" + sha256Hex(canonicalToolSchema(declarations)),
        skillCatalog = canonicalSkills(skills),
        privacyContext = privacyScopeTag,
    )

    /**
     * Classifies the provider cache behavior for one finished run from the actual
     * per-turn usage the provider reported: any positive `cachedInputTokens` means the
     * provider reused a cached prefix; reported usage but no cached input means the
     * provider exposed usage but no cache hit on this run; no usage at all means the
     * route honestly reports [AgentCacheSupport.USAGE_UNSUPPORTED] — never a
     * fabricated hit.
     */
    fun classifyCacheSupport(usage: List<AgentRunUsage>): AgentCacheSupport = when {
        usage.any { (it.cachedInputTokens ?: 0) > 0 } -> AgentCacheSupport.CACHED_PREFIX_REUSE
        usage.any { it.inputTokens != null || it.outputTokens != null } -> AgentCacheSupport.NO_CACHE_REPORTED
        else -> AgentCacheSupport.USAGE_UNSUPPORTED
    }

    /**
     * One bounded prior-dispatch entry for a carried summary: tool name, application
     * status, and value-free refs only. Never prompt text, never raw values.
     */
    data class DispatchSummaryEntry(val tool: String, val status: String, val ref: String?)

    /**
     * Renders at most [maxEntries] most-recent dispatch entries (identifiers and
     * statuses only) for a compaction or resume prompt. Older entries drop from the
     * prompt text; the in-memory dispatch record is untouched and every effect is
     * still re-read from live state before it can authorize anything.
     */
    fun boundedDispatchSummary(
        entries: List<DispatchSummaryEntry>,
        maxEntries: Int = MAX_HISTORY_ENTRIES,
    ): String = buildString {
        append("[")
        entries.takeLast(maxEntries).forEachIndexed { index, entry ->
            if (index > 0) append(",")
            append("{\"tool\":")
            append(org.json.JSONObject.quote(entry.tool))
            append(",\"status\":")
            append(org.json.JSONObject.quote(entry.status))
            entry.ref?.let { append(",\"ref\":").append(org.json.JSONObject.quote(it)) }
            append("}")
        }
        append("]")
    }

    /**
     * Decides whether one over-budget run compacts. Returns the compaction record when
     * the new prompt must drop [priorPayloads] older masked tool-result payloads.
     * Returns null when the run fits its bound, or when no older payload exists to
     * drop (rebuilding the same prompt cannot help — stop honestly instead). The
     * caller rebuilds the provider conversation from the stable prefix plus a bounded
     * summary plus freshly re-read observations; every pending call has already been
     * answered, so no tool-result pairing is lost.
     */
    fun compactForBudget(
        contextCharacters: Int,
        maxContextCharacters: Int,
        priorPayloads: Int,
        carriedDispatches: Int,
        completedTurns: Int,
    ): AgentContextCompaction? {
        if (contextCharacters <= maxContextCharacters) return null
        // Nothing older to drop: rebuilding the same prompt cannot help, so stop
        // honestly instead of looping on an identical prompt.
        if (priorPayloads == 0) return null
        return AgentContextCompaction(
            compactedTurns = completedTurns,
            droppedToolResults = priorPayloads,
            carriedDispatches = carriedDispatches,
        )
    }

    internal fun sha256Hex(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }

}
