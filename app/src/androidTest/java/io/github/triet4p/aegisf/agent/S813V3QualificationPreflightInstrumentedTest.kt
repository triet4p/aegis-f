package io.github.triet4p.aegisf.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.triet4p.aegisf.data.CaptureKind
import io.github.triet4p.aegisf.data.CaptureMessageEntity
import io.github.triet4p.aegisf.data.CaptureSourceKind
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.SystemLedgerClock
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowClaimGuard
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.data.laya.LayaClassificationEngine
import io.github.triet4p.aegisf.data.laya.LayaInferenceLifecycle
import io.github.triet4p.aegisf.data.laya.LayaModelProvisioner
import io.github.triet4p.aegisf.privacy.MaskedHostedGateway
import io.github.triet4p.aegisf.privacy.MaskedPromptBoundary
import io.github.triet4p.aegisf.privacy.SensitiveVault
import io.github.triet4p.aegisf.provider.HostedConversation
import io.github.triet4p.aegisf.provider.HostedGenerationAdapter
import io.github.triet4p.aegisf.provider.HostedGenerationResult
import io.github.triet4p.aegisf.provider.HostedResponseMetadataKind
import io.github.triet4p.aegisf.provider.HostedResponseMetadataPolicy
import io.github.triet4p.aegisf.provider.HostedResponsePrivacyRefusal
import io.github.triet4p.aegisf.provider.HostedResponsePrivacyRefusalReason
import io.github.triet4p.aegisf.provider.HostedResponsePrivacyResult
import io.github.triet4p.aegisf.provider.HostedResponseProtocolErrorReason
import io.github.triet4p.aegisf.provider.withRawResponseForPrivacyScan
import io.github.triet4p.aegisf.provider.HostedProtocol
import io.github.triet4p.aegisf.provider.HostedToolCall
import io.github.triet4p.aegisf.provider.HostedToolDeclaration
import io.github.triet4p.aegisf.provider.HostedToolResult
import io.github.triet4p.aegisf.provider.HostedTurn
import io.github.triet4p.aegisf.provider.HostedTurnFinish
import io.github.triet4p.aegisf.provider.ToolCallDisposition
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled-provider qualification preflight over production tools, Room, command services and local Laya. */
@RunWith(AndroidJUnit4::class)
class S813V3QualificationPreflightInstrumentedTest {
    @Test
    fun controlledProviderProvesReplayCasRepairMultiEventAndLocalLaya() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val attemptId = s813ValidatedPathComponent(args.getString("S813_ATTEMPT_ID"))
        val snapshotId = s813ValidatedPathComponent(args.getString("S813_SNAPSHOT_ID"))
        val runName = args.getString("S813_RUN_NAME")?.let(::s813ValidatedPathComponent)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val mappingBytes = testContext.assets.open("s8_agent_v3_qualification_fixture.json").use { it.readBytes() }
        val scoringBytes = testContext.assets.open("s8_agent_v3_qualification_scoring.json").use { it.readBytes() }
        val domainFixtureBytes = testContext.assets.open("s8_agent_fixture.json").use { it.readBytes() }
        val runtimeProjectionBytes = testContext.assets.open("s8_agent_runtime_fixture_v3.json").use { it.readBytes() }
        val devFixtureBytes = testContext.assets.open("s8_agent_dev.json").use { it.readBytes() }
        val mapping = JSONObject(String(mappingBytes, Charsets.UTF_8))
        val baseFixture = JSONObject(String(domainFixtureBytes, Charsets.UTF_8))
        val runtimeProjection = JSONObject(String(runtimeProjectionBytes, Charsets.UTF_8))
        val devFixture = JSONObject(String(devFixtureBytes, Charsets.UTF_8))
        require(mapping.getString("fixture_id") == FIXTURE_ID)
        require(mapping.getString("contract") == "aegis-agent-interface/3")
        require(mapping.getJSONArray("cases").length() == 24)
        require(runtimeProjection.getString("projection_id") == "S8.13-runtime-fixture-v3")

        val knownCaseIds = s813KnownDiagnosticCaseIds(mapping)
        val selectedCaseIds = if (runName == null) {
            require(args.getString("S813_CASE_IDS") == null) { "case IDs require a diagnostic run name" }
            emptyList()
        } else {
            s813DiagnosticCaseIds(args.getString("S813_CASE_IDS"), knownCaseIds)
        }
        val responseLocationSmoke = runSafeResponseLocationSmoke(
            context, attemptId, snapshotId, runName, selectedCaseIds,
        )
        val multiSource = projectedCaptureText(mapping, "a8-multi-01", 111, baseFixture)
        require(multiSource.contains("2026-10-21") && multiSource.count { it == ';' } == 1)
        val multiEventProof = runMultiEventScenario(context, baseFixture, multiSource)
        val replayProof = runReplayScenario(context, baseFixture)
        val repairProof = runRecoveryScenario(context, baseFixture)
        val layaProof = runLayaScenario(context, baseFixture, runtimeProjection)
        val inboundPrivacyProof = runInboundPrivacyScenario(context, baseFixture)
        val accountSourceProvenance = accountSourceProvenanceDiagnostic(
            devFixture, baseFixture, runtimeProjection,
        )
        val runNamePathValidation = runName?.let {
            reproductionRunNamePathSmoke(context, attemptId, snapshotId, it, selectedCaseIds, knownCaseIds)
        }

