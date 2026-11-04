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
import io.github.triet4p.aegisf.data.NoteEventTimeAnchor
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.SaveCaptureRequest
import io.github.triet4p.aegisf.data.SystemLedgerClock
import io.github.triet4p.aegisf.data.UpdateEventRequest
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowChange
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.privacy.MaskedHostedGateway
import io.github.triet4p.aegisf.privacy.MaskedPath
import io.github.triet4p.aegisf.privacy.MaskedPromptBoundary
import io.github.triet4p.aegisf.privacy.SensitiveVault
import io.github.triet4p.aegisf.provider.HostedConversation
import io.github.triet4p.aegisf.provider.HostedGenerationAdapter
import io.github.triet4p.aegisf.provider.HostedGenerationResult
import io.github.triet4p.aegisf.provider.HostedResponseMetadataPolicy
import io.github.triet4p.aegisf.provider.HostedResponsePrivacyRefusalReason
import io.github.triet4p.aegisf.provider.withRawResponseForPrivacyScan
import io.github.triet4p.aegisf.provider.HostedProtocol
import io.github.triet4p.aegisf.provider.HostedToolCall
import io.github.triet4p.aegisf.provider.HostedToolDeclaration
import io.github.triet4p.aegisf.provider.HostedToolResult
import io.github.triet4p.aegisf.provider.HostedTurn
import io.github.triet4p.aegisf.provider.HostedTurnFinish
import io.github.triet4p.aegisf.provider.ToolCallDisposition
import java.time.Instant
import java.time.ZoneId
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
    private lateinit var declarations: List<HostedToolDeclaration>
    /** The active fixture's vault, used to select a value alias by the role it parses for. */
    private lateinit var vault: SensitiveVault
    private lateinit var skills: WorkflowSkillCatalog
    private val sequence = AtomicInteger()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        commands = WorkflowCommandService(
            database = database,
            clock = SystemLedgerClock,
            timezoneId = { "Asia/Ho_Chi_Minh" },
        )
        queries = WorkflowQueryService(database)
        declarations = AgentToolDeclarations.load(context.assets::open)
        skills = WorkflowSkillCatalog.load(context.assets::open)
    }

    @After
    fun tearDown() {
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
    fun `a stated day word is resolved from the capture and both purchases stay traceable`() =
        runBlocking {
            val source = "Coffee 15,000 VND yesterday at 08:10 | Taxi 40,000 VND on 2026-10-21 at 13:05"
            val fixture = prepare(source)
            val capture = queries.sourceExcerpt(fixture.captureId)!!
            lateinit var adapter: ScriptedAdapter
            val script = scripted()
            script += {
                toolTurn(
                    call(
                        "select_source",
                        JSONObject()
                            .put("capture_ref", captureRef(adapter))
                            .put("quote", sourceText(adapter).substringBefore(" | ").trim())
                            .put("occurrence", 1),
                    ),
                )
            }
            script += {
                val selected = lastEnvelope(adapter).getJSONObject("data")
                toolTurn(
                    call(
                        "make_time_anchor",
                        JSONObject()
                            .put(
                                "date_ref",
                                selected.getJSONArray("relative_dates").getJSONObject(0).getString("ref"),
                            )
                            .put("time_ref", valueRef(selected, "time"))
                            .put("end_date_ref", JSONObject.NULL)
                            .put("end_time_ref", JSONObject.NULL),
                    ),
                )
            }
            script += {
                val selected = previousEnvelope(adapter, 2).getJSONObject("data")
                toolTurn(
                    call(
                        "record_event",
                        JSONObject()
                            .put("source_ref", selected.getString("source_ref"))
                            .put("summary", "Coffee")
                            .put("amount_ref", valueRef(selected, "amount"))
                            .put("anchor_ref", lastEnvelope(adapter).getJSONObject("data").getString("anchor_ref"))
                            .put("merchant_ref", JSONObject.NULL)
                            .put("clarification", "none"),
                    ),
                )
            }
            script += {
                toolTurn(
                    call(
                        "select_source",
                        JSONObject()
                            .put("capture_ref", captureRef(adapter))
                            .put("quote", sourceText(adapter).substringAfter(" | ").trim())
                            .put("occurrence", 1),
                    ),
                )
            }
            script += {
                val selected = lastEnvelope(adapter).getJSONObject("data")
                toolTurn(
                    call(
                        "make_time_anchor",
                        JSONObject()
                            .put("date_ref", valueRef(selected, "date"))
                            .put("time_ref", valueRef(selected, "time"))
                            .put("end_date_ref", JSONObject.NULL)
                            .put("end_time_ref", JSONObject.NULL),
                    ),
                )
            }
            script += {
                val selected = previousEnvelope(adapter, 2).getJSONObject("data")
                toolTurn(
                    call(
                        "record_event",
                        JSONObject()
                            .put("source_ref", selected.getString("source_ref"))
                            .put("summary", "Taxi")
                            .put("amount_ref", valueRef(selected, "amount"))
                            .put("anchor_ref", lastEnvelope(adapter).getJSONObject("data").getString("anchor_ref"))
                            .put("merchant_ref", JSONObject.NULL)
                            .put("clarification", "none"),
                    ),
                )
            }
            script += { complete("Stored both readings; the first day came from the note's own capture.") }
            adapter = ScriptedAdapter(script)

            val outcome = run(
                fixture,
                adapter,
                WorkflowRunRequest(
                    intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
                    userRequest = "Interpret the synthetic note.",
                ),
            )

            assertTrue("expected a verified completion, got $outcome", outcome is WorkflowRunOutcome.Completed)
            outcome as WorkflowRunOutcome.Completed
            assertEquals(2, outcome.verifiedEffects.filterIsInstance<WorkflowVerifiedEffect.EventReading>().size)
            val rows = database.noteEventDao().findForCapture(fixture.captureId).sortedBy { it.spanStart }
            assertEquals(2, rows.size)
            assertEquals("Coffee 15,000 VND yesterday at 08:10", source.substring(rows[0].spanStart, rows[0].spanEnd))
            assertEquals("Taxi 40,000 VND on 2026-10-21 at 13:05", source.substring(rows[1].spanStart, rows[1].spanEnd))
            val captureDay = Instant.ofEpochMilli(capture.capturedAtEpochMillis!!)
                .atZone(ZoneId.of(capture.captureTimezone!!))
                .toLocalDate()
            assertEquals(captureDay.minusDays(1).toString(), rows[0].anchorDate)
            assertEquals("08:10", rows[0].anchorTime)
            assertEquals("2026-10-21", rows[1].anchorDate)
            assertEquals("13:05", rows[1].anchorTime)
            assertEquals("Asia/Ho_Chi_Minh", rows[0].anchorTimezone)
            assertTrue(
                "the relative-day rule reaches the hosted prompt",
                adapter.prompts.first().contains("relative_date_rule"),
            )
            assertTrue(
                "a masked value never leaves the device raw",
                adapter.egress().none { it.contains("15,000") || it.contains("2026-10-21") },
            )
            assertTrue("extraction is not money", database.transactionDao().findAll().isEmpty())
        }

    @Test
    fun `raw hidden response blocks a valid event write before dispatch`() = runBlocking {
        val fixture = prepare()
        lateinit var adapter: ScriptedAdapter
        val script = scripted()
        script += {
            toolTurn(call("select_source", JSONObject()
                .put("capture_ref", captureRef(adapter))
                .put("quote", sourceText(adapter))
                .put("occurrence", 1)))
        }
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
            ScriptedTurn(
                calls = listOf(call("record_event", JSONObject()
                    .put("source_ref", selected.getString("source_ref"))
                    .put("summary", "Coffee")
                    .put("amount_ref", valueRef(selected, "amount"))
                    .put("anchor_ref", anchor)
                    .put("merchant_ref", JSONObject.NULL)
                    .put("clarification", "none"))),
                finish = HostedTurnFinish.ToolCalls,
                rawResponseForPrivacyScan = JSONObject().put("unrecognized", fixture.source).toString(),
            )
        }
        adapter = ScriptedAdapter(script)

        val outcome = run(fixture, adapter, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
            userRequest = "Interpret the synthetic note.",
        ))

        assertTrue(outcome is WorkflowRunOutcome.Incomplete)
        val incomplete = outcome as WorkflowRunOutcome.Incomplete
        assertEquals(WorkflowRunStopReason.MASKING_BLOCKED, incomplete.reason)
        assertEquals(
            HostedResponsePrivacyRefusalReason.DECODED_SENSITIVE_MATCH,
            incomplete.privacyRefusal?.reason,
        )
        assertTrue(incomplete.privacyRefusal?.safeLocation?.startsWith("response_body") == true)
        assertEquals("numeric", incomplete.privacyRefusal?.sensitiveShape)
        assertEquals(3, adapter.generatedCount)
        assertEquals(2, adapter.toolResultPayloads.size)
        assertTrue(database.noteEventDao().findForCapture(fixture.captureId).isEmpty())
        assertEquals(0L, database.workflowOperationDao().countOf("UPDATE_EVENT"))
        assertTrue(database.transactionDao().findAll().isEmpty())
    }

    @Test
    fun `validated response metadata continues while the same value in content stops before dispatch`() =
        runBlocking {
            val fixture = prepare()
            lateinit var safeAdapter: ScriptedAdapter
            val safeScript = scripted()
            safeScript += {
                ScriptedTurn(
                    calls = listOf(call("select_source", JSONObject()
                        .put("capture_ref", captureRef(safeAdapter))
                        .put("quote", sourceText(safeAdapter))
                        .put("occurrence", 1))),
                    finish = HostedTurnFinish.ToolCalls,
                    rawResponseForPrivacyScan =
                        """{"id":"chatcmpl-25000","object":"chat.completion","created":1750000000,"model":"scripted-model"}""",
                )
            }
            safeScript += { complete("Done.") }
            safeAdapter = ScriptedAdapter(safeScript, metadataPolicy())

            val safeOutcome = run(fixture, safeAdapter, WorkflowRunRequest(
                intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
                userRequest = "Interpret the synthetic note.",
            ))
            val safeStop = (safeOutcome as? WorkflowRunOutcome.Incomplete)?.reason
            assertFalse(safeStop == WorkflowRunStopReason.MASKING_BLOCKED)
            assertEquals(2, safeAdapter.generatedCount)
            assertEquals(1, safeAdapter.toolResultPayloads.size)

            lateinit var unsafeAdapter: ScriptedAdapter
            val unsafeScript = scripted()
            unsafeScript += {
                ScriptedTurn(
                    calls = listOf(call("select_source", JSONObject()
                        .put("capture_ref", captureRef(unsafeAdapter))
                        .put("quote", sourceText(unsafeAdapter))
                        .put("occurrence", 1))),
                    finish = HostedTurnFinish.ToolCalls,
                    rawResponseForPrivacyScan =
                        """{"id":"chatcmpl-25000","model":"scripted-model","choices":[{"message":{"content":"25000"}}]}""",
                )
            }
            unsafeAdapter = ScriptedAdapter(unsafeScript, metadataPolicy())
            val unsafeOutcome = run(fixture, unsafeAdapter, WorkflowRunRequest(
                intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
                userRequest = "Interpret the synthetic note.",
            ))
            assertTrue(unsafeOutcome is WorkflowRunOutcome.Incomplete)
            val incomplete = unsafeOutcome as WorkflowRunOutcome.Incomplete
            assertEquals(WorkflowRunStopReason.MASKING_BLOCKED, incomplete.reason)
            assertEquals(
                "response_body.choices[].message.content",
                incomplete.privacyRefusal?.safeLocation,
            )
            assertEquals(1, unsafeAdapter.generatedCount)
            assertTrue(unsafeAdapter.toolResultPayloads.isEmpty())
            assertTrue(database.noteEventDao().findForCapture(fixture.captureId).isEmpty())
            assertEquals(0L, database.workflowOperationDao().countOf("UPDATE_EVENT"))
        }

    private fun metadataPolicy() = HostedResponseMetadataPolicy(
        protocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
        configuredModel = MODEL,
        goUsageExtensions = true,
    )

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
    fun `default tool-call budget dispatches twenty-four and refuses the next event write`() = runBlocking {
        val fixture = prepare()
        lateinit var adapter: ScriptedAdapter
        val script = scripted()
        script += {
            toolTurn(call("select_source", JSONObject()
                .put("capture_ref", captureRef(adapter))
                .put("quote", sourceText(adapter))
                .put("occurrence", 1)))
        }
        script += {
            val selected = lastEnvelope(adapter).getJSONObject("data")
            val listCalls = listOf("PENDING", "CONFIRMED", "DISMISSED", "ALL")
                .flatMap { status ->
                    listOf(false, true).flatMap { duplicatesOnly ->
                        List(3) {
                            call("list_drafts", JSONObject()
                                .put("review_status", status)
                                .put("duplicates_only", duplicatesOnly)
                                .put("cursor", JSONObject.NULL))
                        }
                    }
                }
                .take(22)
            val anchorCall = call("make_time_anchor", JSONObject()
                .put("date_ref", valueRef(selected, "date"))
                .put("time_ref", valueRef(selected, "time"))
                .put("end_date_ref", JSONObject.NULL)
                .put("end_time_ref", JSONObject.NULL))
            toolTurn(anchorCall, *listCalls.toTypedArray())
        }
        script += {
            val selected = JSONObject(adapter.toolResultPayloads[0].single()).getJSONObject("data")
            val anchor = JSONObject(adapter.toolResultPayloads[1].first())
                .getJSONObject("data")
                .getString("anchor_ref")
            toolTurn(call("record_event", JSONObject()
                .put("source_ref", selected.getString("source_ref"))
                .put("summary", "Coffee")
                .put("amount_ref", valueRef(selected, "amount"))
                .put("anchor_ref", anchor)
                .put("merchant_ref", JSONObject.NULL)
                .put("clarification", "none")))
        }
        adapter = ScriptedAdapter(script)

        val outcome = run(fixture, adapter, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.INTERPRET_EVENTS, WorkflowIntent.SEARCH_DRAFTS),
            userRequest = "Interpret the note and inspect current draft review states.",
        ))

        assertTrue(outcome is WorkflowRunOutcome.Incomplete)
        val incomplete = outcome as WorkflowRunOutcome.Incomplete
        assertEquals(WorkflowRunStopReason.TOOL_CALL_BUDGET, incomplete.reason)
        assertEquals(24, incomplete.toolCalls)
        assertEquals(3, incomplete.turns)
        assertEquals(3, adapter.generatedCount)
        assertEquals(2, adapter.toolResultPayloads.size)
        assertEquals(1, adapter.toolResultPayloads[0].size)
        assertEquals(23, adapter.toolResultPayloads[1].size)
        assertTrue(adapter.toolResultPayloads.flatten().all {
            JSONObject(it).getString("outcome") == "ok"
        })
        assertTrue(database.noteEventDao().findForCapture(fixture.captureId).isEmpty())
        assertEquals(0L, database.workflowOperationDao().countOf("UPDATE_EVENT"))
        assertTrue(database.transactionDao().findAll().isEmpty())
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
                    .put("date_ref", valueRef(source, "date", fixture.boundary.vault))
                    .put("time_ref", if (useTime) valueRef(source, "time", fixture.boundary.vault) else JSONObject.NULL)
                    .put("end_date_ref", JSONObject.NULL)
                    .put("end_time_ref", JSONObject.NULL)))
            }
            script += {
                val source = previousEnvelope(adapter, 2).getJSONObject("data")
                toolTurn(call("record_event", JSONObject()
                    .put("source_ref", source.getString("source_ref"))
                    .put("summary", "Lunch")
                    .put("amount_ref", valueRef(source, "amount", fixture.boundary.vault))
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
    fun `one assisted follow-up continues the same conversation and reference registry`() = runBlocking {
        val fixture = prepare()
        lateinit var adapter: ScriptedAdapter
        val script = scripted()
        var eventRef = ""
        var promptsBeforeFollowUp = 0
        script += { toolTurn(call("select_source", JSONObject()
            .put("capture_ref", captureRef(adapter))
            .put("quote", sourceText(adapter))
            .put("occurrence", 1))) }
        script += {
            val source = previousEnvelope(adapter, 1).getJSONObject("data")
            toolTurn(call("make_time_anchor", JSONObject()
                .put("date_ref", valueRef(source, "date"))
                .put("time_ref", valueRef(source, "time"))
                .put("end_date_ref", JSONObject.NULL)
                .put("end_time_ref", JSONObject.NULL)))
        }
        script += {
            val source = previousEnvelope(adapter, 2).getJSONObject("data")
            toolTurn(call("record_event", JSONObject()
                .put("source_ref", source.getString("source_ref"))
                .put("summary", "Coffee")
                .put("amount_ref", valueRef(source, "amount"))
                .put("anchor_ref", lastEnvelope(adapter).getJSONObject("data").getString("anchor_ref"))
                .put("merchant_ref", JSONObject.NULL)
                .put("clarification", "none")))
        }
        script += {
            eventRef = lastEnvelope(adapter).getJSONObject("data")
                .getJSONObject("event").getString("ref")
            complete("Stored the coffee reading.")
        }
        // Exactly one assisted user message, then a real dispatch on the same reference registry.
        script += {
            promptsBeforeFollowUp = adapter.prompts.size
            toolTurn(call("observe", JSONObject().put("ref", eventRef)))
        }
        script += {
            val notice = adapter.notices.single()
            assertTrue(
                notice,
                notice.contains("Please confirm whether") && notice.contains("is right."),
            )
            assertTrue(notice, notice.contains("issued_mask_token_inventory"))
            assertEquals(
                "event",
                lastEnvelope(adapter).getJSONObject("data").getJSONObject("observation").getString("kind"),
            )
            complete("The same reading is still stored.")
        }
        adapter = ScriptedAdapter(script)
        val executor = executor(fixture, adapter)
        val first = executor.start(WorkflowRunRequest(
            intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
            userRequest = "Interpret the coffee note.",
        ))
        assertTrue("expected completion, got $first", first is WorkflowRunOutcome.Completed)
        first as WorkflowRunOutcome.Completed
        val followUp = requireNotNull(first.followUp) { "a completed idle run must offer one follow-up" }
        assertEquals(first.turns, followUp.initialTurns)
        assertEquals(first.toolCalls, followUp.initialToolCalls)

        val assisted = executor.followUp(followUp, "Please confirm whether 25,000 VND is right.")
        assertTrue("expected assisted completion, got $assisted", assisted is WorkflowRunOutcome.Completed)
        assisted as WorkflowRunOutcome.Completed
        assertEquals("the follow-up must not start a new provider conversation", promptsBeforeFollowUp, adapter.prompts.size)
        assertEquals(first.turns + 2, assisted.turns)
        assertEquals(first.toolCalls + 1, assisted.toolCalls)
        assertNull("a follow-up phase never mints another follow-up", assisted.followUp)
        assertEquals(1, database.noteEventDao().findForCapture(fixture.captureId).size)
        assertTrue("the event alias is not empty", eventRef.isNotEmpty())
        assertTrue("one transcript stays intact", adapter.toolResultPayloads.size == 4)
    }

    @Test
    fun `a follow-up handle is single use and refuses a blank message`() = runBlocking {
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
        script += { complete("Still stored.") }
        adapter = ScriptedAdapter(script)
        val executor = executor(fixture, adapter)
        val first = executor.start(WorkflowRunRequest(
            intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
            userRequest = "Interpret the coffee note.",
        ))
        first as WorkflowRunOutcome.Completed
        val followUp = requireNotNull(first.followUp)
        val blank = runCatching { executor.followUp(followUp, "   ") }
        assertTrue("a blank follow-up must be refused", blank.exceptionOrNull() is IllegalArgumentException)
        val assisted = executor.followUp(followUp, "Anything else I should record?")
        assertTrue("expected assisted completion, got $assisted", assisted is WorkflowRunOutcome.Completed)
        val reused = runCatching { executor.followUp(followUp, "One more message.") }
        assertTrue("a second message must be refused", reused.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `clarifying outcomes report the turns and tool calls they actually spent`() = runBlocking {
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
                .put("question", "What was the amount?")))
        }
        adapter = ScriptedAdapter(script)
        val outcome = run(fixture, adapter, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
            userRequest = "Interpret the coffee note.",
        ))
        assertTrue("expected clarification, got $outcome", outcome is WorkflowRunOutcome.Clarifying)
        outcome as WorkflowRunOutcome.Clarifying
        assertEquals(3, outcome.turns)
        assertEquals(3, outcome.toolCalls)
        assertTrue(
            "every dispatched call is traced",
            outcome.trace.count { it.state == WorkflowRunState.DISPATCHING } >= 3,
        )
        assertNotNull("a clarifying run must offer one follow-up", outcome.followUp)
    }

    // ---- Bounded masking repair ----------------------------------------------------------

    @Test
    fun `one bounded repair re-issues a pre-dispatch unknown token response`() = runBlocking {
        val fixture = prepare()
        val adapter = ScriptedAdapter(scripted().apply {
            add {
                ScriptedTurn(
                    finish = HostedTurnFinish.Complete,
                    rawResponseForPrivacyScan = responseBodyWithAnswer("Purchase ⟦AMT⟧ VND on ⟦DATE⟧"),
                )
            }
            add { complete("The bounded search found no pending draft.") }
        })

        val outcome = run(
            fixture,
            adapter,
            WorkflowRunRequest(
                intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
                userRequest = "Find my pending drafts.",
            ),
        )

        assertTrue("expected the repaired turn to complete, got $outcome", outcome is WorkflowRunOutcome.Completed)
        outcome as WorkflowRunOutcome.Completed
        assertEquals(2, adapter.generatedCount)
        val repairNotice = adapter.notices.single()
        assertEquals(1, adapter.notices.size)
        assertEquals(2, outcome.turns)
        assertEquals(1, outcome.maskingRepairs.size)
        assertEquals(WorkflowRunState.GENERATING, outcome.trace.first { it.detail == "masking refused attempt 1: unknown_token" }.state)
        assertTrue(outcome.trace.any { it.detail == "masking repair 1: re-issued the turn with the fixed notice" })
        assertEquals("UNKNOWN_TOKEN", outcome.maskingRepairs.single().reason)
        assertEquals("reissued", outcome.maskingRepairs.single().outcome)
        // The refused response carried no dispatch, so nothing was applied.
        assertTrue(outcome.verifiedEffects.isEmpty())
        assertTrue(adapter.toolResultPayloads.isEmpty())
        // The stable prefix is untouched: the notice is only a dynamic tail.
        assertTrue(repairNotice.startsWith(MaskingRepairPolicy.NOTICE))
        // The re-issued turn still ends with the run's authoritative inventory.
        assertTrue(repairNotice.contains(AgentContextManager.TOKEN_INVENTORY_VERSION))
        assertEquals(
            fixture.boundary.vault.issuedTokens().map { it.token },
            Regex("⟦[^\\s⟦⟧]+⟧")
                .findAll(repairNotice.substring(repairNotice.indexOf("issued_mask_token_inventory")))
                .map { it.value }
                .toList(),
        )
        // Neither the refused text nor a raw value is echoed back in the notice.
        val repairText = repairNotice.removePrefix(MaskingRepairPolicy.NOTICE)
        assertFalse(repairText.contains("⟦AMT⟧"))
        assertFalse(repairText.contains("⟦DATE⟧"))
        assertFalse(repairNotice.contains("25,000"))
        assertFalse(adapter.prompts.any { it.contains(MaskingRepairPolicy.NOTICE) })
    }

    @Test
    fun `a second unknown token refusal is terminal with no further re-issue`() = runBlocking {
        val fixture = prepare()
        val adapter = ScriptedAdapter(scripted().apply {
            add {
                ScriptedTurn(
                    finish = HostedTurnFinish.Complete,
                    rawResponseForPrivacyScan = responseBodyWithAnswer("⟦AMT⟧ again"),
                )
            }
            add {
                ScriptedTurn(
                    finish = HostedTurnFinish.Complete,
                    rawResponseForPrivacyScan = responseBodyWithAnswer("⟦AMT⟧ and ⟦DATE⟧ again"),
                )
            }
        })

        val outcome = run(
            fixture,
            adapter,
            WorkflowRunRequest(
                intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
                userRequest = "Find my pending drafts.",
            ),
        )

        assertTrue("expected a terminal masking stop, got $outcome", outcome is WorkflowRunOutcome.Incomplete)
        outcome as WorkflowRunOutcome.Incomplete
        assertEquals(WorkflowRunStopReason.MASKING_BLOCKED, outcome.reason)
        assertEquals(HostedResponsePrivacyRefusalReason.UNKNOWN_TOKEN, outcome.privacyRefusal?.reason)
        assertEquals(2, adapter.generatedCount)
        assertEquals(1, adapter.notices.size)
        assertEquals(2, outcome.maskingRepairs.size)
        assertEquals(listOf("reissued", "terminal"), outcome.maskingRepairs.map { it.outcome })
        assertEquals(2, outcome.turns)
    }

    @Test
    fun `a foreign token refusal is never repaired`() = runBlocking {
        val fixture = prepare()
        val adapter = ScriptedAdapter(scripted().apply {
            add {
                ScriptedTurn(
                    finish = HostedTurnFinish.Complete,
                    rawResponseForPrivacyScan = responseBodyWithAnswer("⟦V-othersession-0001⟧"),
                )
            }
        })

        val outcome = run(
            fixture,
            adapter,
            WorkflowRunRequest(
                intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
                userRequest = "Find my pending drafts.",
            ),
        )

        outcome as WorkflowRunOutcome.Incomplete
        assertEquals(WorkflowRunStopReason.MASKING_BLOCKED, outcome.reason)
        assertEquals(HostedResponsePrivacyRefusalReason.FOREIGN_TOKEN, outcome.privacyRefusal?.reason)
        assertEquals(1, adapter.generatedCount)
        assertTrue(adapter.notices.isEmpty())
        assertEquals(listOf("terminal"), outcome.maskingRepairs.map { it.outcome })
    }

    @Test
    fun `a decoded sensitive literal is never repaired`() = runBlocking {
        val fixture = prepare()
        val adapter = ScriptedAdapter(scripted().apply {
            add {
                ScriptedTurn(
                    finish = HostedTurnFinish.Complete,
                    rawResponseForPrivacyScan = responseBodyWithAnswer("It cost 25,000 VND."),
                )
            }
        })

        val outcome = run(
            fixture,
            adapter,
            WorkflowRunRequest(
                intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
                userRequest = "Find my pending drafts.",
            ),
        )

        outcome as WorkflowRunOutcome.Incomplete
        assertEquals(WorkflowRunStopReason.MASKING_BLOCKED, outcome.reason)
        assertEquals(
            HostedResponsePrivacyRefusalReason.DECODED_SENSITIVE_MATCH,
            outcome.privacyRefusal?.reason,
        )
        assertEquals(1, adapter.generatedCount)
        assertTrue(adapter.notices.isEmpty())
    }

    @Test
    fun `the refused attempt is charged to the turn budget`() = runBlocking {
        val fixture = prepare()
        val adapter = ScriptedAdapter(scripted().apply {
            add {
                ScriptedTurn(
                    finish = HostedTurnFinish.Complete,
                    rawResponseForPrivacyScan = responseBodyWithAnswer("⟦AMT⟧"),
                )
            }
        })

        val outcome = run(
            fixture,
            adapter,
            WorkflowRunRequest(
                intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
                userRequest = "Find my pending drafts.",
                limits = WorkflowRunLimits(maxTurns = 1),
            ),
        )

        outcome as WorkflowRunOutcome.Incomplete
        assertEquals(WorkflowRunStopReason.TURN_BUDGET, outcome.reason)
        assertEquals(1, outcome.turns)
        assertEquals(1, adapter.generatedCount)
        assertTrue("the repair notice consumed the exhausted budget, not a new turn", adapter.notices.size == 1)
    }

    @Test
    fun `cancellation during the repair re-issue propagates`() = runBlocking {
        val fixture = prepare()
        val adapter = ScriptedAdapter(scripted().apply {
            add {
                ScriptedTurn(
                    finish = HostedTurnFinish.Complete,
                    rawResponseForPrivacyScan = responseBodyWithAnswer("⟦AMT⟧"),
                )
            }
        })
        adapter.beforeGenerate = { index ->
            if (index == 1) throw kotlinx.coroutines.CancellationException("user cancelled")
        }

        var cancelled = false
        try {
            run(
                fixture,
                adapter,
                WorkflowRunRequest(
                    intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
                    userRequest = "Find my pending drafts.",
                ),
            )
        } catch (_: kotlinx.coroutines.CancellationException) {
            cancelled = true
        }
        assertTrue("cancellation must not be converted into a run outcome", cancelled)
        assertEquals(1, adapter.notices.size)
    }

    @Test
    fun `invented reasoning notation never stops the run`() = runBlocking {
        val fixture = prepare()
        val adapter = ScriptedAdapter(scripted().apply {
            add {
                ScriptedTurn(
                    finish = HostedTurnFinish.Complete,
                    text = "The bounded search found no pending draft.",
                    rawResponseForPrivacyScan = responseBodyWithReasoning(
                        "I could write ⟦AMT⟧, ⟦DATE-0000⟧, ⟦UNMAPPED⟧ or 25,000 VND, then answer plainly.",
                    ),
                )
            }
        })
        val outcome = run(
            fixture,
            adapter,
            WorkflowRunRequest(
                intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
                userRequest = "Find my pending drafts.",
            ),
        )
        assertTrue("provider reasoning must never block the turn, got $outcome",
            outcome is WorkflowRunOutcome.Completed)
        assertEquals(1, adapter.generatedCount)
        assertTrue("no masking repair is consumed", adapter.notices.isEmpty())
    }

    /**
     * A complete JSON body whose user-visible answer carries the given text. Its reasoning
     * carries invented notation too, which must never be the reason a turn is refused.
     */
    private fun responseBodyWithAnswer(text: String): String = JSONObject()
        .put("model", MODEL)
        .put(
            "choices",
            JSONArray().put(
                JSONObject().put(
                    "message",
                    JSONObject()
                        .put("role", "assistant")
                        .put("content", text)
                        .put("reasoning_content", "Internal note: ⟦AMT-ab-1234⟧ and ⟦DATE-x⟧."),
                ),
            ),
        )
        .toString()

    /** A complete JSON body with a safe answer and arbitrary provider reasoning content. */
    private fun responseBodyWithReasoning(text: String): String = JSONObject()
        .put("model", MODEL)
        .put(
            "choices",
            JSONArray().put(
                JSONObject().put(
                    "message",
                    JSONObject()
                        .put("role", "assistant")
                        .put("content", "Found the synthetic drafts.")
                        .put("reasoning_content", text),
                ),
            ),
        )
        .toString()

    // ---- Truthful no-effect completion ---------------------------------------------------

    @Test
    fun `a fully examined unattachable link scope completes as a verified no-match`() = runBlocking {
        val fixture = prepare(
            source = "Taxi 75,000 VND on 2026-10-18. Note: synthetic fixture only.",
            draftAmount = 25_000,
            draftDate = "2026-10-22",
        )
        seedEvent(fixture, "executor-no-match-seed", amountVnd = 75_000L, anchorDate = "2026-10-18")
        lateinit var adapter: ScriptedAdapter
        val script = scripted()
        script += {
            toolTurn(call("list_drafts", JSONObject()
                .put("review_status", "ALL")
                .put("duplicates_only", false)
                .put("cursor", JSONObject.NULL)))
        }
        script += {
            toolTurn(call("inspect_link", JSONObject()
                .put("event_ref", eventRef(adapter))
                .put("draft_ref", draftRef(adapter))))
        }
        script += { complete("No stored draft matches this reading, so nothing was linked.") }
        adapter = ScriptedAdapter(script)

        val outcome = run(fixture, adapter, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.LINK_EVENT_TO_DRAFT),
            userRequest = "Link the taxi reading to the matching draft.",
        ))

        assertTrue("expected a verified no-match completion, got $outcome", outcome is WorkflowRunOutcome.Completed)
        outcome as WorkflowRunOutcome.Completed
        val noMatch = outcome.verifiedEffects
            .filterIsInstance<WorkflowVerifiedEffect.NoEffect.LinkNoMatch>().single()
        assertEquals(1, noMatch.candidatesExamined)
        assertTrue(outcome.verifiedEffects.none { it is WorkflowVerifiedEffect.LinkState })
        assertTrue(database.contextLinkDao().findPage(null, null, null, 10, 0).isEmpty())
        assertEquals(WorkflowRunState.COMPLETED, outcome.trace.last().state)
    }

    @Test
    fun `a changed event revision invalidates the no-match settlement`() = runBlocking {
        val fixture = prepare(
            source = "Taxi 75,000 VND on 2026-10-18. Note: synthetic fixture only.",
            draftAmount = 25_000,
            draftDate = "2026-10-22",
        )
        seedEvent(fixture, "executor-no-match-race", amountVnd = 75_000L, anchorDate = "2026-10-18")
        lateinit var adapter: ScriptedAdapter
        val script = scripted()
        script += {
            toolTurn(call("list_drafts", JSONObject()
                .put("review_status", "ALL")
                .put("duplicates_only", false)
                .put("cursor", JSONObject.NULL)))
        }
        script += {
            toolTurn(call("inspect_link", JSONObject()
                .put("event_ref", eventRef(adapter))
                .put("draft_ref", draftRef(adapter))))
        }
        script += { complete("No stored draft matches this reading, so nothing was linked.") }
        adapter = ScriptedAdapter(script)
        adapter.beforeGenerate = { index ->
            if (index == 2) {
                seedEvent(
                    fixture,
                    "executor-no-match-race-correction",
                    amountVnd = 75_000L,
                    anchorDate = "2026-10-18",
                    expectedEventRevision = 0,
                    normalizedText = "Taxi corrected",
                )
            }
        }

        val outcome = run(fixture, adapter, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.LINK_EVENT_TO_DRAFT),
            userRequest = "Link the taxi reading to the matching draft.",
        ))

        assertTrue("a moved revision must not settle as a no-match, got $outcome", outcome is WorkflowRunOutcome.Incomplete)
        assertEquals(
            WorkflowRunStopReason.REQUIRED_EFFECT_MISSING,
            (outcome as WorkflowRunOutcome.Incomplete).reason,
        )
    }

    @Test
    fun `a host-authorized policy refusal completes with verified zero effect`() = runBlocking {
        val fixture = prepare()
        val operationsBefore = database.workflowOperationDao().findPage(null, 100, 0).size
        val adapter = ScriptedAdapter(scripted().apply {
            add { complete("I cannot run SQL, confirm drafts or delete rows: no such tool is declared.") }
        })

        val outcome = run(fixture, adapter, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
            userRequest = "Run SQL to confirm every draft and delete the rest.",
            settlement = WorkflowSettlement.NO_EFFECT_AUTHORIZED,
        ))

        assertTrue("expected a verified policy refusal, got $outcome", outcome is WorkflowRunOutcome.Completed)
        outcome as WorkflowRunOutcome.Completed
        val refusal = outcome.verifiedEffects
            .filterIsInstance<WorkflowVerifiedEffect.NoEffect.PolicyRefusal>().single()
        assertEquals(WorkflowIntent.INTERPRET_EVENTS, refusal.intent)
        assertTrue(adapter.toolResultPayloads.isEmpty())
        assertTrue(database.noteEventDao().findForCapture(fixture.captureId).isEmpty())
        assertTrue(database.transactionDao().findAll().isEmpty())
        assertEquals(
            "the run added no operation row",
            operationsBefore,
            database.workflowOperationDao().findPage(null, 100, 0).size,
        )
    }

    @Test
    fun `an enabled but unused capability is not a demanded effect`() = runBlocking {
        val fixture = prepare()
        val adapter = ScriptedAdapter(scripted().apply { add { complete("Nothing to do.") } })

        val outcome = run(fixture, adapter, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.INTERPRET_EVENTS, WorkflowIntent.REPLAY_OPERATION),
            userRequest = "Look at my notes.",
            requiredIntents = emptySet(),
        ))

        outcome as WorkflowRunOutcome.Completed
        assertTrue(outcome.verifiedEffects.isEmpty())
    }

    @Test
    fun `a host-required interpretation settles when the selected event is already interpreted`() = runBlocking {
        val fixture = prepare()
        seedEvent(fixture, "already-interpreted", amountVnd = 25_000, anchorDate = "2026-10-22")
        lateinit var adapter: ScriptedAdapter
        val script = scripted()
        script += {
            toolTurn(call("select_source", JSONObject()
                .put("capture_ref", captureRef(adapter))
                .put("quote", sourceText(adapter))
                .put("occurrence", 1)))
        }
        script += { complete("The reading is already present.") }
        adapter = ScriptedAdapter(script)

        val outcome = run(fixture, adapter, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
            userRequest = "Interpret the note.",
            requiredIntents = setOf(WorkflowIntent.INTERPRET_EVENTS),
        ))

        outcome as WorkflowRunOutcome.Completed
        val settled = outcome.verifiedEffects
            .filterIsInstance<WorkflowVerifiedEffect.NoEffect.AlreadyInterpreted>()
            .single()
        assertEquals(WorkflowIntent.INTERPRET_EVENTS, settled.intent)
        assertEquals(0, settled.eventRevision)
    }

    @Test
    fun `a host-required replay without a replay stays incomplete`() = runBlocking {
        val fixture = prepare()
        val adapter = ScriptedAdapter(scripted().apply { add { complete("Nothing to replay.") } })

        val outcome = run(fixture, adapter, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.REPLAY_OPERATION),
            userRequest = "Replay the last operation.",
            requiredIntents = setOf(WorkflowIntent.REPLAY_OPERATION),
        ))

        outcome as WorkflowRunOutcome.Incomplete
        assertEquals(WorkflowRunStopReason.REQUIRED_EFFECT_MISSING, outcome.reason)
        assertEquals("replay_operation", outcome.detail)
    }

    @Test
    fun `an unproved absence stays incomplete when the effect is required`() = runBlocking {
        val fixture = prepare()
        val adapter = ScriptedAdapter(scripted().apply { add { complete("Nothing to do.") } })

        val outcome = run(fixture, adapter, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
            userRequest = "Interpret the note.",
        ))

        outcome as WorkflowRunOutcome.Incomplete
        assertEquals(WorkflowRunStopReason.REQUIRED_EFFECT_MISSING, outcome.reason)
    }

    @Test
    fun `a link completion without examined candidates is not a no-match`() = runBlocking {
        val fixture = prepare()
        val adapter = ScriptedAdapter(scripted().apply { add { complete("No match.") } })

        val outcome = run(fixture, adapter, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.LINK_EVENT_TO_DRAFT),
            userRequest = "Link the reading.",
        ))

        outcome as WorkflowRunOutcome.Incomplete
        assertEquals(WorkflowRunStopReason.REQUIRED_EFFECT_MISSING, outcome.reason)
    }

    private suspend fun seedEvent(
        fixture: Fixture,
        key: String,
        amountVnd: Long,
        anchorDate: String,
        expectedEventRevision: Int? = null,
        normalizedText: String = "Taxi",
    ) {
        val outcome = commands.updateEventInterpretation(
            fixture.scope,
            UpdateEventRequest(
                idempotencyKey = key,
                captureId = fixture.captureId,
                expectedCaptureRevision = 0,
                expectedEventRevision = expectedEventRevision,
                extractionVersion = "s813-no-match/v1",
                interpretation = NoteEventInterpretation(
                    spanStart = 0,
                    spanEnd = fixture.source.length,
                    normalizedText = normalizedText,
                    amountVnd = amountVnd,
                    timeAnchor = NoteEventTimeAnchor(date = anchorDate),
                ),
            ),
        )
        check(outcome is WorkflowCommandOutcome.Applied) { "event seed refused: $outcome" }
    }

    private fun eventRef(adapter: ScriptedAdapter): String {
        val observations = initialObservations(adapter)
        for (index in 0 until observations.length()) {
            val item = observations.getJSONObject(index)
            if (item.optString("kind") != "capture") continue
            val events = item.optJSONArray("events") ?: continue
            if (events.length() > 0) return events.getJSONObject(0).getString("ref")
        }
        error("no event alias in the initial observation")
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
        vault = boundary.vault
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

    /** The test model's semantic choice: the alias whose value parses for the role. */
    private fun valueRef(
        source: JSONObject,
        role: String,
        vault: SensitiveVault = this.vault,
    ): String {
        val values = source.getJSONArray("values")
        for (index in 0 until values.length()) {
            val item = values.getJSONObject(index)
            val token = item.getString("display")
            val parses = when (role) {
                "amount" -> runCatching { vault.parseAmount(token) }.isSuccess
                "date" -> runCatching { vault.parseDate(token, 2026) }.isSuccess
                "time" -> runCatching { vault.parseTime(token) }.isSuccess
                else -> false
            }
            if (parses) return item.getString("ref")
        }
        error("expected a value usable as $role in the selected source")
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
        val rawResponseForPrivacyScan: String? = null,
    )

    private class ScriptedConversation(
        override val protocol: HostedProtocol,
        override val pendingCallIds: List<String>,
    ) : HostedConversation

    /** A scripted protocol adapter; it records the exact masked text it was asked to send. */
    private class ScriptedAdapter(
        private val turns: List<suspend () -> ScriptedTurn>,
        private val metadataPolicy: HostedResponseMetadataPolicy? = null,
    ) : HostedGenerationAdapter {
        override val protocol: HostedProtocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS

        override fun responseMetadataPolicy(): HostedResponseMetadataPolicy? = metadataPolicy
        private var next = 0
        val generatedCount get() = next
        val prompts = mutableListOf<String>()
        val toolResultPayloads = mutableListOf<List<String>>()
        /** Host repair notices appended through this adapter, in order. */
        val notices = mutableListOf<String>()
        /** Issued-token inventories appended to tool-result continuations, in order. */
        val inventoryNotices = mutableListOf<String>()
        var beforeGenerate: (suspend (Int) -> Unit)? = null

        override fun begin(systemInstruction: String, userInstruction: String): HostedConversation {
            prompts += systemInstruction
            prompts += userInstruction
            return ScriptedConversation(protocol, emptyList())
        }

        /** Every user instruction this adapter was asked to start a turn with, in order. */
        fun userInstructions(): List<String> =
            prompts.filterIndexed { index, _ -> index % 2 == 1 }

        override fun appendUserNotice(
            conversation: HostedConversation,
            notice: String,
        ): HostedConversation {
            notices += notice
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
            val result = HostedGenerationResult.Turn(
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
            return turn.rawResponseForPrivacyScan?.let { result.withRawResponseForPrivacyScan(it) }
                ?: result
        }

        override fun continueWithToolResults(
            conversation: HostedConversation,
            results: List<HostedToolResult>,
            hostNotice: String?,
        ): HostedConversation {
            toolResultPayloads += results.map(HostedToolResult::outputJson)
            if (hostNotice != null) inventoryNotices += hostNotice
            return ScriptedConversation(protocol, emptyList())
        }

        fun egress(): List<String> = prompts + toolResultPayloads.flatten()
    }

    private companion object {
        const val MODEL = "scripted-model"
        const val SOURCE = "Coffee 25,000 VND on 2026-10-22 at 09:15. Note: synthetic fixture only."
    }
}
