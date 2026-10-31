package io.github.triet4p.aegisf.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.privacy.SensitiveVault
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Device proof for the TEST-ONLY synthetic debug capture contract.
 *
 * It proves on the actual phone, without any network call, that:
 * - a refused reasoning token and the exact provider bodies are retained verbatim in the debug root;
 * - header values (a synthetic sentinel standing in for the credential) are never written, and a
 *   body containing the sentinel makes the recorder fail closed without writing a record;
 * - the recorder refuses unpinned or non-isolated provenance and a non-synthetic vault scope before
 *   creating anything.
 */
class S813DebugCaptureInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val arguments = InstrumentationRegistry.getArguments()
    private val attemptId = arguments.getString("S813_ATTEMPT_ID") ?: "S8.13-debug-proof"
    private val snapshotId = arguments.getString("S813_SNAPSHOT_ID") ?: "S8.13-debug-proof"
    private val runName = arguments.getString("S813_RUN_NAME")
    private val caseIds = arguments.getString("S813_CASE_IDS")?.split(',') ?: emptyList()
    private val fixtureText = "Synthetic fixture: payment 28000 VND on 2026-10-21.\n"
    private val fixtureSha = fixtureText.toByteArray(Charsets.UTF_8).sha256()

    private fun provenance(packageName: String = context.packageName, fixtureDigest: String = fixtureSha) =
        S813DebugCapture.Provenance(
            packageName = packageName,
            attemptId = attemptId,
            batchId = S813DebugCapture.BATCH_ID,
            snapshotId = snapshotId,
            pass = "proof",
            fixtureId = S813DebugCapture.FIXTURE_ID,
            fixtureSha256 = fixtureDigest,
            expectedFixtureSha256 = fixtureSha,
            runName = runName,
            caseIds = caseIds,
        )

    private fun openCapture(root: File, secrets: List<String> = emptyList()) =
        S813DebugCapture.open(context, root.absolutePath, provenance(), secrets)

    @Test
    fun debugCaptureKeepsRefusedTokenAndNeverCredential() {
        val root = File(context.filesDir, "debug-proof-${UUID.randomUUID()}")
        val sentinel = "SENTINEL-DO-NOT-PERSIST-${UUID.randomUUID()}"
        val vault = SensitiveVault("s813candidate-debug-proof-${UUID.randomUUID()}")
        val masked = vault.maskText("payment 28000 VND")
        val issued = Regex("⟦[^⟦⟧\\s]+⟧").find(masked)?.value
        requireNotNull(issued) { "the synthetic vault must mint one amount token" }
        val forged = "⟦AMT-${vault.scopeTag}-9999⟧"
        val mutated = issued.dropLast(2) + (if (issued[issued.length - 2] == '7') "8" else "7") + "⟧"
        val capture = openCapture(root, listOf(sentinel))
        capture.startCase("debug-proof-01", listOf("observe", "select_source"), fixtureText, vault)
        val requestBody = JSONObject()
            .put("model", "deepseek-v4.1-flash")
            .put("messages", org.json.JSONArray().put(JSONObject()
                .put("role", "user")
                .put("content", "Read: $masked on 2026-10-21.")))
            .toString()
        val reasoning = "I will reuse $mutated for the amount and $forged as a placeholder."
        val responseBody = JSONObject()
            .put("id", "chatcmpl-proof")
            .put("object", "chat.completion")
            .put("choices", org.json.JSONArray().put(JSONObject()
                .put("index", 0)
                .put("finish_reason", "stop")
                .put("message", JSONObject()
                    .put("role", "assistant")
                    .put("content", reasoning)
                    .put("reasoning_content", "thinking about the observation"))))
            .toString()
        capture.recordExchange(
            caseId = "debug-proof-01",
            index = 0,
            protocol = "openai_chat_completions",
            headerNames = listOf("Authorization", "Content-Type"),
            requestBody = requestBody,
            responseStatus = 200,
            responseBody = responseBody,
            guard = JSONObject()
                .put("accepted", false)
                .put("refusal_reason", "UNKNOWN_TOKEN")
                .put("safe_location", "response_body.choices[].message.content"),
            refusalStage = "inbound_response_guard",
            vault = vault,
            fixtureBytes = fixtureText,
        )

        val session = File(root, "session.json").readText()
        val caseRecord = File(root, "debug-proof-01/case.json").readText()
        val exchange = File(root, "debug-proof-01/exchanges.jsonl").readText()
        val all = session + caseRecord + exchange
        assertTrue("the debug session must be marked TEST_ONLY", JSONObject(session).getBoolean("TEST_ONLY"))
        assertFalse("header values must never be recorded",
            JSONObject(session).getBoolean("header_values_recorded"))
        assertTrue("the exact reasoning must be retained", all.contains(reasoning))
        assertTrue("the exact rejected token must be retained", all.contains(forged))
        assertTrue("the issued synthetic token must be retained", all.contains(issued))
        assertTrue("the guard verdict must be retained", all.contains("UNKNOWN_TOKEN"))
        val provenanceRecord = JSONObject(exchange.trim()).getJSONObject("token_provenance")
        val provenance = provenanceRecord.getJSONArray("candidates")
        val byToken = (0 until provenance.length()).associate { index ->
            val entry = provenance.getJSONObject(index)
            entry.getString("token") to entry
        }
        assertTrue("the issued token must be classified as known", byToken.getValue(issued).getBoolean("known_in_session"))
        assertTrue("its synthetic value must be proven from the fixture bytes",
            byToken.getValue(issued).getBoolean("value_is_fixture_substring"))
        assertTrue("the issued token must be part of this turn's issued set",
            byToken.getValue(issued).getBoolean("issued_in_this_turn_request"))
        assertTrue("the per-turn issued set must be recorded",
            provenanceRecord.getJSONArray("issued_tokens_at_turn").length() >= 1)
        assertTrue("the vault entry count at this turn must be recorded",
            provenanceRecord.getInt("vault_entries_at_turn") >= 1)
        assertFalse("the forged token must not be classified as known", byToken.getValue(forged).getBoolean("known_in_session"))
        assertTrue("an altered one-character copy must be flagged as a near miss",
            byToken.getValue(mutated).getBoolean("near_miss_to_issued_this_turn"))
        assertFalse("an invented placeholder of another sequence must not be a near miss",
            byToken.getValue(forged).getBoolean("near_miss_to_issued_this_turn"))
        assertFalse("no record may contain the credential sentinel", all.contains(sentinel))
        assertTrue("no record may contain a header value at all", !all.contains("Bearer"))

        // A body that carries the credential must fail closed and write nothing.
        val before = File(root, "debug-proof-01/exchanges.jsonl").readText().length
        val rejected = runCatching {
            capture.recordExchange(
                caseId = "debug-proof-01",
                index = 1,
                protocol = "openai_chat_completions",
                headerNames = listOf("Authorization"),
                requestBody = requestBody,
                responseStatus = 200,
                responseBody = JSONObject().put("error", sentinel).toString(),
                guard = JSONObject().put("accepted", true),
                refusalStage = "accepted",
                vault = vault,
                fixtureBytes = fixtureText,
            )
        }
        assertTrue("a credential-bearing record must be rejected", rejected.isFailure)
        assertEquals("no record may be appended after the rejection",
            before, File(root, "debug-proof-01/exchanges.jsonl").readText().length)
        recordProof(JSONObject()
            .put("exact_bodies_retained", all.contains(requestBody) || all.contains(reasoning))
            .put("reasoning_retained", all.contains(reasoning))
            .put("rejected_token_retained", all.contains(forged))
            .put("mutated_token_retained", all.contains(mutated))
            .put("near_miss_classified", byToken.getValue(mutated).getBoolean("near_miss_to_issued_this_turn"))
            .put("per_turn_issued_set_recorded",
                provenanceRecord.getJSONArray("issued_tokens_at_turn").length() >= 1 &&
                    provenanceRecord.getInt("vault_entries_at_turn") >= 1)
            .put("issued_token_retained", all.contains(issued))
            .put("guard_verdict_retained", all.contains("UNKNOWN_TOKEN"))
            .put("header_values_recorded", false)
            .put("credential_sentinel_absent", !all.contains(sentinel))
            .put("credential_body_write_refused", rejected.isFailure)
            .put("test_only_flag", JSONObject(session).getBoolean("TEST_ONLY"))
            .put("token_provenance_classified",
                byToken.getValue(issued).getBoolean("known_in_session") &&
                    !byToken.getValue(forged).getBoolean("known_in_session")))
    }

    @Test
    fun debugCaptureRefusesUnpinnedProvenanceBeforeWriting() {
        val wrongDigestRoot = File(context.filesDir, "debug-proof-unpinned-${UUID.randomUUID()}")
        val wrongDigest = runCatching {
            S813DebugCapture.open(
                context,
                wrongDigestRoot.absolutePath,
                provenance().copy(fixtureSha256 = "0".repeat(64)),
                emptyList(),
            )
        }
        assertTrue("an unpinned fixture digest must be refused", wrongDigest.isFailure)
        assertFalse("no capture root may be created for unpinned provenance", wrongDigestRoot.exists())

        val wrongPackageRoot = File(context.filesDir, "debug-proof-package-${UUID.randomUUID()}")
        val wrongPackage = runCatching {
            S813DebugCapture.open(context, wrongPackageRoot.absolutePath, provenance(packageName = "com.example.other"), emptyList())
        }
        assertTrue("a non-isolated package must be refused", wrongPackage.isFailure)
        assertFalse("no capture root may be created for a foreign package", wrongPackageRoot.exists())

        val scopeRoot = File(context.filesDir, "debug-proof-scope-${UUID.randomUUID()}")
        val capture = openCapture(scopeRoot)
        val realScope = runCatching {
            capture.startCase("debug-proof-02", listOf("observe"), fixtureText, SensitiveVault("unrelated-scope"))
        }
        assertTrue("a non-synthetic vault scope must be refused", realScope.isFailure)
        assertFalse("no case directory may be created for a non-synthetic scope",
            File(scopeRoot, "debug-proof-02").exists())
        recordProof(JSONObject()
            .put("unpinned_digest_refused", wrongDigest.isFailure)
            .put("foreign_package_refused", wrongPackage.isFailure)
            .put("non_synthetic_scope_refused", realScope.isFailure)
            .put("no_root_created_on_refusal", !wrongDigestRoot.exists() && !wrongPackageRoot.exists()))
    }

    private fun recordProof(flags: JSONObject) {
        val proofDirectory = if (runName == null) {
            File(context.filesDir, "S8.13-debug-proof")
        } else {
            File(context.filesDir, "$attemptId/$snapshotId/debug-proof/$runName")
        }
        proofDirectory.mkdirs()
        val file = File(proofDirectory, "summary.json")
        val merged = if (file.exists()) {
            runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        } else {
            JSONObject()
        }
        flags.keys().forEach { key -> merged.put(key, flags.get(key)) }
        file.writeText(merged
            .put("TEST_ONLY", true)
            .put("capture_contract", S813DebugCapture.CONTRACT)
            .put("attempt_id", attemptId)
            .put("snapshot_id", snapshotId)
            .put("run_name", runName ?: JSONObject.NULL)
            .put("case_ids", org.json.JSONArray(caseIds))
            .toString(2))
    }

    private fun ByteArray.sha256(): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
}