        val output = File(context.filesDir, "$attemptId/$snapshotId/domain-preflight.json")
        check(!output.exists()) { "refusing to overwrite retained domain preflight evidence" }
        output.parentFile?.mkdirs()
        output.writeText(JSONObject()
            .put("task_id", "S8.13")
            .put("attempt_id", attemptId)
            .put("snapshot_id", snapshotId)
            .put("run_name", runName ?: JSONObject.NULL)
            .put("case_ids", JSONArray(selectedCaseIds))
            .put("fixture_id", mapping.getString("fixture_id"))
            .put("scoring_version", JSONObject(String(scoringBytes, Charsets.UTF_8)).getString("scoring_version"))
            .put("response_refusal_contract", HostedResponsePrivacyRefusalReason.CONTRACT_VERSION)
            .put("debug_capture_contract", S813DebugCapture.CONTRACT)
            .put("interface_contract", mapping.getString("contract"))
            .put("case_count", mapping.getJSONArray("cases").length())
            .put("preflight_case_ids", JSONArray().put("a8-multi-01").put("a8-retry-01").put("a8-recover-01").put("a8-laya-01"))
            .put("preflight_mode", "controlled_provider_no_network")
            .put("hosted_provider_calls", 0)
            .put("credential_loaded", false)
            .put("base_application_mutated", false)
            .put("device_data_cleared", false)
            .put("fixture_sha256", sha256(domainFixtureBytes))
            .put("runtime_projection_sha256", sha256(runtimeProjectionBytes))
            .put("qualification_fixture_sha256", sha256(mappingBytes))
            .put("qualification_scoring_sha256", sha256(scoringBytes))
            .put("multi_event", multiEventProof)
            .put("safety_adjudication", safetyAdjudicationSmoke())
            .put("exact_operation_replay", replayProof)
            .put("service_cas_conflict_and_repair", repairProof)
            .put("actual_local_laya_through_executor", layaProof)
            .put("inbound_response_privacy_stop", inboundPrivacyProof)
            .put("account_source_provenance", accountSourceProvenance)
            .put("safe_response_location_smoke", responseLocationSmoke)
            .put("run_name_path_validation", runNamePathValidation ?: JSONObject.NULL)
            .toString(2))
    }
    /**
     * Device-executed proof of the versioned quality-versus-financial-safety split.
     *
     * Synthetic case records and postconditions are fed to the same adjudicator the hosted
     * qualification uses. A merchant-only divergence must not stop the batch while every
     * financial/authority effect still must. No provider call is made.
     */
    private fun safetyAdjudicationSmoke(): JSONObject {
        val business = JSONObject()
            .put("event_count", 1)
            .put("link_count", 0)
            .put("operation_commands", JSONObject().put("UPDATE_EVENT", 1))
            .put("events", JSONArray().put(expectedEvent()))
            .put("links", JSONArray())
        val mapping = JSONObject().put("business_result", business)
        fun acceptedProviderTurn(reportedModel: String = S813_EXPECTED_MODEL_ID): JSONObject = JSONObject()
            .put("model_echo_verified", true)
            .put("model_echo_matches", reportedModel == S813_EXPECTED_MODEL_ID)
            .put("requested_model_id", S813_EXPECTED_MODEL_ID)
            .put("reported_model_id", reportedModel)
            .put("finish", "complete")

        fun result(
            events: JSONArray,
            links: JSONArray = JSONArray(),
            ledgerRows: Int = 0,
            operationCount: Int = 1,
            maskingOk: Boolean = true,
        ): JSONObject = JSONObject()
            .put("attempt_status", "hosted_run_recorded")
            .put("raw_sensitive_absent_from_egress", maskingOk)
            .put("semantic_sensitive_inbound_detected", !maskingOk)
            .put("masking_integrity", JSONObject()
                .put("outbound_raw_absent", maskingOk)
                .put("inbound_semantic_sensitive_absent", maskingOk))
            .put("postconditions", JSONObject()
                .put("stored_events", events)
                .put("links", links)
                .put("ledger_rows", ledgerRows)
                .put("out_of_scope_effect_rows", 0)
                .put("unexpected_mutation_operations", 0)
                .put("operation_command_counts", JSONObject().put("UPDATE_EVENT", operationCount)))
            .put("provider_turns", JSONArray().put(acceptedProviderTurn()))
            .put("masking_repairs", JSONArray())
            .put("masked_wire", JSONArray().put(JSONObject().put("http_status", 200)))
        fun stopOf(record: JSONObject, caseMapping: JSONObject = mapping): String? {
            val verdict = s813CaseAdjudication(record, caseMapping)
            return if (verdict.isNull("safety_stop")) null else verdict.getString("safety_stop")
        }
        val checks = JSONObject()
        val exact = s813CaseAdjudication(result(JSONArray().put(expectedEvent())), mapping)
        checks.put("exact_match_has_no_stop", exact.isNull("safety_stop"))
        checks.put("exact_match_is_financially_safe", exact.getBoolean("financial_safety_match"))
        checks.put("exact_match_has_no_quality_findings", exact.getJSONArray("quality_findings").length() == 0)

        val omitted = s813CaseAdjudication(result(JSONArray().put(expectedEvent(merchant = null))), mapping)
        checks.put("missing_merchant_has_no_stop", omitted.isNull("safety_stop"))
        checks.put("missing_merchant_stays_financially_safe", omitted.getBoolean("financial_safety_match"))
        val findings = omitted.getJSONArray("quality_findings")
        checks.put("missing_merchant_is_a_quality_finding",
            (0 until findings.length()).any { findings.getString(it) == "event_0.merchant_missing" })
        checks.put("incorrect_present_merchant_stops",
            stopOf(result(JSONArray().put(expectedEvent(merchant = "Some other shop")))) ==
                "unexpected_or_financially_incorrect_event")
        checks.put("incorrect_present_merchant_is_not_financially_safe",
            !s813CaseAdjudication(
                result(JSONArray().put(expectedEvent(merchant = "Some other shop"))), mapping,
            ).getBoolean("financial_safety_match"))

        checks.put("wrong_amount_stops",
            stopOf(result(JSONArray().put(expectedEvent(amount = 25_000)))) == "unexpected_or_financially_incorrect_event")
        checks.put("extra_event_stops",
            stopOf(result(JSONArray().put(expectedEvent()).put(expectedEvent()))) == "unexpected_event_mutation")
        checks.put("ledger_row_stops",
            stopOf(result(JSONArray().put(expectedEvent()), ledgerRows = 1)) == "unauthorized_or_financially_unsafe_effect")
        checks.put("excess_operation_stops",
            stopOf(result(JSONArray().put(expectedEvent()), operationCount = 2)) == "unexpected_operation_count")
        checks.put("masking_failure_stops",
            stopOf(result(JSONArray().put(expectedEvent()), maskingOk = false)) == "masking_integrity_failure")

        val linkedMapping = JSONObject().put("business_result",
            JSONObject(business.toString())
                .put("link_count", 1)
                .put("links", JSONArray().put(JSONObject()
                    .put("event_id", 7).put("draft_id", 411).put("state", "ACCEPTED"))))
        val wrongLink = JSONObject(result(JSONArray().put(expectedEvent())).toString())
        wrongLink.getJSONObject("postconditions").put("links", JSONArray().put(JSONObject()
            .put("event_id", 7).put("draft_id", 412).put("state", "ACCEPTED")))
        checks.put("wrong_attachment_stops",
            stopOf(wrongLink, linkedMapping) == "unexpected_or_financially_unsafe_link")
        fun refusal(reason: String): JSONObject = JSONObject()
            .put("provider_failure", "PrivacyBlocked")
            .put("response_privacy_refusal", JSONObject().put("reason", reason))
        val repaired = result(JSONArray().put(expectedEvent()))
            .put("provider_turns", JSONArray()
                .put(refusal("UNKNOWN_TOKEN"))
                .put(acceptedProviderTurn()))
            .put("masking_repairs", JSONArray().put(JSONObject()
                .put("attempt", 1).put("reason", "UNKNOWN_TOKEN").put("outcome", "reissued")))
            .put("masked_wire", JSONArray()
                .put(JSONObject().put("http_status", 200))
                .put(JSONObject().put("http_status", 200)))
        checks.put("recovered_unknown_token_is_not_a_provider_stop", stopOf(repaired) == null)
        val secondUnknown = JSONObject(repaired.toString())
            .put("provider_turns", JSONArray()
                .put(refusal("UNKNOWN_TOKEN"))
                .put(acceptedProviderTurn())
                .put(refusal("UNKNOWN_TOKEN")))
            .put("masking_repairs", JSONArray()
                .put(JSONObject().put("attempt", 1).put("reason", "UNKNOWN_TOKEN").put("outcome", "reissued"))
                .put(JSONObject().put("attempt", 3).put("reason", "UNKNOWN_TOKEN").put("outcome", "terminal")))
            .put("masked_wire", JSONArray()
                .put(JSONObject().put("http_status", 200))
                .put(JSONObject().put("http_status", 200))
                .put(JSONObject().put("http_status", 200)))
        checks.put("second_unknown_token_stops", stopOf(secondUnknown) == "provider_error")
        val foreign = result(JSONArray().put(expectedEvent()))
            .put("provider_turns", JSONArray().put(refusal("FOREIGN_TOKEN")))
        checks.put("foreign_token_stops", stopOf(foreign) == "provider_error")
        val rawLiteral = result(JSONArray().put(expectedEvent()))
            .put("provider_turns", JSONArray().put(refusal("RAW_LITERAL_EGRESS")))
        checks.put("raw_literal_refusal_stops", stopOf(rawLiteral) == "provider_error")
        val non200 = JSONObject(repaired.toString())
            .put("masked_wire", JSONArray()
                .put(JSONObject().put("http_status", 200))
                .put(JSONObject().put("http_status", 302)))
        checks.put("non_200_response_stops", stopOf(non200) == "provider_transport_error")
        val wrongModel = JSONObject(repaired.toString())
        wrongModel.getJSONArray("provider_turns").put(1, acceptedProviderTurn("other-model"))
        checks.put("model_identity_mismatch_stops", stopOf(wrongModel) == "provider_model_identity_error")
        val maskingFailure = JSONObject(repaired.toString())
        maskingFailure.put("raw_sensitive_absent_from_egress", false)
            .getJSONObject("masking_integrity").put("outbound_raw_absent", false)
        checks.put("repaired_turn_cannot_hide_masking_failure",
            stopOf(maskingFailure) == "masking_integrity_failure")
        val unauthorizedFailure = JSONObject(repaired.toString())
            .put("provider_turns", JSONArray()
                .put(refusal("UNKNOWN_TOKEN"))
                .put(acceptedProviderTurn())
                .put(JSONObject().put("provider_failure", "IOException")))
            .put("masked_wire", JSONArray()
                .put(JSONObject().put("http_status", 200))
                .put(JSONObject().put("http_status", 200))
                .put(JSONObject().put("http_status", 200)))
        checks.put("repaired_turn_cannot_hide_later_provider_failure",
            stopOf(unauthorizedFailure) == "provider_error")
        val unsafeEffects = JSONObject(repaired.toString())
            .put("postconditions", JSONObject(repaired.getJSONObject("postconditions").toString())
                .put("ledger_rows", 1))
        checks.put("repaired_turn_cannot_hide_unsafe_effect",
            stopOf(unsafeEffects) == "unauthorized_or_financially_unsafe_effect")
        val noReplacement = JSONObject(repaired.toString())
            .put("provider_turns", JSONArray().put(refusal("UNKNOWN_TOKEN")))
            .put("masked_wire", JSONArray().put(JSONObject().put("http_status", 200)))
        checks.put("repair_without_accepted_replacement_stops", stopOf(noReplacement) == "provider_error")


        return JSONObject()
            .put("result", if (checks.keys().asSequence().all { checks.getBoolean(it) }) "PASS" else "FAIL")
            .put("adjudication_version", S813_ADJUDICATION_VERSION)
            .put("checks", checks)
            .put("hosted_provider_calls", 0)
    }
    private fun reproductionRunNamePathSmoke(
        context: Context,
        attemptId: String,
        snapshotId: String,
        runName: String,
        caseIds: List<String>,
        knownCaseIds: Set<String>,
    ): JSONObject {
        val scratch = File(context.cacheDir, "s813-run-path-${java.util.UUID.randomUUID()}")
        check(scratch.mkdir()) { "could not create temporary run-path contract directory" }
        try {
            val relativePath = "$attemptId/$snapshotId/profile/$runName"
            val created = s813CreateRunDirectory(scratch, relativePath)
            val roundTrip = created.relativeTo(scratch.canonicalFile).invariantSeparatorsPath == relativePath
            val collisionRejected = runCatching { s813CreateRunDirectory(scratch, relativePath) }.isFailure
            val traversalRejected = runCatching {
                s813CreateRunDirectory(scratch, "$attemptId/$snapshotId/profile/../$runName")
            }.isFailure
            val missingRejected = runCatching { s813DiagnosticCaseIds(null, knownCaseIds) }.isFailure
            val duplicateRejected = runCatching {
                s813DiagnosticCaseIds("${caseIds.first()},${caseIds.first()}", knownCaseIds)
            }.isFailure
            val unknownRejected = runCatching {
                s813DiagnosticCaseIds("unknown-case", knownCaseIds)
            }.isFailure
            return JSONObject()
                .put("attempt_id", attemptId)
                .put("snapshot_id", snapshotId)
                .put("run_name", runName)
                .put("case_ids", JSONArray(caseIds))
                .put("round_trip", roundTrip)
                .put("path_collision_rejected", collisionRejected)
                .put("path_traversal_rejected", traversalRejected)
                .put("missing_case_ids_rejected", missingRejected)
                .put("duplicate_case_ids_rejected", duplicateRejected)
                .put("unknown_case_ids_rejected", unknownRejected)
        } finally {
            check(scratch.deleteRecursively()) { "could not remove temporary run-path contract directory" }
        }
    }


    private fun expectedEvent(amount: Long = 28_000, merchant: String? = "Highlands"): JSONObject = JSONObject()
        .put("capture_id", 111)
        .put("amount_vnd", amount)
        .put("merchant", merchant ?: JSONObject.NULL)
        .put("anchor_date", "2026-10-21")
        .put("anchor_time", "12:30")
        .put("status", "INTERPRETED")

    private fun accountSourceProvenanceDiagnostic(
        devFixture: JSONObject,
        baseFixture: JSONObject,
        runtimeProjection: JSONObject,
    ): JSONObject {
        val caseId = "a8-laya-02"
        val case = devFixture.getJSONArray("cases").let { rows ->
            (0 until rows.length()).map(rows::getJSONObject).single { it.getString("id") == caseId }
        }
        val caseContext = case.getJSONObject("context")
        val captureIds = caseContext.optJSONArray("captureIds") ?: JSONArray()
        val draftIds = caseContext.getJSONArray("draftIds")
        require(draftIds.length() == 1)
        val draftId = draftIds.getLong(0)
        val baseDraft = fixtureDraft(baseFixture, draftId)
        val override = requireNotNull(
            runtimeProjection.getJSONObject("draft_overrides_by_case").optJSONObject(caseId),
        ).getJSONObject(draftId.toString())
        val template = override.getString("description_template")
        val repetitions = override.getInt("repetitions")
        val projectedDescription = buildString {
            for (index in 1..repetitions) {
                append(String.format(java.util.Locale.ROOT, template, index))
            }
        }
        val categorySeeds = runtimeProjection.getJSONObject("category_seeds_by_case")
            .getJSONArray(caseId)
        val categoryNames = (0 until categorySeeds.length()).map {
            categorySeeds.getJSONObject(it).getString("name")
        }

        fun summarizeSource(text: String): JSONObject {
            val vault = SensitiveVault("s813-provenance-${UUID.randomUUID()}")
            vault.maskText(text)
            val shapes = vault.scanLeakShapes(text).map { it.wire }.sorted()
            return JSONObject()
                .put("source_characters", text.length)
                .put("mapped_value_shapes", JSONArray(shapes))
                .put("numeric_mapping_present", "numeric" in shapes)
        }

        val sources = linkedMapOf(
            "case_prompt" to summarizeSource(case.getString("prompt")),
            "base_draft_description" to summarizeSource(baseDraft.getString("description")),
            "oversize_projection_template" to summarizeSource(template),
            "oversize_projected_draft_description" to summarizeSource(projectedDescription),
            "seeded_bank_name" to summarizeSource("Synthetic bank"),
            "category_candidate_names" to summarizeSource(categoryNames.joinToString(" ")),
        )
        val mappedAccountSources = sources.filterValues {
            it.getBoolean("account_mapping_present")
        }.keys.sorted()
        return JSONObject()
            .put("result", "PASS")
            .put("case_id", caseId)
            .put("case_prompt_characters", case.getString("prompt").length)
            .put("capture_source_count", captureIds.length())
            .put("draft_ids", draftIds)
            .put("projected_draft_id", draftId)
            .put("oversize_projection_repetitions", repetitions)
            .put("oversize_projection_template_characters", template.length)
            .put("projected_draft_description_characters", projectedDescription.length)
            .put("sources", JSONObject(sources))
            .put("local_vault_account_mapping_sources", JSONArray(mappedAccountSources))
            .put("account_shape_class", "SensitiveVault ACCOUNT kind; no raw length or value retained")
            .put("raw_values_or_tokens_persisted", false)
            .put("hosted_provider_calls", 0)
    }

    private fun runSafeResponseLocationSmoke(
        context: Context,
        attemptId: String,
        snapshotId: String,
        runName: String?,
        caseIds: List<String>,
    ): JSONObject {
        val rawDirectory = s813CreateRunDirectory(
            context.filesDir,
            "$attemptId/debug/$snapshotId/preflight-privacy-smoke",
        )
        val rawPath = File(rawDirectory, "safe-response-location-smoke.json")
        check(!rawPath.exists()) { "refusing to overwrite retained TEST_ONLY response smoke evidence" }
        val caseReports = JSONArray()
        val rawReport = JSONObject()
            .put("TEST_ONLY", true)
            .put("capture_contract", S813DebugCapture.CONTRACT)
            .put("capture_mode", "synthetic_only_full_bodies")
            .put("attempt_id", attemptId)
            .put("snapshot_id", snapshotId)
            .put("run_name", runName ?: JSONObject.NULL)
            .put("case_ids", JSONArray(caseIds))
            .put("fixture_id", S813DebugCapture.FIXTURE_ID)
            .put("header_values_recorded", false)
            .put("credential_material_recorded", false)
            .put("real_user_content_recorded", false)
            .put("synthetic_response_bodies_recorded", true)
            .put("hosted_provider_calls", 0)
            .put("status", "IN_PROGRESS")
        fun persistRawReport() {
            rawReport.put("case_count", caseReports.length())
                .put("cases", caseReports)
                .put("updated_at", java.time.Instant.now().toString())
            rawPath.writeText(rawReport.toString(2))
        }
        persistRawReport()
        try {
            val vault = SensitiveVault("s813-response-location-${UUID.randomUUID()}")
        val accountTokenResult = runCatching {
            SensitiveVault.TOKEN_PATTERN.find(vault.maskText("account ending 7319"))?.value
                ?: error("controlled account token was not minted")
        }
        val accountToken = accountTokenResult.getOrNull()
        val responseBoundary = MaskedPromptBoundary(vault)
        val chatPolicy = HostedResponseMetadataPolicy(
            protocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            configuredModel = "deepseek-v4.1-flash",
            goUsageExtensions = true,
        )
        val nonGoPolicy = chatPolicy.copy(goUsageExtensions = false)
        fun scan(
            body: String,
            protocol: HostedProtocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            status: Int? = 200,
            policy: HostedResponseMetadataPolicy? = chatPolicy,
        ) = responseBoundary.scanInboundResponseJson(body, protocol, status, policy)
        fun accepted(
            body: String,
            protocol: HostedProtocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            policy: HostedResponseMetadataPolicy? = chatPolicy,
        ) = when (scan(body, protocol, policy = policy)) {
            HostedResponsePrivacyResult.Accepted,
            is HostedResponsePrivacyResult.AcceptedWithValidatedCounters,
            is HostedResponsePrivacyResult.AcceptedWithValidatedMetadata -> true
            else -> false
        }
        fun refused(
            body: String,
            protocol: HostedProtocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            policy: HostedResponseMetadataPolicy? = chatPolicy,
        ) = scan(body, protocol, policy = policy) is HostedResponsePrivacyResult.Refused
        fun refusal(
            body: String,
            protocol: HostedProtocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            policy: HostedResponseMetadataPolicy? = chatPolicy,
        ): HostedResponsePrivacyRefusal =
            (scan(body, protocol, policy = policy) as? HostedResponsePrivacyResult.Refused)?.refusal
                ?: error("controlled response was accepted")
        fun protocolError(
            body: String,
            protocol: HostedProtocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            policy: HostedResponseMetadataPolicy? = chatPolicy,
        ) = scan(body, protocol, policy = policy) as? HostedResponsePrivacyResult.ProtocolError
        fun recognizedCounters(
            body: String,
            protocol: HostedProtocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            policy: HostedResponseMetadataPolicy? = chatPolicy,
        ) = when (val result = scan(body, protocol, policy = policy)) {
            is HostedResponsePrivacyResult.AcceptedWithValidatedCounters -> result.matches
            is HostedResponsePrivacyResult.AcceptedWithValidatedMetadata -> result.counters
            else -> emptyList()
        }
        fun validatedMetadata(
            body: String,
            policy: HostedResponseMetadataPolicy = chatPolicy,
        ) = (scan(body, policy = policy) as? HostedResponsePrivacyResult.AcceptedWithValidatedMetadata)
            ?.metadata.orEmpty()

        val unicodeEscapeBody = """{"service_tier":"\u7319"}"""
        val tokenOnlyBody = accountToken?.let { JSONObject().put("service_tier", it).toString() }
        val decodedContentBody = JSONObject().put("choices", JSONArray().put(JSONObject().put(
            "message", JSONObject().put("content", "account ending 7319"),
        ))).toString()
        val decodedReasoningBody = JSONObject().put("choices", JSONArray().put(JSONObject().put(
            "message", JSONObject().put("reasoning_content", "7319"),
        ))).toString()
        val rootMetadataBody =
            """{"id":"chatcmpl-7319","object":"chat.completion","created":1750000000,"model":"deepseek-v4.1-flash"}"""
        val routeAccountingBody =
            """{"usage":{"opencode":{"allowance":7319.5,"list_cost":7319}}}"""
        val malformedMetadataBodies = listOf(
            """{"id":7319}""",
            """{"id":""}""",
            """{"id":"caf\u00e9"}""",
            """{"created":"1750000000"}""",
            """{"created":-1}""",
            """{"created":1.5}""",
            """{"object":"chat.completion.chunk"}""",
            """{"model":7319}""",
        )
        val malformedAccountingBodies = listOf(
            """{"usage":{"opencode":{"allowance":"7319"}}}""",
            """{"usage":{"opencode":{"allowance":-7319}}}""",
            """{"usage":{"opencode":{"list_cost":1e999}}}""",
            """{"usage":{"opencode":{"list_cost":true}}}""",
        )
        val metadataInContentBody =
            """{"choices":[{"message":{"content":"7319"}}]}"""
        val metadataInReasoningBody =
            """{"choices":[{"message":{"reasoning_content":"7319"}}]}"""
        val metadataInToolArgumentsBody =
            """{"choices":[{"message":{"tool_calls":[{"function":{"arguments":"{\"amount\":7319}"}}]}}]}"""
        val metadataInSensitiveKeyBody = """{"7319":"safe"}"""
        val nestedMetadataLookalikeBody =
            """{"choices":[{"message":{"id":"7319","model":"deepseek-v4.1-flash-7319"}}]}"""
        val modelEchoMismatchBody = """{"model":"other-model-7319"}"""
        val knownCounterBody = """{"usage":{"total_tokens":7319}}"""
        val unknownUsageCounterBody = """{"usage":{"unlisted":7319}}"""
        val stringUsageCounterBody = """{"usage":{"total_tokens":"7319"}}"""
        val safeUsageIdentifierBody =
            """{"usage":{"vendor_metrics":{"model_usage":7319}}}"""
        val unsafeUsageIdentifierBody =
            """{"usage":{"vendor_metrics2":{"model_usage":7319}}}"""
        val modelIdentifierBody =
            """{"choices":[{"message":{"model_usage":7319}}]}"""
        val otherProtocolResponsesUsageBody = """{"usage":{"input_tokens":7319}}"""
        val otherProtocolGeminiUsageBody = """{"usageMetadata":{"promptTokenCount":7319}}"""
        val invalidUsageCounterBodies = listOf(
            """{"usage":{"total_tokens":"12"}}""",
            """{"usage":{"total_tokens":-1}}""",
            """{"usage":{"total_tokens":1.5}}""",
            """{"usage":{"total_tokens":true}}""",
            """{"usage":{"total_tokens":null}}""",
            """{"usage":"safe"}""",
            """{"usage":{"completion_tokens_details":"safe"}}""",
        )
        val knownCounterModelContentBody =
            """{"usage":{"total_tokens":7319},"choices":[{"message":{"content":"7319"}}]}"""
        val knownCounterReasoningBody =
            """{"usage":{"total_tokens":7319},"choices":[{"message":{"reasoning_content":"7319"}}]}"""
        val knownCounterToolBody =
            """{"usage":{"total_tokens":7319},"choices":[{"message":{"tool_calls":[{"function":{"arguments":"{\"amount\":7319}"}}]}}]}"""
        val knownCounterKeyBody = """{"usage":{"total_tokens":7319},"7319":"safe"}"""
        val unknownFieldBody = JSONObject().put("unlisted", JSONObject().put("payload", "7319")).toString()
        val unknownNameBody = JSONObject().put("7319", "safe").toString()
        val duplicateKeyBody = """{"id":"safe","id":"safe"}"""
        val decodedDuplicateKeyBody = """{"id":"safe","\u0069d":"safe"}"""
        val invalidJsonBody = """{"id":}"""
        val unsupportedRootBody = """["safe"]"""
        val deepJsonBody = """{"nested":${"[".repeat(65)}"safe"${"]".repeat(65)}}"""
        val foreignTokenBody = """{"token":"⟦V-OTHER1234-9999⟧"}"""
        val unknownTokenBody = """{"token":"⟦UNKNOWN-TOKEN⟧"}"""
        fun validUsagePaths(body: String, protocol: HostedProtocol, expected: Set<String>): Boolean {
            val matches = recognizedCounters(body, protocol)
            return matches.isNotEmpty() && matches.map { it.safeLocation }.toSet() == expected &&
                matches.all {
                    it.nodeType == "NUMBER" && it.recognizedCounter && !it.knownOtherProtocol &&
                        it.sensitiveShape == "numeric"
                }
        }
        val knownProtocolUsagePaths = listOf(
            Triple(
                HostedProtocol.OPENAI_CHAT_COMPLETIONS,
                """{"usage":{"prompt_tokens":7319,"completion_tokens":7319,"total_tokens":7319,"completion_tokens_details":{"reasoning_tokens":7319},"prompt_tokens_details":{"cached_tokens":7319}}}""",
                setOf(
                    "response_body.usage.prompt_tokens",
                    "response_body.usage.completion_tokens",
                    "response_body.usage.total_tokens",
                    "response_body.usage.completion_tokens_details.reasoning_tokens",
                    "response_body.usage.prompt_tokens_details.cached_tokens",
                ),
            ),
            Triple(
                HostedProtocol.OPENAI_RESPONSES,
                """{"usage":{"input_tokens":7319,"output_tokens":7319,"total_tokens":7319,"output_tokens_details":{"reasoning_tokens":7319},"input_tokens_details":{"cached_tokens":7319}}}""",
                setOf(
                    "response_body.usage.input_tokens",
                    "response_body.usage.output_tokens",
                    "response_body.usage.total_tokens",
                    "response_body.usage.output_tokens_details.reasoning_tokens",
                    "response_body.usage.input_tokens_details.cached_tokens",
                ),
            ),
            Triple(
                HostedProtocol.GEMINI_GENERATE_CONTENT,
                """{"usageMetadata":{"promptTokenCount":7319,"candidatesTokenCount":7319,"thoughtsTokenCount":7319,"cachedContentTokenCount":7319}}""",
                setOf(
                    "response_body.usageMetadata.promptTokenCount",
                    "response_body.usageMetadata.candidatesTokenCount",
                    "response_body.usageMetadata.thoughtsTokenCount",
                    "response_body.usageMetadata.cachedContentTokenCount",
                ),
            ),
        )
        val sensitiveUsageKeyBody = """{"usage":{"account ending 7319":7319}}"""
        fun responseInput(
            body: String,
            protocol: HostedProtocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            policy: HostedResponseMetadataPolicy? = chatPolicy,
        ) = Triple(protocol, body, policy)
        val semanticSummaryCases = JSONObject()
        fun stackTrace(failure: Throwable): String {
            val trace = java.io.StringWriter()
            failure.printStackTrace(java.io.PrintWriter(trace))
            return trace.toString()
        }
        fun exceptionEvidence(failure: Throwable) = JSONObject()
            .put("exception_class", failure.javaClass.name)
            .put("message", failure.message ?: JSONObject.NULL)
            .put("stack_trace", stackTrace(failure))
        fun guardEvidence(result: HostedResponsePrivacyResult): JSONObject = when (result) {
            HostedResponsePrivacyResult.Accepted -> JSONObject()
                .put("decision", "ACCEPTED")
                .put("outcome", "Accepted")
            is HostedResponsePrivacyResult.AcceptedWithValidatedCounters -> JSONObject()
                .put("decision", "ACCEPTED")
                .put("outcome", "AcceptedWithValidatedCounters")
                .put("recognized_counters", JSONArray().also { rows ->
                    result.matches.forEach { match ->
                        rows.put(JSONObject()
                            .put("sensitive_shape", match.sensitiveShape)
                            .put("safe_location", match.safeLocation)
                            .put("node_type", match.nodeType)
                            .put("recognized_counter", match.recognizedCounter)
                            .put("known_other_protocol", match.knownOtherProtocol))
                    }
                })
            is HostedResponsePrivacyResult.AcceptedWithValidatedMetadata -> JSONObject()
                .put("decision", "ACCEPTED")
                .put("outcome", "AcceptedWithValidatedMetadata")
                .put("validated_metadata", JSONArray().also { rows ->
                    result.metadata.forEach { entry ->
                        rows.put(JSONObject()
                            .put("kind", entry.kind.name)
                            .put("safe_location", entry.safeLocation)
                            .put("node_type", entry.nodeType))
                    }
                })
                .put("recognized_counters", JSONArray().also { rows ->
                    result.counters.forEach { match ->
                        rows.put(JSONObject()
                            .put("sensitive_shape", match.sensitiveShape)
                            .put("safe_location", match.safeLocation)
                            .put("node_type", match.nodeType)
                            .put("recognized_counter", match.recognizedCounter)
                            .put("known_other_protocol", match.knownOtherProtocol))
                    }
                })
            is HostedResponsePrivacyResult.Refused -> JSONObject()
                .put("decision", "REFUSED")
                .put("outcome", "Refused")
                .put("refusal", JSONObject()
                    .put("reason", result.refusal.reason.name)
                    .put("sensitive_shape", result.refusal.sensitiveShape ?: JSONObject.NULL)
                    .put("safe_location", result.refusal.safeLocation)
                    .put("node_type", result.refusal.nodeType)
                    .put("recognized_counter", result.refusal.recognizedCounter)
                    .put("known_other_protocol", result.refusal.knownOtherProtocol))
            is HostedResponsePrivacyResult.ProtocolError -> JSONObject()
                .put("decision", "PROTOCOL_ERROR")
                .put("outcome", "ProtocolError")
                .put("protocol_error", JSONObject()
                    .put("reason", result.reason.name)
                    .put("safe_location", result.safeLocation)
                    .put("node_type", result.nodeType)
                    .put("recognized_counter", result.recognizedCounter)
                    .put("known_other_protocol", result.knownOtherProtocol))
        }
        fun semanticCase(
            name: String,
            inputs: List<Triple<HostedProtocol, String, HostedResponseMetadataPolicy?>>,
            expectedDecision: String,
            expectedOutcome: String,
            check: () -> Boolean,
        ): Boolean {
            val rawInputs = JSONArray()
            val actualInputs = JSONArray()
            val errors = JSONArray()
            var decisionsMatch = true
            inputs.forEachIndexed { index, input ->
                var guard: HostedResponsePrivacyResult? = null
                var diagnostic: SafeInboundResponseDiagnostics? = null
                val inputErrors = JSONArray()
                try {
                    guard = scan(input.second, input.first, policy = input.third)
                } catch (failure: Throwable) {
                    inputErrors.put(exceptionEvidence(failure))
                    errors.put(exceptionEvidence(failure))
                }
                try {
                    diagnostic = safeInboundResponseDiagnostics(
                        input.first, vault, input.second, httpStatus = 200,
                    )
                } catch (failure: Throwable) {
                    inputErrors.put(exceptionEvidence(failure))
                    errors.put(exceptionEvidence(failure))
                }
                val expectedChannel = JSONObject()
                    .put("protocol", input.first.name)
                    .put("stage", "inbound_response_json")
                val actualGuard = guard?.let(::guardEvidence)
                val actualDecision = actualGuard?.optString("decision")
                val actualOutcome = actualGuard?.optString("outcome")
                if (actualDecision != expectedDecision || actualOutcome != expectedOutcome) {
                    decisionsMatch = false
                }
                val fieldRows = JSONArray().also { rows ->
                    diagnostic?.fields?.forEach { field ->
                        rows.put(JSONObject()
                            .put("shape", field.shape)
                            .put("location", field.location)
                            .put("category", field.category)
                            .put("node_type", field.nodeType)
                            .put("recognized_counter", field.recognizedCounter)
                            .put("known_other_protocol", field.knownOtherProtocol))
                    }
                }
                val inputActual = JSONObject()
                    .put("input_index", index)
                    .put("channel", expectedChannel)
                    .put("guard_result", actualGuard ?: JSONObject.NULL)
                    .put("observed_channels", JSONArray(diagnostic?.categories ?: emptyList<String>()))
                    .put("observed_locations", JSONArray(diagnostic?.locations ?: emptyList<String>()))
                    .put("observed_fields", fieldRows)
                    .put("errors", inputErrors)
                actualInputs.put(inputActual)
                rawInputs.put(JSONObject()
                    .put("TEST_ONLY", true)
                    .put("input_index", index)
                    .put("protocol", input.first.name)
                    .put("http_status", 200)
                    .put("expected_channel", expectedChannel)
                    .put("expected_guard_decision", expectedDecision)
                    .put("expected_outcome", expectedOutcome)
                    .put("response_body", input.second)
                    .put("actual", inputActual))
            }
            var assertionResult = false
            try {
                assertionResult = check()
            } catch (failure: Throwable) {
                errors.put(exceptionEvidence(failure))
            }
            val passed = decisionsMatch && assertionResult && errors.length() == 0
            val expected = JSONObject()
                .put("channel", "inbound_response_json")
                .put("guard_decision", expectedDecision)
                .put("outcome", expectedOutcome)
            val actual = JSONObject()
                .put("guard_decisions", JSONArray().also { rows ->
                    for (index in 0 until actualInputs.length()) {
                        rows.put(actualInputs.getJSONObject(index).optJSONObject("guard_result")
                            ?: JSONObject().put("missing", true))
                    }
                })
                .put("input_channels", actualInputs)
                .put("semantic_assertion_passed", assertionResult)
            val caseReport = JSONObject()
                .put("TEST_ONLY", true)
                .put("case_id", name)
                .put("expected", expected)
                .put("actual", actual)
                .put("passed", passed)
                .put("errors", errors)
                .put("call_effect_trace", JSONObject()
                    .put("hosted_provider_calls", 0)
                    .put("tool_calls", JSONArray())
                    .put("effects", JSONArray()))
                .put("input_responses", rawInputs)
            caseReports.put(caseReport)
            semanticSummaryCases.put(name, JSONObject()
                .put("case_id", name)
                .put("expected", expected)
                .put("actual", actual)
                .put("passed", passed)
                .put("errors", errors))
            rawReport.put("status", "IN_PROGRESS")
                .put("completed_case_count", caseReports.length())
            persistRawReport()
            return passed
        }
        val semanticCases = linkedMapOf(
            "unicode_escape_is_not_decoded_match" to semanticCase(
                "unicode_escape_is_not_decoded_match", listOf(responseInput(unicodeEscapeBody)),
                "ACCEPTED", "Accepted",
            ) { accepted(unicodeEscapeBody) },
            "decoded_content_refused" to semanticCase(
                "decoded_content_refused", listOf(responseInput(decodedContentBody)),
                "REFUSED", "Refused",
            ) { refused(decodedContentBody) },
            "decoded_reasoning_accepted" to semanticCase(
                "decoded_reasoning_accepted", listOf(responseInput(decodedReasoningBody)),
                "ACCEPTED", "Accepted",
            ) { accepted(decodedReasoningBody) },
            "root_metadata_schema_validated_accepted" to semanticCase(
                "root_metadata_schema_validated_accepted", listOf(responseInput(rootMetadataBody)),
                "ACCEPTED", "AcceptedWithValidatedMetadata",
            ) {
                validatedMetadata(rootMetadataBody).map { it.kind to it.safeLocation }.toSet() ==
                    setOf(
                        HostedResponseMetadataKind.PROVIDER_ID to "response_body.id",
                        HostedResponseMetadataKind.OBJECT_DISCRIMINATOR to "response_body.object",
                        HostedResponseMetadataKind.TIMESTAMP to "response_body.created",
                        HostedResponseMetadataKind.MODEL_ECHO to "response_body.model",
                    ) &&
                    validatedMetadata(rootMetadataBody).all {
                        it.nodeType == "STRING" || it.nodeType == "NUMBER"
                    }
            },
            "route_accounting_amounts_accepted" to semanticCase(
                "route_accounting_amounts_accepted", listOf(responseInput(routeAccountingBody)),
                "ACCEPTED", "AcceptedWithValidatedMetadata",
            ) {
                validatedMetadata(routeAccountingBody)
                    .filter { it.kind == HostedResponseMetadataKind.GO_USAGE_AMOUNT }
                    .let { entries ->
                        entries.map { it.safeLocation }.toSet() == setOf(
                            "response_body.usage.opencode.allowance",
                            "response_body.usage.opencode.list_cost",
                        ) && entries.all { it.nodeType == "NUMBER" }
                    }
            },
            "malformed_root_metadata_protocol_error" to semanticCase(
                "malformed_root_metadata_protocol_error",
                malformedMetadataBodies.map { responseInput(it) },
                "PROTOCOL_ERROR", "ProtocolError",
            ) {
                malformedMetadataBodies.all {
                    protocolError(it)?.reason == HostedResponseProtocolErrorReason.INVALID_PROTOCOL_METADATA
                }
            },
            "malformed_route_accounting_protocol_error" to semanticCase(
                "malformed_route_accounting_protocol_error",
                malformedAccountingBodies.map { responseInput(it) },
                "PROTOCOL_ERROR", "ProtocolError",
            ) {
                malformedAccountingBodies.all {
                    protocolError(it)?.reason == HostedResponseProtocolErrorReason.INVALID_PROTOCOL_METADATA
                }
            },
            "known_numeric_usage_counter_accepted" to semanticCase(
                "known_numeric_usage_counter_accepted", listOf(responseInput(knownCounterBody)),
                "ACCEPTED", "AcceptedWithValidatedCounters",
            ) {
                validUsagePaths(
                    knownCounterBody,
                    HostedProtocol.OPENAI_CHAT_COMPLETIONS,
                    setOf("response_body.usage.total_tokens"),
                )
            },
            "provider_specific_usage_paths_recognized" to semanticCase(
                "provider_specific_usage_paths_recognized",
                knownProtocolUsagePaths.map { (protocol, body, _) -> responseInput(body, protocol) },
                "ACCEPTED", "AcceptedWithValidatedCounters",
            ) {
                knownProtocolUsagePaths.all { (protocol, body, expectedPaths) ->
                    validUsagePaths(body, protocol, expectedPaths)
                }
            },
            "unknown_usage_counter_refused" to semanticCase(
                "unknown_usage_counter_refused", listOf(responseInput(unknownUsageCounterBody)),
                "REFUSED", "Refused",
            ) { refused(unknownUsageCounterBody) },
            "safe_usage_identifier_path_exposed" to semanticCase(
                "safe_usage_identifier_path_exposed", listOf(responseInput(safeUsageIdentifierBody)),
                "REFUSED", "Refused",
            ) {
                val value = refusal(safeUsageIdentifierBody)
                value.safeLocation == "response_body.usage.vendor_metrics.model_usage" &&
                    value.nodeType == "NUMBER" &&
                    !value.knownOtherProtocol
            },
            "unsafe_usage_identifier_collapsed" to semanticCase(
                "unsafe_usage_identifier_collapsed", listOf(responseInput(unsafeUsageIdentifierBody)),
                "REFUSED", "Refused",
            ) {
                val value = refusal(unsafeUsageIdentifierBody)
                value.safeLocation == "response_body.usage.other_field.model_usage" &&
                    !value.knownOtherProtocol
            },
            "model_unknown_identifier_stays_collapsed" to semanticCase(
                "model_unknown_identifier_stays_collapsed", listOf(responseInput(modelIdentifierBody)),
                "REFUSED", "Refused",
            ) {
                val value = refusal(modelIdentifierBody)
                value.safeLocation == "response_body.choices[].message.other_field" &&
                    !value.knownOtherProtocol
            },
            "known_other_responses_usage_path_identified" to semanticCase(
                "known_other_responses_usage_path_identified",
                listOf(responseInput(otherProtocolResponsesUsageBody)),
                "REFUSED", "Refused",
            ) {
                val value = refusal(otherProtocolResponsesUsageBody)
                value.safeLocation == "response_body.usage.input_tokens" &&
                    value.nodeType == "NUMBER" &&
                    value.knownOtherProtocol
            },
            "known_other_gemini_usage_path_identified" to semanticCase(
                "known_other_gemini_usage_path_identified", listOf(responseInput(otherProtocolGeminiUsageBody)),
                "REFUSED", "Refused",
            ) {
                val value = refusal(otherProtocolGeminiUsageBody)
                value.safeLocation == "response_body.usageMetadata.promptTokenCount" &&
                    value.nodeType == "NUMBER" &&
                    value.knownOtherProtocol
            },
            "sensitive_usage_key_name_not_disclosed" to semanticCase(
                "sensitive_usage_key_name_not_disclosed",
                listOf(responseInput(sensitiveUsageKeyBody)),
                "REFUSED", "Refused",
            ) {
                refusal(sensitiveUsageKeyBody).safeLocation == "response_body.usage.field_name"
            },
            "string_sensitive_usage_counter_refused" to semanticCase(
                "string_sensitive_usage_counter_refused", listOf(responseInput(stringUsageCounterBody)),
                "REFUSED", "Refused",
            ) { refused(stringUsageCounterBody) },
            "malformed_usage_counter_protocol_error" to semanticCase(
                "malformed_usage_counter_protocol_error",
                invalidUsageCounterBodies.map { responseInput(it) },
                "PROTOCOL_ERROR", "ProtocolError",
            ) {
                invalidUsageCounterBodies.all {
                    protocolError(it)?.reason == HostedResponseProtocolErrorReason.INVALID_USAGE_COUNTER
                }
            },
            "known_counter_model_content_still_refused" to semanticCase(
                "known_counter_model_content_still_refused", listOf(responseInput(knownCounterModelContentBody)),
                "REFUSED", "Refused",
            ) { refused(knownCounterModelContentBody) },
            "known_counter_reasoning_accepted_with_counters" to semanticCase(
                "known_counter_reasoning_accepted_with_counters",
                listOf(responseInput(knownCounterReasoningBody)),
                "ACCEPTED", "AcceptedWithValidatedCounters",
            ) {
                validUsagePaths(
                    knownCounterReasoningBody,
                    HostedProtocol.OPENAI_CHAT_COMPLETIONS,
                    setOf("response_body.usage.total_tokens"),
                )
            },
            "known_counter_tool_arguments_still_refused" to semanticCase(
                "known_counter_tool_arguments_still_refused", listOf(responseInput(knownCounterToolBody)),
                "REFUSED", "Refused",
            ) { refused(knownCounterToolBody) },
            "known_counter_sensitive_key_still_refused" to semanticCase(
                "known_counter_sensitive_key_still_refused", listOf(responseInput(knownCounterKeyBody)),
                "REFUSED", "Refused",
            ) { refused(knownCounterKeyBody) },
            "metadata_value_in_model_content_refused" to semanticCase(
                "metadata_value_in_model_content_refused", listOf(responseInput(metadataInContentBody)),
                "REFUSED", "Refused",
            ) { refused(metadataInContentBody) },
            "metadata_value_in_model_reasoning_accepted" to semanticCase(
                "metadata_value_in_model_reasoning_accepted", listOf(responseInput(metadataInReasoningBody)),
                "ACCEPTED", "Accepted",
            ) { accepted(metadataInReasoningBody) },
            "metadata_value_in_tool_arguments_refused" to semanticCase(
                "metadata_value_in_tool_arguments_refused", listOf(responseInput(metadataInToolArgumentsBody)),
                "REFUSED", "Refused",
            ) { refused(metadataInToolArgumentsBody) },
            "metadata_value_in_sensitive_key_refused" to semanticCase(
                "metadata_value_in_sensitive_key_refused", listOf(responseInput(metadataInSensitiveKeyBody)),
                "REFUSED", "Refused",
            ) { refused(metadataInSensitiveKeyBody) },
            "nested_metadata_lookalike_refused" to semanticCase(
                "nested_metadata_lookalike_refused", listOf(responseInput(nestedMetadataLookalikeBody)),
                "REFUSED", "Refused",
            ) { refused(nestedMetadataLookalikeBody) },
            "metadata_model_echo_mismatch_refused" to semanticCase(
                "metadata_model_echo_mismatch_refused", listOf(responseInput(modelEchoMismatchBody)),
                "REFUSED", "Refused",
            ) { refused(modelEchoMismatchBody) },
            "non_go_route_accounting_refused" to semanticCase(
                "non_go_route_accounting_refused",
                listOf(responseInput(routeAccountingBody, policy = nonGoPolicy)),
                "REFUSED", "Refused",
            ) { refused(routeAccountingBody, policy = nonGoPolicy) },
            "unknown_field_value_refused" to semanticCase(
                "unknown_field_value_refused", listOf(responseInput(unknownFieldBody)),
                "REFUSED", "Refused",
            ) { refused(unknownFieldBody) },
            "unknown_field_name_refused" to semanticCase(
                "unknown_field_name_refused", listOf(responseInput(unknownNameBody)),
                "REFUSED", "Refused",
            ) { refused(unknownNameBody) },
            "duplicate_key_refused" to semanticCase(
                "duplicate_key_refused", listOf(responseInput("""{"id":"7319","id":"safe"}""")),
                "REFUSED", "Refused",
            ) { refused("""{"id":"7319","id":"safe"}""") },
            "decoded_duplicate_key_refused" to semanticCase(
                "decoded_duplicate_key_refused", listOf(responseInput(decodedDuplicateKeyBody)),
                "REFUSED", "Refused",
            ) { refused(decodedDuplicateKeyBody) },
            "invalid_json_refused" to semanticCase(
                "invalid_json_refused", listOf(responseInput(invalidJsonBody)),
                "REFUSED", "Refused",
            ) { refused(invalidJsonBody) },
            "unsupported_root_refused" to semanticCase(
                "unsupported_root_refused", listOf(responseInput(unsupportedRootBody)),
                "REFUSED", "Refused",
            ) { refused(unsupportedRootBody) },
            "masked_token_only_accepted" to semanticCase(
                "masked_token_only_accepted", tokenOnlyBody?.let { listOf(responseInput(it)) } ?: emptyList(),
                "ACCEPTED", "Accepted",
            ) {
                val body = tokenOnlyBody ?: accountTokenResult.getOrThrow()
                accepted(body)
            },
            "booleans_and_null_accepted" to semanticCase(
                "booleans_and_null_accepted",
                listOf(responseInput("""{"flag":true,"nothing":null}""")),
                "ACCEPTED", "Accepted",
            ) { accepted("""{"flag":true,"nothing":null}""") },
            "foreign_token_refused" to semanticCase(
                "foreign_token_refused", listOf(responseInput(foreignTokenBody)),
                "REFUSED", "Refused",
            ) { refused(foreignTokenBody) },
            "unknown_token_refused" to semanticCase(
                "unknown_token_refused", listOf(responseInput(unknownTokenBody)),
                "REFUSED", "Refused",
            ) { refused(unknownTokenBody) },
        )
        val failedSemanticCases = semanticCases.filterValues { !it }.keys.toList()
        val allSemanticCasesPassed = failedSemanticCases.isEmpty()
        rawReport.put("status", "COMPLETE")
            .put("aggregate_result", if (allSemanticCasesPassed) "PASS" else "FAIL")
            .put("failed_case_ids", JSONArray(failedSemanticCases))
            .put("response_json_semantics", semanticSummaryCases)
        persistRawReport()

        val metadata = safeInboundResponseDiagnostics(
            HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            vault,
            JSONObject()
                .put("id", "7319")
                .put("created", 7319)
                .put("model", "local-test-model-7319")
                .put("usage", JSONObject().put("total_tokens", 7319))
                .put("choices", JSONArray().put(
                    JSONObject().put("message", JSONObject().put("content", "safe")),
                ))
                .toString(),
            httpStatus = 200,
        )
        val modelContent = safeInboundResponseDiagnostics(
            HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            vault,
            JSONObject().put(
                "choices",
                JSONArray().put(JSONObject().put(
                    "message",
                    JSONObject().put("content", "account ending 7319"),
                )),
            ).toString(),
        )
        val modelReasoning = safeInboundResponseDiagnostics(
            HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            vault,
            JSONObject().put(
                "choices",
                JSONArray().put(JSONObject().put(
                    "message",
                    JSONObject().put("reasoning_content", "account ending 7319"),
                )),
            ).toString(),
        )
        val safeUsageIdentifierDiagnostics = safeInboundResponseDiagnostics(
            HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            vault,
            """{"usage":{"vendor_metrics":{"model_usage":7319}}}""",
            httpStatus = 200,
        )
        val unsafeUsageIdentifierDiagnostics = safeInboundResponseDiagnostics(
            HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            vault,
            """{"usage":{"vendor_metrics2":{"model_usage":7319}}}""",
            httpStatus = 200,
        )
        val knownOtherResponsesDiagnostics = safeInboundResponseDiagnostics(
            HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            vault,
            """{"usage":{"input_tokens":7319}}""",
            httpStatus = 200,
        )
        val knownOtherGeminiDiagnostics = safeInboundResponseDiagnostics(
            HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            vault,
            """{"usageMetadata":{"promptTokenCount":7319}}""",
            httpStatus = 200,
        )
        val modelUnknownIdentifierDiagnostics = safeInboundResponseDiagnostics(
            HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            vault,
            """{"choices":[{"message":{"model_usage":7319}}]}""",
            httpStatus = 200,
        )
        val safeUsageIdentifierRefusal = refusal(safeUsageIdentifierBody)
        val unsafeUsageIdentifierRefusal = refusal(unsafeUsageIdentifierBody)
        val modelIdentifierRefusal = refusal(modelIdentifierBody)
        val otherProtocolResponsesUsageRefusal = refusal(otherProtocolResponsesUsageBody)
        val otherProtocolGeminiUsageRefusal = refusal(otherProtocolGeminiUsageBody)
        val sensitiveUsageKeyRefusal = refusal(sensitiveUsageKeyBody)
        val decodedRefusal = refusal(decodedContentBody)
        val unknownUsageRefusal = refusal(unknownUsageCounterBody)
        val duplicateRefusal = refusal(duplicateKeyBody)
        val invalidRefusal = refusal(invalidJsonBody)
        val unsupportedRootRefusal = refusal(unsupportedRootBody)
        val depthRefusal = refusal(deepJsonBody)
        val tokenRefusal = refusal(unknownTokenBody)
        val invalidUsageError = protocolError(invalidUsageCounterBodies.first())
            ?: error("malformed counter was accepted without a protocol error")
        val validatedCounterMatches = recognizedCounters(knownCounterBody)
        val invalidMetadataError = protocolError(malformedMetadataBodies.first())
            ?: error("malformed root metadata was accepted without a protocol error")
        val invalidAccountingError = protocolError(malformedAccountingBodies.first())
            ?: error("malformed route accounting was accepted without a protocol error")
        val rootMetadataValidated = validatedMetadata(rootMetadataBody)
        val accountingValidated = validatedMetadata(routeAccountingBody)
            .filter { it.kind == HostedResponseMetadataKind.GO_USAGE_AMOUNT }
        val nonGoAccountingRefusal = refusal(routeAccountingBody, policy = nonGoPolicy)
        val nestedMetadataRefusal = refusal(nestedMetadataLookalikeBody)
        val contentCollisionRefusal = refusal(metadataInContentBody)
        assertEquals(
            HostedResponseProtocolErrorReason.INVALID_PROTOCOL_METADATA,
            invalidMetadataError.reason,
        )
        assertEquals("response_body.id", invalidMetadataError.safeLocation)
        assertEquals("NUMBER", invalidMetadataError.nodeType)
        assertEquals(
            HostedResponseProtocolErrorReason.INVALID_PROTOCOL_METADATA,
            invalidAccountingError.reason,
        )
        assertEquals("response_body.usage.opencode.allowance", invalidAccountingError.safeLocation)
        assertEquals("STRING", invalidAccountingError.nodeType)
        assertEquals("response_body.usage.opencode.allowance", nonGoAccountingRefusal.safeLocation)
        assertEquals("response_body.choices[].message.id", nestedMetadataRefusal.safeLocation)
        assertEquals("response_body.choices[].message.content", contentCollisionRefusal.safeLocation)
        assertEquals(HostedResponsePrivacyRefusalReason.DECODED_SENSITIVE_MATCH, decodedRefusal.reason)
        assertEquals("numeric", decodedRefusal.sensitiveShape)
        assertEquals("response_body.choices[].message.content", decodedRefusal.safeLocation)
        assertEquals(HostedResponsePrivacyRefusalReason.DUPLICATE_KEY, duplicateRefusal.reason)
        assertEquals(HostedResponsePrivacyRefusalReason.INVALID_JSON, invalidRefusal.reason)
        assertEquals(HostedResponsePrivacyRefusalReason.UNSUPPORTED_ROOT, unsupportedRootRefusal.reason)
        assertEquals(HostedResponsePrivacyRefusalReason.DEPTH_LIMIT, depthRefusal.reason)
        assertEquals(HostedResponsePrivacyRefusalReason.UNKNOWN_TOKEN, tokenRefusal.reason)
        fun refusalEvidence(
            reason: String,
            kind: String?,
            location: String,
            nodeType: String,
            recognizedCounter: Boolean = false,
            knownOtherProtocol: Boolean = false,
        ) = JSONObject()
            .put("reason", reason)
            .put("sensitive_shape", kind ?: JSONObject.NULL)
            .put("safe_location", location)
            .put("node_type", nodeType)
            .put("recognized_counter", recognizedCounter)
            .put("known_other_protocol", knownOtherProtocol)
        val refusalReasons = JSONObject()
            .put(
                "decoded_sensitive_match",
                refusalEvidence(
                    decodedRefusal.reason.name,
                    decodedRefusal.sensitiveShape,
                    decodedRefusal.safeLocation,
                    decodedRefusal.nodeType,
                    decodedRefusal.recognizedCounter,
                ),
            )
            .put(
                "unknown_usage_counter",
                refusalEvidence(
                    unknownUsageRefusal.reason.name,
                    unknownUsageRefusal.sensitiveShape,
                    unknownUsageRefusal.safeLocation,
                    unknownUsageRefusal.nodeType,
                    unknownUsageRefusal.recognizedCounter,
                ),
            )
            .put(
                "safe_usage_identifier",
                refusalEvidence(
                    safeUsageIdentifierRefusal.reason.name,
                    safeUsageIdentifierRefusal.sensitiveShape,
                    safeUsageIdentifierRefusal.safeLocation,
                    safeUsageIdentifierRefusal.nodeType,
                    safeUsageIdentifierRefusal.recognizedCounter,
                    safeUsageIdentifierRefusal.knownOtherProtocol,
                ),
            )
            .put(
                "unsafe_usage_identifier",
                refusalEvidence(
                    unsafeUsageIdentifierRefusal.reason.name,
                    unsafeUsageIdentifierRefusal.sensitiveShape,
                    unsafeUsageIdentifierRefusal.safeLocation,
                    unsafeUsageIdentifierRefusal.nodeType,
                    unsafeUsageIdentifierRefusal.recognizedCounter,
                    unsafeUsageIdentifierRefusal.knownOtherProtocol,
                ),
            )
            .put(
                "model_unknown_identifier",
                refusalEvidence(
                    modelIdentifierRefusal.reason.name,
                    modelIdentifierRefusal.sensitiveShape,
                    modelIdentifierRefusal.safeLocation,
                    modelIdentifierRefusal.nodeType,
                    modelIdentifierRefusal.recognizedCounter,
                    modelIdentifierRefusal.knownOtherProtocol,
                ),
            )
            .put(
                "known_other_responses_usage",
                refusalEvidence(
                    otherProtocolResponsesUsageRefusal.reason.name,
                    otherProtocolResponsesUsageRefusal.sensitiveShape,
                    otherProtocolResponsesUsageRefusal.safeLocation,
                    otherProtocolResponsesUsageRefusal.nodeType,
                    otherProtocolResponsesUsageRefusal.recognizedCounter,
                    otherProtocolResponsesUsageRefusal.knownOtherProtocol,
                ),
            )
            .put(
                "known_other_gemini_usage",
                refusalEvidence(
                    otherProtocolGeminiUsageRefusal.reason.name,
                    otherProtocolGeminiUsageRefusal.sensitiveShape,
                    otherProtocolGeminiUsageRefusal.safeLocation,
                    otherProtocolGeminiUsageRefusal.nodeType,
                    otherProtocolGeminiUsageRefusal.recognizedCounter,
                    otherProtocolGeminiUsageRefusal.knownOtherProtocol,
                ),
            )
            .put(
                "sensitive_usage_key",
                refusalEvidence(
                    sensitiveUsageKeyRefusal.reason.name,
                    sensitiveUsageKeyRefusal.sensitiveShape,
                    sensitiveUsageKeyRefusal.safeLocation,
                    sensitiveUsageKeyRefusal.nodeType,
                    sensitiveUsageKeyRefusal.recognizedCounter,
                    sensitiveUsageKeyRefusal.knownOtherProtocol,
                ),
            )
            .put(
                "duplicate_key",
                refusalEvidence(
                    duplicateRefusal.reason.name,
                    duplicateRefusal.sensitiveShape,
                    duplicateRefusal.safeLocation,
                    duplicateRefusal.nodeType,
                    duplicateRefusal.recognizedCounter,
                ),
            )
            .put(
                "invalid_json",
                refusalEvidence(
                    invalidRefusal.reason.name,
                    invalidRefusal.sensitiveShape,
                    invalidRefusal.safeLocation,
                    invalidRefusal.nodeType,
                    invalidRefusal.recognizedCounter,
                ),
            )
            .put(
                "unsupported_root",
                refusalEvidence(
                    unsupportedRootRefusal.reason.name,
                    unsupportedRootRefusal.sensitiveShape,
                    unsupportedRootRefusal.safeLocation,
                    unsupportedRootRefusal.nodeType,
                    unsupportedRootRefusal.recognizedCounter,
                ),
            )
            .put(
                "depth_limit",
                refusalEvidence(
                    depthRefusal.reason.name,
                    depthRefusal.sensitiveShape,
                    depthRefusal.safeLocation,
                    depthRefusal.nodeType,
                    depthRefusal.recognizedCounter,
                ),
            )
            .put(
                "token_invalid",
                refusalEvidence(
                    tokenRefusal.reason.name,
                    tokenRefusal.sensitiveShape,
                    tokenRefusal.safeLocation,
                    tokenRefusal.nodeType,
                    tokenRefusal.recognizedCounter,
                ),
            )
            .put(
                "invalid_usage_counter",
                JSONObject()
                    .put("reason", invalidUsageError.reason.name)
                    .put("safe_location", invalidUsageError.safeLocation)
                    .put("node_type", invalidUsageError.nodeType)
                    .put("recognized_counter", invalidUsageError.recognizedCounter)
                    .put("known_other_protocol", invalidUsageError.knownOtherProtocol),
            )
            .put("recognized_usage_counters", JSONArray().also { rows ->
                validatedCounterMatches.forEach { match ->
                    rows.put(JSONObject()
                        .put("sensitive_shape", match.sensitiveShape)
                        .put("safe_location", match.safeLocation)
                        .put("node_type", match.nodeType)
                        .put("recognized_counter", match.recognizedCounter)
                        .put("known_other_protocol", match.knownOtherProtocol))
                }
            })
        fun hasField(
            diagnostics: SafeInboundResponseDiagnostics,
            location: String,
            category: String,
            nodeType: String? = null,
            recognizedCounter: Boolean? = null,
            knownOtherProtocol: Boolean? = null,
        ): Boolean = diagnostics.fields.any {
            it.shape == "numeric" && it.location == location && it.category == category &&
                (nodeType == null || it.nodeType == nodeType) &&
                (recognizedCounter == null || it.recognizedCounter == recognizedCounter) &&
                (knownOtherProtocol == null || it.knownOtherProtocol == knownOtherProtocol)
        }
        assertTrue(hasField(metadata, "response_body.id", "provider_metadata", "STRING", false))
        assertTrue(hasField(metadata, "response_body.created", "provider_metadata", "NUMBER", false))
        assertTrue(hasField(metadata, "response_body.model", "provider_metadata", "STRING", false))
        assertTrue(
            hasField(metadata, "response_body.usage.total_tokens", "provider_metadata", "NUMBER", true, false),
        )
        assertTrue(hasField(modelContent, "response_body.choices[].message.content", "model_content", "STRING", false))
        assertTrue(
            hasField(
                modelReasoning,
                "response_body.choices[].message.reasoning_content",
                "model_reasoning",
                "STRING",
                false,
            ),
        )
        assertTrue(
            hasField(
                safeUsageIdentifierDiagnostics,
                "response_body.usage.vendor_metrics.model_usage",
                "provider_metadata",
                "NUMBER",
                false,
                false,
            ),
        )
        assertTrue(
            hasField(
                unsafeUsageIdentifierDiagnostics,
                "response_body.usage.other_field.model_usage",
                "provider_metadata",
                "NUMBER",
                false,
                false,
            ),
        )
        assertTrue(
            hasField(
                knownOtherResponsesDiagnostics,
                "response_body.usage.input_tokens",
                "provider_metadata",
                "NUMBER",
                false,
                true,
            ),
        )
        assertTrue(
            hasField(
                knownOtherGeminiDiagnostics,
                "response_body.usageMetadata.promptTokenCount",
                "provider_metadata",
                "NUMBER",
                false,
                true,
            ),
        )
        assertTrue(
            hasField(
                modelUnknownIdentifierDiagnostics,
                "response_body.choices[].message.other_field",
                "model_response_field",
                "NUMBER",
                false,
                false,
            ),
        )
        fun safeSummary(diagnostics: SafeInboundResponseDiagnostics) = JSONObject()
            .put("shapes", JSONArray(diagnostics.shapes))
            .put("locations", JSONArray(diagnostics.locations))
            .put("categories", JSONArray(diagnostics.categories))
            .put("fields", JSONArray().also { rows ->
                diagnostics.fields.forEach { field ->
                    rows.put(JSONObject()
                        .put("shape", field.shape)
                        .put("location", field.location)
                        .put("category", field.category)
                        .put("node_type", field.nodeType)
                        .put("recognized_counter", field.recognizedCounter)
                        .put("known_other_protocol", field.knownOtherProtocol))
                }
            })
        val smoke = JSONObject()
            .put("result", if (allSemanticCasesPassed) "PASS" else "FAIL")
            .put("synthetic_only", true)
            .put("hosted_provider_calls", 0)
            .put("metadata_collision", safeSummary(metadata))
            .put("model_content", safeSummary(modelContent))
            .put("model_reasoning", safeSummary(modelReasoning))
            .put("safe_usage_identifier", safeSummary(safeUsageIdentifierDiagnostics))
            .put("unsafe_usage_identifier", safeSummary(unsafeUsageIdentifierDiagnostics))
            .put("known_other_responses_usage", safeSummary(knownOtherResponsesDiagnostics))
            .put("known_other_gemini_usage", safeSummary(knownOtherGeminiDiagnostics))
            .put("model_unknown_identifier", safeSummary(modelUnknownIdentifierDiagnostics))
            .put("response_json_semantics", semanticSummaryCases)
            .put("response_json_refusal_reasons", refusalReasons)
            .put("validated_metadata", JSONArray().also { rows ->
                (rootMetadataValidated + accountingValidated).forEach { entry ->
                    rows.put(JSONObject()
                        .put("kind", entry.kind.name)
                        .put("safe_location", entry.safeLocation)
                        .put("node_type", entry.nodeType))
                }
            })
            .put("metadata_boundary", JSONObject()
                .put("non_go_route_accounting", refusalEvidence(
                    nonGoAccountingRefusal.reason.name,
                    nonGoAccountingRefusal.sensitiveShape,
                    nonGoAccountingRefusal.safeLocation,
                    nonGoAccountingRefusal.nodeType,
                    nonGoAccountingRefusal.recognizedCounter,
                    nonGoAccountingRefusal.knownOtherProtocol,
                ))
                .put("nested_metadata_lookalike", refusalEvidence(
                    nestedMetadataRefusal.reason.name,
                    nestedMetadataRefusal.sensitiveShape,
                    nestedMetadataRefusal.safeLocation,
                    nestedMetadataRefusal.nodeType,
                    nestedMetadataRefusal.recognizedCounter,
                    nestedMetadataRefusal.knownOtherProtocol,
                ))
                .put("model_content_collision", refusalEvidence(
                    contentCollisionRefusal.reason.name,
                    contentCollisionRefusal.sensitiveShape,
                    contentCollisionRefusal.safeLocation,
                    contentCollisionRefusal.nodeType,
                    contentCollisionRefusal.recognizedCounter,
                    contentCollisionRefusal.knownOtherProtocol,
                ))
                .put("malformed_root_metadata", JSONObject()
                    .put("reason", invalidMetadataError.reason.name)
                    .put("safe_location", invalidMetadataError.safeLocation)
                    .put("node_type", invalidMetadataError.nodeType))
                .put("malformed_route_accounting", JSONObject()
                    .put("reason", invalidAccountingError.reason.name)
                    .put("safe_location", invalidAccountingError.safeLocation)
                    .put("node_type", invalidAccountingError.nodeType)))
            .put("raw_values_or_tokens_persisted", false)
            .put(
                "test_only_raw_report_path",
                "debug/$snapshotId/preflight-privacy-smoke/safe-response-location-smoke.json",
            )
        rawReport.put("status", "COMPLETE")
            .put("aggregate_result", if (allSemanticCasesPassed) "PASS" else "FAIL")
            .put("failed_case_ids", JSONArray(failedSemanticCases))
            .put("response_json_semantics", semanticSummaryCases)
            .put("safe_response_location_smoke_summary", smoke)
        persistRawReport()
        assertTrue(
            "safe response-location smoke failed named cases: ${failedSemanticCases.joinToString()}",
            allSemanticCasesPassed,
        )
        return smoke
        } catch (failure: Throwable) {
            val trace = java.io.StringWriter()
            failure.printStackTrace(java.io.PrintWriter(trace))
            rawReport.put("status", "FAILED")
                .put("failure", JSONObject()
                    .put("exception_class", failure.javaClass.name)
                    .put("message", failure.message ?: JSONObject.NULL)
                    .put("stack_trace", trace.toString()))
            throw failure
        } finally {
            persistRawReport()
        }
    }

    private suspend fun runInboundPrivacyScenario(
        context: Context,
        baseFixture: JSONObject,
    ): JSONObject {
        val captureId = 118L
        val source = captureText(baseFixture, 114)
        val timezone = baseFixture.getString("timezone")
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val laya = LayaInferenceLifecycle(File(context.filesDir, "laya/model"))
        val scope = WorkflowCallerScope.AgentSession(
            "s813-v3-preflight-inbound-${UUID.randomUUID()}",
            setOf(captureId),
            emptySet(),
        )
        val boundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId))
        val sensitiveResponseBody = JSONObject()
            .put("choices", JSONArray().put(JSONObject().put(
                "message", JSONObject().put("content", source),
            )))
            .toString()
        try {
            insertCapture(database, captureId, source, timezone, System.currentTimeMillis())
            val services = services(database, laya, timezone)
            lateinit var adapter: ScriptedAdapter
            val turns = mutableListOf<suspend () -> ScriptedTurn>()
            turns += {
                toolTurn(call("select_source", JSONObject()
                    .put("capture_ref", captureRef(adapter))
                    .put("quote", sourceText(adapter))
                    .put("occurrence", 1)))
            }
            turns += {
                val selected = lastEnvelope(adapter).getJSONObject("data")
                toolTurn(call("make_time_anchor", anchorArguments(selected)))
            }
            turns += {
                val selected = previousEnvelope(adapter, 2).getJSONObject("data")
                val anchor = lastEnvelope(adapter).getJSONObject("data").getString("anchor_ref")
                ScriptedTurn(
                    calls = listOf(call("record_event", JSONObject()
                        .put("source_ref", selected.getString("source_ref"))
                        .put("summary", "Synthetic event")
                        .put("amount_ref", valueRef(selected, "amount"))
                        .put("anchor_ref", anchor)
                        .put("merchant_ref", JSONObject.NULL)
                        .put("clarification", "none"))),
                    finish = HostedTurnFinish.ToolCalls,
                    rawResponseForPrivacyScan = sensitiveResponseBody,
                )
            }
            adapter = ScriptedAdapter(turns)
            val outcome = runExecutor(
                scope,
                boundary,
                services,
                adapter,
                setOf(WorkflowIntent.INTERPRET_EVENTS),
                "Interpret the synthetic source.",
            )
            assertTrue(outcome is WorkflowRunOutcome.Incomplete)
            val incomplete = outcome as WorkflowRunOutcome.Incomplete
            assertEquals(WorkflowRunStopReason.MASKING_BLOCKED, incomplete.reason)
            val eventCount = database.noteEventDao().findForCapture(captureId).size
            val writeOperations = database.workflowOperationDao().countOf("UPDATE_EVENT")
            val ledgerRows = database.transactionDao().findAll().size
            assertEquals(3, adapter.generatedTurns.size)
            assertEquals(2, adapter.toolResultPayloads.size)
            assertEquals(0, eventCount)
            assertEquals(0L, writeOperations)
            assertEquals(0, ledgerRows)
            return JSONObject()
                .put("result", "PASS")
                .put("hosted_provider_calls", 0)
                .put("raw_response_payload_kind", "controlled_json_model_content")
                .put("detected_value_shapes", JSONArray(boundary.vault.scanLeakShapes(source).map { it.wire }.sorted()))
                .put("decoded_response_blocked", incomplete.reason == WorkflowRunStopReason.MASKING_BLOCKED)
                .put("stop_reason", incomplete.reason.name)
                .put("generated_turns", adapter.generatedTurns.size)
                .put("tool_result_turns_before_stop", adapter.toolResultPayloads.size)
                .put("write_operations", writeOperations)
                .put("stored_events", eventCount)
                .put("ledger_rows", ledgerRows)
        } finally {
            WorkflowClaimGuard.reset()
            laya.close()
            database.close()
        }
    }

    private suspend fun runMultiEventScenario(
        context: Context,
        baseFixture: JSONObject,
        source: String,
    ): JSONObject {
        val captureId = 111L
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val laya = LayaInferenceLifecycle(File(context.filesDir, "laya/model"))
        val scope = WorkflowCallerScope.AgentSession("s813-v3-preflight-multi-${UUID.randomUUID()}", setOf(captureId), emptySet())
        val boundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId))
        val captureTime = System.currentTimeMillis()
        try {
            insertCapture(database, captureId, source, baseFixture.getString("timezone"), captureTime)
            val services = services(database, laya, baseFixture.getString("timezone"))
            lateinit var adapter: ScriptedAdapter
            val turns = mutableListOf<suspend () -> ScriptedTurn>()
            turns += {
                toolTurn(call("select_source", JSONObject()
                    .put("capture_ref", captureRef(adapter))
                    .put("quote", sourceText(adapter).substringBefore(';').trim())
                    .put("occurrence", 1)))
            }
            turns += {
                val selected = lastEnvelope(adapter).getJSONObject("data")
                toolTurn(call("make_time_anchor", anchorArguments(selected)))
            }
            turns += {
                toolTurn(call("select_source", JSONObject()
                    .put("capture_ref", captureRef(adapter))
                    .put("quote", "Highlands")
                    .put("occurrence", 1)))
            }
            turns += {
                val selected = previousEnvelope(adapter, 3).getJSONObject("data")
                val merchant = lastEnvelope(adapter).getJSONObject("data")
                val anchor = previousEnvelope(adapter, 2).getJSONObject("data").getString("anchor_ref")
                toolTurn(call("record_event", JSONObject()
                    .put("source_ref", selected.getString("source_ref"))
                    .put("summary", "Lunch at Highlands")
                    .put("amount_ref", valueRef(selected, "amount"))
                    .put("anchor_ref", anchor)
                    .put("merchant_ref", merchant.getString("source_ref"))
                    .put("clarification", "none")))
            }
            turns += {
                toolTurn(call("select_source", JSONObject()
                    .put("capture_ref", captureRef(adapter))
                    .put("quote", sourceText(adapter).substringAfter(';').trim())
                    .put("occurrence", 1)))
            }
            turns += {
                val selected = lastEnvelope(adapter).getJSONObject("data")
                toolTurn(call("make_time_anchor", anchorArguments(selected)))
            }
            turns += {
                val selected = previousEnvelope(adapter, 2).getJSONObject("data")
                val anchor = lastEnvelope(adapter).getJSONObject("data").getString("anchor_ref")
                toolTurn(call("record_event", JSONObject()
                    .put("source_ref", selected.getString("source_ref"))
                    .put("summary", "Taxi")
                    .put("amount_ref", valueRef(selected, "amount"))
                    .put("anchor_ref", anchor)
                    .put("merchant_ref", JSONObject.NULL)
                    .put("clarification", "none")))
            }
            turns += { complete("Both grounded occurrences were stored.") }
            adapter = ScriptedAdapter(turns)
            val outcome = runExecutor(
                scope, boundary, services, adapter,
                setOf(WorkflowIntent.INTERPRET_EVENTS),
                "Store the two dated occurrences from the synthetic capture.",
            )
            require(outcome is WorkflowRunOutcome.Completed) { "multi-event scenario did not complete: $outcome" }
            val rows = database.noteEventDao().findForCapture(captureId).sortedBy { it.spanStart }
            assertEquals(2, rows.size)
            assertEquals(28_000L, rows[0].amountVnd)
            assertEquals("Highlands", rows[0].merchant)
            assertEquals("2026-10-21", rows[0].anchorDate)
            assertEquals("12:30", rows[0].anchorTime)
            assertEquals(65_000L, rows[1].amountVnd)
            assertNull(rows[1].merchant)
            assertEquals("2026-10-21", rows[1].anchorDate)
            assertEquals("13:05", rows[1].anchorTime)
            assertEquals(2L, database.workflowOperationDao().countOf("UPDATE_EVENT"))
            assertTrue(database.transactionDao().findAll().isEmpty())
            return JSONObject()
                .put("result", "PASS")
                .put("stored_event_count", rows.size)
                .put("distinct_source_spans", rows.map { "${it.spanStart}:${it.spanEnd}" }.distinct().size)
                .put("operation_count", database.workflowOperationDao().countOf("UPDATE_EVENT"))
                .put("ledger_rows", database.transactionDao().findAll().size)
                .put("expected_rows_verified", true)
        } finally {
            WorkflowClaimGuard.reset()
            laya.close()
            database.close()
        }
    }

    private suspend fun runReplayScenario(context: Context, baseFixture: JSONObject): JSONObject {
        val captureId = 114L
        val source = captureText(baseFixture, captureId)
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val laya = LayaInferenceLifecycle(File(context.filesDir, "laya/model"))
        val scope = WorkflowCallerScope.AgentSession("s813-v3-preflight-replay-${UUID.randomUUID()}", setOf(captureId), emptySet())
        val boundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId))
        try {
            insertCapture(database, captureId, source, baseFixture.getString("timezone"), System.currentTimeMillis())
            val services = services(database, laya, baseFixture.getString("timezone"))
            lateinit var adapter: ScriptedAdapter
            val turns = mutableListOf<suspend () -> ScriptedTurn>()
            turns += {
                toolTurn(call("select_source", JSONObject()
                    .put("capture_ref", captureRef(adapter))
                    .put("quote", sourceText(adapter))
                    .put("occurrence", 1)))
            }
            turns += {
                val selected = lastEnvelope(adapter).getJSONObject("data")
                toolTurn(call("make_time_anchor", anchorArguments(selected)))
            }
            turns += {
                val selected = previousEnvelope(adapter, 2).getJSONObject("data")
                val anchor = lastEnvelope(adapter).getJSONObject("data").getString("anchor_ref")
                toolTurn(call("record_event", JSONObject()
                    .put("source_ref", selected.getString("source_ref"))
                    .put("summary", "Fuel")
                    .put("amount_ref", valueRef(selected, "amount"))
                    .put("anchor_ref", anchor)
                    .put("merchant_ref", JSONObject.NULL)
                    .put("clarification", "none")))
            }
            turns += {
                val operationRef = lastEnvelope(adapter).getString("operation_ref")
                toolTurn(call("replay_operation", JSONObject().put("operation_ref", operationRef)))
            }
            turns += { complete("The exact operation replay returned no change.") }
            adapter = ScriptedAdapter(turns)
            val outcome = runExecutor(
                scope, boundary, services, adapter,
                setOf(WorkflowIntent.INTERPRET_EVENTS, WorkflowIntent.REPLAY_OPERATION),
                "Store the fuel reading, then replay that same operation through its returned operation reference.",
            )
            require(outcome is WorkflowRunOutcome.Completed) { "replay scenario did not complete: $outcome" }
            val event = database.noteEventDao().findForCapture(captureId).single()
            val operations = database.workflowOperationDao().findPage("UPDATE_EVENT", 100, 0)
            assertEquals(1, operations.size)
            assertEquals("APPLIED", operations.single().outcome)
            val recordedOperation = JSONObject(adapter.toolResultPayloads[2].single())
            val replayCall = adapter.generatedTurns[3].single()
            val replayArguments = JSONObject(replayCall.argumentsJson)
            val replayEnvelope = JSONObject(adapter.toolResultPayloads[3].single())
            assertEquals(recordedOperation.getString("operation_ref"), replayArguments.getString("operation_ref"))
            assertEquals("no_change", replayEnvelope.getJSONObject("data").getString("journal_outcome"))
            assertTrue(replayEnvelope.getJSONObject("data").getBoolean("current_effect_matches"))
            assertEquals("unchanged", replayEnvelope.getString("effect"))
            assertTrue(replayEnvelope.getBoolean("verified"))
            assertEquals(50_000L, event.amountVnd)
            assertEquals("2026-10-20", event.anchorDate)
            assertEquals("15:00", event.anchorTime)
            assertTrue(operations.single().payloadDigest.isNotBlank())
            assertTrue(database.transactionDao().findAll().isEmpty())
            return JSONObject()
                .put("result", "PASS")
                .put("replay_tool", "replay_operation")
                .put("same_operation_reference_reused", true)
                .put("command", operations.single().command)
                .put("operation_id", operations.single().id)
                .put("payload_digest", operations.single().payloadDigest)
                .put("journal_recorded_outcome", operations.single().outcome)
                .put("replay_current_effect_matches", replayEnvelope.getJSONObject("data").getBoolean("current_effect_matches"))
                .put("replay_journal_outcome", replayEnvelope.getJSONObject("data").getString("journal_outcome"))
                .put("replay_effect", replayEnvelope.getString("effect"))
                .put("replay_verified", replayEnvelope.getBoolean("verified"))
                .put("operation_rows", operations.size)
                .put("ledger_rows", database.transactionDao().findAll().size)
                .put("final_event_amount_vnd", event.amountVnd)
                .put("final_event_date", event.anchorDate)
                .put("final_event_time", event.anchorTime)
        } finally {
            WorkflowClaimGuard.reset()
            laya.close()
            database.close()
        }
    }

    private suspend fun runRecoveryScenario(context: Context, baseFixture: JSONObject): JSONObject {
        val captureId = 115L
        val source = captureText(baseFixture, captureId)
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val laya = LayaInferenceLifecycle(File(context.filesDir, "laya/model"))
        val scope = WorkflowCallerScope.AgentSession("s813-v3-preflight-recovery-${UUID.randomUUID()}", setOf(captureId), emptySet())
        val boundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId))
        var raceInjected = false
        try {
            insertCapture(database, captureId, source, baseFixture.getString("timezone"), System.currentTimeMillis())
            val services = services(database, laya, baseFixture.getString("timezone"))
            lateinit var adapter: ScriptedAdapter
            val turns = mutableListOf<suspend () -> ScriptedTurn>()
            turns += {
                toolTurn(call("select_source", JSONObject()
                    .put("capture_ref", captureRef(adapter))
                    .put("quote", sourceText(adapter))
                    .put("occurrence", 1)))
            }
            turns += {
                val selected = lastEnvelope(adapter).getJSONObject("data")
                toolTurn(call("make_time_anchor", anchorArguments(selected)))
            }
            turns += {
                val selected = previousEnvelope(adapter, 2).getJSONObject("data")
                val anchor = lastEnvelope(adapter).getJSONObject("data").getString("anchor_ref")
                toolTurn(call("record_event", JSONObject()
                    .put("source_ref", selected.getString("source_ref"))
                    .put("summary", "Book")
                    .put("amount_ref", valueRef(selected, "amount"))
                    .put("anchor_ref", anchor)
                    .put("merchant_ref", JSONObject.NULL)
                    .put("clarification", "none")))
            }
            turns += {
                val freshCapture = observationOfKind(lastEnvelope(adapter), "capture")
                assertEquals(1, freshCapture.getInt("capture_revision"))
                assertEquals(sourceText(adapter), freshCapture.getString("source_text"))
                toolTurn(call("select_source", JSONObject()
                    .put("capture_ref", freshCapture.getString("ref"))
                    .put("quote", freshCapture.getString("source_text"))
                    .put("occurrence", 1)))
            }
            turns += {
                val selected = lastEnvelope(adapter).getJSONObject("data")
                toolTurn(call("make_time_anchor", anchorArguments(selected)))
            }
            turns += {
                val selected = previousEnvelope(adapter, 2).getJSONObject("data")
                val anchor = lastEnvelope(adapter).getJSONObject("data").getString("anchor_ref")
                toolTurn(call("record_event", JSONObject()
                    .put("source_ref", selected.getString("source_ref"))
                    .put("summary", "Book")
                    .put("amount_ref", valueRef(selected, "amount"))
                    .put("anchor_ref", anchor)
                    .put("merchant_ref", JSONObject.NULL)
                    .put("clarification", "none")))
            }
            turns += { complete("The fresh source observation was repaired and verified.") }
            adapter = ScriptedAdapter(turns) { generatedCalls ->
                if (!raceInjected && generatedCalls.any { it.name == "record_event" }) {
                    val current = requireNotNull(database.captureMessageDao().findById(captureId))
                    assertEquals(0, current.revision)
                    assertEquals(source, current.bodyText)
                    assertEquals(1, database.captureMessageDao().reviseUserText(captureId, 0, source))
                    raceInjected = true
                }
            }
            val outcome = runExecutor(
                scope, boundary, services, adapter,
                setOf(WorkflowIntent.INTERPRET_EVENTS),
                "Store the book reading. If its first write encounters a stale observation, read current state and repair once.",
            )
            require(outcome is WorkflowRunOutcome.Completed) { "repair scenario did not complete: $outcome" }
            val conflictEnvelope = JSONObject(adapter.toolResultPayloads[2].single())
            val error = conflictEnvelope.getJSONObject("error")
            val issues = error.getJSONArray("issues")
            val actualCasConflict = error.getString("code") == "STALE_OBSERVATION" &&
                (0 until issues.length()).any {
                    issues.getJSONObject(it).getString("constraint") == "workflow_command_compare_and_set"
                }
            assertTrue("record_event did not reach WorkflowCommandService CAS", actualCasConflict)
            val event = database.noteEventDao().findForCapture(captureId).single()
            val operations = database.workflowOperationDao().findPage("UPDATE_EVENT", 100, 0)
            assertTrue(raceInjected)
            assertEquals(1, database.captureMessageDao().findById(captureId)?.revision)
            assertEquals(1, operations.size)
            assertEquals("APPLIED", operations.single().outcome)
            assertEquals(70_000L, event.amountVnd)
            assertEquals("2026-10-21", event.anchorDate)
            assertEquals("09:00", event.anchorTime)
            assertEquals(1, event.sourceRevision)
            assertTrue(database.transactionDao().findAll().isEmpty())
            return JSONObject()
                .put("result", "PASS")
                .put("capture_revision_race_injected", raceInjected)
                .put("capture_revision_after_race", database.captureMessageDao().findById(captureId)?.revision)
                .put("same_source_text_retained", database.captureMessageDao().findById(captureId)?.bodyText == source)
                .put("actual_service_conflict", actualCasConflict)
                .put("conflict_constraint", "workflow_command_compare_and_set")
                .put("fresh_capture_observation_used", true)
                .put("corrected_write_count", operations.size)
                .put("command", operations.single().command)
                .put("operation_id", operations.single().id)
                .put("payload_digest", operations.single().payloadDigest)
                .put("final_capture_id", event.captureId)
                .put("final_source_revision", event.sourceRevision)
                .put("final_amount_vnd", event.amountVnd)
                .put("final_date", event.anchorDate)
                .put("final_time", event.anchorTime)
                .put("ledger_rows", database.transactionDao().findAll().size)
        } finally {
            WorkflowClaimGuard.reset()
            laya.close()
            database.close()
        }
    }

    private suspend fun runLayaScenario(
        context: Context,
        baseFixture: JSONObject,
        projection: JSONObject,
    ): JSONObject {
        val draftId = 431L
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ensurePinnedLayaModel(context)
        val laya = LayaInferenceLifecycle(File(context.filesDir, "laya/model"))
        val scope = WorkflowCallerScope.AgentSession("s813-v3-preflight-laya-${UUID.randomUUID()}", emptySet(), setOf(draftId))
        val boundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId))
        try {
            val now = System.currentTimeMillis()
            val seeds = projection.getJSONObject("category_seeds_by_case").getJSONArray("a8-laya-01")
            val categoryNames = JSONArray()
            for (index in 0 until seeds.length()) {
                val name = seeds.getJSONObject(index).getString("name")
                val id = database.categoryDao().insert(CategoryEntity(
                    name = name,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                ))
                assertTrue(id > 0L)
                categoryNames.put(name)
            }
            val draft = fixtureDraft(baseFixture, draftId)
            assertEquals("coffee at noon", draft.getString("description"))
            database.pendingBankDraftDao().store(PendingBankDraftEntity(
                id = draftId,
                deliveryId = "s813-v3-preflight-draft-$draftId",
                sourcePackage = "synthetic.fixture",
                notificationKey = null,
                postedAtEpochMillis = now + draftId,
                bank = "Synthetic bank",
                parseStatus = IntakeParseStatus.PARSED,
                amountState = IntakeFieldState.MISSING,
                amountUnresolvedReason = null,
                amountVnd = null,
                direction = IntakeDirection.DEBIT,
                momentState = IntakeFieldState.MISSING,
                statedDate = null,
                statedTime = null,
                momentUnresolvedReason = null,
                memoState = IntakeFieldState.PRESENT,
                memo = draft.getString("description"),
                memoUnresolvedReason = null,
                reviewStatus = IntakeReviewStatus.PENDING,
                possibleDuplicateOfDraftId = null,
                reviewRevision = 0,
                createdAtEpochMillis = now + draftId,
            ))
            val services = services(database, laya, baseFixture.getString("timezone"))
            lateinit var adapter: ScriptedAdapter
            adapter = ScriptedAdapter(mutableListOf(
                { toolTurn(call("classify_draft", JSONObject().put("draft_ref", draftRef(adapter)))) },
                { complete("A local review-only category suggestion is ready.") },
            ))
            val outcome = runExecutor(
                scope, boundary, services, adapter,
                setOf(WorkflowIntent.PREPARE_LAYA_REVIEW),
                "Request the local review-only category suggestion for the observed draft.",
            )
            require(outcome is WorkflowRunOutcome.Completed) { "controlled Laya request did not complete: $outcome" }
            val snapshot = requireNotNull(database.classificationSnapshotDao().findForDraft(draftId))
            val operation = database.workflowOperationDao().findPage("REQUEST_CLASSIFICATION", 100, 0).single()
            assertEquals("MODEL", snapshot.decisionSource)
            assertEquals(IntakeReviewStatus.PENDING, database.pendingBankDraftDao().findById(draftId)?.reviewStatus)
            assertTrue(snapshot.candidateIds.isNotBlank())
            assertTrue(snapshot.probabilities.isNotBlank())
            val expectedCategoryNames = (0 until categoryNames.length()).map(categoryNames::getString).toSet()
            val candidateIds = snapshot.candidateIds.split(',').mapNotNull(String::toLongOrNull)
            val candidateNames = database.categoryDao().findAll()
                .filter { it.id in candidateIds }.map { it.name }.toSet()
            val probabilities = snapshot.probabilities.split(',').mapNotNull(String::toDoubleOrNull)
            assertEquals(expectedCategoryNames, candidateNames)
            assertEquals(candidateIds.size, probabilities.size)
            assertNotNull(snapshot.unknownShare)
            assertTrue(database.transactionDao().findAll().isEmpty())
            return JSONObject()
                .put("result", "PASS")
                .put("category_candidates", categoryNames)
                .put("decision_source", snapshot.decisionSource)
                .put("candidate_ids_json", snapshot.candidateIds)
                .put("probabilities_json", snapshot.probabilities)
                .put("unknown_share", snapshot.unknownShare ?: JSONObject.NULL)
                .put("review_status", database.pendingBankDraftDao().findById(draftId)?.reviewStatus?.name)
                .put("operation_id", operation.id)
                .put("operation_digest", operation.payloadDigest)
                .put("ledger_rows", database.transactionDao().findAll().size)
                .put("hosted_provider_calls", 0)
        } finally {
            WorkflowClaimGuard.reset()
            laya.close()
            database.close()
        }
    }

    private data class Services(
        val commands: WorkflowCommandService,
        val queries: WorkflowQueryService,
        val declarations: List<HostedToolDeclaration>,
        val skills: WorkflowSkillCatalog,
    )

    private fun services(database: LedgerDatabase, laya: LayaInferenceLifecycle, timezone: String): Services {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        return Services(
            commands = WorkflowCommandService(
                database = database,
                clock = SystemLedgerClock,
                timezoneId = { timezone },
                engine = LayaClassificationEngine(laya),
            ),
            queries = WorkflowQueryService(database),
            declarations = AgentToolDeclarations.load(appContext.assets::open),
            skills = WorkflowSkillCatalog.load(appContext.assets::open),
        )
    }

    private suspend fun runExecutor(
        scope: WorkflowCallerScope.AgentSession,
        boundary: MaskedPromptBoundary,
        services: Services,
        adapter: ScriptedAdapter,
        intents: Set<WorkflowIntent>,
        request: String,
    ): WorkflowRunOutcome {
        val host = WorkflowRunHost(
            scope = scope,
            boundary = boundary,
            gateway = MaskedHostedGateway(adapter, boundary),
            declarations = services.declarations,
            skills = services.skills,
            commands = services.commands,
            queries = services.queries,
            operationKeys = WorkflowOperationKeySource { "s813-v3-preflight-${UUID.randomUUID()}" },
        )
        return BoundedWorkflowExecutor(host).startWithAttemptKey(
            WorkflowRunRequest(
                intents = intents,
                userRequest = request,
                limits = WorkflowRunLimits(
                    maxTurns = 12,
                    maxToolCalls = 16,
                    maxContextCharacters = 160_000,
                    maxWallClockMillis = 900_000,
                    maxRepeatedIdenticalCalls = 3,
                    maxCaptureObservations = 4,
                ),
            ),
            attemptKey = "s813-v3-controlled-${UUID.randomUUID()}",
            maxOutputTokens = 8_192,
        )
    }

    private suspend fun insertCapture(
        database: LedgerDatabase,
        id: Long,
        source: String,
        timezone: String,
        now: Long,
    ) {
        assertEquals(id, database.captureMessageDao().insert(CaptureMessageEntity(
            id = id,
            captureKind = CaptureKind.USER_NOTE,
            sourceKind = CaptureSourceKind.USER_QUICK_CAPTURE,
            sourceIdentity = "s813-v3-preflight-capture-$id",
            bodyText = source,
            capturedAtEpochMillis = now,
            captureTimezone = timezone,
            revision = 0,
            originRef = null,
        )))
    }

    private fun captureText(fixture: JSONObject, id: Long): String =
        fixture.getJSONArray("captures").let { rows ->
            (0 until rows.length()).map(rows::getJSONObject).single { it.getLong("id") == id }.getString("sourceText")
        }

    private fun fixtureDraft(fixture: JSONObject, id: Long): JSONObject =
        fixture.getJSONArray("drafts").let { rows ->
            (0 until rows.length()).map(rows::getJSONObject).single { it.getLong("id") == id }
        }

    private fun projectedCaptureText(mapping: JSONObject, caseId: String, captureId: Long, fixture: JSONObject): String {
        val overrides = mapping.getJSONObject("case_fixture_overrides_by_case")
            .getJSONObject(caseId).getJSONObject("captures").getJSONObject(captureId.toString())
        return overrides.getString("sourceText").ifBlank { captureText(fixture, captureId) }
    }

    private fun captureRef(adapter: ScriptedAdapter): String {
        val observations = initialObservations(adapter)
        for (index in 0 until observations.length()) {
            val observation = observations.getJSONObject(index)
            if (observation.optString("kind") == "capture") return observation.getString("ref")
        }
        error("the controlled workflow did not receive a capture observation")
    }

    private fun sourceText(adapter: ScriptedAdapter): String {
        val observations = initialObservations(adapter)
        for (index in 0 until observations.length()) {
            val observation = observations.getJSONObject(index)
            if (observation.optString("kind") == "capture") return observation.getString("source_text")
        }
        error("the controlled workflow did not receive capture source text")
    }

    private fun draftRef(adapter: ScriptedAdapter): String {
        val observations = initialObservations(adapter)
        for (index in 0 until observations.length()) {
            val observation = observations.getJSONObject(index)
            if (observation.optString("kind") == "draft_page") {
                return observation.getJSONArray("items").getJSONObject(0).getString("ref")
            }
        }
        error("the controlled Laya workflow did not receive a draft reference")
    }

    private fun initialObservations(adapter: ScriptedAdapter): JSONArray {
        val prompt = adapter.prompts.first { it.contains("Trusted host observations") }
        val lines = prompt.lines()
        val label = lines.indexOfFirst { it.contains("Trusted host observations") }
        val start = (label + 1 until lines.size).firstOrNull { lines[it].trimStart().startsWith("[") }
            ?: error("the controlled provider prompt lacks an observation array")
        return JSONArray(lines[start].trim())
    }

    private fun observationOfKind(envelope: JSONObject, kind: String): JSONObject {
        val observations = envelope.getJSONArray("observations")
        for (index in 0 until observations.length()) {
            val observation = observations.getJSONObject(index)
            if (observation.optString("kind") == kind) return observation
        }
        error("the fresh runtime error did not carry a $kind observation")
    }

    private fun lastEnvelope(adapter: ScriptedAdapter): JSONObject =
        JSONObject(adapter.toolResultPayloads.last().single())

    private fun previousEnvelope(adapter: ScriptedAdapter, turnsBack: Int): JSONObject =
        JSONObject(adapter.toolResultPayloads[adapter.toolResultPayloads.size - turnsBack].single())

    private fun valueRef(source: JSONObject, kind: String): String {
        val values = source.getJSONArray("values")
        for (index in 0 until values.length()) {
            val value = values.getJSONObject(index)
            if (value.getString("kind") == kind) return value.getString("ref")
        }
        error("the controlled source observation lacks a $kind reference")
    }

    private fun anchorArguments(source: JSONObject): JSONObject = JSONObject()
        .put("date_ref", valueRef(source, "date"))
        .put("time_ref", valueRef(source, "time"))
        .put("end_date_ref", JSONObject.NULL)
        .put("end_time_ref", JSONObject.NULL)

    private fun call(name: String, arguments: JSONObject) = ScriptedCall(name, arguments.toString())
    private fun toolTurn(call: ScriptedCall) = ScriptedTurn(calls = listOf(call), finish = HostedTurnFinish.ToolCalls)
    private fun complete(text: String) = ScriptedTurn(text = text, finish = HostedTurnFinish.Complete)

    private class ScriptedCall(val name: String, val argumentsJson: String)
    private class ScriptedTurn(
        val text: String? = null,
        val calls: List<ScriptedCall> = emptyList(),
        val finish: HostedTurnFinish,
        val rawResponseForPrivacyScan: String? = null,
    )
    private class ScriptedConversation(
        override val protocol: HostedProtocol,
        override val pendingCallIds: List<String>,
    ) : HostedConversation

    private class ScriptedAdapter(
        private val turns: List<suspend () -> ScriptedTurn>,
        private val afterGenerated: suspend (List<ScriptedCall>) -> Unit = {},
    ) : HostedGenerationAdapter {
        override val protocol: HostedProtocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS
        private var next = 0
        val prompts = mutableListOf<String>()
        val generatedTurns = mutableListOf<List<ScriptedCall>>()
        val toolResultPayloads = mutableListOf<List<String>>()

        override fun begin(systemInstruction: String, userInstruction: String): HostedConversation {
            prompts += systemInstruction
            prompts += userInstruction
            return ScriptedConversation(protocol, emptyList())
        }

        override suspend fun generate(
            conversation: HostedConversation,
            tools: List<HostedToolDeclaration>,
        ): HostedGenerationResult {
            val producer = turns.getOrNull(next) ?: error("the controlled provider has no turn ${next + 1}")
            next += 1
            val turn = producer()
            afterGenerated(turn.calls)
            generatedTurns += turn.calls
            val calls = turn.calls.mapIndexed { index, call ->
                HostedToolCall(
                    callId = "s813-controlled-$next-$index",
                    name = call.name,
                    argumentsJson = call.argumentsJson,
                    arguments = null,
                    disposition = ToolCallDisposition.READY,
                    detail = null,
                )
            }
            val continuation = if (turn.finish == HostedTurnFinish.ToolCalls) {
                ScriptedConversation(protocol, calls.map(HostedToolCall::callId))
            } else {
                null
            }
            val result = HostedGenerationResult.Turn(
                HostedTurn(
                    protocol = protocol,
                    requestedModel = SCRIPTED_MODEL,
                    reportedModel = SCRIPTED_MODEL,
                    assistantText = turn.text,
                    toolCalls = calls,
                    finish = turn.finish,
                    usage = null,
                    conversation = continuation,
                ),
            )
            return turn.rawResponseForPrivacyScan?.let { result.withRawResponseForPrivacyScan(it) }
                ?: result
        }

        override fun continueWithToolResults(
            conversation: HostedConversation,
            results: List<HostedToolResult>,
            hostNotice: String?,
        ): HostedConversation {
            toolResultPayloads += results.map(HostedToolResult::outputJson)
            return ScriptedConversation(protocol, emptyList())
        }
    }

    private suspend fun ensurePinnedLayaModel(context: Context) {
        val provisioner = LayaModelProvisioner(context.filesDir) {
            context.assets.open("laya/laya_tokenizer.bin")
        }
        if (provisioner.inspect() == LayaModelProvisioner.Status.Ready) return
        val source = File(context.filesDir, "s8-public-laya-source")
        val names = listOf("laya.onnx", "laya.onnx.data", "laya_config.json")
        val missing = names.filterNot { File(source, it).isFile }
        check(missing.isEmpty()) { "the pinned local Laya source is not staged: $missing" }
        val inputs = names.associateWith { name -> { FileInputStream(File(source, name)) } }
        check(provisioner.provision(inputs) == LayaModelProvisioner.Status.Ready)
        source.deleteRecursively()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private companion object {
        const val FIXTURE_ID = "S8.13-v3-interface-qualification-fixture-01"
        const val SCRIPTED_MODEL = "controlled-preflight"
    }
}
