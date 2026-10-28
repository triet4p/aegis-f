package io.github.triet4p.aegisf.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.data.CaptureEventQuery
import io.github.triet4p.aegisf.data.ContextLinkState
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.LinkOperationQuery
import io.github.triet4p.aegisf.data.NoteEventInterpretation
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.SaveCaptureRequest
import io.github.triet4p.aegisf.data.SystemLedgerClock
import io.github.triet4p.aegisf.data.UpdateEventRequest
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowChange
import io.github.triet4p.aegisf.data.WorkflowClaimGuard
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.data.laya.LayaClassificationEngine
import io.github.triet4p.aegisf.data.laya.LayaInferenceLifecycle
import io.github.triet4p.aegisf.privacy.MaskedHostedGateway
import io.github.triet4p.aegisf.privacy.MaskedPath
import io.github.triet4p.aegisf.privacy.MaskedPromptBoundary
import io.github.triet4p.aegisf.privacy.SensitiveVault
import io.github.triet4p.aegisf.provider.HostedConversation
import io.github.triet4p.aegisf.provider.HostedGenerationAdapter
import io.github.triet4p.aegisf.provider.HostedGenerationResult
import io.github.triet4p.aegisf.provider.HostedProtocol
import io.github.triet4p.aegisf.provider.HostedToolCall
import io.github.triet4p.aegisf.provider.HostedToolDeclaration
import io.github.triet4p.aegisf.provider.HostedToolResult
import io.github.triet4p.aegisf.provider.HostedTurn
import io.github.triet4p.aegisf.provider.HostedTurnFinish
import io.github.triet4p.aegisf.provider.ToolCallDisposition
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Controlled-turn tests of the production executor over the closed S8.13 interface: selective
 * tool loading, exact reference flow, clarification suspension/resume, effect verification,
 * budgets, bounded protocol failures and truthful local-model completion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BoundedWorkflowExecutorTest {
    private lateinit var database: LedgerDatabase
    private lateinit var commands: WorkflowCommandService
    private lateinit var queries: WorkflowQueryService
    private lateinit var laya: LayaInferenceLifecycle
    private lateinit var declarations: List<HostedToolDeclaration>
    private lateinit var skills: WorkflowSkillCatalog
    private val sequence = AtomicInteger()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        laya = LayaInferenceLifecycle(File("build/s813-executor-test-missing-model"))
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
        laya.close()
        database.close()
    }

    @Test
    fun `representative event then link completes only after fresh verification`() = runBlocking {
        val fixture = prepare()
        lateinit var adapter: ScriptedAdapter
        val script = scripted()
        script += { toolTurn(call("select_source", JSONObject()
            .put("capture_ref", captureRef(adapter))
            .put("quote", sourceText(adapter))
            .put("occurrence", 1))) }
        script += {
            val selected = lastEnvelope(adapter).getJSONObject("data")
            toolTurn(call("make_time_anchor", JSONObject()
                .put("date_ref", valueRef(selected, "date"))
                .put("time_ref", valueRef(selected, "time"))
                .put("end_date_ref", JSONObject.NULL)
                .put("end_time_ref", JSONObject.NULL)))
        }
        script += {
            val selected = previousEnvelope(adapter, 2).getJSONObject("data")
            val anchor = lastEnvelope(adapter).getJSONObject("data").getString("anchor_ref")
            toolTurn(call("record_event", JSONObject()
                .put("source_ref", selected.getString("source_ref"))
                .put("summary", "Coffee")
                .put("amount_ref", valueRef(selected, "amount"))
                .put("anchor_ref", anchor)
                .put("merchant_ref", JSONObject.NULL)
                .put("clarification", "none")))
        }
        script += {
            val event = lastEnvelope(adapter).getJSONObject("data").getJSONObject("event")
            toolTurn(call("inspect_link", JSONObject()
                .put("event_ref", event.getString("ref"))
                .put("draft_ref", draftRef(adapter))))
        }
        script += {
            toolTurn(call("propose_link", JSONObject()
                .put("pair_ref", lastEnvelope(adapter).getJSONObject("data").getString("pair_ref"))))
        }
        script += {
            toolTurn(call("attach_link", JSONObject()
                .put("proposal_ref", lastEnvelope(adapter).getJSONObject("data").getString("proposal_ref"))))
        }
        script += { complete("The synthetic event is attached to the synthetic draft.") }
        adapter = ScriptedAdapter(script)

        val outcome = run(fixture, adapter, WorkflowRunRequest(
            intents = setOf(
                WorkflowIntent.SEARCH_DRAFTS,
                WorkflowIntent.INTERPRET_EVENTS,
                WorkflowIntent.LINK_EVENT_TO_DRAFT,
            ),
            userRequest = "Interpret the synthetic note, find the pending draft, and attach it.",
        ))

        assertTrue("expected a verified completion, got $outcome", outcome is WorkflowRunOutcome.Completed)
        outcome as WorkflowRunOutcome.Completed
        assertTrue(outcome.verifiedEffects.any { it is WorkflowVerifiedEffect.EventReading })
        val link = outcome.verifiedEffects.filterIsInstance<WorkflowVerifiedEffect.LinkState>().single()
        assertEquals(ContextLinkState.ACCEPTED, link.state)
        assertEquals(ContextLinkState.ACCEPTED, queries.linkOperations(
            LinkOperationQuery(bounds = QueryPageRequest(10, 0)),
        ).items.single().state)
        assertEquals(1, database.noteEventDao().findForCapture(fixture.captureId).size)
        assertEquals("Asia/Ho_Chi_Minh", database.noteEventDao().findForCapture(fixture.captureId).single().anchorTimezone)
        assertTrue(database.transactionDao().findAll().isEmpty())
        assertEquals(IntakeReviewStatus.PENDING, queries.draftCandidate(fixture.draftId)?.reviewStatus)
        assertTrue(adapter.egress().none { it.contains("25,000") })
        assertTrue(adapter.egress().none { it.contains("2026-10-22") })
        assertTrue(adapter.egress().none { it.contains("09:15") })
        assertEquals(WorkflowRunState.COMPLETED, outcome.trace.last().state)
    }

    @Test
    fun `a final answer without a settled requested effect never completes`() = runBlocking {
        val fixture = prepare()
        val adapter = ScriptedAdapter(scripted() + { complete("Done.") })
        val outcome = run(fixture, adapter, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
            userRequest = "Interpret the note.",
        ))
        assertTrue(outcome is WorkflowRunOutcome.Incomplete)
        assertEquals(
            WorkflowRunStopReason.REQUIRED_EFFECT_MISSING,
            (outcome as WorkflowRunOutcome.Incomplete).reason,
        )
        assertTrue(database.noteEventDao().findForCapture(fixture.captureId).isEmpty())
    }

    @Test
    fun `stale selected source is answered with a safe error and never overwrites`() = runBlocking {
        val fixture = prepare()
        lateinit var adapter: ScriptedAdapter
        val script = scripted()
        script += { toolTurn(call("select_source", JSONObject()
            .put("capture_ref", captureRef(adapter))
            .put("quote", sourceText(adapter))
            .put("occurrence", 1))) }
        script += {
            val source = previousEnvelope(adapter, 1).getJSONObject("data")
            // The source moves on between selection and the write.
            commands.reviseCapture(
                WorkflowCallerScope.User,
                io.github.triet4p.aegisf.data.ReviseCaptureRequest(
                    idempotencyKey = "executor-stale-revise",
                    captureId = fixture.captureId,
                    expectedRevision = 0,
                    bodyText = "${fixture.source} (edited)",
                ),
            )
            toolTurn(call("record_event", JSONObject()
                .put("source_ref", source.getString("source_ref"))
                .put("summary", "Coffee")
                .put("amount_ref", valueRef(source, "amount"))
                .put("anchor_ref", JSONObject.NULL)
                .put("merchant_ref", JSONObject.NULL)
                .put("clarification", "none")))
        }
        script += { complete("I could not store the reading.") }
        adapter = ScriptedAdapter(script)

        val outcome = run(fixture, adapter, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
            userRequest = "Interpret the note.",
        ))
        assertTrue(outcome is WorkflowRunOutcome.Incomplete)
        assertEquals(
            WorkflowRunStopReason.EFFECT_NOT_VERIFIED,
            (outcome as WorkflowRunOutcome.Incomplete).reason,
        )
        val errorEnvelope = JSONObject(adapter.toolResultPayloads.last().single())
        assertEquals("STALE_OBSERVATION", errorEnvelope.getJSONObject("error").getString("code"))
        assertTrue(database.noteEventDao().findForCapture(fixture.captureId).isEmpty())
    }

    @Test
    fun `postcondition reread refuses completion when the effect changed underneath`() = runBlocking {
        val fixture = prepare()
        lateinit var adapter: ScriptedAdapter
        val script = scripted()
        script += { toolTurn(call("select_source", JSONObject()
            .put("capture_ref", captureRef(adapter))
            .put("quote", sourceText(adapter))
            .put("occurrence", 1))) }
        script += {
            val source = previousEnvelope(adapter, 1).getJSONObject("data")
            toolTurn(call("record_event", JSONObject()
                .put("source_ref", source.getString("source_ref"))
                .put("summary", "Coffee")
                .put("amount_ref", valueRef(source, "amount"))
                .put("anchor_ref", JSONObject.NULL)
                .put("merchant_ref", JSONObject.NULL)
                .put("clarification", "none")))
        }
        script += { complete("Stored.") }
        adapter = ScriptedAdapter(script)
        adapter.beforeGenerate = { index ->
            if (index == 2) {
                commands.updateEventInterpretation(
                    WorkflowCallerScope.User,
                    UpdateEventRequest(
                        idempotencyKey = "executor-concurrent-revision",
                        captureId = fixture.captureId,
                        expectedCaptureRevision = 0,
                        expectedEventRevision = 0,
                        extractionVersion = "concurrent/v1",
                        interpretation = NoteEventInterpretation(
                            spanStart = 0,
                            spanEnd = fixture.source.length,
                            normalizedText = "Changed underneath the run",
                            amountVnd = 25_000L,
                        ),
                    ),
                )
            }
        }
        val outcome = run(fixture, adapter, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
            userRequest = "Interpret the note.",
        ))
        assertTrue(outcome is WorkflowRunOutcome.Incomplete)
        assertEquals(
            WorkflowRunStopReason.EFFECT_NOT_VERIFIED,
            (outcome as WorkflowRunOutcome.Incomplete).reason,
        )
    }

    @Test
    fun `an identical repeated call stops as a loop after the declared bound`() = runBlocking {
        val fixture = prepare()
        val query = call("list_drafts", JSONObject()
            .put("review_status", "PENDING")
            .put("duplicates_only", false)
            .put("cursor", JSONObject.NULL))
        val adapter = ScriptedAdapter(
            scripted() + { toolTurn(query) } + { toolTurn(query) } + { toolTurn(query) } + { toolTurn(query) },
        )
        val outcome = run(fixture, adapter, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
            userRequest = "Find the draft.",
        ))
        assertTrue(outcome is WorkflowRunOutcome.Incomplete)
        assertEquals(WorkflowRunStopReason.LOOP_DETECTED, (outcome as WorkflowRunOutcome.Incomplete).reason)
    }

    @Test
    fun `a turn that would exceed the tool budget is not partially dispatched`() = runBlocking {
        val fixture = prepare()
        val adapter = ScriptedAdapter(scripted() + {
            toolTurn(
                call("list_drafts", JSONObject().put("review_status", "PENDING").put("duplicates_only", false).put("cursor", JSONObject.NULL)),
                call("list_drafts", JSONObject().put("review_status", "ALL").put("duplicates_only", false).put("cursor", JSONObject.NULL)),
            )
        })
        val outcome = run(fixture, adapter, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
            userRequest = "Find the draft.",
            limits = WorkflowRunLimits(maxToolCalls = 1),
        ))
        assertTrue(outcome is WorkflowRunOutcome.Incomplete)
        assertEquals(WorkflowRunStopReason.TOOL_CALL_BUDGET, (outcome as WorkflowRunOutcome.Incomplete).reason)
        assertTrue(adapter.toolResultPayloads.isEmpty())
    }

    @Test
    fun `the context budget stops before any provider request`() = runBlocking {
        val fixture = prepare()
        val adapter = ScriptedAdapter(scripted())
        val outcome = run(fixture, adapter, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
            userRequest = "x".repeat(6_000),
            limits = WorkflowRunLimits(maxContextCharacters = 4_096),
        ))
        assertTrue(outcome is WorkflowRunOutcome.Incomplete)
        assertEquals(WorkflowRunStopReason.CONTEXT_BUDGET, (outcome as WorkflowRunOutcome.Incomplete).reason)
        assertTrue(adapter.prompts.isEmpty())
    }

    @Test
    fun `unknown and schema-invalid calls are answered but never dispatched`() = runBlocking {
        val fixture = prepare()
        val adapter = ScriptedAdapter(scripted() + {
            toolTurn(
                call("execute_sql", JSONObject().put("sql", "select 1")),
                call("observe", JSONObject(), disposition = ToolCallDisposition.SCHEMA_INVALID, detail = "arguments.ref: required"),
            )
        } + { complete("No effect.") })
        val outcome = run(fixture, adapter, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
            userRequest = "Interpret the note.",
        ))
        assertTrue(outcome is WorkflowRunOutcome.Incomplete)
        val results = adapter.toolResultPayloads.single()
        assertEquals(2, results.size)
        results.forEach { raw ->
            val envelope = JSONObject(raw)
            assertEquals("error", envelope.getString("outcome"))
            assertEquals("not_dispatched", envelope.getString("dispatch"))
        }
        assertTrue(database.noteEventDao().findForCapture(fixture.captureId).isEmpty())
        assertEquals(0L, database.workflowOperationDao().countOf("UPDATE_EVENT"))
    }

    @Test
    fun `a truncated turn dispatches nothing`() = runBlocking {
        val fixture = prepare()
        val adapter = ScriptedAdapter(listOf { ScriptedTurn(finish = HostedTurnFinish.Incomplete("max_output_tokens")) })
        val outcome = run(fixture, adapter, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
            userRequest = "Interpret the note.",
        ))
        assertTrue(outcome is WorkflowRunOutcome.Incomplete)
        assertEquals(WorkflowRunStopReason.TRUNCATED_TURN, (outcome as WorkflowRunOutcome.Incomplete).reason)
        assertTrue(adapter.toolResultPayloads.isEmpty())
    }

    @Test
    fun `a raw sensitive literal in arguments stops hosted work without an effect`() = runBlocking {
        val fixture = prepare()
        val adapter = ScriptedAdapter(scripted() + {
            toolTurn(call("record_event", JSONObject()
                .put("source_ref", "r_${"0".repeat(24)}")
                .put("summary", "Coffee")
                .put("amount_ref", "25,000 VND")
                .put("anchor_ref", JSONObject.NULL)
                .put("merchant_ref", JSONObject.NULL)
                .put("clarification", "none")))
        })
        val outcome = run(fixture, adapter, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
            userRequest = "Interpret the note.",
        ))
        assertTrue(outcome is WorkflowRunOutcome.Incomplete)
        assertEquals(WorkflowRunStopReason.MASKING_BLOCKED, (outcome as WorkflowRunOutcome.Incomplete).reason)
        assertTrue(database.noteEventDao().findForCapture(fixture.captureId).isEmpty())
        assertEquals(0L, database.workflowOperationDao().countOf("UPDATE_EVENT"))
    }

    @Test
    fun `clarification suspends once and resume records the clarified amount`() = runBlocking {
        val fixture = prepare(source = "Taxi ride on 2026-10-22")
        lateinit var adapter: ScriptedAdapter
        val script = scripted()
        script += { toolTurn(call("select_source", JSONObject()
            .put("capture_ref", captureRef(adapter))
            .put("quote", sourceText(adapter))
            .put("occurrence", 1))) }
        script += {
            val source = previousEnvelope(adapter, 1).getJSONObject("data")
            toolTurn(call("record_event", JSONObject()
                .put("source_ref", source.getString("source_ref"))
                .put("summary", "Taxi ride")
                .put("amount_ref", JSONObject.NULL)
                .put("anchor_ref", JSONObject.NULL)
                .put("merchant_ref", JSONObject.NULL)
                .put("clarification", "amount")))
        }
        script += {
            val source = previousEnvelope(adapter, 2).getJSONObject("data")
            toolTurn(call("ask_user", JSONObject()
                .put("subject_ref", source.getString("source_ref"))
                .put("field", "amount")
                .put("question", "What was the amount for the taxi ride?")))
        }
        script += {
            // The resumed model observes fresh state and reselects the span, which now binds
            // the stored partial reading, instead of reusing the stale absence binding.
            toolTurn(call("select_source", JSONObject()
                .put("capture_ref", captureRef(adapter))
                .put("quote", sourceText(adapter))
                .put("occurrence", 1)))
        }
        script += {
            val valueRef = clarificationValueRef(adapter)
            val source = previousEnvelope(adapter, 1).getJSONObject("data")
            toolTurn(call("record_event", JSONObject()
                .put("source_ref", source.getString("source_ref"))
                .put("summary", "Taxi ride")
                .put("amount_ref", valueRef)
                .put("anchor_ref", JSONObject.NULL)
                .put("merchant_ref", JSONObject.NULL)
                .put("clarification", "none")))
        }
        script += { complete("Stored after clarification.") }
        adapter = ScriptedAdapter(script)
        val executor = executor(fixture, adapter)
        val request = WorkflowRunRequest(
            intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
            userRequest = "Interpret the taxi note.",
        )
        val first = executor.start(request)
        assertTrue("expected clarification, got $first", first is WorkflowRunOutcome.Clarifying)
        first as WorkflowRunOutcome.Clarifying
        assertEquals("amount", first.field)
        assertTrue("clarification must name its subject", first.continuation.subjectRef != null)
        assertEquals("UNRESOLVED", database.noteEventDao().findForCapture(fixture.captureId).single().status.name)

        val resumed = executor.resume(first.continuation, "25000")
        assertTrue("expected completion after resume, got $resumed", resumed is WorkflowRunOutcome.Completed)
        val stored = database.noteEventDao().findForCapture(fixture.captureId).single()
        assertEquals(25_000L, stored.amountVnd)
        assertNull(stored.unresolvedReason)
    }

    @Test
    fun `date-only anchor has no zone while a timed anchor keeps the trusted zone`() = runBlocking {
        val dateOnly = prepare(source = "Lunch 30,000 VND on 2026-10-22", draftDate = "2026-10-22")
        val timed = prepare(source = "Lunch 30,000 VND on 2026-10-22 at 12:30", draftDate = "2026-10-22")

        assertEquals(null, storedEvent(dateOnly, useTime = false).anchorTimezone)
        assertEquals("Asia/Ho_Chi_Minh", storedEvent(timed, useTime = true).anchorTimezone)
    }

    private suspend fun storedEvent(fixture: Fixture, useTime: Boolean) =
        runBlocking {
            lateinit var adapter: ScriptedAdapter
            val script = scripted()
            script += { toolTurn(call("select_source", JSONObject()
                .put("capture_ref", captureRef(adapter))
                .put("quote", sourceText(adapter))
                .put("occurrence", 1))) }
            script += {
                val source = previousEnvelope(adapter, 1).getJSONObject("data")
                toolTurn(call("make_time_anchor", JSONObject()
                    .put("date_ref", valueRef(source, "date"))
                    .put("time_ref", if (useTime) valueRef(source, "time") else JSONObject.NULL)
                    .put("end_date_ref", JSONObject.NULL)
                    .put("end_time_ref", JSONObject.NULL)))
            }
            script += {
                val source = previousEnvelope(adapter, 2).getJSONObject("data")
                toolTurn(call("record_event", JSONObject()
                    .put("source_ref", source.getString("source_ref"))
                    .put("summary", "Lunch")
                    .put("amount_ref", valueRef(source, "amount"))
                    .put("anchor_ref", lastEnvelope(adapter).getJSONObject("data").getString("anchor_ref"))
                    .put("merchant_ref", JSONObject.NULL)
                    .put("clarification", "none")))
            }
            script += { complete("Stored.") }
            adapter = ScriptedAdapter(script)
            val outcome = run(fixture, adapter, WorkflowRunRequest(
                intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
                userRequest = "Interpret the note.",
            ))
            assertTrue("expected completion, got $outcome", outcome is WorkflowRunOutcome.Completed)
            database.noteEventDao().findForCapture(fixture.captureId).single()
        }

    @Test
    fun `local model unavailability completes truthfully without a prediction`() = runBlocking {
        val fixture = prepare()
        lateinit var adapter: ScriptedAdapter
        val script = scripted()
        script += { toolTurn(call("classify_draft", JSONObject().put("draft_ref", draftRef(adapter)))) }
        script += { complete("The local suggestion is unavailable; review stays manual.") }
        adapter = ScriptedAdapter(script)
        val outcome = run(fixture, adapter, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.PREPARE_LAYA_REVIEW),
            userRequest = "Suggest a category for the draft.",
        ))
        assertTrue("expected completion, got $outcome", outcome is WorkflowRunOutcome.Completed)
        outcome as WorkflowRunOutcome.Completed
        val manual = outcome.verifiedEffects.filterIsInstance<WorkflowVerifiedEffect.ClassificationUnavailable>().single()
        assertEquals("MODEL_UNAVAILABLE", manual.reasonCode)
        assertEquals(IntakeReviewStatus.PENDING, queries.draftCandidate(fixture.draftId)?.reviewStatus)
        assertEquals(0, queries.classificationOperations(
            io.github.triet4p.aegisf.data.ClassificationOperationQuery(bounds = QueryPageRequest(10, 0)),
        ).items.size)
        val result = JSONObject(adapter.toolResultPayloads.single().single())
        assertEquals("MODEL_UNAVAILABLE", result.getJSONObject("error").getString("code"))
        assertEquals("dispatched", result.getString("dispatch"))
    }

    // ---- Harness -------------------------------------------------------------------------

    private fun run(
        fixture: Fixture,
        adapter: ScriptedAdapter,
        request: WorkflowRunRequest,
        clock: WorkflowRunClock = WorkflowRunClock { System.currentTimeMillis() },
    ): WorkflowRunOutcome = runBlocking { executor(fixture, adapter, clock).start(request) }

    private fun executor(
        fixture: Fixture,
        adapter: ScriptedAdapter,
        clock: WorkflowRunClock = WorkflowRunClock { System.currentTimeMillis() },
    ): BoundedWorkflowExecutor {
        val gateway = MaskedHostedGateway(adapter, fixture.boundary)
        val host = WorkflowRunHost(
            scope = fixture.scope,
            boundary = fixture.boundary,
            gateway = gateway,
            declarations = declarations,
            skills = skills,
            commands = commands,
            queries = queries,
            operationKeys = WorkflowOperationKeySource { "s813-executor-key-${sequence.incrementAndGet()}" },
            clock = clock,
        )
        return BoundedWorkflowExecutor(host)
    }

    private suspend fun prepare(
        source: String = SOURCE,
        draftMemo: String = "Coffee",
        draftAmount: Long = 25_000,
        draftDate: String = "2026-10-22",
        draftTime: String = "09:15",
    ): Fixture {
        val captureOutcome = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest("s813-executor-capture-${sequence.incrementAndGet()}", source),
        )
        check(captureOutcome is WorkflowCommandOutcome.Applied) { "capture refused: $captureOutcome" }
        val captureId = (captureOutcome.change as WorkflowChange.CaptureSaved).captureId
        val draftId = database.pendingBankDraftDao().store(
            PendingBankDraftEntity(
                deliveryId = "s813-executor-draft-${sequence.incrementAndGet()}",
                sourcePackage = "synthetic.fixture",
                notificationKey = null,
                postedAtEpochMillis = System.currentTimeMillis(),
                bank = "Synthetic Bank",
                parseStatus = IntakeParseStatus.PARSED,
                amountState = IntakeFieldState.PRESENT,
                amountVnd = draftAmount,
                amountUnresolvedReason = null,
                direction = IntakeDirection.DEBIT,
                momentState = IntakeFieldState.PRESENT,
                statedDate = draftDate,
                statedTime = draftTime,
                momentUnresolvedReason = null,
                memoState = IntakeFieldState.PRESENT,
                memo = draftMemo,
                memoUnresolvedReason = null,
                createdAtEpochMillis = System.currentTimeMillis(),
            ),
        )
        val scope = WorkflowCallerScope.AgentSession(
            "s813-executor-session-${sequence.incrementAndGet()}",
            setOf(captureId),
            setOf(draftId),
        )
        val boundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId))
        val maskedSource = boundary.maskOutbound(source, MaskedPath.CONTEXT)
        return Fixture(captureId, draftId, scope, boundary, maskedSource, source)
    }

    private fun initialObservations(adapter: ScriptedAdapter): JSONArray =
        observationBlock(adapter.prompts.last { it.contains("Trusted host observations") })

    /** The observations array immediately following the prompt's observation label. */
    private fun observationBlock(prompt: String): JSONArray {
        val lines = prompt.lines()
        val label = lines.indexOfFirst { it.contains("Trusted host observations") }
        check(label >= 0) { "no observation label in the prompt" }
        val start = (label + 1 until lines.size).firstOrNull { lines[it].trimStart().startsWith("[") }
            ?: error("no observation block in the prompt")
        return JSONArray(lines[start].trim())
    }

    private fun captureRef(adapter: ScriptedAdapter): String {
        val observations = initialObservations(adapter)
        for (index in 0 until observations.length()) {
            val item = observations.getJSONObject(index)
            if (item.optString("kind") == "capture") return item.getString("ref")
        }
        error("no capture alias in the initial observation")
    }

    private fun sourceText(adapter: ScriptedAdapter): String {
        val observations = initialObservations(adapter)
        for (index in 0 until observations.length()) {
            val item = observations.getJSONObject(index)
            if (item.optString("kind") == "capture") return item.getString("source_text")
        }
        error("no capture source text in the initial observation")
    }

    private fun draftRef(adapter: ScriptedAdapter): String {
        val observations = initialObservations(adapter)
        for (index in 0 until observations.length()) {
            val item = observations.getJSONObject(index)
            if (item.optString("kind") == "draft_page") {
                return item.getJSONArray("items").getJSONObject(0).getString("ref")
            }
        }
        error("no draft alias in the initial observation")
    }

    private fun lastEnvelope(adapter: ScriptedAdapter): JSONObject =
        JSONObject(adapter.toolResultPayloads.last().single())

    private fun previousEnvelope(adapter: ScriptedAdapter, turnsBack: Int): JSONObject =
        JSONObject(adapter.toolResultPayloads[adapter.toolResultPayloads.size - turnsBack].single())

    private fun valueRef(source: JSONObject, kind: String): String {
        val values = source.getJSONArray("values")
        for (index in 0 until values.length()) {
            val item = values.getJSONObject(index)
            if (item.getString("kind") == kind) return item.getString("ref")
        }
        error("expected a $kind alias in the selected source")
    }

    private fun clarificationValueRef(adapter: ScriptedAdapter): String {
        val prompt = adapter.prompts.last { it.contains("Trusted host observations") }
        val observations = observationBlock(prompt)
        for (index in 0 until observations.length()) {
            val item = observations.getJSONObject(index)
            if (item.optString("kind") == "clarification") return item.getString("value_ref")
        }
        error("no clarification value alias in the resume observation; prompt=$prompt")
    }

    private fun scripted(): MutableList<suspend () -> ScriptedTurn> = mutableListOf()

    private fun toolTurn(vararg calls: ScriptedCall) = ScriptedTurn(calls = calls.toList(), finish = HostedTurnFinish.ToolCalls)

    private fun complete(text: String) = ScriptedTurn(text = text, finish = HostedTurnFinish.Complete)

    private fun call(
        name: String,
        arguments: JSONObject,
        disposition: ToolCallDisposition = ToolCallDisposition.READY,
        detail: String? = null,
    ) = ScriptedCall(name, arguments.toString(), disposition, detail)

    private data class Fixture(
        val captureId: Long,
        val draftId: Long,
        val scope: WorkflowCallerScope.AgentSession,
        val boundary: MaskedPromptBoundary,
        val maskedSource: String,
        val source: String,
    )

    private class ScriptedCall(
        val name: String,
        val argumentsJson: String?,
        val disposition: ToolCallDisposition = ToolCallDisposition.READY,
        val detail: String? = null,
    )

    private class ScriptedTurn(
        val text: String? = null,
        val calls: List<ScriptedCall> = emptyList(),
        val finish: HostedTurnFinish,
    )

    private class ScriptedConversation(
        override val protocol: HostedProtocol,
        override val pendingCallIds: List<String>,
    ) : HostedConversation

    /** A scripted protocol adapter; it records the exact masked text it was asked to send. */
    private class ScriptedAdapter(
        private val turns: List<suspend () -> ScriptedTurn>,
    ) : HostedGenerationAdapter {
        override val protocol: HostedProtocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS
        private var next = 0
        val prompts = mutableListOf<String>()
        val toolResultPayloads = mutableListOf<List<String>>()
        var beforeGenerate: (suspend (Int) -> Unit)? = null

        override fun begin(systemInstruction: String, userInstruction: String): HostedConversation {
            prompts += systemInstruction
            prompts += userInstruction
            return ScriptedConversation(protocol, emptyList())
        }

        override suspend fun generate(
            conversation: HostedConversation,
            tools: List<HostedToolDeclaration>,
        ): HostedGenerationResult {
            beforeGenerate?.invoke(next)
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
                    detail = call.detail,
                )
            }
            val continuation = if (turn.finish == HostedTurnFinish.ToolCalls) {
                ScriptedConversation(protocol, calls.map(HostedToolCall::callId))
            } else {
                null
            }
            return HostedGenerationResult.Turn(
                HostedTurn(
                    protocol = protocol,
                    requestedModel = MODEL,
                    reportedModel = MODEL,
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
        ): HostedConversation {
            toolResultPayloads += results.map(HostedToolResult::outputJson)
            return ScriptedConversation(protocol, emptyList())
        }

        fun egress(): List<String> = prompts + toolResultPayloads.flatten()
    }

    private companion object {
        const val MODEL = "scripted-model"
        const val SOURCE = "Coffee 25,000 VND on 2026-10-22 at 09:15. Note: synthetic fixture only."
    }
}
