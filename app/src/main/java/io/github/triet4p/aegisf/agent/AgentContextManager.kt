package io.github.triet4p.aegisf.agent

import io.github.triet4p.aegisf.privacy.IssuedMaskToken
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

    /**
     * Version of the S8.11 runtime block appended to every system instruction.
     *
     * v4 added the token-syntax invariant: the masked-value delimiters may appear only as an
     * exact copy of a complete displayed token, which is the one form the inbound guard accepts.
     *
     * v5 replaces the blanket "a privacy refusal is never retried" sentence with the exact,
     * bounded exception the owner approved: one host-issued re-issue of a pre-dispatch
     * `UNKNOWN_TOKEN` response, with a fixed shape-only notice and no model/provider fallback.
     *
     * v6 records the owner-approved guard boundary and the automatic issued-token inventory:
     * provider reasoning/thinking is not guard input, the answer and tool-call arguments are
     * enforced, and the host appends the run's complete issued-token list to every turn through
     * [tokenInventoryBlock]. The version is part of the cache identity, so this wording change
     * cannot reuse a prefix cached under the previous contract.
     *
     * v7 states the two grounding invariants that follow the observed failures: a clarification
     * field names the component the source or user actually leaves unresolved (a missing clock
     * time is time, not date), and a row is named to the user with the application's own
     * user_label from the observation that binds it.
     */
    const val CONTEXT_BLOCK_VERSION = "s8-agent-context/v7"

    /** Version of the host-authored issued-token inventory; wording changes mint a new one. */
    const val TOKEN_INVENTORY_VERSION = "s8-agent-token-inventory/v1"

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
        appendLine("token_syntax_rule: bracket-delimited masked values may be written only as an exact")
        appendLine("copy of one complete token listed in this turn's issued-token inventory. Never")
        appendLine("abbreviate, reformat, renumber, guess or invent one, or copy a value from another")
        appendLine("run; when no listed value fits, refer to the value by its alias or in plain words.")
        appendLine("token_inventory_rule: the host appends this run's complete issued-token list to every")
        appendLine("turn. That list is authoritative: an unlisted bracket-delimited value was never issued.")

        appendLine("reference_rule: references are opaque host-issued aliases bound to one observation's revisions; never parse, construct or reuse one across runs, and re-observe instead of refreshing a stale binding.")
        appendLine("mutation_identity_rule: mutation identities and the recorded operation receipt are host-owned; never supply or request one.")
        appendLine("replay_evidence_rule: exact replay is the recorded operation alias only; report the journal no_change result and current effect verification separately.")
        appendLine("repair_rule: repair only errors whose control permits it, using fresh authoritative masked observations. Terminal errors stop hosted work.")
        appendLine("masking_repair_rule: one masking refusal is repairable by the HOST only — when a complete response carried a masked-value form this session never issued, the host discards that whole response, appends one fixed shape-only notice and re-issues the same turn once. You never retry a privacy refusal with another spelling, raw value, endpoint or fallback model, and a second refusal or any foreign-token, raw-literal or decoded-sensitive finding stays terminal.")
    }

    /**
     * The authoritative issued-token inventory for one turn.
     *
     * It lists every token this run has issued, in mint order, with the kind of value each one
     * stands for — never the underlying value, never a rehydration hint, and never a token from
     * another run. The list is complete: it is never filtered, reordered or truncated, so an
     * absent entry always means "not issued" rather than "omitted for space". The executor
     * appends it as the last dynamic prompt element of every turn (initial, clarification,
     * compaction, tool-result continuation and masking repair); the stable versioned prefix
     * stays byte-identical, so provider prefix caching is unaffected. A run whose inventory
     * cannot fit its declared context bound stops with `CONTEXT_BUDGET` instead of dropping
     * entries.
     */
    fun tokenInventoryBlock(tokens: List<IssuedMaskToken>): String = buildString {
        append("issued_mask_token_inventory (").append(TOKEN_INVENTORY_VERSION)
        append("): ").append(tokens.size)
        appendLine(" token(s) issued in this run, in issuance order.")
        if (tokens.isEmpty()) {
            appendLine("- none: no masked value has been issued yet; use plain words until an observation shows one.")
        } else {
            tokens.forEach { token ->
                append("- ").append(token.token).append(" kind=").appendLine(token.kind.name)
            }
        }
        append(
            "This list is authoritative and complete. Use a listed token only as an exact " +
                "complete copy; never abbreviate, reformat, renumber, invent or reuse a value " +
                "from another run, and never treat an unlisted value as issued.",
        )
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
        promptContract = CONTEXT_BLOCK_VERSION,
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
