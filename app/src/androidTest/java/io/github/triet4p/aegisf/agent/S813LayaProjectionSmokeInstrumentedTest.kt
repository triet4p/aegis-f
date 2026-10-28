package io.github.triet4p.aegisf.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.triet4p.aegisf.data.AttachLinkRequest
import io.github.triet4p.aegisf.data.CaptureKind
import io.github.triet4p.aegisf.data.CaptureMessageEntity
import io.github.triet4p.aegisf.data.CaptureSourceKind
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.ClassificationEngineResult
import io.github.triet4p.aegisf.data.ContextLinkState
import io.github.triet4p.aegisf.data.DraftCandidateQuery
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.NoteEventEntity
import io.github.triet4p.aegisf.data.NoteEventStatus
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.ProposeLinkRequest
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.SystemLedgerClock
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowDenialReason
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.data.noteEventIdentity
import io.github.triet4p.aegisf.data.laya.LayaCandidatePlanner
import io.github.triet4p.aegisf.data.laya.LayaClassificationEngine
import io.github.triet4p.aegisf.data.laya.LayaEffectiveContext
import io.github.triet4p.aegisf.data.laya.LayaEffectiveContextComposer
import io.github.triet4p.aegisf.data.laya.LayaInferenceLifecycle
import io.github.triet4p.aegisf.data.laya.LayaInferenceOutcome
import io.github.triet4p.aegisf.data.laya.LayaModelProvisioner
import io.github.triet4p.aegisf.data.laya.LayaRuntimeContract
import io.github.triet4p.aegisf.data.laya.LayaSequence
import io.github.triet4p.aegisf.data.laya.LayaTokenizer
import io.github.triet4p.aegisf.privacy.MaskedPath
import io.github.triet4p.aegisf.privacy.MaskedPromptBoundary
import io.github.triet4p.aegisf.privacy.SensitiveVault
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual local-Laya evidence for the versioned S8.13 projection, without hosted calls. */
@RunWith(AndroidJUnit4::class)
class S813LayaProjectionSmokeInstrumentedTest {
    @Test
    fun actualLayaLogitsAndOversizeRefusalUseTheProductionServices() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentationContext = InstrumentationRegistry.getInstrumentation().context
        val fixtureBytes = instrumentationContext.assets.open("s8_agent_fixture.json").use { it.readBytes() }
        val projectionBytes = instrumentationContext.assets.open("s8_agent_runtime_fixture_v3.json").use { it.readBytes() }
        val fixture = JSONObject(String(fixtureBytes, Charsets.UTF_8))
        val projection = JSONObject(String(projectionBytes, Charsets.UTF_8))
        assertEquals("aegis-f-s8-agent-runtime-fixture-projection/v3", projection.getString("schema"))
        assertEquals(
            sha256(fixtureBytes),
            projection.getJSONObject("base_inputs").getJSONObject("fixture").getString("sha256"),
        )
        assertNotNull(AgentToolDeclarations.load(context.assets::open))
        assertNotNull(WorkflowSkillCatalog.load(context.assets::open))
        ensurePinnedLayaModel(context)
        val lifecycle = LayaInferenceLifecycle(File(context.filesDir, "laya/model"))
        try {
            val preparation = lifecycle.prepare()
            assertTrue("pinned local Laya did not prepare: $preparation", preparation is LayaInferenceOutcome.Completed)

            val coffeeFixture = runtimeFixtureForCase(fixture, projection, "a8-laya-01")
            val busFareFixture = runtimeFixtureForCase(fixture, projection, "a8h-laya-01")
            val oversizeFixture = runtimeFixtureForCase(fixture, projection, "a8-laya-02")
            val coffeeDescription = draftDescription(coffeeFixture)
            val busFareDescription = draftDescription(busFareFixture)
            val oversizeDescription = draftDescription(oversizeFixture)
            assertEquals("coffee at noon", expectedDescription(instrumentationContext, "a8-laya-01"))
            assertEquals("bus fare", expectedDescription(instrumentationContext, "a8h-laya-01"))
            assertEquals(coffeeDescription, expectedDescription(instrumentationContext, "a8-laya-01"))
            assertEquals(busFareDescription, expectedDescription(instrumentationContext, "a8h-laya-01"))
            val evidence = JSONArray()
            evidence.put(runAdapterCase(
                context, coffeeFixture, projection, lifecycle, "a8-laya-01",
                coffeeDescription, coffeeDescription, oversized = false,
            ))
            evidence.put(runAdapterCase(
                context, busFareFixture, projection, lifecycle, "a8h-laya-01",
                busFareDescription, busFareDescription, oversized = false,
            ))
            evidence.put(runAdapterCase(
                context, oversizeFixture, projection, lifecycle, "a8-laya-02",
                oversizeDescription, oversizeDescription, oversized = true,
            ))
            val linkEvidence = assertLinkFixturePreflight(context, fixture, projection, lifecycle)
            val record = JSONObject()
                .put("task_id", "S8.13")
                .put("attempt_id", "S8.13-replan-a7")
                .put("projection_id", projection.getString("projection_id"))
                .put("synthetic_only", true)
                .put("hosted_provider_calls", 0)
                .put("laya_max_len_tokens", LayaRuntimeContract.MAX_LEN)
                .put("laya_head_max_len_tokens", LayaRuntimeContract.HEAD_MAX_LEN)
                .put("guaranteed_primary_text_room_tokens", LayaEffectiveContextComposer.guaranteedRoomTokens(
                    LayaRuntimeContract.MAX_LEN,
                    LayaRuntimeContract.HEAD_MAX_LEN,
                ))
                .put("runtime_prepare", "pinned local model opened with its production probe")
                .put("cases", evidence)
                .put("link_preflight", linkEvidence)
            val output = File(context.filesDir, "S8.13-replan-a7/laya-smoke.json")
            output.parentFile?.mkdirs()
            output.writeText(record.toString(2))
        } finally {
            lifecycle.close()
        }
    }

    private suspend fun runAdapterCase(
        context: Context,
        fixture: JSONObject,
        projection: JSONObject,
        lifecycle: LayaInferenceLifecycle,
        caseId: String,
        storedDescription: String,
        requestedDescription: String,
        oversized: Boolean,
    ): JSONObject {
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val now = System.currentTimeMillis()
            val categorySeeds = projection.getJSONObject("category_seeds_by_case").getJSONArray(caseId)
            val categoryIds = JSONArray()
            val categoryNames = JSONArray()
            for (index in 0 until categorySeeds.length()) {
                val category = categorySeeds.getJSONObject(index)
                val categoryId = database.categoryDao().insert(
                    CategoryEntity(
                        name = category.getString("name"),
                        createdAtEpochMillis = now,
                        updatedAtEpochMillis = now,
                    ),
                )
                assertTrue("synthetic category was not inserted", categoryId > 0L)
                categoryIds.put(categoryId)
                categoryNames.put(category.getString("name"))
            }
            val fixtureDraft = fixture.getJSONArray("drafts").let { rows ->
                (0 until rows.length()).map(rows::getJSONObject).single { it.getLong("id") == DRAFT_ID }
            }
            seedDraft(database, fixtureDraft, storedDescription, now)
            val scopeId = "s813-smoke-$caseId"
            val scope = WorkflowCallerScope.AgentSession(scopeId, emptySet(), setOf(DRAFT_ID))
            val boundary = MaskedPromptBoundary(SensitiveVault(scopeId))
            val commands = WorkflowCommandService(
                database = database,
                clock = SystemLedgerClock,
                timezoneId = { fixture.getString("timezone") },
                engine = LayaClassificationEngine(lifecycle),
            )
            val queries = WorkflowQueryService(database)
            val declarations = AgentToolDeclarations.load(context.assets::open)
            val registry = AgentReferenceRegistry(scopeId)
            val runtime = AgentToolRuntime(
                commands = commands,
                queries = queries,
                session = AgentToolSession(scope, boundary, registry),
                declarations = declarations,
            )
            val draftPage = requireNotNull(runtime.initialDraftObservation()) {
                "the scoped draft was not observable"
            }
            val draftRef = draftPage.getJSONArray("items").getJSONObject(0).getString("ref")
            val tokenizerBytes = context.assets.open("laya/laya_tokenizer.bin").use { it.readBytes() }
            val tokenCount = DataInputStream(tokenizerBytes.inputStream()).use { input ->
                LayaTokenizer.load(input).encode(LayaSequence.scrub(requestedDescription)).size
            }
            val room = LayaEffectiveContextComposer.guaranteedRoomTokens(
                LayaRuntimeContract.MAX_LEN,
                LayaRuntimeContract.HEAD_MAX_LEN,
            )
            if (oversized) assertTrue("projected input is not actually oversized: $tokenCount <= $room", tokenCount > room)
            var overbudgetEngineEvidence: JSONObject? = null
            if (oversized) {
                val result = LayaClassificationEngine(lifecycle).classify(
                    LayaEffectiveContext.of(requestedDescription),
                    database.categoryDao().findAll().filterNot { it.archived },
                )
                val decided = (result as? ClassificationEngineResult.Decided)?.decision
                    ?: error("the prepared local Laya engine did not return an over-budget decision")
                val unknown = decided.decision as? LayaCandidatePlanner.Decision.Unknown
                    ?: error("over-budget input unexpectedly produced a category choice")
                assertEquals(
                    LayaCandidatePlanner.ManualReason.EFFECTIVE_INPUT_OVER_BUDGET,
                    unknown.reason,
                )
                assertEquals(requestedDescription, decided.effectiveInput.text)
                assertEquals(tokenCount, decided.effectiveInput.tokenCount)
                assertEquals(room, decided.effectiveInput.roomTokens)
                assertEquals(
                    io.github.triet4p.aegisf.data.laya.LayaEffectiveInput.Refusal.PRIMARY_EXCEEDS_ROOM,
                    decided.effectiveInput.refusal,
                )
                assertNull(decided.evaluation)
                overbudgetEngineEvidence = JSONObject()
                    .put("decision", unknown.reason.name)
                    .put("effective_input_sha256", sha256(decided.effectiveInput.text.toByteArray(Charsets.UTF_8)))
                    .put("effective_input_matches_requested", decided.effectiveInput.text == requestedDescription)
                    .put("token_count", decided.effectiveInput.tokenCount)
                    .put("room_tokens", decided.effectiveInput.roomTokens)
                    .put("refusal", decided.effectiveInput.refusal?.name)
                    .put("evaluation_present", decided.evaluation != null)
            }
            val toolResult = runtime.execute(
                "classify_draft",
                JSONObject().put("draft_ref", draftRef),
                "s813-smoke-$caseId-classify",
            ).envelope
            var shortenedResult: JSONObject? = null
            val snapshot = database.classificationSnapshotDao().findForDraft(DRAFT_ID)
            var operations = database.workflowOperationDao().findPage(null, 100, 0)
            val hasModelDistribution = snapshot != null && snapshot.candidateIds.isNotBlank() &&
                snapshot.probabilities.isNotBlank() && snapshot.unknownShare != null && snapshot.confidence != null
            if (oversized) {
                assertEquals("error", toolResult.getString("outcome"))
                assertEquals("INPUT_OVER_BUDGET", toolResult.getJSONObject("error").getString("code"))
                assertNull(snapshot)
                assertTrue(operations.isEmpty())
                assertTrue(database.transactionDao().findAll().isEmpty())
                val unchanged = requireNotNull(database.pendingBankDraftDao().findById(DRAFT_ID))
                assertEquals(IntakeReviewStatus.PENDING, unchanged.reviewStatus)
                assertEquals(0, unchanged.reviewRevision)
                assertNull(unchanged.reviewCategoryId)
                // No caller text exists any more: the only input is the stored description,
                // so a retry refuses the same oversize input instead of shortening it.
                shortenedResult = runtime.execute(
                    "classify_draft",
                    JSONObject().put("draft_ref", draftRef),
                    "s813-smoke-$caseId-classify-shortened",
                ).envelope
                assertEquals("error", shortenedResult!!.getString("outcome"))
                assertEquals("INPUT_OVER_BUDGET", shortenedResult!!.getJSONObject("error").getString("code"))
                operations = database.workflowOperationDao().findPage(null, 100, 0)
                assertTrue(operations.isEmpty())
            } else {
                assertEquals("ok", toolResult.getString("outcome"))
                assertEquals("applied", toolResult.getString("effect"))
                assertTrue("the exact text did not produce a stored model distribution", hasModelDistribution)
                assertEquals("MODEL", snapshot?.decisionSource)
                val expectedCandidateIds = (0 until categoryIds.length()).map { categoryIds.getLong(it) }.toSet()
                val recordedCandidateIds = snapshot?.candidateIds.orEmpty().split(',')
                    .mapNotNull(String::toLongOrNull).toSet()
                assertTrue("local ONNX distribution omitted a legitimate category", recordedCandidateIds.containsAll(expectedCandidateIds))
                assertTrue(operations.any { it.command == "REQUEST_CLASSIFICATION" && it.outcome == "APPLIED" })
                assertTrue(database.transactionDao().findAll().isEmpty())
            }
            return JSONObject()
                .put("case_id", caseId)
                .put("entrypoint", "agent_tool_runtime")
                .put("stored_description_sha256", sha256(storedDescription.toByteArray(Charsets.UTF_8)))
                .put("exact_input_matches_stored", requestedDescription == storedDescription)
                .put("requested_description_sha256", sha256(requestedDescription.toByteArray(Charsets.UTF_8)))
                .put("requested_description_characters", requestedDescription.length)
                .put("requested_description_tokens", tokenCount)
                .put("guaranteed_room_tokens", room)
                .put("oversized", oversized)
                .put("category_ids", categoryIds)
                .put("category_names", categoryNames)
                .put("tool_result", toolResult)
                .put("shortened_substitution_result", shortenedResult ?: JSONObject.NULL)
                .put("overbudget_engine_evidence", overbudgetEngineEvidence ?: JSONObject.NULL)
                .put("snapshot_present", snapshot != null)
                .put("model_distribution_evidence", hasModelDistribution)
                .put("snapshot_decision_source", snapshot?.decisionSource ?: JSONObject.NULL)
                .put("snapshot_candidate_ids_json", snapshot?.candidateIds ?: JSONObject.NULL)
                .put("snapshot_probabilities_json", snapshot?.probabilities ?: JSONObject.NULL)
                .put("snapshot_unknown_share", snapshot?.unknownShare ?: JSONObject.NULL)
                .put("classification_operation_count", operations.count { it.command == "REQUEST_CLASSIFICATION" })
                .put("ledger_rows", database.transactionDao().findAll().size)
                .put("oversize_no_persisted_effect", oversized && snapshot == null && operations.isEmpty())
                .put(
                    "oversize_no_truncation_or_substitution",
                    oversized && requestedDescription == storedDescription &&
                        shortenedResult?.optJSONObject("error")?.optString("code") == "INPUT_OVER_BUDGET",
                )
        } finally {
            database.close()
        }
    }
    private suspend fun assertLinkFixturePreflight(
        context: Context,
        baseFixture: JSONObject,
        projection: JSONObject,
        lifecycle: LayaInferenceLifecycle,
    ): JSONObject {
        val uniqueFixture = runtimeFixtureForCase(baseFixture, projection, "a8-link-01")
        val uniqueDatabase = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val unique = try {
            val now = System.currentTimeMillis()
            val event = fixtureRow(uniqueFixture, "events", 311)
            val capture = fixtureRow(uniqueFixture, "captures", event.getLong("captureId"))
            seedEvent(uniqueDatabase, uniqueFixture, event, capture, now)
            val draft = fixtureRow(uniqueFixture, "drafts", 411)
            seedDraft(uniqueDatabase, draft, draft.getString("description"), now)
            val scope = WorkflowCallerScope.AgentSession("s813-a7-link-unique", setOf(111L), setOf(411L))
            val commands = WorkflowCommandService(
                database = uniqueDatabase,
                clock = SystemLedgerClock,
                timezoneId = { uniqueFixture.getString("timezone") },
                engine = LayaClassificationEngine(lifecycle),
            )
            val proposed = commands.proposeLink(scope, ProposeLinkRequest("s813-a7-unique-propose", 311, 411))
            assertTrue("unique coherent pair was not proposed", proposed is WorkflowCommandOutcome.Applied)
            val attached = commands.attachLink(scope, AttachLinkRequest("s813-a7-unique-attach", 311, 411, 0))
            assertTrue("coherent scoped pair was not attachable", attached is WorkflowCommandOutcome.Applied)
            val storedLink = requireNotNull(uniqueDatabase.contextLinkDao().findByPair(311, 411))
            assertEquals(ContextLinkState.ACCEPTED, storedLink.state)
            assertTrue(uniqueDatabase.transactionDao().findAll().isEmpty())
            JSONObject()
                .put("event_id", 311)
                .put("draft_id", 411)
                .put("event_amount_vnd", event.getLong("amountVnd"))
                .put("draft_amount_vnd", draft.getLong("amountVnd"))
                .put("event_date", event.getString("anchorDate"))
                .put("draft_date", draft.getString("statedDate"))
                .put("propose", "APPLIED")
                .put("attach", "APPLIED")
                .put("link_state", storedLink.state.name)
                .put("ledger_rows", uniqueDatabase.transactionDao().findAll().size)
        } finally {
            uniqueDatabase.close()
        }

        val duplicateFixture = runtimeFixtureForCase(baseFixture, projection, "a8h-link-01")
        val duplicateDatabase = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val duplicate = try {
            val now = System.currentTimeMillis()
            val event = fixtureRow(duplicateFixture, "events", 313)
            val capture = fixtureRow(duplicateFixture, "captures", event.getLong("captureId"))
            seedEvent(duplicateDatabase, duplicateFixture, event, capture, now)
            for (id in listOf(421L, 422L)) {
                val draft = fixtureRow(duplicateFixture, "drafts", id)
                seedDraft(duplicateDatabase, draft, draft.getString("description"), now)
            }
            val queries = WorkflowQueryService(duplicateDatabase)
            val order = queries.draftCandidates(
                DraftCandidateQuery(
                    reviewStatus = IntakeReviewStatus.PENDING,
                    onlyPossibleDuplicates = false,
                    bounds = QueryPageRequest(limit = 5),
                ),
            ).items.map { it.id }
            assertEquals(listOf(422L, 421L), order)
            val scope = WorkflowCallerScope.AgentSession("s813-a7-link-duplicate", setOf(112L), setOf(421L, 422L))
            val commands = WorkflowCommandService(
                database = duplicateDatabase,
                clock = SystemLedgerClock,
                timezoneId = { duplicateFixture.getString("timezone") },
                engine = LayaClassificationEngine(lifecycle),
            )
            val proposed = commands.proposeLink(scope, ProposeLinkRequest("s813-a7-duplicate-propose", 313, 422))
            assertTrue("deterministic first query candidate was not proposed", proposed is WorkflowCommandOutcome.Applied)
            val denied = commands.attachLink(scope, AttachLinkRequest("s813-a7-duplicate-attach", 313, 422, 0))
                as? WorkflowCommandOutcome.Denied
                ?: error("the duplicate pair was not refused by attachment policy")
            assertEquals(WorkflowDenialReason.ATTACH_POLICY, denied.reason)
            val storedLink = requireNotNull(duplicateDatabase.contextLinkDao().findByPair(313, 422))
            assertEquals(ContextLinkState.PROPOSED, storedLink.state)
            assertTrue(duplicateDatabase.transactionDao().findAll().isEmpty())
            JSONObject()
                .put("event_id", 313)
                .put("candidate_query_order", JSONArray(order))
                .put("selected_before_generation", 422)
                .put("possible_duplicate_of", 421)
                .put("propose", "APPLIED")
                .put("attach", denied.reason.name)
                .put("link_state", storedLink.state.name)
                .put("ledger_rows", duplicateDatabase.transactionDao().findAll().size)
        } finally {
            duplicateDatabase.close()
        }

        val mismatchFixture = runtimeFixtureForCase(baseFixture, projection, "a8-nomatch-01")
        val mismatchDatabase = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val mismatch = try {
            val now = System.currentTimeMillis()
            val event = fixtureRow(mismatchFixture, "events", 312)
            val capture = fixtureRow(mismatchFixture, "captures", event.getLong("captureId"))
            seedEvent(mismatchDatabase, mismatchFixture, event, capture, now)
            val draft = fixtureRow(mismatchFixture, "drafts", 411)
            seedDraft(mismatchDatabase, draft, draft.getString("description"), now)
            val scope = WorkflowCallerScope.AgentSession("s813-a7-link-mismatch", setOf(119L), setOf(411L))
            val commands = WorkflowCommandService(
                database = mismatchDatabase,
                clock = SystemLedgerClock,
                timezoneId = { mismatchFixture.getString("timezone") },
                engine = LayaClassificationEngine(lifecycle),
            )
            val proposed = commands.proposeLink(scope, ProposeLinkRequest("s813-a7-mismatch-propose", 312, 411))
            assertTrue("in-scope mismatch proposal setup failed", proposed is WorkflowCommandOutcome.Applied)
            val denied = commands.attachLink(scope, AttachLinkRequest("s813-a7-mismatch-attach", 312, 411, 0))
                as? WorkflowCommandOutcome.Denied
                ?: error("the financial mismatch was not refused by attachment policy")
            assertEquals(WorkflowDenialReason.ATTACH_POLICY, denied.reason)
            assertTrue(mismatchDatabase.transactionDao().findAll().isEmpty())
            JSONObject()
                .put("event_id", 312)
                .put("draft_id", 411)
                .put("event_amount_vnd", event.getLong("amountVnd"))
                .put("draft_amount_vnd", draft.getLong("amountVnd"))
                .put("event_date", event.getString("anchorDate"))
                .put("draft_date", draft.getString("statedDate"))
                .put("attach", denied.reason.name)
                .put("ledger_rows", mismatchDatabase.transactionDao().findAll().size)
        } finally {
            mismatchDatabase.close()
        }
        return JSONObject()
            .put("successful_unique_attach", unique)
            .put("duplicate_requires_user_choice", duplicate)
            .put("financial_mismatch_refused", mismatch)
    }

    private suspend fun seedEvent(
        database: LedgerDatabase,
        fixture: JSONObject,
        event: JSONObject,
        capture: JSONObject,
        now: Long,
    ) {
        val captureId = capture.getLong("id")
        val source = capture.getString("sourceText")
        assertEquals(
            captureId,
            database.captureMessageDao().insert(
                CaptureMessageEntity(
                    id = captureId,
                    captureKind = CaptureKind.USER_NOTE,
                    sourceKind = CaptureSourceKind.USER_QUICK_CAPTURE,
                    sourceIdentity = "s813-a7-capture-$captureId",
                    bodyText = source,
                    capturedAtEpochMillis = now,
                    captureTimezone = fixture.getString("timezone"),
                    revision = capture.getInt("revision"),
                    originRef = null,
                ),
            ),
        )
        val start = event.optInt("spanStart", 0)
        val end = event.optInt("spanEnd", source.length)
        val eventId = event.getLong("id")
        val stored = NoteEventEntity(
            id = eventId,
            captureId = captureId,
            eventIdentity = noteEventIdentity(
                CaptureSourceKind.USER_QUICK_CAPTURE,
                "s813-a7-capture-$captureId",
                start,
                end,
            ),
            spanStart = start,
            spanEnd = end,
            normalizedText = event.optString("normalizedText").takeIf { it.isNotBlank() && it != "null" },
            amountVnd = event.optLong("amountVnd").takeIf { event.has("amountVnd") && !event.isNull("amountVnd") },
            merchant = event.optString("merchant").takeIf { it.isNotBlank() && it != "null" },
            anchorDate = event.optString("anchorDate").takeIf { it.isNotBlank() && it != "null" },
            anchorTime = event.optString("anchorTime").takeIf { it.isNotBlank() && it != "null" },
            anchorTimezone = event.optString("anchorTimezone").takeIf { it.isNotBlank() && it != "null" },
            intervalEndDate = null,
            intervalEndTime = null,
            extractionVersion = "s813-runtime-fixture-v3",
            unresolvedReason = null,
            status = NoteEventStatus.valueOf(event.getString("status")),
            revision = event.getInt("revision"),
            sourceRevision = capture.getInt("revision"),
            createdAtEpochMillis = now + eventId,
        )
        assertEquals(eventId, database.noteEventDao().insert(stored))
    }

    private fun fixtureRow(fixture: JSONObject, section: String, id: Long): JSONObject =
        fixture.getJSONArray(section).let { rows ->
            (0 until rows.length()).map(rows::getJSONObject).single { it.getLong("id") == id }
        }

    private suspend fun seedDraft(
        database: LedgerDatabase,
        fixture: JSONObject,
        description: String,
        now: Long,
    ) {
        val id = fixture.getLong("id")
        val amount = fixture.optLong("amountVnd").takeIf { fixture.has("amountVnd") && !fixture.isNull("amountVnd") }
        val date = fixture.optString("statedDate").takeIf { it.isNotBlank() && it != "null" }
        val time = fixture.optString("statedTime").takeIf { it.isNotBlank() && it != "null" }
        val inserted = database.pendingBankDraftDao().store(
            PendingBankDraftEntity(
                id = id,
                deliveryId = "s813-smoke-draft-$id",
                sourcePackage = "synthetic.fixture",
                notificationKey = null,
                postedAtEpochMillis = now,
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
                memoState = IntakeFieldState.PRESENT,
                memo = description,
                memoUnresolvedReason = null,
                reviewStatus = IntakeReviewStatus.valueOf(fixture.optString("reviewStatus", "PENDING")),
                possibleDuplicateOfDraftId = fixture.optLong("possibleDuplicateOfDraftId")
                    .takeIf { fixture.has("possibleDuplicateOfDraftId") && !fixture.isNull("possibleDuplicateOfDraftId") },
                reviewRevision = fixture.optInt("reviewRevision", 0),
                createdAtEpochMillis = now + id,
            ),
        )
        assertEquals(id, inserted)
    }

    private fun expectedDescription(context: Context, caseId: String): String {
        val suiteNames = listOf("s8_agent_dev.json", "s8_agent_heldout.json")
        for (suiteName in suiteNames) {
            val suite = context.assets.open(suiteName).use { JSONObject(String(it.readBytes(), Charsets.UTF_8)) }
            val cases = suite.getJSONArray("cases")
            for (index in 0 until cases.length()) {
                val candidate = cases.getJSONObject(index)
                if (candidate.getString("id") != caseId) continue
                return candidate.getJSONObject("expect").getJSONArray("calls")
                    .getJSONObject(0).getJSONObject("args").getString("description")
            }
        }
        error("frozen Laya case not found: $caseId")
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
            val rowIds = rowOverrides.keys()
            while (rowIds.hasNext()) {
                val rowId = rowIds.next()
                val row = fixtureRow(fixture, section, rowId.toLong())
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
            fixtureRow(fixture, "drafts", draftId).put("description", description)
        }
        return fixture
    }

    private fun draftDescription(fixture: JSONObject): String =
        fixtureRow(fixture, "drafts", DRAFT_ID).getString("description")

    private suspend fun ensurePinnedLayaModel(context: Context) {
        val provisioner = LayaModelProvisioner(context.filesDir) {
            context.assets.open("laya/laya_tokenizer.bin")
        }
        if (provisioner.inspect() == LayaModelProvisioner.Status.Ready) return
        val source = File(context.filesDir, "s8-public-laya-source")
        val names = listOf("laya.onnx", "laya.onnx.data", "laya_config.json")
        val missing = names.filterNot { File(source, it).isFile }
        assertTrue("pinned Laya source files are not staged: $missing", missing.isEmpty())
        val inputs = names.associateWith { name -> { FileInputStream(File(source, name)) } }
        assertEquals(LayaModelProvisioner.Status.Ready, provisioner.provision(inputs))
        source.deleteRecursively()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private companion object {
        const val DRAFT_ID = 431L
    }
}
