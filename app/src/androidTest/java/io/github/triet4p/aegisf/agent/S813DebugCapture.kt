package io.github.triet4p.aegisf.agent

import android.content.Context
import io.github.triet4p.aegisf.privacy.SensitiveVault
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * TEST-ONLY synthetic-fixture debug capture for the S8.13 qualification harness.
 *
 * Why this exists: the qualification evidence contract stores provider bodies redacted, which made
 * the eight `UNKNOWN_TOKEN` reasoning refusals undiagnosable — the offending text was not retained
 * anywhere. The owner explicitly authorized full test observability for the synthetic fixtures, so
 * this recorder keeps the exact request/response bodies (including reasoning and the rejected token)
 * in a separate debug root.
 *
 * Safety rules enforced here, not by convention:
 * - it only opens inside the isolated qualification package, for the frozen synthetic fixture id
 *   and the exact frozen fixture digest, under the app's own sandbox;
 * - it only records a case whose vault scope tag is the synthetic `s813cand…` pattern;
 * - it never receives HTTP header values: callers pass header *names* only;
 * - every written string is scanned against the in-memory credential candidates and the write fails
 *   closed if any could leak;
 * - records are marked TEST_ONLY and are written outside the qualification evidence run directory.
 */
internal class S813DebugCapture private constructor(
    private val root: File,
    private val provenance: Provenance,
    private val forbiddenSecrets: List<String>,
) {
    data class Provenance(
        val packageName: String,
        val attemptId: String,
        val batchId: String,
        val snapshotId: String,
        val pass: String,
        val fixtureId: String,
        val fixtureSha256: String,
        val expectedFixtureSha256: String,
        val runName: String? = null,
        val caseIds: List<String> = emptyList(),
    )

    private val caseDirectories = linkedMapOf<String, File>()
    private fun identity(record: JSONObject, caseId: String? = null): JSONObject {
        record.put("attempt_id", provenance.attemptId)
            .put("batch_id", provenance.batchId)
            .put("snapshot_id", provenance.snapshotId)
            .put("run_name", provenance.runName ?: JSONObject.NULL)
            .put("case_ids", JSONArray(provenance.caseIds))
        if (caseId != null) record.put("case_id", caseId)
        return record
    }

    fun startCase(caseId: String, toolNames: List<String>, fixtureBytes: String, vault: SensitiveVault) {
        // The credential scan happens before any byte reaches the debug root.
        guardAgainstSecrets(fixtureBytes)
        requireGate(vault)
        if (provenance.pass == "reproduction") {
            require(caseId in provenance.caseIds) { "debug capture case is not in the selected diagnostic invocation" }
        }
        val directory = File(root, caseId)
        check(!directory.exists()) { "refusing to overwrite retained debug capture for $caseId" }
        check(directory.mkdirs()) { "could not create the isolated debug capture directory" }
        caseDirectories[caseId] = directory
        write(directory, "case.json", identity(JSONObject()
            .put("TEST_ONLY", true)
            .put("capture_contract", CONTRACT)
            .put("capture_mode", "synthetic_only_full_bodies")
            .put("header_values_recorded", false)
            .put("tool_names", JSONArray(toolNames))
            .put("synthetic_scope_tag", vault.scopeTag)
            .put("synthetic_scope_prefix_ok", vault.scopeTag.startsWith(SYNTHETIC_SCOPE_PREFIX))
            .put("fixture_id", provenance.fixtureId)
            .put("fixture_sha256", provenance.fixtureSha256)
            .put("expects_raw_values_only_when_fixture_substring", true)
            .put("fixture_bytes_sha256", fixtureBytes.sha256())
            .put("opened_at", java.time.Instant.now().toString()), caseId))
    }

    /**
     * Appends one provider exchange verbatim: request body, response body, the decoded assistant
     * message, the guard verdict and the refusal stage. Header values are never accepted.
     */
    fun recordExchange(
        caseId: String,
        index: Int,
        protocol: String,
        headerNames: List<String>,
        requestBody: String,
        responseStatus: Int,
        responseBody: String,
        guard: JSONObject,
        refusalStage: String,
        vault: SensitiveVault,
        fixtureBytes: String,
    ) {
        val directory = requireNotNull(caseDirectories[caseId]) { "debug capture case is not started" }
        // A request or response body that carries the credential must never be written at all.
        guardAgainstSecrets(requestBody)
        guardAgainstSecrets(responseBody)
        val assistant = assistantMessageOf(protocol, responseBody)
        val record = identity(JSONObject()
            .put("TEST_ONLY", true)
            .put("capture_contract", CONTRACT)
            .put("exchange_index", index)
            .put("protocol", protocol)
            .put("request_header_names", JSONArray(headerNames))
            .put("request_header_values_recorded", false)
            .put("request_body", requestBody)
            .put("http_status", responseStatus)
            .put("response_body", responseBody)
            .put("assistant_message", assistant ?: JSONObject.NULL)
            .put("assistant_reasoning", assistant?.optString("reasoning_content")?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("assistant_text", assistant?.optString("content")?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("response_privacy_guard", guard)
            .put("refusal_stage", refusalStage)
            .put("token_provenance", tokenProvenance(requestBody, responseBody, vault, fixtureBytes)), caseId)
        append(directory, "exchanges.jsonl", record.toString())
    }

    /** Appends the harness's own per-turn derived view (chronological calls, arguments, results). */
    fun recordTurn(caseId: String, turn: JSONObject) {
        val directory = requireNotNull(caseDirectories[caseId]) { "debug capture case is not started" }
        val text = turn.toString()
        guardAgainstSecrets(text)
        append(directory, "turns.jsonl", identity(JSONObject()
            .put("TEST_ONLY", true)
            .put("capture_contract", CONTRACT)
            .put("turn", turn), caseId)
            .toString())
    }

    /** Appends the executor/domain effect snapshot after the case's tools ran. */
    fun recordEffects(caseId: String, postconditions: JSONObject, outcome: String, trace: JSONArray, verifiedEffects: JSONArray) {
        val directory = requireNotNull(caseDirectories[caseId]) { "debug capture case is not started" }
        val text = identity(JSONObject()
            .put("TEST_ONLY", true)
            .put("capture_contract", CONTRACT)
            .put("outcome", outcome)
            .put("postconditions", postconditions)
            .put("executor_trace", trace)
            .put("verified_effects", verifiedEffects), caseId)
            .toString()
        guardAgainstSecrets(text)
        write(directory, "effects.json", JSONObject(text))
    }

    /**
     * Per-turn provenance context. `issued_tokens_at_turn` is the set of tokens present in this
     * turn's request body — the exact tokens the session had issued and the model could have copied —
     * so a response token is decidable as issued, altered (near miss of an issued token) or invented
     * without storing any vault map or raw value.
     */
    private fun tokenProvenance(
        requestBody: String,
        responseBody: String,
        vault: SensitiveVault,
        fixtureBytes: String,
    ): JSONObject {
        val pattern = Regex("⟦[^⟦⟧\\s]{1,64}⟧")
        val requestTokens = pattern.findAll(requestBody).map { it.value }.distinct().toList()
        val responseTokens = pattern.findAll(responseBody).map { it.value }.distinct().toList()
        val candidates = (requestTokens + responseTokens).distinct()
        val array = JSONArray()
        candidates.forEach { token ->
            val known = vault.isKnownToken(token)
            val issuedAtTurn = requestTokens.contains(token)
            val kind = token.drop(1).substringBefore('-').takeIf { it.isNotEmpty() }
            val nearMiss = !issuedAtTurn && responseTokens.contains(token) &&
                requestTokens.any { other -> kindOf(other) == kind && editDistance(other, token) in 1..2 }
            val entry = JSONObject()
                .put("token", token)
                .put("known_in_session", known)
                .put("issued_in_this_turn_request", issuedAtTurn)
                .put("character_length", token.length)
                .put("kind_prefix", kind ?: JSONObject.NULL)
                .put("seen_in_request", issuedAtTurn)
                .put("seen_in_response", responseTokens.contains(token))
                .put("near_miss_to_issued_this_turn", nearMiss)
            if (known) {
                val syntheticValue = syntheticValueOf(token, vault)
                val provenSynthetic = syntheticValue != null && fixtureBytes.contains(syntheticValue)
                entry.put("value_is_fixture_substring", provenSynthetic)
                if (provenSynthetic) entry.put("synthetic_value", syntheticValue)
            }
            array.put(entry)
        }
        return JSONObject()
            .put("vault_entries_at_turn", vault.entryCount())
            .put("issued_tokens_at_turn", JSONArray(requestTokens))
            .put("candidates", array)
    }

    private fun kindOf(token: String): String = token.drop(1).substringBefore('-')

    /** Bounded Levenshtein distance; enough to spot a one- or two-character altered copy. */
    private fun editDistance(first: String, second: String): Int {
        if (first == second) return 0
        if (kotlin.math.abs(first.length - second.length) > 2) return 3
        var previous = IntArray(second.length + 1) { it }
        var current = IntArray(second.length + 1)
        for (index in 1..first.length) {
            current[0] = index
            for (column in 1..second.length) {
                val substitution = previous[column - 1] + if (first[index - 1] == second[column - 1]) 0 else 1
                current[column] = minOf(previous[column] + 1, current[column - 1] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[second.length]
    }

    private fun syntheticValueOf(token: String, vault: SensitiveVault): String? = runCatching {
        when {
            token.startsWith("⟦AMT-") -> vault.rehydrateAmount(token).toString()
            token.startsWith("⟦DATE-") -> vault.rehydrateDate(token)
            token.startsWith("⟦TIME-") -> vault.rehydrateTime(token)
            token.startsWith("⟦ACCT-") -> vault.rehydrateAccount(token)
            token.startsWith("⟦BANK-") -> vault.rehydrateBank(token)
            else -> null
        }
    }.getOrNull()

    private fun assistantMessageOf(protocol: String, body: String): JSONObject? = runCatching {
        val root = JSONObject(body)
        when (protocol.uppercase()) {
            "OPENAI_CHAT_COMPLETIONS" -> root.optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message")
            else -> null
        }
    }.getOrNull()

    private fun requireIdentity() {
        s813ValidatedPathComponent(provenance.attemptId)
        s813ValidatedPathComponent(provenance.snapshotId)
        require(provenance.batchId == BATCH_ID) { "debug capture provenance has the wrong batch" }
        provenance.runName?.let(::s813ValidatedPathComponent)
        if (provenance.pass == "reproduction") {
            val runName = s813ValidatedPathComponent(provenance.runName)
            require(provenance.caseIds.isNotEmpty() && provenance.caseIds.distinct() == provenance.caseIds) {
                "debug capture requires the ordered, unique selected case IDs"
            }
            require(provenance.caseIds.all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) }) {
                "debug capture case IDs must be safe path components"
            }
            val expectedRoot = File(
                appFilesRoot,
                "${provenance.attemptId}/debug/${provenance.snapshotId}/$runName",
            ).canonicalFile
            require(root.canonicalFile == expectedRoot) {
                "debug capture root does not match the invocation identity"
            }
        }
    }

    private fun requireGate(vault: SensitiveVault) {
        require(provenance.packageName == ISOLATED_PACKAGE) {
            "debug capture requires the isolated qualification package, found ${provenance.packageName}"
        }
        requireIdentity()
        require(provenance.fixtureId == FIXTURE_ID && provenance.fixtureSha256 == provenance.expectedFixtureSha256 &&
            provenance.expectedFixtureSha256.isNotBlank()) {
            "debug capture requires the frozen synthetic fixture digest"
        }
        val rootPrefix = File(appFilesRoot).canonicalPath.trimEnd(File.separatorChar) + File.separator
        require(root.canonicalPath.startsWith(rootPrefix)) { "debug capture root must stay inside the app sandbox" }
        require(vault.scopeTag.startsWith(SYNTHETIC_SCOPE_PREFIX)) {
            "debug capture requires the synthetic fixture scope, found an unexpected scope"
        }
    }

    private fun guardAgainstSecrets(text: String) {
        forbiddenSecrets.filter { it.isNotBlank() && it.length >= 8 }.forEach { secret ->
            check(!text.contains(secret)) { "refusing to persist a debug record that contains credential material" }
        }
    }

    private fun write(directory: File, name: String, content: JSONObject) {
        File(directory, name).writeText(content.toString(2))
    }

    private fun append(directory: File, name: String, line: String) {
        File(directory, name).appendText(line + "\n")
    }

    private fun String.sha256(): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val CONTRACT = "aegis-s813-debug-capture/v1"
        const val ISOLATED_PACKAGE = "io.github.triet4p.aegisf.s813evidence"
        const val SYNTHETIC_SCOPE_PREFIX = "s813cand"
        const val FIXTURE_ID = "S8.13-v3-interface-qualification-fixture-01"
        const val BATCH_ID = "S8.13-v3-interface-qualification-batch-21"

        private var appFilesRoot: String = ""

        fun open(
            context: Context,
            debugRoot: String,
            provenance: Provenance,
            forbiddenSecrets: List<String>,
        ): S813DebugCapture {
            appFilesRoot = context.filesDir.absolutePath
            require(provenance.packageName == context.packageName) {
                "debug capture provenance package does not match the running application"
            }
            val root = File(debugRoot)
            val capture = S813DebugCapture(root, provenance, forbiddenSecrets)
            capture.requireStaticGate()
            check(!root.exists()) { "refusing to overwrite a retained debug capture root" }
            check(root.mkdirs()) { "could not create the isolated debug capture root" }
            capture.write(root, "session.json", capture.identity(JSONObject()
                .put("TEST_ONLY", true)
                .put("capture_contract", CONTRACT)
                .put("capture_mode", "synthetic_only_full_bodies")
                .put("package", provenance.packageName)
                .put("pass", provenance.pass)
                .put("fixture_id", provenance.fixtureId)
                .put("fixture_sha256", provenance.fixtureSha256)
                .put("expected_fixture_sha256", provenance.expectedFixtureSha256)
                .put("header_values_recorded", false)
                .put("credential_scope", "process memory only")
                .put("opened_at", java.time.Instant.now().toString())))
            return capture
        }
    }

    private fun requireStaticGate() {
        require(provenance.packageName == ISOLATED_PACKAGE) {
            "debug capture requires the isolated qualification package, found ${provenance.packageName}"
        }
        requireIdentity()
        require(provenance.fixtureId == FIXTURE_ID && provenance.fixtureSha256 == provenance.expectedFixtureSha256 &&
            provenance.expectedFixtureSha256.isNotBlank()) {
            "debug capture requires the frozen synthetic fixture digest"
        }
    }
}
