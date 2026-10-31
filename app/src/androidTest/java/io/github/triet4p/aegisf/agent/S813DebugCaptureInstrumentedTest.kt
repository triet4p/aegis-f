package io.github.triet4p.aegisf.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.privacy.SensitiveVault
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    /**
     * Device proof that the explicit exhaustive batch-continuation mode keeps a failing case
     * verdict, records raw capture for it through the real recorder, and still measures the next
     * fully isolated case. It also proves the pass identity field on every per-case record and the
     * strict rejection of a forged or retained-prefix exhaustive binding. No network is used.
     */
    @Test
    fun exhaustiveContinuationRetainsFailingCaseAndIsolatesNext() {
        val frozenFixtureText = InstrumentationRegistry.getInstrumentation().context.assets
            .open("s8_agent_v3_qualification_fixture.json")
            .use { String(it.readBytes(), Charsets.UTF_8) }
        val frozenFixtureSha = frozenFixtureText.toByteArray(Charsets.UTF_8).sha256()
        val proofRootName = "S8.13-exhaustive-capture-proof-${UUID.randomUUID()}"
        val proofRoot = File(context.filesDir, proofRootName)
        val vault = SensitiveVault("s813candidate-exhaustive-proof-${UUID.randomUUID()}")
        val capture = S813DebugCapture.open(
            context,
            proofRoot.absolutePath,
            S813DebugCapture.Provenance(
                packageName = context.packageName,
                attemptId = attemptId,
                batchId = S813DebugCapture.BATCH_ID,
                snapshotId = snapshotId,
                pass = "proof",
                fixtureId = S813DebugCapture.FIXTURE_ID,
                fixtureSha256 = frozenFixtureSha,
                expectedFixtureSha256 = frozenFixtureSha,
            ),
            emptyList(),
        )
        val masked = vault.maskText("payment 28000 VND on 2026-10-21")
        val issuedToken = requireNotNull(Regex("⟦[^⟦⟧\\s]+⟧").find(masked)?.value) {
            "the synthetic vault must mint one amount token"
        }
        val requestBody = JSONObject()
            .put("model", S813_EXPECTED_MODEL_ID)
            .put("messages", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("content", "Read: $masked on 2026-10-21.")))
            .toString()
        val responseBody = JSONObject()
            .put("id", "chatcmpl-exhaustive-proof")
            .put("choices", JSONArray().put(JSONObject()
                .put("index", 0)
                .put("finish_reason", "stop")
                .put("message", JSONObject()
                    .put("role", "assistant")
                    .put("content", "Recording the reading."))))
            .toString()

        fun recordCase(caseId: String, postconditions: JSONObject) {
            capture.startCase(caseId, listOf("inspect_link", "propose_link", "ask_user"), frozenFixtureText, vault)
            capture.recordExchange(
                caseId = caseId,
                index = 0,
                protocol = "openai_chat_completions",
                headerNames = listOf("Authorization", "Content-Type"),
                requestBody = requestBody,
                responseStatus = 200,
                responseBody = responseBody,
                guard = JSONObject().put("accepted", true),
                refusalStage = "accepted",
                vault = vault,
                fixtureBytes = frozenFixtureText,
            )
            capture.recordTurn(caseId, JSONObject()
                .put("index", 0)
                .put("call", "propose_link")
                .put("result", JSONObject().put("accepted", true)))
            capture.recordEffects(
                caseId,
                postconditions,
                "Clarifying",
                JSONArray().put(JSONObject().put("call", "propose_link")),
                JSONArray(),
            )
        }

        val failingPostconditions = JSONObject()
            .put("ledger_rows", 0)
            .put("out_of_scope_effect_rows", 0)
            .put("unexpected_mutation_operations", 0)
            .put("stored_events", JSONArray())
            .put("links", JSONArray().put(JSONObject()
                .put("event_id", 313)
                .put("draft_id", 421)
                .put("state", "PROPOSED")))
            .put("operation_command_counts", JSONObject().put("PROPOSE_LINK", 1))
        val cleanPostconditions = JSONObject()
            .put("ledger_rows", 0)
            .put("out_of_scope_effect_rows", 0)
            .put("unexpected_mutation_operations", 0)
            .put("stored_events", JSONArray())
            .put("links", JSONArray())
            .put("operation_command_counts", JSONObject())
        recordCase("a8-link-02", failingPostconditions)
        recordCase("a8-laya-01", cleanPostconditions)

        fun providerTurn(): JSONObject = JSONObject()
            .put("model_echo_verified", true)
            .put("model_echo_matches", true)
            .put("requested_model_id", S813_EXPECTED_MODEL_ID)
            .put("reported_model_id", S813_EXPECTED_MODEL_ID)
            .put("finish", "tool_calls")

        fun caseResult(caseId: String, postconditions: JSONObject): JSONObject = JSONObject()
            .put("case_id", caseId)
            .put("attempt_status", "hosted_run_recorded")
            .put("raw_sensitive_absent_from_egress", true)
            .put("semantic_sensitive_inbound_detected", false)
            .put("masking_integrity", JSONObject()
                .put("outbound_raw_absent", true)
                .put("inbound_semantic_sensitive_absent", true))
            .put("provider_turns", JSONArray().put(providerTurn()))
            .put("masking_repairs", JSONArray())
            .put("masked_wire", JSONArray().put(JSONObject()
                .put("http_status", 200)
                .put("transport_failure_type", "")))
            .put("postconditions", postconditions)

        val failingMapping = JSONObject().put("business_result", JSONObject()
            .put("event_count", 0)
            .put("link_count", 1)
            .put("links", JSONArray().put(JSONObject()
                .put("event_id", 313)
                .put("draft_id", 422)
                .put("state", "PROPOSED")))
            .put("operation_commands", JSONObject().put("PROPOSE_LINK", 1)))
        val cleanMapping = JSONObject().put("business_result", JSONObject()
            .put("event_count", 0)
            .put("link_count", 0)
            .put("operation_commands", JSONObject()))
        val failingResult = caseResult("a8-link-02", failingPostconditions)
        val cleanResult = caseResult("a8-laya-01", cleanPostconditions)
        val failingVerdict = s813CaseAdjudication(failingResult, failingMapping)
        val cleanVerdict = s813CaseAdjudication(cleanResult, cleanMapping)
        assertEquals(
            "unexpected_or_financially_unsafe_link",
            failingVerdict.getString("safety_stop"),
        )
        assertFalse("the wrong link must stay a financial-safety failure", failingVerdict.getBoolean("financial_safety_match"))
        assertTrue("the clean case must have no safety stop", cleanVerdict.isNull("safety_stop"))
        assertTrue(cleanVerdict.getBoolean("financial_safety_match"))

        val failingDecision = s813ContinuationDecision("a8-link-02", failingResult, failingVerdict, null)
        val cleanDecision = s813ContinuationDecision("a8-laya-01", cleanResult, cleanVerdict, null)
        assertEquals(S813_EXHAUSTIVE_DECISION_CONTINUE, failingDecision.getString("decision"))
        assertEquals(S813_EXHAUSTIVE_DECISION_CONTINUE, cleanDecision.getString("decision"))
        assertEquals(
            "unexpected_or_financially_unsafe_link",
            failingDecision.getString("case_safety_stop"),
        )
        assertFalse("a continued case must not be marked fatal", failingDecision.has("fatal_class") &&
            !failingDecision.isNull("fatal_class"))
        val fatalDecision = s813ContinuationDecision(
            "a8-link-02",
            failingResult,
            failingVerdict,
            "credential_secrecy_breach",
        )
        assertEquals(S813_EXHAUSTIVE_DECISION_STOP, fatalDecision.getString("decision"))
        assertEquals("credential_secrecy_breach", fatalDecision.getString("fatal_class"))

        // Case isolation: every case owns a separate in-memory database, so a marker written by the
        // failing case is not visible to the next case before its own seeding runs.
        val firstDb = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val secondDb = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        fun rowCount(db: LedgerDatabase, table: String): Int =
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
        val isolationVerified = try {
            val secondBefore = rowCount(secondDb, "capture_messages")
            firstDb.openHelper.writableDatabase.execSQL(
                "INSERT INTO capture_messages(captureKind, sourceKind, sourceIdentity, bodyText, " +
                    "capturedAtEpochMillis, captureTimezone, revision, originRef, tombstone) VALUES " +
                    "('USER_NOTE','USER_QUICK_CAPTURE','exhaustive-proof-isolation',NULL,1,'UTC',0,NULL,0)",
            )
            val firstAfter = rowCount(firstDb, "capture_messages")
            val secondAfter = rowCount(secondDb, "capture_messages")
            firstAfter == 1 && secondBefore == 0 && secondAfter == 0
        } finally {
            firstDb.close()
            secondDb.close()
        }
        assertTrue("the failing case must not contaminate the next isolated case", isolationVerified)

        // The frozen slot plan and its negative bindings stay strict.
        val syntheticOrder = (1..S813_QUALIFICATION_CASE_COUNT).map { "case-$it" }
        val retainedPrefix = syntheticOrder.take(S813_EXHAUSTIVE_RETAINED_SLOT_COUNT)
        val passOneSelection = s813ExhaustivePassCaseIds(syntheticOrder, "1")
        assertFalse("pass 1 must never rerun a retained slot",
            passOneSelection.any { it in retainedPrefix })
        assertEquals(syntheticOrder, s813ExhaustivePassCaseIds(syntheticOrder, "2"))
        assertEquals(syntheticOrder, s813ExhaustivePassCaseIds(syntheticOrder, "3"))
        val synthLaunch = s813FreezeBoundQualificationLaunch(
            attemptId = "S8.13-exhaustive-proof",
            batchId = S813DebugCapture.BATCH_ID,
            snapshotId = "S8.13-exhaustive-proof-freeze-1",
            profile = S813_QUALIFICATION_CANDIDATE,
            expectedProfile = S813_QUALIFICATION_CANDIDATE,
            expectedBatchId = S813DebugCapture.BATCH_ID,
        )
        val priorProvenance = "a".repeat(64)
        val acceptedArgs = mapOf(
            "S813_EXHAUSTIVE_CONTRACT" to S813_EXHAUSTIVE_CONTRACT,
            "S813_EXHAUSTIVE_CASE_IDS" to passOneSelection.joinToString(","),
            "S813_EXHAUSTIVE_CASE_IDS_SHA256" to s813ExhaustiveCaseIdsSha256(passOneSelection),
            "S813_EXHAUSTIVE_PRIOR_PROVENANCE_SHA256" to priorProvenance,
            "S813_EXHAUSTIVE_IDENTITY_SHA256" to s813ExhaustiveIdentityDigest(
                "S8.13-exhaustive-proof",
                S813DebugCapture.BATCH_ID,
                "S8.13-exhaustive-proof-freeze-1",
                "1",
                passOneSelection,
                retainedPrefix,
                priorProvenance,
            ),
        )
        assertNotNull(
            "the exact frozen slot plan must be accepted",
            s813ExhaustiveContinuation(acceptedArgs, synthLaunch, "1", syntheticOrder),
        )
        val retainedRerunRejected = runCatching {
            s813ExhaustiveContinuation(
                acceptedArgs + ("S813_EXHAUSTIVE_CASE_IDS" to syntheticOrder.joinToString(",")),
                synthLaunch,
                "1",
                syntheticOrder,
            )
        }.isFailure
        val forgedDigestRejected = runCatching {
            s813ExhaustiveContinuation(
                acceptedArgs + ("S813_EXHAUSTIVE_IDENTITY_SHA256" to "b".repeat(64)),
                synthLaunch,
                "1",
                syntheticOrder,
            )
        }.isFailure
        val wrongPrefixRejected = runCatching {
            s813ExhaustiveContinuation(
                acceptedArgs + ("S813_EXHAUSTIVE_PRIOR_PROVENANCE_SHA256" to "c".repeat(64)),
                synthLaunch,
                "1",
                syntheticOrder,
            )
        }.isFailure
        assertTrue("rerunning the retained prefix must be rejected", retainedRerunRejected)
        assertTrue("a forged exhaustive identity digest must be rejected", forgedDigestRejected)
        assertTrue("a different prior provenance must change the identity", wrongPrefixRejected)

        recordProof(JSONObject()
            .put("exhaustive_continuation_proof", true)
            .put("capture_proof_relative", proofRootName)
            .put("capture_proof_case_ids", JSONArray(listOf("a8-link-02", "a8-laya-01")))
            .put("capture_proof_fixture_sha256", frozenFixtureSha)
            .put("failing_case_verdict_retained", failingDecision.getBoolean("verdict_retained"))
            .put("failing_case_continued", failingDecision.getString("decision") == S813_EXHAUSTIVE_DECISION_CONTINUE)
            .put("next_case_measured_after_failure", cleanDecision.getString("decision") == S813_EXHAUSTIVE_DECISION_CONTINUE)
            .put("fatal_condition_stops_batch", fatalDecision.getString("decision") == S813_EXHAUSTIVE_DECISION_STOP)
            .put("case_isolation_verified", isolationVerified)
            .put("retained_prefix_rerun_rejected", retainedRerunRejected)
            .put("forged_exhaustive_identity_rejected", forgedDigestRejected)
            .put("wrong_prior_provenance_rejected", wrongPrefixRejected))
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
