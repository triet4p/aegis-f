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
import io.github.triet4p.aegisf.data.NoteEventEntity
import io.github.triet4p.aegisf.data.noteEventIdentity
import io.github.triet4p.aegisf.data.NoteEventStatus
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.SystemLedgerClock
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowClaimGuard
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.data.laya.LayaClassificationEngine
import io.github.triet4p.aegisf.data.laya.LayaInferenceLifecycle
import io.github.triet4p.aegisf.data.laya.LayaEffectiveContextComposer
import io.github.triet4p.aegisf.data.laya.LayaRuntimeContract
import io.github.triet4p.aegisf.data.laya.LayaSequence
import io.github.triet4p.aegisf.data.laya.LayaTokenizer
import io.github.triet4p.aegisf.data.laya.LayaModelProvisioner
import io.github.triet4p.aegisf.privacy.MaskedHostedGateway
import io.github.triet4p.aegisf.privacy.InboundRefusal
import io.github.triet4p.aegisf.privacy.InboundScan
import io.github.triet4p.aegisf.privacy.MaskedPromptBoundary
import io.github.triet4p.aegisf.privacy.SensitiveVault
import io.github.triet4p.aegisf.provider.HostedAdapters
import io.github.triet4p.aegisf.provider.HostedAuthentication
import io.github.triet4p.aegisf.provider.HostedConversation
import io.github.triet4p.aegisf.provider.HostedGenerationAdapter
import io.github.triet4p.aegisf.provider.HostedGenerationBudget
import io.github.triet4p.aegisf.provider.HostedGenerationResult
import io.github.triet4p.aegisf.provider.HostedHttpRequest
import io.github.triet4p.aegisf.provider.HostedHttpResponse
import io.github.triet4p.aegisf.provider.HostedProviderCapabilities
import io.github.triet4p.aegisf.provider.HostedProviderConfig
import io.github.triet4p.aegisf.provider.HostedProtocol
import io.github.triet4p.aegisf.provider.HostedReasoningEffort
import io.github.triet4p.aegisf.provider.HostedToolDeclaration
import io.github.triet4p.aegisf.provider.HostedToolResult
import io.github.triet4p.aegisf.provider.HttpTransport
import io.github.triet4p.aegisf.provider.UrlConnectionHttpTransport
import java.io.File
import java.io.DataInputStream
import java.io.FileInputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual hosted qualification through the production masked executor and isolated Room services. */
@RunWith(AndroidJUnit4::class)
class S813QualificationInstrumentedTest {
    @Test
    fun runFrozenDevAndHeldoutSuites() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val profile = requireNotNull(args.getString("S813_PROFILE"))
        val pass = requireNotNull(args.getString("S813_PASS"))
        val attemptId = args.getString("S813_ATTEMPT_ID") ?: "S8.13-replan-a7"
        val batchId = args.getString("S813_BATCH_ID") ?: "S8.13-runtime-fixture-v3-batch-03"
        val requestedCaseIds = args.getString("S813_CASE_IDS")?.split(',')?.map(String::trim)
        if (requestedCaseIds != null) {
            require(attemptId == DIAGNOSTIC_ATTEMPT_ID)
            require(batchId == DIAGNOSTIC_BATCH_ID)
            require(requestedCaseIds == DIAGNOSTIC_CASE_IDS)
        } else {
            require(attemptId == "S8.13-replan-a7" && batchId == "S8.13-runtime-fixture-v3-batch-03")
        }
        val runCaseIds: List<String>
        require(profile in PROFILE_IDS && pass in setOf("1", "2", "3"))
        val credentialName = if (profile == GO_PROFILE) GO_KEY else GOOGLE_KEY
        val credential = requireNotNull(args.getString(credentialName)) { "required hosted credential unavailable" }
        require(credential.isNotBlank())
        val provider = providerConfig(profile, credential)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentationContext = InstrumentationRegistry.getInstrumentation().context
        val inputHashes = JSONObject()
        fun readInput(name: String, key: String): JSONObject {
            val bytes = instrumentationContext.assets.open(name).use { it.readBytes() }
            inputHashes.put(key, sha256(bytes))
            return JSONObject(String(bytes, Charsets.UTF_8))
        }
        val fixture = readInput("s8_agent_fixture.json", "fixture")
        val projection = readInput("s8_agent_runtime_fixture_v3.json", "runtime_projection")
        val dev = readInput("s8_agent_dev.json", "dev_suite")
        val heldout = readInput("s8_agent_heldout.json", "heldout_suite")
        val baseHashes = projection.getJSONObject("base_inputs")
        require(inputHashes.getString("fixture") == baseHashes.getJSONObject("fixture").getString("sha256"))
        require(inputHashes.getString("dev_suite") == baseHashes.getJSONObject("dev_suite").getString("sha256"))
        require(inputHashes.getString("heldout_suite") == baseHashes.getJSONObject("heldout_suite").getString("sha256"))
        readInput("s8_agent_tools.json", "tools")
        readInput("s8_agent_skills.json", "skills")
        readInput("s8_agent_scoring.json", "scoring")
        inputHashes.put(
            "runtime_tools",
            sha256(context.assets.open("s8_agent_tools_v3.json").use { it.readBytes() }),
        )
        inputHashes.put(
            "runtime_skills",
            sha256(context.assets.open("s8_agent_runtime_skills_v3.json").use { it.readBytes() }),
        )
        val runDir = File(context.filesDir, "$attemptId/$profile/pass-$pass")
        check(!runDir.exists()) { "refusing to overwrite retained S8.13 run evidence" }
        check(runDir.mkdirs()) { "could not create isolated S8.13 run evidence directory" }
        val started = Instant.now().toString()
        val outcomes = JSONArray()
        val caseRows = linkedMapOf<String, JSONObject>()
        for (suite in listOf(dev, heldout)) {
            val cases = suite.getJSONArray("cases")
            for (index in 0 until cases.length()) {
                val original = cases.getJSONObject(index)
                caseRows[original.getString("id")] = original
            }
        }
        runCaseIds = requestedCaseIds ?: caseRows.keys.toList()
        require(runCaseIds.size == if (requestedCaseIds == null) 24 else DIAGNOSTIC_CASE_IDS.size)
        val rows = JSONArray().apply {
            runCaseIds.forEach { id -> put(requireNotNull(caseRows[id]) { "selected case is not in frozen inputs" }) }
        }
        require(rows.length() == runCaseIds.size) { "selected case identities must be unique and available" }
        if (rows.toString().contains(credential)) error("credential unexpectedly overlaps fixture data")
        var safetyStop: String? = null

