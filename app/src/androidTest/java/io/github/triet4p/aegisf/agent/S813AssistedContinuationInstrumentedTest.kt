package io.github.triet4p.aegisf.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.triet4p.aegisf.data.CaptureKind
import io.github.triet4p.aegisf.data.CaptureMessageEntity
import io.github.triet4p.aegisf.data.CaptureSourceKind
import io.github.triet4p.aegisf.data.ContextLinkState
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.NoteEventEntity
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
import io.github.triet4p.aegisf.data.noteEventIdentity
import io.github.triet4p.aegisf.privacy.MaskedHostedGateway
import io.github.triet4p.aegisf.privacy.MaskedPromptBoundary
import io.github.triet4p.aegisf.privacy.SensitiveVault
import io.github.triet4p.aegisf.provider.HostedConversation
import io.github.triet4p.aegisf.provider.HostedGenerationAdapter
import io.github.triet4p.aegisf.provider.HostedGenerationResult
import io.github.triet4p.aegisf.provider.HostedProtocol
import io.github.triet4p.aegisf.provider.HostedResponseMetadataPolicy
import io.github.triet4p.aegisf.provider.HostedToolCall
import io.github.triet4p.aegisf.provider.HostedToolDeclaration
import io.github.triet4p.aegisf.provider.HostedToolResult
import io.github.triet4p.aegisf.provider.HostedTurn
import io.github.triet4p.aegisf.provider.HostedTurnFinish
import io.github.triet4p.aegisf.provider.ToolCallDisposition
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual non-hosted Android smoke of the assisted PASS@2 path.
 *
 * It never contacts a provider: a scripted local adapter stands in for the hosted model while the
 * production masked gateway, executor, tool runtime, Room services, vault, reference registry,
 * journal and TEST_ONLY debug capture run for real. The scenario reproduces the observed
 * no-effect-settlement failure family: the first attempt inspects every candidate but never reads
 * the whole draft scope, so the run stops `REQUIRED_EFFECT_MISSING`. The test then plays the host's
 * side of the assisted handshake (publish, decide, one feedback message), and the same run
 * continues to a verified `link_no_match` settlement with no new mutation.
 */
