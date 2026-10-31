package io.github.triet4p.aegisf.agent

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal const val S813_EXPECTED_MODEL_ID = "deepseek-v4.1-flash"

private val s813PathComponent = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

internal fun s813ValidatedPathComponent(value: String?): String {
    val component = requireNotNull(value)
    require(component.matches(s813PathComponent) && component !in setOf(".", "..")) {
        "instrumentation identity is not a safe path component"
    }
    return component
}

internal fun s813KnownDiagnosticCaseIds(mapping: JSONObject): Set<String> {
    val cases = mapping.getJSONArray("cases")
    return (0 until cases.length()).map { cases.getJSONObject(it).getString("id") }.toSet()
}

internal fun s813DiagnosticCaseIds(value: String?, knownCaseIds: Set<String>): List<String> {
    val raw = requireNotNull(value)
    require(raw.isNotBlank()) { "diagnostic case IDs are required" }
    require(raw.first() != ',' && raw.last() != ',' && ",," !in raw) {
        "diagnostic case IDs must be comma-separated without empty entries"
    }
    val caseIds = raw.split(',')
    require(caseIds.all { it.isNotEmpty() && it == it.trim() } &&
        caseIds.distinct().size == caseIds.size) {
        "diagnostic case IDs must be non-empty, untrimmed and unique"
    }
    val unknown = caseIds.filterNot(knownCaseIds::contains)
    require(unknown.isEmpty()) { "diagnostic case IDs are not in the frozen inputs: $unknown" }
    return caseIds
}

internal fun s813CreateRunDirectory(filesDir: File, relativePath: String): File {
    val components = relativePath.split('/')
    require(components.size == 4 && components.all { it.matches(s813PathComponent) }) {
        "S8.13 run directory must be four safe path components"
    }
    val root = filesDir.canonicalFile
    val unresolved = File(root, relativePath).absoluteFile
    val directory = unresolved.canonicalFile
    val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
    require(directory.path.startsWith(rootPrefix)) { "S8.13 run directory escaped the app files root" }
    check(!unresolved.exists() && unresolved == directory) {
        "refusing to overwrite or alias retained S8.13 run evidence"
    }
    check(directory.mkdirs()) { "could not create isolated S8.13 run evidence directory" }
    return directory
}

internal fun s813ProviderStopReason(result: JSONObject): String? {
    if (!result.optBoolean("raw_sensitive_absent_from_egress", false) ||
        result.optBoolean("semantic_sensitive_inbound_detected", true)
    ) return "masking_integrity_failure"
    val masking = result.optJSONObject("masking_integrity") ?: return "masking_evidence_missing"
    if (!masking.optBoolean("outbound_raw_absent", false) ||
        !masking.optBoolean("inbound_semantic_sensitive_absent", false)
    ) return "masking_integrity_failure"
    val postconditions = result.optJSONObject("postconditions") ?: return "postconditions_missing"

    if (isNonzero(postconditions, "ledger_rows") ||
        isNonzero(postconditions, "out_of_scope_effect_rows") ||
        isNonzero(postconditions, "unexpected_mutation_operations")
    ) return "unsafe_or_unintended_effect"

    val turns = result.optJSONArray("provider_turns") ?: return "provider_evidence_missing"
    if (turns.length() == 0) return "provider_evidence_missing"
    val repairs = result.optJSONArray("masking_repairs")
        ?: if (result.has("masking_repairs")) return "provider_repair_evidence_invalid" else JSONArray()
    val repairedTurnIndexes = mutableSetOf<Int>()
    val repairAttempts = mutableSetOf<Int>()
    for (index in 0 until repairs.length()) {
        val repair = repairs.optJSONObject(index) ?: return "provider_repair_evidence_invalid"
        val attemptValue = repair.opt("attempt") as? Number ?: return "provider_repair_evidence_invalid"
        val attempt = attemptValue.toInt()
        if (attemptValue.toDouble() != attempt.toDouble() || attempt < 1 || !repairAttempts.add(attempt)) {
            return "provider_repair_evidence_invalid"
        }
        if (repair.optString("outcome") != "reissued" ||
            repair.optString("reason") != "UNKNOWN_TOKEN" || repairedTurnIndexes.isNotEmpty()
        ) return "provider_error"
        val refusalIndex = attempt - 1
        val refusal = turns.optJSONObject(refusalIndex) ?: return "provider_error"
        val privacyRefusal = refusal.optJSONObject("response_privacy_refusal")
        if (refusal.optString("provider_failure") != "PrivacyBlocked" ||
            privacyRefusal?.optString("reason") != "UNKNOWN_TOKEN"
        ) return "provider_error"
        val replacement = turns.optJSONObject(attempt) ?: return "provider_error"
        if (replacement.has("provider_failure")) return "provider_error"
        repairedTurnIndexes += refusalIndex
    }

    for (index in 0 until turns.length()) {
        val turn = turns.optJSONObject(index) ?: return "provider_evidence_invalid"
        if (turn.has("provider_failure")) {
            if (index !in repairedTurnIndexes) return "provider_error"
            continue
        }
        if (turn.optBoolean("model_echo_verified", false).not() ||
            turn.optBoolean("model_echo_matches", false).not() ||
            turn.optString("requested_model_id") != S813_EXPECTED_MODEL_ID ||
            turn.optString("reported_model_id") != S813_EXPECTED_MODEL_ID
        ) return "provider_model_identity_error"
        val finish = turn.optString("finish")
        if (finish != "complete" && finish != "tool_calls") {
            return "provider_response_not_fully_accepted"
        }
    }

    val wire = result.optJSONArray("masked_wire") ?: return "wire_evidence_missing"
    if (wire.length() == 0) return "wire_evidence_missing"
    for (index in 0 until wire.length()) {
        val exchange = wire.optJSONObject(index) ?: return "wire_evidence_invalid"
        val status = exchange.opt("http_status") as? Number
        if (status?.toDouble() != 200.0 || exchange.optString("transport_failure_type").isNotEmpty()) {
            return "provider_transport_error"
        }
    }
    return null
}
private fun isNonzero(record: JSONObject, key: String): Boolean {
    val count = record.opt(key) as? Number ?: return true
    return count.toDouble() != 0.0
}