        for (index in 0 until rows.length()) {
            if (safetyStop != null) {
                val skipped = JSONObject()
                    .put("case_id", runCaseIds[index])
                    .put("attempt_status", "not_run_safety_stop")
                    .put("stop_reason", safetyStop)
                outcomes.put(skipped)
                File(runDir, "${runCaseIds[index]}.json").writeText(skipped.toString(2))
                continue
            }
            val originalCase = rows.getJSONObject(index)
            val case = projectCase(originalCase, projection)
            val runtimeFixture = runtimeFixtureForCase(fixture, projection, case.getString("id"))
            val result = try {
                runCase(context, case, runtimeFixture, projection, provider, profile, pass, runDir)
            } catch (failure: Throwable) {
                JSONObject()
                    .put("case_id", case.getString("id"))
                    .put("attempt_status", "harness_or_provider_error")
                    .put("failure_type", failure::class.simpleName ?: "unknown")
                    .put("failure_detail", provider.authentication.redact(failure.message ?: "no detail").take(300))
                    .put("provider_error", true)
            }
            outcomes.put(result)
            File(runDir, "${case.getString("id")}.json").writeText(result.toString(2))
            if (requestedCaseIds != null) safetyStop = diagnosticStopReason(result)
            if (index + 1 < rows.length()) {
                delay(if (provider.protocol == HostedProtocol.GEMINI_GENERATE_CONTENT) 8_000L else 2_000L)
            }
        }
        val report = JSONObject()
            .put("task", "S8.13")
            .put("profile", profile)
            .put("attempt_id", attemptId)
            .put("batch_id", batchId)
            .put("runtime_fixture_projection", projection.getString("projection_id"))
            .put("pass", pass)
            .put("transport_protocol", protocolEvidenceLabel(provider.protocol))
            .put("max_output_tokens_per_turn", provider.budget.maxOutputTokens)
            .put("run_limits", JSONObject()
                .put("max_turns_per_case", 6)
                .put("max_tool_calls_per_case", 8)
                .put("max_context_characters", 160000)
                .put("max_wall_clock_millis", 900000)
                .put("max_repeated_identical_calls", 3)
                .put("max_capture_observations", 4))
            .put("started_at", started)
            .put("finished_at", Instant.now().toString())
            .put("input_sha256", inputHashes)
            .put("expected_cases", runCaseIds.size)
            .put("recorded_cases", outcomes.length())
            .put("case_ids", JSONArray(runCaseIds))
            .put("measurement_scope", if (requestedCaseIds == null) "full_fixture" else "diagnostic_subset")
            .put("safety_stop", safetyStop ?: JSONObject.NULL)
            .put("credential_recorded", false)
            .put("fixture_only", true)
            .put("cases", outcomes)
        File(runDir, "live-run.json").writeText(report.toString(2))
        WorkflowClaimGuard.reset()
        assertEquals("all selected case attempts must remain in the denominator", runCaseIds.size, outcomes.length())
    }

    private fun diagnosticStopReason(result: JSONObject): String? {
        if (result.optString("attempt_status") != "hosted_run_recorded") return "case_execution_error"
        if (!result.optBoolean("raw_sensitive_absent_from_egress", false)) return "raw_sensitive_egress"
        if (result.optBoolean("raw_sensitive_inbound_detected", true)) return "raw_sensitive_inbound"
        val masking = result.optJSONObject("masking_integrity") ?: return "masking_evidence_missing"
        if (!masking.optBoolean("outbound_raw_absent", false) ||
            !masking.optBoolean("inbound_raw_absent", false)
        ) return "masking_integrity_failure"
        val turns = result.optJSONArray("provider_turns") ?: return "provider_evidence_missing"
        for (index in 0 until turns.length()) {
            if (turns.getJSONObject(index).has("provider_failure")) return "provider_error"
        }
        val wire = result.optJSONArray("masked_wire") ?: return "wire_evidence_missing"
        for (index in 0 until wire.length()) {
            val exchange = wire.getJSONObject(index)
            if (exchange.optInt("http_status", 200) >= 400 ||
                exchange.optString("transport_failure_type").isNotEmpty()
            ) return "provider_transport_error"
        }
        val postconditions = result.optJSONObject("postconditions") ?: return "postconditions_missing"
        if (postconditions.optInt("ledger_rows") != 0 ||
            postconditions.optInt("out_of_scope_effect_rows") != 0 ||
            postconditions.optInt("unexpected_mutation_operations") != 0
        ) return "unsafe_or_unintended_effect"
        return null
    }

    private suspend fun runCase(
        context: Context,
        case: JSONObject,
        fixture: JSONObject,
        projection: JSONObject,
        provider: HostedProviderConfig,
        profile: String,
        pass: String,
        evidenceDir: File,
    ): JSONObject {
        val caseId = case.getString("id")
        val family = case.getString("family")
        val caseContext = case.getJSONObject("context")
        val captureIds = caseCaptureIds(caseContext, fixture)
        val drafts = fixture.getJSONArray("drafts")
        val draftIds = draftScope(case, family, drafts)
        val scopeId = "s813-$profile-$pass-$caseId-${UUID.randomUUID()}"
        val scope = WorkflowCallerScope.AgentSession(scopeId, captureIds, draftIds)
        val boundary = MaskedPromptBoundary(SensitiveVault(scopeId))
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val laya = LayaInferenceLifecycle(File(context.filesDir, "laya/model"))
        val wire = RecordingTransport(
            UrlConnectionHttpTransport(),
            provider,
            protocolEvidenceLabel(provider.protocol),
            boundary.vault,
            File(evidenceDir, "$caseId.provider-attempts.json"),
        )
        try {
            val categorySeeds = projection.getJSONObject("category_seeds_by_case").optJSONArray(caseId)
                ?: JSONArray()
            seedFixture(db, fixture, captureIds, draftIds, eventIds(caseContext), categorySeeds)
            if (family in setOf("laya_request", "laya_over_budget")) ensurePinnedLayaModel(context)
            val commands = WorkflowCommandService(
                database = db,
                clock = SystemLedgerClock,
                timezoneId = { fixture.getString("timezone") },
                engine = LayaClassificationEngine(laya),
            )
            val queries = WorkflowQueryService(db)
            val declarations = AgentToolDeclarations.load(context.assets::open)
            val skills = WorkflowSkillCatalog.load(context.assets::open)
            val observed = mutableListOf<JSONObject>()
            var recoveryRaceInjected = false
            val adapter = RecordingAdapter(
                HostedAdapters.create(provider, wire),
                observed,
                boundary.vault,
            ) { generated ->
                val staleCall = (generated as? HostedGenerationResult.Turn)?.turn?.toolCalls
                    ?.firstOrNull { call ->
                        val arguments = call.arguments.orEmpty()
                        call.name == "record_event" &&
                            (arguments["captureId"] as? Number)?.toLong() == 115L &&
                            (arguments["expectedCaptureRevision"] as? Number)?.toLong() == 0L
                    }
                if (caseId == "a8-recover-01" && !recoveryRaceInjected && staleCall != null) {
                    val source = requireNotNull(db.captureMessageDao().findById(115)?.bodyText)
                    assertEquals(1, db.captureMessageDao().reviseUserText(115, 0, source))
                    recoveryRaceInjected = true
                }
            }
            val host = WorkflowRunHost(
                scope = scope,
                boundary = boundary,
                gateway = MaskedHostedGateway(adapter, boundary),
                declarations = declarations,
                skills = skills,
                commands = commands,
                queries = queries,
                operationKeys = WorkflowOperationKeySource { "s813-${UUID.randomUUID()}" },
            )
            val startedNanos = System.nanoTime()
            val outcome = withContext(Dispatchers.IO) {
                BoundedWorkflowExecutor(host).startWithAttemptKey(
                    WorkflowRunRequest(
                        intents = intents(family),
                        userRequest = case.getString("prompt"),
                        limits = WorkflowRunLimits(
                            maxTurns = 6,
                            maxToolCalls = 8,
                            maxContextCharacters = 160_000,
                            maxWallClockMillis = 900_000,
                            maxRepeatedIdenticalCalls = 3,
                            maxCaptureObservations = 4,
                        ),
                    ),
                    attemptKey = "s813-run-${UUID.randomUUID()}",
                    maxOutputTokens = provider.budget.maxOutputTokens,
                )
            }
            val latencyMillis = (System.nanoTime() - startedNanos) / 1_000_000
            val actualPostconditions = postconditions(db, captureIds, draftIds, case)
            val layaEvidence = layaEvidence(context, db, case, projection, observed)
            return JSONObject()
                .put("case_id", caseId)
                .put("attempt_status", "hosted_run_recorded")
                .put("outcome", outcomeName(outcome))
                .put("outcome_reason", when (outcome) {
                    is WorkflowRunOutcome.Refused -> outcome.reason
                    is WorkflowRunOutcome.Incomplete -> outcome.reason.name
                    is WorkflowRunOutcome.Clarifying -> outcome.field ?: JSONObject.NULL
                    is WorkflowRunOutcome.Completed -> JSONObject.NULL
                })
                .put("turns", outcomeTurns(outcome))
                .put("tool_calls", outcomeCalls(outcome))
                .put("end_to_end_latency_ms", latencyMillis)
                .put("provider_turns", JSONArray(observed))
                .put("masked_wire", wire.evidence())
                .put("postconditions", actualPostconditions)
                .put("raw_sensitive_absent_from_egress", wire.rawSensitiveAbsent)
                .put("raw_sensitive_inbound_detected", wire.rawSensitiveInboundDetected)
                .put("masking_integrity", maskingEvidence(case, db, boundary, wire))
                .put("usage", outcomeUsage(outcome))
                .put("trace", outcomeTrace(outcome))
                .put("cache_support", outcomeCacheSupport(outcome))
                .put("verified_effects", outcomeVerifiedEffects(outcome))
                .put("laya_evidence", layaEvidence)
                .put(
                    "revision_race",
                    if (recoveryRaceInjected) {
                        JSONObject().put("old_revision", 0).put("current_revision", 1)
                            .put("source_text_changed", false)
                            .put("service_conflict_observed", observedRevisionConflict(observed))
                    } else {
                        JSONObject.NULL
                    },
                )
                .put("credential_recorded", false)
        } finally {
            laya.close()
            db.close()
        }
    }

    private fun observedRevisionConflict(observed: List<JSONObject>): Boolean =
        observed.any { turn ->
            val results = turn.optJSONArray("tool_results") ?: return@any false
            (0 until results.length()).any { index ->
                val payload = runCatching {
                    JSONObject(results.getJSONObject(index).optString("output_json"))
                }.getOrNull()
                payload?.optString("status") == "conflict"
            }
        }

    private suspend fun seedFixture(
        db: LedgerDatabase,
        fixture: JSONObject,
        capturesInScope: Set<Long>,
        draftsInScope: Set<Long>,
        eventIdsInScope: Set<Long>,
        projectedCategories: JSONArray,
    ) {
        val now = System.currentTimeMillis()
        for (index in 0 until projectedCategories.length()) {
            val row = projectedCategories.getJSONObject(index)
            val inserted = db.categoryDao().insert(
                CategoryEntity(
                    name = row.getString("name"),
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                ),
            )
            assertTrue("synthetic category was not inserted", inserted > 0L)
        }
        val captures = fixture.getJSONArray("captures")
        for (index in 0 until captures.length()) {
            val row = captures.getJSONObject(index)
            val id = row.getLong("id")
            if (id !in capturesInScope) continue
            assertEquals(id, db.captureMessageDao().insert(CaptureMessageEntity(
                id = id,
                captureKind = CaptureKind.USER_NOTE,
                sourceKind = CaptureSourceKind.USER_QUICK_CAPTURE,
                sourceIdentity = "s813-capture-$id",
                bodyText = row.getString("sourceText"),
                capturedAtEpochMillis = now,
                captureTimezone = fixture.getString("timezone"),
                revision = row.getInt("revision"),
                originRef = null,
            )))
        }
        val drafts = fixture.getJSONArray("drafts")
        for (index in 0 until drafts.length()) {
            val row = drafts.getJSONObject(index)
            val id = row.getLong("id")
            if (id !in draftsInScope) continue
            val amount = row.optLong("amountVnd").takeIf { row.has("amountVnd") && !row.isNull("amountVnd") }
            val date = row.optString("statedDate").takeIf { it.isNotBlank() && it != "null" }
            val time = row.optString("statedTime").takeIf { it.isNotBlank() && it != "null" }
            val memo = row.optString("description").takeIf { it.isNotBlank() && it != "null" }
            assertEquals(id, db.pendingBankDraftDao().store(PendingBankDraftEntity(
                id = id,
                deliveryId = "s813-draft-$id",
                sourcePackage = "synthetic.fixture",
                notificationKey = null,
                postedAtEpochMillis = now + id,
                bank = "Synthetic bank",
                parseStatus = IntakeParseStatus.PARSED,
                amountState = if (amount == null) IntakeFieldState.MISSING else IntakeFieldState.PRESENT,
                amountVnd = amount,
                amountUnresolvedReason = null,
                direction = IntakeDirection.DEBIT,
                momentState = if (date == null) IntakeFieldState.MISSING else IntakeFieldState.PRESENT,
                statedDate = date,
                statedTime = time,
                momentUnresolvedReason = null,
                memoState = if (memo == null) IntakeFieldState.MISSING else IntakeFieldState.PRESENT,
                memo = memo,
                memoUnresolvedReason = null,
                reviewStatus = IntakeReviewStatus.valueOf(row.optString("reviewStatus", "PENDING")),
                possibleDuplicateOfDraftId = row.optLong("possibleDuplicateOfDraftId").takeIf { row.has("possibleDuplicateOfDraftId") && !row.isNull("possibleDuplicateOfDraftId") },
                reviewRevision = row.optInt("reviewRevision", 0),
                createdAtEpochMillis = now + id,
            )))
        }
        val events = fixture.getJSONArray("events")
        for (index in 0 until events.length()) {
            val row = events.getJSONObject(index)
            if (row.getLong("id") !in eventIdsInScope) continue
            val date = row.optString("anchorDate").takeIf { it.isNotBlank() && it != "null" }
            val time = row.optString("anchorTime").takeIf { it.isNotBlank() && it != "null" }
            val captureId = row.getLong("captureId")
            val sourceText = requireNotNull(db.captureMessageDao().findById(captureId)?.bodyText)
            val spanStart = row.optInt("spanStart", 0)
            val spanEnd = row.optInt("spanEnd", sourceText.length)
            val entity = NoteEventEntity(
                id = row.getLong("id"),
                captureId = captureId,
                eventIdentity = noteEventIdentity(
                    CaptureSourceKind.USER_QUICK_CAPTURE,
                    "s813-capture-$captureId",
                    spanStart,
                    spanEnd,
                ),
                spanStart = spanStart,
                spanEnd = spanEnd,
                normalizedText = row.getString("normalizedText"),
                amountVnd = row.optLong("amountVnd").takeIf { row.has("amountVnd") && !row.isNull("amountVnd") },
                merchant = row.optString("merchant").takeIf { it.isNotBlank() && it != "null" },
                anchorDate = date,
                anchorTime = time,
                anchorTimezone = row.optString("anchorTimezone").takeIf { it.isNotBlank() && it != "null" },
                intervalEndDate = null,
                intervalEndTime = null,
                extractionVersion = "s813-fixture-v3",
                unresolvedReason = null,
                status = NoteEventStatus.valueOf(row.getString("status")),
                revision = row.getInt("revision"),
                sourceRevision = 0,
                createdAtEpochMillis = now + row.getLong("id"),
            )
            assertEquals(entity.id, db.noteEventDao().insert(entity))
        }
    }
    private fun projectCase(case: JSONObject, projection: JSONObject): JSONObject {
        val projected = JSONObject(case.toString())
        val caseId = projected.getString("id")
        projection.getJSONObject("runtime_prompt_overrides_by_case").optString(caseId)
            .takeIf { it.isNotEmpty() }
            ?.let { projected.put("prompt", it) }
        val argumentOverrides = projection.getJSONObject("expectation_argument_overrides_by_case")
            .optJSONObject(caseId) ?: return projected
        val calls = projected.getJSONObject("expect").getJSONArray("calls")
        val toolNames = argumentOverrides.keys()
        while (toolNames.hasNext()) {
            val toolName = toolNames.next()
            val matching = (0 until calls.length()).map(calls::getJSONObject)
                .filter { it.getString("name") == toolName }
            require(matching.size == 1) { "$caseId must have one $toolName expectation to override" }
            val arguments = matching.single().getJSONObject("args")
            val paths = argumentOverrides.getJSONObject(toolName)
            val pathNames = paths.keys()
            while (pathNames.hasNext()) {
                val pathName = pathNames.next()
                val parts = pathName.split('.')
                var target = arguments
                for (part in parts.dropLast(1)) target = target.getJSONObject(part)
                target.put(parts.last(), paths.get(pathName))
            }
        }
        return projected
    }

    private fun runtimeFixtureForCase(
        baseFixture: JSONObject,
        projection: JSONObject,
        caseId: String,
    ): JSONObject {
        val fixture = JSONObject(baseFixture.toString())
        val caseOverrides = projection.getJSONObject("fixture_overrides_by_case").optJSONObject(caseId)
            ?: JSONObject()
        for (section in listOf("captures", "events", "drafts")) {
            val rowOverrides = caseOverrides.optJSONObject(section) ?: continue
            val rows = fixture.getJSONArray(section)
            val rowIds = rowOverrides.keys()
            while (rowIds.hasNext()) {
                val rowId = rowIds.next()
                val row = (0 until rows.length()).map(rows::getJSONObject)
                    .single { it.getLong("id") == rowId.toLong() }
                val fields = rowOverrides.getJSONObject(rowId)
                val fieldNames = fields.keys()
                while (fieldNames.hasNext()) {
                    val field = fieldNames.next()
                    row.put(field, fields.get(field))
                }
            }
        }
        val oversized = projection.getJSONObject("draft_overrides_by_case").optJSONObject(caseId)
            ?: return fixture
        val drafts = fixture.getJSONArray("drafts")
        val ids = oversized.keys()
        while (ids.hasNext()) {
            val draftId = ids.next().toLong()
            val override = oversized.getJSONObject(draftId.toString())
            val template = override.getString("description_template")
            val repetitions = override.getInt("repetitions")
            require(repetitions in 1..10_000)
            val description = buildString {
                for (index in 1..repetitions) append(String.format(Locale.ROOT, template, index))
            }
            val draft = (0 until drafts.length()).map(drafts::getJSONObject)
                .single { it.getLong("id") == draftId }
            draft.put("description", description)
        }
        return fixture
    }

    private fun caseCaptureIds(caseContext: JSONObject, fixture: JSONObject): Set<Long> {
        val ids = caseContext.optJSONArray("captureIds")?.longSet().orEmpty().toMutableSet()
        val eventIds = buildSet {
            caseContext.optJSONArray("eventIds")?.longSet()?.let(::addAll)
            caseContext.optLong("eventId").takeIf { it > 0 }?.let(::add)
        }
        val events = fixture.getJSONArray("events")
        for (index in 0 until events.length()) {
            val event = events.getJSONObject(index)
            if (event.getLong("id") in eventIds) ids += event.getLong("captureId")
        }
        return ids
    }

    private fun draftScope(case: JSONObject, family: String, drafts: JSONArray): Set<Long> {
        case.getJSONObject("context").optJSONArray("draftIds")?.let { return it.longSet() }
        if (family == "no_match") {
            return (0 until drafts.length()).map { drafts.getJSONObject(it).getLong("id") }.toSet()
        }
        val calls = case.getJSONObject("expect").optJSONArray("calls") ?: JSONArray()
        return buildSet {
            for (index in 0 until calls.length()) {
                calls.getJSONObject(index).getJSONObject("args").optLong("draftId")
                    .takeIf { it > 0 }?.let(::add)
            }
        }
    }
    private fun eventIds(caseContext: JSONObject): Set<Long> = buildSet {
        caseContext.optJSONArray("eventIds")?.longSet()?.let(::addAll)
        caseContext.optLong("eventId").takeIf { it > 0 }?.let(::add)
    }


    private fun intents(family: String) = when (family) {
        "bounded_candidate_search" -> setOf(WorkflowIntent.SEARCH_DRAFTS)
        "propose_attach_scope", "no_match" -> setOf(WorkflowIntent.LINK_EVENT_TO_DRAFT)
        "laya_request", "laya_over_budget" -> setOf(WorkflowIntent.PREPARE_LAYA_REVIEW)
        else -> setOf(WorkflowIntent.INTERPRET_EVENTS)
    }

    private suspend fun postconditions(
        db: LedgerDatabase,
        captures: Set<Long>,
        drafts: Set<Long>,
        case: JSONObject,
    ): JSONObject {
        val events = captures.flatMap { db.noteEventDao().findForCapture(it) }
        val allEvents = db.noteEventDao().findPage(null, null, 1000, 0)
        val links = db.contextLinkDao().findPage(null, null, null, 1000, 0)
        val draftRows = db.pendingBankDraftDao().findAll()
        val captureRows = db.captureMessageDao().findAllIncludingTombstones()
        val operations = db.workflowOperationDao().findPage(null, 1000, 0)
        val expectedCalls = case.getJSONObject("expect").optJSONArray("calls") ?: JSONArray()
        val expectedCommands = buildSet {
            for (index in 0 until expectedCalls.length()) {
                when (expectedCalls.getJSONObject(index).getString("name")) {
                    "record_event" -> add("UPDATE_EVENT")
                    "propose_link" -> add("PROPOSE_LINK")
                    "attach_link" -> add("ATTACH_LINK")
                    "classify_draft" -> add("REQUEST_CLASSIFICATION")
                }
            }
        }
        val scopedEventIds = allEvents.filter { it.captureId in captures }.map { it.id }.toSet()
        val outOfScopeRows = captureRows.count { it.id !in captures } +
            draftRows.count { it.id !in drafts } +
            allEvents.count { it.captureId !in captures } +
            links.count { it.eventId !in scopedEventIds || it.draftId !in drafts }
        val unexpectedOperations = operations.count { it.command !in expectedCommands }
        val snapshots = JSONArray()
        for (draftId in drafts) {
            val snapshot = db.classificationSnapshotDao().findForDraft(draftId) ?: continue
            snapshots.put(JSONObject()
                .put("draft_id", snapshot.draftId)
                .put("decision_source", snapshot.decisionSource)
                .put("suggested_category_id", snapshot.suggestedCategoryId ?: JSONObject.NULL)
                .put("candidate_ids_json", snapshot.candidateIds)
                .put("probabilities_json", snapshot.probabilities)
                .put("unknown_share", snapshot.unknownShare ?: JSONObject.NULL)
                .put("confidence", snapshot.confidence ?: JSONObject.NULL)
                .put("stale_reason", snapshot.staleReason ?: JSONObject.NULL))
        }
        val categories = db.categoryDao().findAll()
        return JSONObject()
            .put("stored_events", JSONArray().also { out -> events.forEach { e -> out.put(JSONObject()
                .put("id", e.id).put("capture_id", e.captureId).put("span_start", e.spanStart).put("span_end", e.spanEnd)
                .put("normalized_text", e.normalizedText ?: JSONObject.NULL).put("amount_vnd", e.amountVnd ?: JSONObject.NULL)
                .put("merchant", e.merchant ?: JSONObject.NULL).put("anchor_date", e.anchorDate ?: JSONObject.NULL)
                .put("anchor_time", e.anchorTime ?: JSONObject.NULL).put("status", e.status.name).put("revision", e.revision)) } })
            .put("links", JSONArray().also { out -> links.forEach { l -> out.put(JSONObject()
                .put("id", l.id).put("event_id", l.eventId).put("draft_id", l.draftId).put("state", l.state.name).put("revision", l.revision)) } })
            .put("drafts", JSONArray().also { out -> draftRows.forEach { d -> out.put(JSONObject()
                .put("id", d.id).put("review_status", d.reviewStatus.name).put("review_revision", d.reviewRevision)) } })
            .put("operations", JSONArray().also { out -> operations.forEach { op -> out.put(JSONObject()
                .put("command", op.command).put("outcome", op.outcome)) } })
            .put("classification_snapshots", snapshots)
            .put("categories", JSONArray().also { out -> categories.forEach { row -> out.put(JSONObject()
                .put("id", row.id).put("name", row.name).put("archived", row.archived)) } })
            .put("ledger_rows", db.transactionDao().findAll().size)
            .put("out_of_scope_effect_rows", outOfScopeRows)
            .put("unexpected_mutation_operations", unexpectedOperations)
    }
    private suspend fun layaEvidence(
        context: Context,
        db: LedgerDatabase,
        case: JSONObject,
        projection: JSONObject,
        observedTurns: List<JSONObject>,
    ): JSONObject {
        val caseId = case.getString("id")
        if (case.getString("family") !in setOf("laya_request", "laya_over_budget")) {
            return JSONObject().put("requested", false)
        }
        val draftId = case.getJSONObject("context").getJSONArray("draftIds").getLong(0)
        val draft = requireNotNull(db.pendingBankDraftDao().findById(draftId))
        val description = draft.memo.orEmpty()
        val budget = projection.getJSONObject("laya_budget")
        val maxLen = budget.getInt("max_len_tokens")
        val headMaxLen = budget.getInt("head_max_len_tokens")
        val room = LayaEffectiveContextComposer.guaranteedRoomTokens(maxLen, headMaxLen)
        require(room == budget.getInt("guaranteed_primary_text_room_tokens"))
        require(maxLen == LayaRuntimeContract.MAX_LEN && headMaxLen == LayaRuntimeContract.HEAD_MAX_LEN)
        val tokenizerBytes = context.assets.open("laya/laya_tokenizer.bin").use { it.readBytes() }
        val tokenCount = DataInputStream(tokenizerBytes.inputStream()).use { input ->
            LayaTokenizer.load(input).encode(LayaSequence.scrub(description)).size
        }
        val snapshot = db.classificationSnapshotDao().findForDraft(draftId)
        val operations = db.workflowOperationDao().findPage("REQUEST_CLASSIFICATION", 100, 0)
        val activeCategories = db.categoryDao().findAll().filterNot { it.archived }
        val callIds = mutableSetOf<String>()
        var callCount = 0
        var allDescriptionArgumentsMatch = true
        val descriptionArgumentEvidence = JSONArray()
        val results = JSONArray()
        for (turn in observedTurns) {
            val calls = turn.optJSONArray("calls") ?: JSONArray()
            for (index in 0 until calls.length()) {
                val call = calls.getJSONObject(index)
                if (call.optString("name") == "classify_draft") {
                    val callId = call.optString("call_id")
                    val arguments = call.optJSONObject("arguments")
                    val supplied = arguments?.optString("description")
                    val matches = arguments?.has("description") == true && supplied == description
                    allDescriptionArgumentsMatch = allDescriptionArgumentsMatch && matches
                    descriptionArgumentEvidence.put(JSONObject()
                        .put("call_id", callId)
                        .put("description_sha256", supplied?.let {
                            sha256(it.toByteArray(Charsets.UTF_8))
                        } ?: JSONObject.NULL)
                        .put("matches_current_description", matches))
                    callCount += 1
                    callIds += callId
                }
            }
            val toolResults = turn.optJSONArray("tool_results") ?: JSONArray()
            for (index in 0 until toolResults.length()) {
                val result = toolResults.getJSONObject(index)
                if (result.optString("call_id") !in callIds) continue
                val output = runCatching { JSONObject(result.getString("output_json")) }.getOrNull()
                results.put(JSONObject()
                    .put("status", output?.optString("status") ?: "unparseable")
                    .put("reason", output?.optString("reason") ?: JSONObject.NULL))
            }
        }
        val hasModelDistribution = snapshot != null &&
            snapshot.candidateIds.isNotBlank() && snapshot.probabilities.isNotBlank() &&
            snapshot.unknownShare != null && snapshot.confidence != null
        val serviceBudgetRefusal = (0 until results.length()).any { index ->
            val result = results.getJSONObject(index)
            result.optString("status") == "refused" &&
                result.optString("reason") == "model_input_over_budget"
        }
        val isOversizeCase = caseId == budget.getString("oversize_case")
        return JSONObject()
            .put("requested", true)
            .put("case_id", caseId)
            .put("draft_id", draftId)
            .put("description_characters", description.length)
            .put("description_sha256", sha256(description.toByteArray(Charsets.UTF_8)))
            .put("tokenizer_asset_sha256", sha256(tokenizerBytes))
            .put("primary_description_tokens", tokenCount)
            .put("max_len_tokens", maxLen)
            .put("head_max_len_tokens", headMaxLen)
            .put("guaranteed_primary_text_room_tokens", room)
            .put("description_argument_evidence", descriptionArgumentEvidence)
            .put("all_classification_descriptions_match_current", if (callCount > 0) {
                allDescriptionArgumentsMatch
            } else {
                JSONObject.NULL
            })
            .put("over_budget", tokenCount > room)
            .put("active_categories", JSONArray().also { out -> activeCategories.forEach { category -> out.put(JSONObject()
                .put("id", category.id).put("name", category.name)) } })
            .put("classification_tool_call_count", callCount)
            .put("classification_tool_results", results)
            .put("persisted_snapshot", snapshot != null)
            .put("model_distribution_evidence", hasModelDistribution)
            .put("snapshot_decision_source", snapshot?.decisionSource ?: JSONObject.NULL)
            .put("snapshot_candidate_ids_json", snapshot?.candidateIds ?: JSONObject.NULL)
            .put("snapshot_probabilities_json", snapshot?.probabilities ?: JSONObject.NULL)
            .put("snapshot_unknown_share", snapshot?.unknownShare ?: JSONObject.NULL)
            .put("classification_operation_count", operations.size)
            .put("service_budget_refusal_observed", serviceBudgetRefusal)
            .put(
                "oversize_no_truncation_or_substitution",
                isOversizeCase && tokenCount > room && (callCount == 0 || allDescriptionArgumentsMatch),
            )
            .put(
                "oversize_refusal_pre_effect",
                isOversizeCase && tokenCount > room && snapshot == null && operations.isEmpty() &&
                    draft.reviewStatus == IntakeReviewStatus.PENDING && draft.reviewRevision == 0 &&
                    (callCount == 0 || allDescriptionArgumentsMatch),
            )
    }

    private suspend fun maskingEvidence(
        case: JSONObject,
        db: LedgerDatabase,
        boundary: MaskedPromptBoundary,
        wire: RecordingTransport,
    ): JSONObject {
        val outboundClean = wire.rawSensitiveAbsent
        val inboundClean = !wire.rawSensitiveInboundDetected
        if (case.getString("family") != "privacy") {
            return JSONObject()
                .put("outbound_raw_absent", outboundClean)
                .put("inbound_raw_absent", inboundClean)
        }
        val captureId = case.getJSONObject("context").getJSONArray("captureIds").getLong(0)
        val source = requireNotNull(db.captureMessageDao().findById(captureId)?.bodyText)
        val masked = boundary.vault.maskText(source)
        val tokens = Regex("⟦(AMT|DATE|TIME|ACCT|BANK)-[^⟧]+⟧")
            .findAll(masked).map { it.groupValues[1] }.toList()
        val relationsPreserved = masked.contains("Salary") && masked.contains("to account ending") &&
            tokens.containsAll(listOf("AMT", "DATE", "ACCT"))
        val inbound = boundary.scanInbound("⟦AMT-${boundary.vault.scopeTag}-9999⟧")
        val unknownTokenRefused =
            inbound is InboundScan.Refused && inbound.reason == InboundRefusal.UNKNOWN_TOKEN
        return JSONObject()
            .put("outbound_raw_absent", outboundClean)
            .put("inbound_raw_absent", inboundClean)
            .put("relations_preserved", relationsPreserved)
            .put("unknown_token_refused", unknownTokenRefused)
            .put("exact_rehydration", boundary.vault.rehydrateText(masked) == source)
            .put("masked_source", masked)
    }

    private fun providerConfig(profile: String, credential: String): HostedProviderConfig = when (profile) {
        GOOGLE_PROFILE -> google("gemini-3.5-flash-lite", credential)
        GO_PROFILE -> HostedProviderConfig(
            protocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            endpoint = "https://opencode.ai/zen/go/v1/chat/completions",
            model = "deepseek-v4.1-flash",
            authentication = HostedAuthentication.Bearer(credential),
            budget = HostedGenerationBudget(8192, temperature = 0.1, parallelToolCalls = false),
            capabilities = HostedProviderCapabilities(true, true, false, true, 8192),
            clientHeaders = mapOf("x-opencode-session" to "s8.13-qualification", "User-Agent" to "aegis-f-s8.13-qualification/1.0"),
        )
        else -> error("unsupported S8.13 profile")
    }

    private fun protocolEvidenceLabel(protocol: HostedProtocol): String = when (protocol) {
        HostedProtocol.GEMINI_GENERATE_CONTENT -> "generate_content"
        HostedProtocol.OPENAI_CHAT_COMPLETIONS -> "chat_completions"
        HostedProtocol.OPENAI_RESPONSES -> "responses"
    }
    private fun google(model: String, credential: String) = HostedProviderConfig(
        protocol = HostedProtocol.GEMINI_GENERATE_CONTENT,
        endpoint = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
        model = model,
        authentication = HostedAuthentication.GoogleApiKey(credential),
        budget = HostedGenerationBudget(8192, reasoningEffort = HostedReasoningEffort.HIGH, temperature = 0.1),
        capabilities = HostedProviderCapabilities(true, true, true, true, 8192),
    )

    private fun ensurePinnedLayaModel(context: Context) {
        val provisioner = LayaModelProvisioner(context.filesDir) { context.assets.open("laya/laya_tokenizer.bin") }
        val source = File(context.filesDir, "s8-public-laya-source")
        runBlocking {
            if (provisioner.inspect() == LayaModelProvisioner.Status.Ready) return@runBlocking
            val names = listOf("laya.onnx", "laya.onnx.data", "laya_config.json")
            val missing = names.filterNot { File(source, it).isFile }
            check(missing.isEmpty()) { "pinned Laya source files are not staged: $missing" }
            val inputs = names.associateWith { name -> { FileInputStream(File(source, name)) } }
            check(provisioner.provision(inputs) == LayaModelProvisioner.Status.Ready) { "pinned Laya provisioning failed" }
        }
        source.deleteRecursively()
    }

    private fun outcomeTrace(value: WorkflowRunOutcome): JSONArray {
        val steps = when (value) {
            is WorkflowRunOutcome.Completed -> value.trace
            is WorkflowRunOutcome.Clarifying -> value.trace
            is WorkflowRunOutcome.Refused -> value.trace
            is WorkflowRunOutcome.Incomplete -> value.trace
        }
        return JSONArray().also { out ->
            steps.forEach { step ->
                out.put(JSONObject().put("state", step.state.name).put("detail", step.detail ?: JSONObject.NULL))
            }
        }
    }

    private fun outcomeCacheSupport(value: WorkflowRunOutcome): String? = when (value) {
        is WorkflowRunOutcome.Completed -> value.cacheSupport.name
        is WorkflowRunOutcome.Incomplete -> value.cacheSupport.name
        else -> null
    }

    private fun outcomeVerifiedEffects(value: WorkflowRunOutcome): JSONArray {
        val effects = (value as? WorkflowRunOutcome.Completed)?.verifiedEffects.orEmpty()
        return JSONArray().also { out ->
            effects.forEach { effect ->
                val row = when (effect) {
                    is WorkflowVerifiedEffect.EventReading -> JSONObject()
                        .put("kind", "event_reading").put("event_id", effect.eventId)
                        .put("capture_id", effect.captureId).put("revision", effect.revision)
                    is WorkflowVerifiedEffect.LinkState -> JSONObject()
                        .put("kind", "link_state").put("link_id", effect.linkId)
                        .put("event_id", effect.eventId).put("draft_id", effect.draftId)
                        .put("state", effect.state.name).put("revision", effect.revision)
                    is WorkflowVerifiedEffect.Classification -> JSONObject()
                        .put("kind", "classification").put("draft_id", effect.draftId)
                        .put("source", effect.source.name).put("review_status", effect.reviewStatus.name)
                    is WorkflowVerifiedEffect.ClassificationUnavailable -> JSONObject()
                        .put("kind", "classification_unavailable").put("draft_id", effect.draftId)
                        .put("reason", effect.reasonCode).put("review_status", effect.reviewStatus.name)
                    is WorkflowVerifiedEffect.ReplayIdentity -> JSONObject()
                        .put("kind", "replay_identity").put("operation_key", effect.operationKey)
                        .put("current_effect_matches", effect.currentEffectMatches)
                }
                out.put(row)
            }
        }
    }

    private fun outcomeName(value: WorkflowRunOutcome) = when (value) {
        is WorkflowRunOutcome.Completed -> "Completed"
        is WorkflowRunOutcome.Clarifying -> "Clarifying"
        is WorkflowRunOutcome.Refused -> "Refused"
        is WorkflowRunOutcome.Incomplete -> "Incomplete:${value.reason.name}"
    }

    private fun outcomeTurns(value: WorkflowRunOutcome) = when (value) {
        is WorkflowRunOutcome.Completed -> value.turns
        is WorkflowRunOutcome.Incomplete -> value.turns
        else -> 0
    }

    private fun outcomeCalls(value: WorkflowRunOutcome) = when (value) {
        is WorkflowRunOutcome.Completed -> value.toolCalls
        is WorkflowRunOutcome.Incomplete -> value.toolCalls
        else -> 0
    }

    private fun outcomeUsage(value: WorkflowRunOutcome): JSONArray {
        val rows = when (value) {
            is WorkflowRunOutcome.Completed -> value.usage
            is WorkflowRunOutcome.Incomplete -> value.usage
            else -> emptyList()
        }
        return JSONArray().also { out -> rows.forEach { u -> out.put(JSONObject()
            .put("input_tokens", u.inputTokens ?: JSONObject.NULL).put("output_tokens", u.outputTokens ?: JSONObject.NULL)
            .put("reasoning_tokens", u.reasoningTokens ?: JSONObject.NULL).put("cached_input_tokens", u.cachedInputTokens ?: JSONObject.NULL)) } }
    }

    private class RecordingTransport(
        private val delegate: HttpTransport,
        private val config: HostedProviderConfig,
        private val protocolLabel: String,
        private val vault: SensitiveVault,
        private val evidenceFile: File,
    ) : HttpTransport {
        private val exchanges = JSONArray()
        var rawSensitiveAbsent = true
            private set
        var rawSensitiveInboundDetected = false
            private set

        override suspend fun post(request: HostedHttpRequest): HostedHttpResponse {
            val body = request.body.toString()
            val leak = vault.scanLeak(body)
            rawSensitiveAbsent = rawSensitiveAbsent && !leak
            val safeRequest = JSONObject(request.body.toString()).also(::stripExecutionMetadata)
            val item = JSONObject().put("protocol", protocolLabel)
                .put("header_names", JSONArray(request.headers.keys.sorted()))
                .put("request", if (leak) "[raw_sensitive_literal_detected]" else safeRequest)
            exchanges.put(item)
            if (leak) {
                item.put("transport_failure_type", "masked_egress_blocked")
                persistEvidence("masking_blocked")
                throw java.io.IOException("raw sensitive request blocked before network egress")
            }
            persistEvidence("in_flight")
            return try {
                val response = delegate.post(request)
                val safeBody = config.authentication.redact(response.body)
                item.put("http_status", response.status)
                rawSensitiveInboundDetected = rawSensitiveInboundDetected || vault.scanLeak(safeBody)
                item.put(
                    "response",
                    if (vault.scanLeak(safeBody)) "[raw_sensitive_response_redacted]"
                    else sanitizedProviderResponse(safeBody),
                )
                persistEvidence("response_recorded")
                response
            } catch (failure: Throwable) {
                item.put("transport_failure_type", failure::class.simpleName ?: "unknown")
                item.put("transport_failure", config.authentication.redact(failure.message ?: "no detail").take(300))
                persistEvidence("transport_error")
                throw failure
            }
        }

        fun evidence() = JSONArray(exchanges.toString())
        private fun persistEvidence(status: String) {
            writeJsonAtomically(
                evidenceFile,
                JSONObject()
                    .put("case_id", evidenceFile.name.removeSuffix(".provider-attempts.json"))
                    .put("attempt_status", status)
                    .put("exchanges", evidence())
                    .toString(2),
            )
        }
    }

    private class RecordingAdapter(
        private val delegate: HostedGenerationAdapter,
        private val turns: MutableList<JSONObject>,
        private val vault: SensitiveVault,
        private val afterGeneration: suspend (HostedGenerationResult) -> Unit,
    ) : HostedGenerationAdapter by delegate {
        override suspend fun generate(conversation: HostedConversation, tools: List<HostedToolDeclaration>): HostedGenerationResult {
            val result = delegate.generate(conversation, tools)
            afterGeneration(result)
            val row = JSONObject()
            when (result) {
                is HostedGenerationResult.Turn -> {
                    val assistant = result.turn.assistantText
                    val requestedModel = result.turn.requestedModel
                    val reportedModel = result.turn.reportedModel
                    val echoVerified = !requestedModel.isNullOrBlank() && !reportedModel.isNullOrBlank()
                    row.put("model_echo_verified", echoVerified)
                        .put(
                            "model_echo_matches",
                            if (echoVerified) requestedModel == reportedModel else JSONObject.NULL,
                        )
                        .put("assistant_text", if (assistant != null && vault.scanLeak(assistant)) {
                            row.put("raw_sensitive_inbound_detected", true)
                            "[raw_sensitive_assistant_text_redacted]"
                        } else {
                            assistant ?: JSONObject.NULL
                        })
                    row.put("calls", JSONArray().also { calls -> result.turn.toolCalls.forEach { call ->
                        val args = call.arguments?.let(::JSONObject)
                        val rawLeak = (args != null && vault.scanLeak(args.toString())) ||
                            (call.argumentsJson != null && vault.scanLeak(call.argumentsJson))
                        if (rawLeak) row.put("raw_sensitive_inbound_detected", true)
                        calls.put(JSONObject().put("call_id", call.callId)
                            .put("name", call.name).put("disposition", call.disposition.name)
                            .put("arguments", if (rawLeak) "[raw_sensitive_tool_arguments_redacted]" else args ?: JSONObject.NULL)
                            .put("arguments_json", if (rawLeak) "[raw_sensitive_tool_arguments_redacted]" else call.argumentsJson ?: JSONObject.NULL))
                    } })
                }
                is HostedGenerationResult.ProviderFailure ->
                    row.put("provider_failure", result.failure::class.simpleName ?: "unknown")
            }
            turns += row
            return result
        }

        override fun continueWithToolResults(conversation: HostedConversation, results: List<HostedToolResult>): HostedConversation {
            val last = turns.lastOrNull()
            last?.put("tool_results", JSONArray().also { out -> results.forEach { result ->
                val leak = vault.scanLeak(result.outputJson)
                if (leak) last.put("raw_sensitive_inbound_detected", true)
                out.put(JSONObject().put("call_id", result.callId)
                    .put("output_json", if (leak) "[raw_sensitive_tool_result_redacted]" else result.outputJson))
            } })
            return delegate.continueWithToolResults(conversation, results)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }
    private fun JSONArray.longSet(): Set<Long> = buildSet { for (i in 0 until length()) add(getLong(i)) }

    private companion object {
        const val GOOGLE_KEY = "GOOGLE_AI_STUDIO_KEY"
        const val GO_KEY = "OPENCODE_GO_API_KEY"
        const val GOOGLE_PROFILE = "candidate-a"
        const val GO_PROFILE = "candidate-b"
        const val DIAGNOSTIC_ATTEMPT_ID = "S8.13-replan-a9"
        const val DIAGNOSTIC_BATCH_ID = "S8.13-repair-diagnostic-batch-01"
        val DIAGNOSTIC_CASE_IDS = listOf("a8-recover-01", "a8-retry-01", "a8h-retry-01")
        val PROFILE_IDS = setOf(GOOGLE_PROFILE, GO_PROFILE)
    }
}
private fun stripExecutionMetadata(value: JSONObject) {
    val removed = mutableListOf<String>()
    val names = value.keys()
    while (names.hasNext()) {
        val name = names.next()
        val normalized = name.lowercase(Locale.ROOT)
        if (normalized.contains("model") || normalized.contains("provider")
            || normalized.contains("effort") || normalized.contains("thinking")
            || normalized == "temperature" || normalized == "top_p"
        ) {
            removed += name
        } else {
            when (val nested = value.opt(name)) {
                is JSONObject -> stripExecutionMetadata(nested)
                is JSONArray -> stripExecutionMetadata(nested)
            }
        }
    }
    removed.forEach(value::remove)
}

private fun stripExecutionMetadata(values: JSONArray) {
    for (index in 0 until values.length()) {
        when (val nested = values.opt(index)) {
            is JSONObject -> stripExecutionMetadata(nested)
            is JSONArray -> stripExecutionMetadata(nested)
        }
    }
}

private fun sanitizedProviderResponse(text: String): String =
    runCatching {
        JSONObject(text).also(::stripExecutionMetadata).toString()
    }.getOrElse { "[non-JSON response omitted; status and failure class retained separately]" }

private fun writeJsonAtomically(file: File, content: String) {
    requireNotNull(file.parentFile).mkdirs()
    val temporary = File(file.parentFile, "${file.name}.tmp")
    temporary.writeText(content)
    if (!temporary.renameTo(file)) {
        if (file.exists()) file.delete()
        check(temporary.renameTo(file)) { "could not persist S8.13 evidence record" }
    }
}