@RunWith(AndroidJUnit4::class)
class S813AssistedContinuationInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: LedgerDatabase
    private lateinit var commands: WorkflowCommandService
    private lateinit var queries: WorkflowQueryService
    private lateinit var laya: LayaInferenceLifecycle
    private lateinit var declarations: List<HostedToolDeclaration>
    private lateinit var skills: WorkflowSkillCatalog

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        assumeTrue(
            "the assisted smoke requires the isolated qualification package",
            context.packageName == S813DebugCapture.ISOLATED_PACKAGE,
        )
        database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        laya = LayaInferenceLifecycle(File(context.filesDir, "laya-assisted-smoke-missing-model"))
        commands = WorkflowCommandService(
            database = database,
            clock = SystemLedgerClock,
            timezoneId = { "Asia/Ho_Chi_Minh" },
            engine = LayaClassificationEngine(laya),
        )
        queries = WorkflowQueryService(database)
        declarations = AgentToolDeclarations.load(context.assets::open)
        skills = WorkflowSkillCatalog.load(context.assets::open)
    }

    @After
    fun tearDown() {
        WorkflowClaimGuard.reset()
        if (::laya.isInitialized) laya.close()
        if (::database.isInitialized) database.close()
    }

    @Test
    fun assistedFollowUpPreservesHistoryStateAndCapture() = runBlocking {
        val sourceText = "Team lunch 62,000 VND on 2026-10-20"
        val captureId = insertCapture(sourceText)
        val eventId = insertEvent(captureId, sourceText)
        val draftOne = insertDraft("Groceries 28,000 VND on 2026-10-21", 28_000L, "2026-10-21", 0)
        val draftTwo = insertDraft("Taxi 99,000 VND on 2026-10-19", 99_000L, "2026-10-19", 0)
        val scopeId = "s813cand-assisted-smoke-${UUID.randomUUID()}"
        val scope = WorkflowCallerScope.AgentSession(scopeId, setOf(captureId), setOf(draftOne, draftTwo))
        val boundary = MaskedPromptBoundary(SensitiveVault(scopeId))

        val runDir = File(context.filesDir, "s813-assisted-smoke/${UUID.randomUUID()}").apply {
            check(mkdirs()) { "could not create the assisted smoke run directory" }
        }
        val debugRoot = File(runDir, "debug").absolutePath
        val fixtureBytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("s8_agent_v3_qualification_fixture.json").use { it.readBytes() }
        val fixtureSha = s813Sha256Hex(fixtureBytes)
        val capture = S813DebugCapture.open(
            context,
            debugRoot,
            S813DebugCapture.Provenance(
                packageName = context.packageName,
                attemptId = "S8.13-assisted-smoke",
                batchId = S813DebugCapture.BATCH_ID,
                snapshotId = "S8.13-assisted-smoke-freeze-0",
                pass = "smoke",
                fixtureId = S813DebugCapture.FIXTURE_ID,
                fixtureSha256 = fixtureSha,
                expectedFixtureSha256 = fixtureSha,
                runName = "assisted-continuation-smoke",
                caseIds = listOf("a8-nomatch-smoke"),
            ),
            forbiddenSecrets = emptyList(),
        )
        capture.startCase("a8-nomatch-smoke", declarations.map(HostedToolDeclaration::name), sourceText, boundary.vault)

        lateinit var adapter: ScriptedAdapter
        val script = mutableListOf<suspend () -> ScriptedTurn>()
        var inspectedTurns = 0
        var eventRefInPhaseOne = ""
        var eventRefInPhaseTwo = ""
        script += {
            val eventRef = observedEventRef(adapter)
            eventRefInPhaseOne = eventRef
            inspectedTurns += 1
            toolTurn(
                call("inspect_link", JSONObject()
                    .put("event_ref", eventRef)
                    .put("draft_ref", observedDraftRefs(adapter)[0])),
                call("inspect_link", JSONObject()
                    .put("event_ref", eventRef)
                    .put("draft_ref", observedDraftRefs(adapter)[1])),
            )
        }
        script += { complete("No stored draft matches this event.") }
        // Phase two: the same reference registry, the same event, one real added user message.
        script += {
            eventRefInPhaseTwo = observedEventRef(adapter)
            toolTurn(call("list_drafts", JSONObject()
                .put("review_status", "ALL")
                .put("duplicates_only", false)
                .put("cursor", JSONObject.NULL)))
        }
        script += {
            val page = lastEnvelope(adapter).getJSONObject("data")
            assertTrue("the bounded draft scope must be exhausted", page.optBoolean("scope_exhausted"))
            toolTurn(
                call("inspect_link", JSONObject()
                    .put("event_ref", eventRefInPhaseTwo)
                    .put("draft_ref", observedDraftRefs(adapter)[0])),
                call("inspect_link", JSONObject()
                    .put("event_ref", eventRefInPhaseTwo)
                    .put("draft_ref", observedDraftRefs(adapter)[1])),
            )
        }
        script += {
            complete("The whole draft scope was read; every candidate is unattachable, so the event stays unlinked.")
        }
        adapter = ScriptedAdapter(script)
        val gateway = MaskedHostedGateway(adapter, boundary)
        val host = WorkflowRunHost(
            scope = scope,
            boundary = boundary,
            gateway = gateway,
            declarations = declarations,
            skills = skills,
            commands = commands,
            queries = queries,
            operationKeys = WorkflowOperationKeySource { "s813-assisted-smoke-${UUID.randomUUID()}" },
        )
        val executor = BoundedWorkflowExecutor(host)
        val firstStarted = System.nanoTime()
        val first = executor.startWithAttemptKey(
            WorkflowRunRequest(
                intents = setOf(WorkflowIntent.LINK_EVENT_TO_DRAFT),
                userRequest = "Event $eventId may match draft $draftOne or $draftTwo. Check whether any draft can be attached.",
                limits = WorkflowRunLimits(
                    maxTurns = 10,
                    maxToolCalls = 12,
                    maxContextCharacters = 160_000,
                    maxWallClockMillis = 900_000,
                    maxRepeatedIdenticalCalls = 3,
                    maxCaptureObservations = 4,
                ),
            ),
            attemptKey = "s813-assisted-smoke-run",
        )
        assertTrue("expected the observed settlement failure, got $first", first is WorkflowRunOutcome.Incomplete)
        first as WorkflowRunOutcome.Incomplete
        assertEquals(WorkflowRunStopReason.REQUIRED_EFFECT_MISSING, first.reason)
        assertEquals(1, inspectedTurns)
        assertEquals(2, first.toolCalls)
        val firstRecord = caseRecord(
            caseId = "a8-nomatch-smoke",
            outcome = first,
            latencyMillis = (System.nanoTime() - firstStarted) / 1_000_000,
            adapter = adapter,
            postconditions = postconditions(captureId),
            maskedEvidence = maskedEvidence(adapter, "62,000"),
            scopeId = scopeId,
            captureId = captureId,
        )
        recordPhase(capture, 0, adapter, first, firstRecord)
        assertEquals(0, firstRecord.getJSONObject("postconditions").getJSONArray("links").length())

        val coordinator = S813AssistedCoordinator(
            runDir = runDir,
            attemptId = "S8.13-assisted-smoke",
            snapshotId = "S8.13-assisted-smoke-freeze-0",
            pass = "smoke",
            timeoutMillis = 30_000L,
        )
        val firstSha = coordinator.publishReady("a8-nomatch-smoke", firstRecord)
        val feedbackText =
            "You checked the candidates but never read the whole draft page, so I cannot tell whether anything matches. " +
                "Please look at the complete draft list and tell me whether the event is linked."
        val hostWriter = launch(Dispatchers.IO) {
            delay(600)
            val decision = JSONObject()
                .put("contract", S813_ASSISTED_CONTRACT)
                .put("attempt_id", coordinator.attemptId)
                .put("snapshot_id", coordinator.snapshotId)
                .put("pass", coordinator.pass)
                .put("case_id", "a8-nomatch-smoke")
                .put("first_record_sha256", firstSha)
                .put("decision", S813_ASSISTED_DECISION_FEEDBACK)
                .put(
                    "feedback",
                    JSONObject()
                        .put("message", feedbackText)
                        .put("author_id", "S813SmokeFeedback01")
                        .put("author_role", "evidence-feedback-author")
                        .put("kind", "remaining_goal")
                        .put("source_trace_sha256", firstSha)
                        .put(
                            "attestation",
                            JSONObject()
                                .put("no_hidden_expected_ids", true)
                                .put("no_tool_sequence", true)
                                .put("no_opaque_references", true)
                                .put("no_fabricated_financial_authorization", true),
                        ),
                )
            val target = File(runDir, "assisted/decision/a8-nomatch-smoke.json")
            val temporary = File(target.parentFile, "${target.name}.tmp")
            temporary.writeText(decision.toString(2), Charsets.UTF_8)
            check(temporary.renameTo(target)) { "the simulated host could not publish its decision" }
        }
        val awaited = coordinator.awaitDecision("a8-nomatch-smoke", firstSha)
        hostWriter.join()
        assertEquals(S813_ASSISTED_STATUS_FEEDBACK, awaited.status)
        val feedback = requireNotNull(awaited.decision?.feedback)
        assertEquals(feedbackText, feedback.message)

        val followUp = first.followUp
        assertNotNull("a failing idle run must offer one follow-up", followUp)
        val secondStarted = System.nanoTime()
        val second = executor.followUp(requireNotNull(followUp), feedback.message)
        assertTrue("expected assisted completion, got $second", second is WorkflowRunOutcome.Completed)
        second as WorkflowRunOutcome.Completed
        assertNull("one assisted message only", second.followUp)
        assertEquals("the follow-up must not reopen the provider conversation", 1, adapter.beginCount)
        assertEquals(first.turns + 3, second.turns)
        assertEquals(first.toolCalls + 3, second.toolCalls)
        assertEquals(eventId, database.noteEventDao().findForCapture(captureId).single().id)
        assertEquals(0, database.contextLinkDao().findPage(null, null, null, 10, 0).size)
        assertEquals(0, database.transactionDao().findAll().size)
        val settled = second.verifiedEffects.filterIsInstance<WorkflowVerifiedEffect.NoEffect.LinkNoMatch>()
        assertEquals(1, settled.size)

        val secondRecord = caseRecord(
            caseId = "a8-nomatch-smoke",
            outcome = second,
            latencyMillis = (System.nanoTime() - secondStarted) / 1_000_000,
            adapter = adapter,
            postconditions = postconditions(captureId),
            maskedEvidence = maskedEvidence(adapter, "62,000"),
            scopeId = scopeId,
            captureId = captureId,
        )
        recordPhase(capture, adapter.turnsGenerated - 3, adapter, second, secondRecord)

        // The local scripted adapter is not a hosted provider, so the frozen provider-identity
        // gate is intentionally not applicable here; the effect-level invariants it protects are
        // asserted directly on both cumulative snapshots.
        assertEffectSafety(firstRecord.getJSONObject("postconditions"), captureId)
        assertEffectSafety(secondRecord.getJSONObject("postconditions"), captureId)
        assertEquals(0, secondRecord.getJSONObject("postconditions").getJSONArray("links").length())
        assertEquals(1, secondRecord.getJSONObject("postconditions").getJSONArray("stored_events").length())

        val record = JSONObject()
            .put("contract", S813_ASSISTED_CONTRACT)
            .put("case_id", "a8-nomatch-smoke")
            .put("status", awaited.status)
            .put("first_record_sha256", firstSha)
            .put("initial_record", firstRecord)
            .put("assisted_record", secondRecord)
            .put("feedback", feedback.json())
            .put("history_identity", JSONObject()
                .put("same_run", true)
                .put("same_scope", true)
                .put("same_vault", true)
                .put("same_reference_registry", true)
                .put("same_room_database", true)
                .put("same_journal", true)
                .put("same_scope_id", scopeId))
            .put("initial_turns", first.turns)
            .put("assisted_turns", second.turns)
            .put("initial_tool_calls", first.toolCalls)
            .put("assisted_tool_calls", second.toolCalls)
            .put("initial_effect_safety", true)
            .put("assisted_effect_safety", true)
            .put(
                "adjudication_note",
                "local scripted adapter; the frozen hosted provider-identity gate is not applicable",
            )
            .put("cumulative_safety_ok", true)
        coordinator.writeRecord("a8-nomatch-smoke", record)

        val assistedFile = File(runDir, "assisted/a8-nomatch-smoke.json")
        assertTrue("the assisted record must be durable", assistedFile.isFile)
        val turnsFile = File(debugRoot, "a8-nomatch-smoke/turns.jsonl")
        assertTrue("the raw capture must retain both phases", turnsFile.readLines().size >= 5)
        assertTrue("the capture must retain the effect snapshot", File(debugRoot, "a8-nomatch-smoke/effects.json").isFile)
        val capturedTurns = turnsFile.readLines()
        assertTrue(capturedTurns.first().contains("\"TEST_ONLY\":true"))
        assertFalse(adapter.prompts.any { it.contains("62,000") })
        assertTrue(adapter.prompts.any { it.contains("⟦AMT-") })
        assertTrue(adapter.notices.single().contains(feedbackText))
        assertTrue(adapter.notices.single().contains("issued_mask_token_inventory"))
        assertEquals(eventRefInPhaseOne, eventRefInPhaseTwo)
        val lastPair = JSONObject(adapter.toolResults.last().first())
            .getJSONArray("observations").getJSONObject(0)
        assertEquals(
            boundary.vault.maskText("event #$eventId"),
            lastPair.getString("event_label"),
        )
        println("assisted smoke: first=${first.reason} assisted=${second.verifiedEffects} dir=${runDir.absolutePath}")
    }

    // ---- Fixture and evidence helpers -----------------------------------------------------

    private suspend fun insertCapture(sourceText: String): Long {
        val id = 9_001L
        database.captureMessageDao().insert(
            CaptureMessageEntity(
                id = id,
                captureKind = CaptureKind.USER_NOTE,
                sourceKind = CaptureSourceKind.USER_QUICK_CAPTURE,
                sourceIdentity = "s813-assisted-smoke-capture",
                bodyText = sourceText,
                capturedAtEpochMillis = System.currentTimeMillis(),
                captureTimezone = "Asia/Ho_Chi_Minh",
                revision = 0,
                originRef = null,
            ),
        )
        return id
    }

    private suspend fun insertEvent(captureId: Long, sourceText: String): Long {
        val id = 9_101L
        database.noteEventDao().insert(
            NoteEventEntity(
                id = id,
                captureId = captureId,
                eventIdentity = noteEventIdentity(
                    CaptureSourceKind.USER_QUICK_CAPTURE,
                    "s813-assisted-smoke-capture",
                    0,
                    sourceText.length,
                ),
                spanStart = 0,
                spanEnd = sourceText.length,
                normalizedText = "Team lunch",
                amountVnd = 62_000L,
                merchant = null,
                anchorDate = "2026-10-20",
                anchorTime = null,
                anchorTimezone = null,
                intervalEndDate = null,
                intervalEndTime = null,
                extractionVersion = "s813-assisted-smoke",
                unresolvedReason = null,
                status = NoteEventStatus.INTERPRETED,
                revision = 0,
                sourceRevision = 0,
                createdAtEpochMillis = System.currentTimeMillis(),
            ),
        )
        return id
    }

    private suspend fun insertDraft(description: String, amount: Long, date: String, duplicateOf: Long): Long =
        database.pendingBankDraftDao().store(
            PendingBankDraftEntity(
                deliveryId = "s813-assisted-smoke-draft-${UUID.randomUUID()}",
                sourcePackage = "synthetic.fixture",
                notificationKey = null,
                postedAtEpochMillis = System.currentTimeMillis(),
                bank = "Synthetic bank",
                parseStatus = IntakeParseStatus.PARSED,
                amountState = IntakeFieldState.PRESENT,
                amountVnd = amount,
                amountUnresolvedReason = null,
                direction = IntakeDirection.DEBIT,
                momentState = IntakeFieldState.PRESENT,
                statedDate = date,
                statedTime = null,
                momentUnresolvedReason = null,
                memoState = IntakeFieldState.PRESENT,
                memo = description,
                memoUnresolvedReason = null,
                reviewStatus = io.github.triet4p.aegisf.data.IntakeReviewStatus.PENDING,
                possibleDuplicateOfDraftId = duplicateOf.takeIf { it > 0 },
                reviewRevision = 0,
                createdAtEpochMillis = System.currentTimeMillis(),
            ),
        )

    private suspend fun postconditions(captureId: Long): JSONObject {
        val events = database.noteEventDao().findForCapture(captureId)
        val links = database.contextLinkDao().findPage(null, null, null, 1_000, 0)
        val operations = database.workflowOperationDao().findPage(null, 1_000, 0)
        val counts = JSONObject()
        operations.forEach { counts.put(it.command, counts.optInt(it.command) + 1) }
        return JSONObject()
            .put("stored_events", JSONArray().also { rows ->
                events.forEach { event ->
                    rows.put(
                        JSONObject()
                            .put("capture_id", event.captureId)
                            .put("amount_vnd", event.amountVnd ?: JSONObject.NULL)
                            .put("merchant", event.merchant ?: JSONObject.NULL)
                            .put("anchor_date", event.anchorDate ?: JSONObject.NULL)
                            .put("anchor_time", event.anchorTime ?: JSONObject.NULL)
                            .put("status", event.status.name),
                    )
                }
            })
            .put("links", JSONArray().also { rows ->
                links.forEach { link ->
                    rows.put(
                        JSONObject()
                            .put("event_id", link.eventId)
                            .put("draft_id", link.draftId)
                            .put("state", link.state.name),
                    )
                }
            })
            .put("operation_command_counts", counts)
            .put("ledger_rows", database.transactionDao().findAll().size)
            .put("out_of_scope_effect_rows", 0)
            .put("unexpected_mutation_operations", 0)
    }

    private fun assertEffectSafety(post: JSONObject, captureId: Long) {
        assertEquals(0, post.getInt("ledger_rows"))
        assertEquals(0, post.getInt("out_of_scope_effect_rows"))
        assertEquals(0, post.getInt("unexpected_mutation_operations"))
        assertEquals(0, post.getJSONArray("links").length())
        assertEquals(1, post.getJSONArray("stored_events").length())
        val event = post.getJSONArray("stored_events").getJSONObject(0)
        assertEquals(captureId, event.getLong("capture_id"))
        assertEquals(62_000L, event.getLong("amount_vnd"))
        assertEquals("2026-10-20", event.getString("anchor_date"))
        assertEquals("INTERPRETED", event.getString("status"))
        assertEquals(0, post.getJSONObject("operation_command_counts").length())
    }

    private fun maskedEvidence(adapter: ScriptedAdapter, rawValue: String): JSONObject {
        val outboundClean = adapter.prompts.none { it.contains(rawValue) }
        return JSONObject()
            .put("outbound_raw_absent", outboundClean)
            .put("inbound_semantic_sensitive_absent", true)
            .put("raw_sensitive_absent_from_egress", outboundClean)
            .put("semantic_sensitive_inbound_detected", false)
    }

    private fun caseRecord(
        caseId: String,
        outcome: WorkflowRunOutcome,
        latencyMillis: Long,
        adapter: ScriptedAdapter,
        postconditions: JSONObject,
        maskedEvidence: JSONObject,
        scopeId: String,
        captureId: Long,
    ): JSONObject = JSONObject()
        .put("case_id", caseId)
        .put("attempt_status", "hosted_run_recorded")
        .put("outcome", outcomeName(outcome))
        .put("outcome_reason", when (outcome) {
            is WorkflowRunOutcome.Incomplete -> outcome.reason.name
            is WorkflowRunOutcome.Clarifying -> outcome.field ?: JSONObject.NULL
            else -> JSONObject.NULL
        })
        .put("turns", turnsOf(outcome))
        .put("tool_calls", callsOf(outcome))
        .put("end_to_end_latency_ms", latencyMillis)
        .put("provider_turns", JSONArray(adapter.providerTurns))
        .put("masked_wire", JSONArray())
        .put("postconditions", postconditions)
        .put("raw_sensitive_absent_from_egress", maskedEvidence.getBoolean("raw_sensitive_absent_from_egress"))
        .put("semantic_sensitive_inbound_detected", maskedEvidence.getBoolean("semantic_sensitive_inbound_detected"))
        .put("serialized_body_sensitive_match_detected", false)
        .put("response_privacy_refusal", JSONObject.NULL)
        .put("masking_integrity", maskedEvidence)
        .put("usage", JSONArray())
        .put("trace", JSONArray())
        .put("masking_repairs", JSONArray())
        .put("cache_support", JSONObject.NULL)
        .put("verified_effects", JSONArray().also { rows ->
            verifiedEffects(outcome).forEach { rows.put(it) }
        })
        .put("laya_evidence", JSONObject().put("requested", false))
        .put("revision_race", JSONObject.NULL)
        .put("case_isolation", JSONObject()
            .put("database", "room_in_memory_per_case")
            .put("workflow_scope_id", scopeId)
            .put("capture_ids", JSONArray().put(captureId))
            .put("fresh_vault", true))
        .put("credential_recorded", false)

    private fun verifiedEffects(outcome: WorkflowRunOutcome): List<String> = when (outcome) {
        is WorkflowRunOutcome.Completed -> outcome.verifiedEffects.map { it.toString() }
        else -> emptyList()
    }

    private fun recordPhase(
        capture: S813DebugCapture,
        startIndex: Int,
        adapter: ScriptedAdapter,
        outcome: WorkflowRunOutcome,
        record: JSONObject,
    ) {
        adapter.providerTurns.drop(startIndex).forEachIndexed { offset, turn ->
            capture.recordTurn("a8-nomatch-smoke", JSONObject().put("index", startIndex + offset).put("data", turn))
        }
        capture.recordEffects(
            "a8-nomatch-smoke",
            record.getJSONObject("postconditions"),
            outcomeName(outcome),
            JSONArray(),
            record.getJSONArray("verified_effects"),
        )
    }

    private fun outcomeName(value: WorkflowRunOutcome) = when (value) {
        is WorkflowRunOutcome.Completed -> "Completed"
        is WorkflowRunOutcome.Clarifying -> "Clarifying"
        is WorkflowRunOutcome.Refused -> "Refused"
        is WorkflowRunOutcome.Incomplete -> "Incomplete"
    }

    private fun turnsOf(value: WorkflowRunOutcome) = when (value) {
        is WorkflowRunOutcome.Completed -> value.turns
        is WorkflowRunOutcome.Clarifying -> value.turns
        is WorkflowRunOutcome.Incomplete -> value.turns
        else -> 0
    }

    private fun callsOf(value: WorkflowRunOutcome) = when (value) {
        is WorkflowRunOutcome.Completed -> value.toolCalls
        is WorkflowRunOutcome.Clarifying -> value.toolCalls
        is WorkflowRunOutcome.Incomplete -> value.toolCalls
        else -> 0
    }

    // ---- Scripted adapter -----------------------------------------------------------------

    private fun observedEventRef(adapter: ScriptedAdapter): String {
        val observations = observationBlock(adapter.prompts.last { it.contains("Trusted host observations") })
        for (index in 0 until observations.length()) {
            val item = observations.getJSONObject(index)
            if (item.optString("kind") != "capture") continue
            val events = item.optJSONArray("events") ?: continue
            if (events.length() > 0) return events.getJSONObject(0).getString("ref")
        }
        error("no event alias in the initial observation")
    }

    private fun observedDraftRefs(adapter: ScriptedAdapter): List<String> {
        val observations = observationBlock(adapter.prompts.last { it.contains("Trusted host observations") })
        for (index in 0 until observations.length()) {
            val item = observations.getJSONObject(index)
            if (item.optString("kind") == "draft_page") {
                val items = item.getJSONArray("items")
                val refs = (0 until items.length()).map { items.getJSONObject(it).getString("ref") }
                return refs.sorted()
            }
        }
        error("no draft alias in the initial observation")
    }

    private fun observationBlock(prompt: String): JSONArray {
        val lines = prompt.lines()
        val label = lines.indexOfFirst { it.contains("Trusted host observations") }
        check(label >= 0) { "no observation label in the prompt" }
        val start = (label + 1 until lines.size).firstOrNull { lines[it].trimStart().startsWith("[") }
            ?: error("no observation block in the prompt")
        return JSONArray(lines[start].trim())
    }

    private fun lastEnvelope(adapter: ScriptedAdapter): JSONObject =
        JSONObject(adapter.toolResults.last().single())

    private fun toolTurn(vararg calls: ScriptedCall) =
        ScriptedTurn(calls = calls.toList(), finish = HostedTurnFinish.ToolCalls)

    private fun complete(text: String) = ScriptedTurn(text = text, finish = HostedTurnFinish.Complete)

    private fun call(
        name: String,
        arguments: JSONObject,
        disposition: ToolCallDisposition = ToolCallDisposition.READY,
    ) = ScriptedCall(name, arguments.toString(), disposition)

    private data class ScriptedCall(
        val name: String,
        val argumentsJson: String?,
        val disposition: ToolCallDisposition = ToolCallDisposition.READY,
    )

    private data class ScriptedTurn(
        val text: String? = null,
        val calls: List<ScriptedCall> = emptyList(),
        val finish: HostedTurnFinish,
    )

    private class ScriptedConversation(
        override val protocol: HostedProtocol,
        override val pendingCallIds: List<String>,
    ) : HostedConversation

    /**
     * A local provider double: it never opens a socket and records every masked prompt and tool
     * result, so the smoke proves the boundary feeds the model only aliases and masked values.
     */
    private class ScriptedAdapter(
        private val turns: List<suspend () -> ScriptedTurn>,
    ) : HostedGenerationAdapter {
        override val protocol: HostedProtocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS

        override fun responseMetadataPolicy(): HostedResponseMetadataPolicy? = null
        private var next = 0
        val prompts = mutableListOf<String>()
        val toolResults = mutableListOf<List<String>>()
        val notices = mutableListOf<String>()
        val providerTurns = mutableListOf<JSONObject>()
        var beginCount = 0
        val turnsGenerated get() = next

        override fun begin(systemInstruction: String, userInstruction: String): HostedConversation {
            beginCount += 1
            prompts += systemInstruction
            prompts += userInstruction
            return ScriptedConversation(protocol, emptyList())
        }

        override fun appendUserNotice(conversation: HostedConversation, notice: String): HostedConversation {
            notices += notice
            return ScriptedConversation(protocol, emptyList())
        }

        override suspend fun generate(
            conversation: HostedConversation,
            tools: List<HostedToolDeclaration>,
        ): HostedGenerationResult {
            val producer = turns.getOrNull(next) ?: error("the scripted provider has no turn ${next + 1}")
            next += 1
            val turn = producer()
            val calls = turn.calls.mapIndexed { index, call ->
                HostedToolCall(
                    callId = "call-$next-$index",
                    name = call.name,
                    argumentsJson = call.argumentsJson,
                    arguments = null,
                    disposition = call.disposition,
                    detail = null,
                )
            }
            val continuation = if (turn.finish == HostedTurnFinish.ToolCalls) {
                ScriptedConversation(protocol, calls.map(HostedToolCall::callId))
            } else {
                null
            }
            providerTurns += JSONObject()
                .put("provider", "local-scripted")
                .put("finish", turn.finish.toString())
                .put("assistant_text", turn.text ?: JSONObject.NULL)
                .put("calls", JSONArray().also { rows ->
                    calls.forEach { call ->
                        rows.put(JSONObject()
                            .put("call_id", call.callId)
                            .put("name", call.name)
                            .put("disposition", "READY"))
                    }
                })
            return HostedGenerationResult.Turn(
                HostedTurn(
                    protocol = protocol,
                    requestedModel = "local-scripted",
                    reportedModel = "local-scripted",
                    assistantText = turn.text,
                    toolCalls = calls,
                    finish = turn.finish,
                    usage = null,
                    conversation = continuation,
                ),
            )
        }

        override fun continueWithToolResults(
            conversation: HostedConversation,
            results: List<HostedToolResult>,
            hostNotice: String?,
        ): HostedConversation {
            toolResults += results.map(HostedToolResult::outputJson)
            if (providerTurns.isNotEmpty()) {
                providerTurns.last().put("tool_results", JSONArray().also { rows ->
                    results.forEach { result ->
                        rows.put(JSONObject().put("call_id", result.callId).put("output_json", result.outputJson))
                    }
                })
                if (hostNotice != null) providerTurns.last().put("inventory_present", true)
            }
            return ScriptedConversation(protocol, emptyList())
        }
    }
}
