package io.github.triet4p.aegisf.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.data.AgentTraceStore
import io.github.triet4p.aegisf.data.CanonicalAgentRetryPolicy
import io.github.triet4p.aegisf.data.ContextLinkState
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.LinkOperationQuery
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.SaveCaptureRequest
import io.github.triet4p.aegisf.data.SystemLedgerClock
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
import io.github.triet4p.aegisf.provider.HostedProtocol
import io.github.triet4p.aegisf.provider.HostedProviderFailure
import io.github.triet4p.aegisf.provider.HostedToolCall
import io.github.triet4p.aegisf.provider.HostedToolResult
import io.github.triet4p.aegisf.provider.HostedTurn
import io.github.triet4p.aegisf.provider.HostedTurnFinish
import io.github.triet4p.aegisf.provider.ToolCallDisposition
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The S8.10 durable history and safe retry/cancel/restart tests.
 *
 * Real Room/services/journal/history; only the provider is scripted at the adapter
 * seam. Every assertion about an effect reads stored rows; the conversation read and
 * the safe export are asserted to carry no operation keys, digests, mappings,
 * credentials, or raw sensitive literals.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class S810DurableRunHistoryTest {
    private lateinit var database: LedgerDatabase
    private lateinit var commands: WorkflowCommandService
    private lateinit var queries: WorkflowQueryService
    private lateinit var history: AgentRunHistory
    /** The most recently prepared fixture's vault, for parse-based value selection. */
    private lateinit var activeVault: SensitiveVault
    private lateinit var declarations: List<io.github.triet4p.aegisf.provider.HostedToolDeclaration>
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
        history = AgentRunHistory(database)
        declarations = AgentToolDeclarations.load(context.assets::open)
        skills = WorkflowSkillCatalog.load(context.assets::open)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `repeated user send replays one run without duplicating the effect`() = runBlocking {
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
        script += { complete("Prepared the synthetic reading.") }
        adapter = ScriptedAdapter(script)
        val request = WorkflowRunRequest(
            intents = setOf(WorkflowIntent.SEARCH_DRAFTS, WorkflowIntent.INTERPRET_EVENTS),
            userRequest = "Prepare the synthetic pair.",
        )
        val first = runTracked(fixture, adapter, request, key("run-a"))
        val secondRequest = WorkflowRunRequest(
            intents = setOf(WorkflowIntent.SEARCH_DRAFTS, WorkflowIntent.INTERPRET_EVENTS),
            userRequest = "Prepare the synthetic pair.",
        )
        val attempted = history.admitRun(fixture.scope, secondRequest, fixture.boundary, 8192)
        assertTrue("the repeated send must replay the same run", attempted.replayed)
        assertEquals(first, attempted.runId)
        assertEquals(1L, database.workflowOperationDao().countOf("UPDATE_EVENT"))
        val conversation = history.conversationForRun(first)
        assertTrue(conversation.any { it.role == "USER" })
        assertTrue("no operation key may enter the visible conversation", conversation.none {
            it.text.contains("attempt-") || it.text.contains("run-")
        })
    }

    @Test
    fun `interrupted update reconciles to applied instead of duplicating`() = runBlocking {
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
            val source = previousEnvelope(adapter, 1).getJSONObject("data")
            toolTurn(call("record_event", JSONObject()
                .put("source_ref", source.getString("source_ref"))
                .put("summary", "Coffee")
                .put("amount_ref", valueRef(source, "amount"))
                .put("anchor_ref", JSONObject.NULL)
                .put("merchant_ref", JSONObject.NULL)
                .put("clarification", "none")))
        }
        script += { complete("The synthetic event is interpreted.") }
        adapter = ScriptedAdapter(script)
        val request = WorkflowRunRequest(
            intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
            userRequest = "Interpret the synthetic note.",
        )
        val runId = runTracked(fixture, adapter, request, key("run-b"))
        assertEquals("one event row", 1, database.noteEventDao().findForCapture(fixture.captureId).size)
        assertEquals(1L, database.workflowOperationDao().countOf("UPDATE_EVENT"))
        val verdict = history.reconcileUncertainWrite(runId, 1)
        assertTrue("the applied write must reconcile as applied, got $verdict", verdict is UncertainWriteVerdict.Applied)
    }

    @Test
    fun `an exact repeated call replays without a second journal row or event`() = runBlocking {
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
            val source = previousEnvelope(adapter, 1).getJSONObject("data")
            toolTurn(call("record_event", JSONObject()
                .put("source_ref", source.getString("source_ref"))
                .put("summary", "Coffee")
                .put("amount_ref", valueRef(source, "amount"))
                .put("anchor_ref", JSONObject.NULL)
                .put("merchant_ref", JSONObject.NULL)
                .put("clarification", "none")))
        }
        script += {
            // Exact repetition is the recorded operation alias, not a reconstructed payload.
            val operationRef = previousEnvelope(adapter, 1).getString("operation_ref")
            toolTurn(call("replay_operation", JSONObject().put("operation_ref", operationRef)))
        }
        script += { complete("done") }
        adapter = ScriptedAdapter(script)
        val request = WorkflowRunRequest(
            intents = setOf(WorkflowIntent.INTERPRET_EVENTS, WorkflowIntent.REPLAY_OPERATION),
            userRequest = "Interpret the synthetic note and confirm the exact operation.",
        )
        val runId = runTracked(fixture, adapter, request, key("run-c"))
        assertEquals(1, database.noteEventDao().findForCapture(fixture.captureId).size)
        assertEquals("one journal row per mutation key", 1L, database.workflowOperationDao().countOf("UPDATE_EVENT"))
        val attempts = database.agentRunAttemptDao().findForRun(runId)
        assertEquals(1, attempts.size)
        val payload = JSONObject(adapter.toolResultPayloads.last().single())
        assertEquals("replay_operation", payload.getString("tool"))
        assertEquals("unchanged", payload.getString("effect"))
        assertEquals("no_change", payload.getJSONObject("data").getString("journal_outcome"))
        assertTrue(payload.getBoolean("verified"))
        assertEquals(1L, database.workflowOperationDao().countOf("UPDATE_EVENT"))
    }

    @Test
    fun `a masking refusal after an applied write stops hosted work and reconciles locally`() = runBlocking {
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
            val source = previousEnvelope(adapter, 1).getJSONObject("data")
            toolTurn(call("record_event", JSONObject()
                .put("source_ref", source.getString("source_ref"))
                .put("summary", "Coffee")
                .put("amount_ref", valueRef(source, "amount"))
                .put("anchor_ref", JSONObject.NULL)
                .put("merchant_ref", JSONObject.NULL)
                .put("clarification", "none")))
        }
        script += {
            // The write is already applied when this later turn carries a raw literal.
            toolTurn(call("observe", JSONObject().put("ref", "25,000 VND")))
        }
        script += { complete("This turn must never be requested.") }
        adapter = ScriptedAdapter(script)
        val request = WorkflowRunRequest(
            intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
            userRequest = "Interpret the synthetic note.",
        )
        val admitted = history.admitRun(fixture.scope, request, fixture.boundary, 8192)
        val outcome = BoundedWorkflowExecutor(hostOf(fixture, adapter)).startTracked(
            request = request,
            history = history,
            attemptKey = admitted.attemptKey,
            maxOutputTokens = 8192,
        )
        assertTrue("expected a masking stop, got $outcome", outcome is WorkflowRunOutcome.Incomplete)
        assertEquals(
            WorkflowRunStopReason.MASKING_BLOCKED,
            (outcome as WorkflowRunOutcome.Incomplete).reason,
        )
        // The earlier call really applied; the host reconciled it locally instead of retrying.
        val operationCount = database.workflowOperationDao().countOf("UPDATE_EVENT")
        assertEquals(
            "no event after masking stop; ops=$operationCount outcome=$outcome",
            1,
            database.noteEventDao().findForCapture(fixture.captureId).size,
        )
        assertEquals(1L, database.workflowOperationDao().countOf("UPDATE_EVENT"))
        // No continuation turn was requested after the refusal.
        assertEquals(2, adapter.prompts.size)
        assertTrue(adapter.prompts.none { it.contains("25,000") })
    }

    @Test
    fun `cancel leaves the stored effect intact and never claims rollback`() = runBlocking {
        val fixture = prepare()
        val request = WorkflowRunRequest(
            intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
            userRequest = "Find the pending drafts.",
        )
        val admitted = history.admitRun(fixture.scope, request, fixture.boundary, 8192)
        assertTrue(history.cancelRun(admitted.runId, "user_cancelled"))
        val runId = admitted.runId
        val stored = database.agentRunDao().findByRunId(runId)!!
        assertEquals("CANCELLED", stored.state)
        val conversation = history.conversationForRun(runId)
        assertTrue(conversation.any { it.role == "STATUS" && it.text.contains("cancelled", ignoreCase = true) })
        assertTrue("a query-only run writes no ledger row", database.transactionDao().findAll().isEmpty())
    }

    @Test
    fun `request text is masked before history persistence`() = runBlocking {
        val fixture = prepare()
        val keyLikeValue = "SYNTHETICAPIKEY123456"
        val userRequest = "Find notes for $keyLikeValue."
        val request = WorkflowRunRequest(
            intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
            userRequest = userRequest,
        )
        val expectedMasked = fixture.boundary.maskOutbound(userRequest, MaskedPath.HISTORY)
        val admitted = history.admitRun(fixture.scope, request, fixture.boundary, 8192)
        val storedRun = database.agentRunDao().findByRunId(admitted.runId)!!
        val storedUserLine = history.conversationForRun(admitted.runId).single { it.role == "USER" }

        assertFalse(storedRun.requestText.contains(keyLikeValue))
        assertFalse(storedUserLine.text.contains(keyLikeValue))
        assertEquals(expectedMasked, storedRun.requestText)
        assertEquals(expectedMasked, storedUserLine.text)
    }

    @Test
    fun `retention prunes history and export excludes keys and mappings`() = runBlocking {
        val fixture = prepare()
        repeat(3) { index ->
            history.admitRun(
                fixture.scope,
                WorkflowRunRequest(
                    intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
                    userRequest = "History probe $index ${sequence.incrementAndGet()}.",
                ),
                fixture.boundary,
                8192,
            )
        }
        assertEquals(3L, database.agentRunDao().countOf(fixture.scope.scopeId))
        val exported = history.safeExport(fixture.scope.scopeId, 50, 0)
        val body = exported.toString()
        assertFalse("attempt keys must not export", body.contains("attempt-"))
        assertFalse("digests must not export", body.contains("digest"))
        assertFalse("operation journal must not export", body.contains("UPDATE_EVENT"))
        assertFalse("raw amounts must not export", body.contains("25,000"))
        assertTrue(history.deleteScopeHistory(fixture.scope.scopeId) >= 3)
        assertEquals(0L, database.agentRunDao().countOf(fixture.scope.scopeId))
        // Business stores are separate from the conversation: the draft survives deletion.
        assertTrue(queries.draftCandidate(fixture.draftId) != null)
    }

    @Test
    fun `late reply cannot settle a cancelled attempt`() = runBlocking {
        val fixture = prepare()
        val admitted = history.admitRun(
            fixture.scope,
            WorkflowRunRequest(
                intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
                userRequest = "Find the pending drafts ${sequence.incrementAndGet()}.",
            ),
            fixture.boundary,
            8192,
        )
        assertTrue(history.claimForExecution(admitted.runId, 1))
        assertTrue(history.cancelRun(admitted.runId, "user_cancelled"))
        val row = database.agentRunAttemptDao().findByKey(admitted.attemptKey)!!
        assertEquals("CANCELLED", row.state)
        assertEquals(null, row.ownerToken)
        // A late reply under the old token cannot settle the cancelled row.
        assertEquals(
            0,
            database.agentRunAttemptDao().settle(
                id = row.id,
                ownerToken = admitted.ownerToken ?: "missing",
                state = "COMPLETED",
                stopReason = null,
                effectKind = "query",
                eventId = null,
                eventRevision = null,
                linkId = null,
                linkState = null,
                linkRevision = null,
                draftId = null,
                decisionSource = null,
                selectedCategoryId = null,
                observationJson = "{}",
                now = System.currentTimeMillis(),
            ),
        )
    }

    @Test
    fun `clarification question and answer are stored in order and the continuation is not a retry`() =
        runBlocking {
            val fixture = prepare()
            lateinit var adapter: ScriptedAdapter
            val script = scripted()
            script += {
                toolTurn(call("list_drafts", JSONObject()
                    .put("review_status", "PENDING")
                    .put("duplicates_only", false)
                    .put("cursor", JSONObject.NULL)))
            }
            script += {
                val draftRef = JSONObject(adapter.toolResultPayloads[0].single())
                    .getJSONObject("data")
                    .getJSONArray("items")
                    .getJSONObject(0)
                    .getString("ref")
                toolTurn(call("ask_user", JSONObject()
                    .put("subject_ref", draftRef)
                    .put("field", "candidate")
                    .put("question", "Which saved draft did you mean?")))
            }
            script += { complete("Continued after the answer.") }
            adapter = ScriptedAdapter(script)
            val executor = BoundedWorkflowExecutor(hostOf(fixture, adapter))
            val request = WorkflowRunRequest(
                intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
                userRequest = "Find the draft I meant.",
            )
            val admitted = history.admitRun(fixture.scope, request, fixture.boundary, 8192)
            val first = executor.startTracked(
                admitted = admitted,
                request = request,
                history = history,
                maxOutputTokens = 8192,
            )
            assertTrue("expected a clarification, got $first", first is WorkflowRunOutcome.Clarifying)
            first as WorkflowRunOutcome.Clarifying

            val resumed = executor.resumeTracked(
                history = history,
                runId = admitted.runId,
                continuation = first.continuation,
                userAnswer = "The pending one.",
            )
            assertTrue("expected the resumed turn to settle, got $resumed", resumed is WorkflowRunOutcome.Completed)

            val lines = history.conversationForRun(admitted.runId)
            val questionIndex = lines.indexOfFirst {
                it.role == "AGENT" && it.text == "Which saved draft did you mean?"
            }
            val answerIndex = lines.indexOfFirst { it.role == "USER" && it.text == "The pending one." }
            val answerLineIndex = lines.indexOfFirst {
                it.role == "AGENT" && it.text == "Continued after the answer."
            }
            assertTrue("the clarification question was not stored", questionIndex >= 0)
            assertTrue("the clarification answer was not stored after its question", answerIndex > questionIndex)
            assertTrue("the resumed answer was not stored after the user's answer", answerLineIndex > answerIndex)
            assertTrue(
                "a continuation must not be labelled as a retry",
                lines.any { it.role == "STATUS" && it.text.contains("continued after your answer") } &&
                    lines.none { it.text.contains("retried") },
            )

            val run = requireNotNull(database.agentRunDao().findByRunId(admitted.runId))
            val restored = io.github.triet4p.aegisf.ui.transcriptOf(
                run = run,
                lines = lines,
                interruptedLine = "interrupted",
                clarificationExpiredLine = "expired",
            ) { sequence.incrementAndGet().toLong() }
            val restoredQuestion = restored.indexOfFirst {
                it is io.github.triet4p.aegisf.ui.ChatTranscriptEntry.Assistant &&
                    it.text == "Which saved draft did you mean?"
            }
            val restoredAnswer = restored.indexOfFirst {
                it is io.github.triet4p.aegisf.ui.ChatTranscriptEntry.User && it.text == "The pending one."
            }
            assertTrue("a restored transcript must show the question", restoredQuestion >= 0)
            assertTrue("a restored transcript must show the answer after it", restoredAnswer > restoredQuestion)
        }

    @Test
    fun `a per-turn tool pause is stored as a failed attempt that offers the next turn`() = runBlocking {
        val fixture = prepare()
        val calls = (1..13).map { index ->
            call("list_drafts", JSONObject()
                .put("review_status", "PENDING")
                .put("duplicates_only", false)
                .put("cursor", "stored-page-$index"))
        }
        val adapter = ScriptedAdapter(scripted() + { toolTurn(*calls.toTypedArray()) })
        val executor = BoundedWorkflowExecutor(hostOf(fixture, adapter))
        val request = WorkflowRunRequest(
            intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
            userRequest = "Look through my drafts in several pages.",
        )
        val admitted = history.admitRun(fixture.scope, request, fixture.boundary, 8192)
        val outcome = executor.startTracked(
            admitted = admitted,
            request = request,
            history = history,
            maxOutputTokens = 8192,
        )

        assertTrue("expected a per-turn pause, got $outcome", outcome is WorkflowRunOutcome.Incomplete)
        outcome as WorkflowRunOutcome.Incomplete
        assertEquals(WorkflowRunStopReason.TOOL_CALL_BUDGET, outcome.reason)
        assertEquals(12, outcome.toolCalls)
        assertNotNull("the durable pause offers the next user turn", outcome.followUp)
        assertEquals("FAILED", database.agentRunDao().findByRunId(admitted.runId)?.state)
        assertTrue(
            "the durable transcript states the same stop reason the live outcome reported",
            history.conversationForRun(admitted.runId).any {
                it.role == "STATUS" && it.text.contains("tool_call_budget")
            },
        )
    }

    @Test
    fun `clarification continuations are not capped at the failure-retry attempts`() = runBlocking {
        val fixture = prepare()
        lateinit var adapter: ScriptedAdapter
        val script = scripted()
        repeat(5) { index ->
            script += {
                toolTurn(call("ask_user", JSONObject()
                    .put("subject_ref", draftRef(adapter))
                    .put("field", "candidate")
                    .put("question", "Which saved draft did you mean ($index)?")))
            }
        }
        script += { complete("All five answers were used.") }
        adapter = ScriptedAdapter(script)
        val executor = BoundedWorkflowExecutor(hostOf(fixture, adapter))
        val request = WorkflowRunRequest(
            intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
            userRequest = "Find the draft I meant.",
        )
        val admitted = history.admitRun(fixture.scope, request, fixture.boundary, 8192)
        var outcome = executor.startTracked(
            admitted = admitted,
            request = request,
            history = history,
            maxOutputTokens = 8192,
        )
        var clarifications = 0
        while (outcome is WorkflowRunOutcome.Clarifying) {
            clarifications += 1
            check(clarifications <= 6) { "the clarification loop did not settle" }
            outcome = requireNotNull(
                executor.resumeTracked(
                    history = history,
                    runId = admitted.runId,
                    continuation = outcome.continuation,
                    userAnswer = "The pending one ($clarifications).",
                ),
            ) { "the clarification continuation was capped at attempt $clarifications" }
        }

        assertTrue("expected a settled run, got $outcome", outcome is WorkflowRunOutcome.Completed)
        assertEquals("five clarification answers must continue the same run", 5, clarifications)
        assertTrue(
            "the failure-retry cap must be smaller than the proven clarification count",
            CanonicalAgentRetryPolicy.MAX_ATTEMPTS < 6,
        )
        assertEquals("SUCCEEDED", database.agentRunDao().findByRunId(admitted.runId)?.state)
        assertEquals(6, database.agentRunAttemptDao().findForRun(admitted.runId).size)
        val lines = history.conversationForRun(admitted.runId)
        assertEquals(
            5,
            lines.count { it.role == "AGENT" && it.text.contains("Which saved draft did you mean") },
        )
        assertEquals(5, lines.count { it.role == "USER" && it.text.contains("The pending one") })
    }

    // ---- S11.C2 retry admission and applied-effect reconciliation --------------------------

    @Test
    fun `a retryable transport failure admits one request retry without a second user line`() =
        runBlocking {
            val fixture = prepare()
            val script = scripted()
            script += {
                ScriptedTurn(
                    finish = HostedTurnFinish.Complete,
                    failure = HostedProviderFailure.Transport("synthetic offline turn"),
                )
            }
            script += { complete("Retried answer.") }
            val adapter = ScriptedAdapter(script)
            val executor = BoundedWorkflowExecutor(hostOf(fixture, adapter))
            val request = WorkflowRunRequest(
                intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
                userRequest = "Find the draft I meant.",
            )
            val admitted = history.admitRun(fixture.scope, request, fixture.boundary, 8192)
            check(history.claimForExecution(admitted.runId, admitted.attempt))
            val failed = executor.startWithAttemptKey(request, key("c2-transport"), 8192)
            assertTrue("expected a transport stop, got $failed", failed is WorkflowRunOutcome.Incomplete)
            history.recordAttemptOutcome(admitted.runId, admitted.attempt, failed, fixture.boundary)

            val admission = history.retryAdmission(fixture.scope, admitted.runId)
            assertTrue("a clean retryable failure must admit a retry, got $admission", admission is RetryAdmission.Ready)
            admission as RetryAdmission.Ready
            assertEquals(RetryTarget.REQUEST, admission.target)
            assertEquals(2, admission.nextAttempt)

            val retried = executor.retryRequestTracked(history, admitted.runId, request, 8192)
            assertTrue("expected the retry to settle, got $retried", retried is WorkflowRunOutcome.Completed)
            assertEquals("SUCCEEDED", database.agentRunDao().findByRunId(admitted.runId)?.state)
            assertEquals(2, database.agentRunAttemptDao().findForRun(admitted.runId).size)
            assertEquals(
                "a retry must not append a second copy of the user's request",
                1,
                history.conversationForRun(admitted.runId).count { it.role == "USER" },
            )
        }

    @Test
    fun `an exhausted retryable run offers no retry`() = runBlocking {
        val fixture = prepare()
        val script = scripted()
        repeat(CanonicalAgentRetryPolicy.MAX_ATTEMPTS) {
            script += {
                ScriptedTurn(
                    finish = HostedTurnFinish.Complete,
                    failure = HostedProviderFailure.Transport("synthetic offline turn"),
                )
            }
        }
        val adapter = ScriptedAdapter(script)
        val executor = BoundedWorkflowExecutor(hostOf(fixture, adapter))
        val request = WorkflowRunRequest(
            intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
            userRequest = "Find the draft I meant.",
        )
        val admitted = history.admitRun(fixture.scope, request, fixture.boundary, 8192)
        val outcome = executor.startTracked(
            admitted = admitted,
            request = request,
            history = history,
            maxOutputTokens = 8192,
            retryDelay = { _ -> },
        )
        assertTrue("expected an exhausted stop, got $outcome", outcome is WorkflowRunOutcome.Incomplete)
        assertEquals(
            "the automatic retry must spend the whole allowance",
            CanonicalAgentRetryPolicy.MAX_ATTEMPTS,
            database.agentRunDao().findByRunId(admitted.runId)?.attempt,
        )
        assertEquals(
            "an exhausted run must not offer a dead-end retry",
            RetryAdmission.Blocked(RetryBlockReason.ATTEMPTS_EXHAUSTED),
            history.retryAdmission(fixture.scope, admitted.runId),
        )
    }

    @Test
    fun `a terminal provider rejection offers no retry`() = runBlocking {
        val fixture = prepare()
        val script = scripted()
        script += {
            ScriptedTurn(
                finish = HostedTurnFinish.Complete,
                failure = HostedProviderFailure.Rejected(400, "synthetic rejected request"),
            )
        }
        val adapter = ScriptedAdapter(script)
        val executor = BoundedWorkflowExecutor(hostOf(fixture, adapter))
        val request = WorkflowRunRequest(
            intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
            userRequest = "Find the draft I meant.",
        )
        val admitted = history.admitRun(fixture.scope, request, fixture.boundary, 8192)
        check(history.claimForExecution(admitted.runId, admitted.attempt))
        val failed = executor.startWithAttemptKey(request, key("c2-terminal"), 8192)
        assertTrue("expected a provider stop, got $failed", failed is WorkflowRunOutcome.Incomplete)
        history.recordAttemptOutcome(admitted.runId, admitted.attempt, failed, fixture.boundary)
        assertEquals(
            "a terminal failure must not offer Retry",
            RetryAdmission.Blocked(RetryBlockReason.TERMINAL),
            history.retryAdmission(fixture.scope, admitted.runId),
        )
    }

    @Test
    fun `an in-progress run reports that it is still being processed`() = runBlocking {
        val fixture = prepare()
        val request = WorkflowRunRequest(
            intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
            userRequest = "Find the draft I meant.",
        )
        val admitted = history.admitRun(fixture.scope, request, fixture.boundary, 8192)
        check(history.claimForExecution(admitted.runId, admitted.attempt))
        assertEquals(
            "a running attempt must not race a retry",
            RetryAdmission.Blocked(RetryBlockReason.STILL_PROCESSING),
            history.retryAdmission(fixture.scope, admitted.runId),
        )
    }

    @Test
    fun `a possibly applied write is detected from its per-tool journal keys`() = runBlocking {
        val fixture = prepare()
        val tracedHistory = traceEnabledHistory()
        lateinit var adapter: ScriptedAdapter
        val script = scripted()
        script += {
            toolTurn(call("select_source", JSONObject()
                .put("capture_ref", captureRef(adapter))
                .put("quote", sourceText(adapter))
                .put("occurrence", 1)))
        }
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
        script += {
            ScriptedTurn(
                finish = HostedTurnFinish.Complete,
                failure = HostedProviderFailure.Transport("dropped after the write"),
            )
        }
        adapter = ScriptedAdapter(script)
        val executor = BoundedWorkflowExecutor(
            hostOf(
                fixture,
                adapter,
                AgentTraceRecorder.enabled(AgentTraceStore(database.agentTraceDao())),
            ),
        )
        val request = WorkflowRunRequest(
            intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
            userRequest = "Interpret the synthetic note.",
        )
        val admitted = tracedHistory.admitRun(fixture.scope, request, fixture.boundary, 8192)
        val outcome = executor.startTracked(
            admitted = admitted,
            request = request,
            history = tracedHistory,
            maxOutputTokens = 8192,
            retryDelay = { _ -> },
        )
        assertTrue("expected a transport stop, got $outcome", outcome is WorkflowRunOutcome.Incomplete)
        assertEquals("one event row", 1, database.noteEventDao().findForCapture(fixture.captureId).size)
        val operations = queries.workflowOperationsForScope(fixture.scope.scopeId)
        val recordKey = admitted.attemptKey + ":record"
        assertTrue(
            "the attempt's actual per-tool journal key must carry the applied write, got $operations",
            operations.any { it.idempotencyKey == recordKey && it.outcome == "APPLIED" },
        )
        assertEquals(
            "an applied write must block replay, not duplicate it",
            RetryAdmission.Blocked(RetryBlockReason.POSSIBLY_APPLIED),
            tracedHistory.retryAdmission(fixture.scope, admitted.runId),
        )
        assertEquals("nothing may be replayed", 1, database.noteEventDao().findForCapture(fixture.captureId).size)
    }

    @Test
    fun `a failed clarification retries the same answer instead of the original request`() = runBlocking {
        val fixture = prepare()
        lateinit var adapter: ScriptedAdapter
        val script = scripted()
        script += {
            toolTurn(call("list_drafts", JSONObject()
                .put("review_status", "PENDING")
                .put("duplicates_only", false)
                .put("cursor", JSONObject.NULL)))
        }
        script += {
            val draftRef = JSONObject(adapter.toolResultPayloads[0].single())
                .getJSONObject("data")
                .getJSONArray("items")
                .getJSONObject(0)
                .getString("ref")
            toolTurn(call("ask_user", JSONObject()
                .put("subject_ref", draftRef)
                .put("field", "candidate")
                .put("question", "Which saved draft did you mean?")))
        }
        script += {
            ScriptedTurn(
                finish = HostedTurnFinish.Complete,
                failure = HostedProviderFailure.Transport("the answer turn was dropped"),
            )
        }
        script += { complete("Continued after the answer.") }
        adapter = ScriptedAdapter(script)
        val executor = BoundedWorkflowExecutor(hostOf(fixture, adapter))
        val request = WorkflowRunRequest(
            intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
            userRequest = "Find the draft I meant.",
        )
        val admitted = history.admitRun(fixture.scope, request, fixture.boundary, 8192)
        val first = executor.startTracked(
            admitted = admitted,
            request = request,
            history = history,
            maxOutputTokens = 8192,
        )
        assertTrue("expected a clarification, got $first", first is WorkflowRunOutcome.Clarifying)
        first as WorkflowRunOutcome.Clarifying
        val answer = "The pending one."
        val failed = executor.resumeTracked(
            history = history,
            runId = admitted.runId,
            continuation = first.continuation,
            userAnswer = answer,
        )
        assertTrue("expected the answer turn to fail, got $failed", failed is WorkflowRunOutcome.Incomplete)

        val admission = history.retryAdmission(fixture.scope, admitted.runId)
        assertTrue("a clean failed answer must admit a retry, got $admission", admission is RetryAdmission.Ready)
        admission as RetryAdmission.Ready
        assertEquals(RetryTarget.CLARIFICATION, admission.target)
        assertEquals(3, admission.nextAttempt)

        val promptsBefore = adapter.prompts.size
        val retried = executor.retryClarificationTracked(
            history = history,
            runId = admitted.runId,
            continuation = first.continuation,
            userAnswer = answer,
        )
        assertTrue("expected the retried answer to settle, got $retried", retried is WorkflowRunOutcome.Completed)
        val lines = history.conversationForRun(admitted.runId)
        assertEquals(
            "the answer must not be recorded twice",
            1,
            lines.count { it.role == "USER" && it.text == answer },
        )
        assertEquals(
            "only the original request and the answer are user lines",
            2,
            lines.count { it.role == "USER" },
        )
        val run = requireNotNull(database.agentRunDao().findByRunId(admitted.runId))
        assertEquals("SUCCEEDED", run.state)
        assertEquals(3, run.attempt)
        assertTrue(
            "a clarification retry must stay a continuation, not be labelled a request retry",
            lines.any { it.role == "STATUS" && it.text.contains("continued after your answer (attempt 3)") } &&
                lines.none { it.role == "STATUS" && it.text.contains("retried (attempt 3)") },
        )
        val retryPrompts = adapter.prompts.drop(promptsBefore)
        assertTrue(
            "the retry must re-issue the clarification answer, got $retryPrompts",
            retryPrompts.any { prompt ->
                prompt.contains("The user answered the application's clarification.") &&
                    prompt.contains(answer)
            },
        )
        assertTrue(
            "the retry must not replay the original request",
            retryPrompts.none { it.contains("Find the draft I meant.") },
        )
    }

    // ---- Harness -------------------------------------------------------------------------

    private suspend fun runTracked(
        fixture: Fixture,
        adapter: ScriptedAdapter,
        request: WorkflowRunRequest,
        attemptKey: String,
    ): String {
        val admitted = history.admitRun(fixture.scope, request, fixture.boundary, 8192)
        check(history.claimForExecution(admitted.runId, admitted.attempt)) { "run not claimed" }
        val host = hostOf(fixture, adapter)
        val executor = BoundedWorkflowExecutor(host)
        val outcome = executor.startWithAttemptKey(request, attemptKey, 8192)
        history.recordAttemptOutcome(admitted.runId, admitted.attempt, outcome, fixture.boundary)
        return admitted.runId
    }

    private fun hostOf(
        fixture: Fixture,
        adapter: ScriptedAdapter,
        trace: AgentTraceRecorder = AgentTraceRecorder.disabled(),
    ): WorkflowRunHost {
        val gateway = MaskedHostedGateway(adapter, fixture.boundary)
        return WorkflowRunHost(
            scope = fixture.scope,
            boundary = fixture.boundary,
            gateway = gateway,
            declarations = declarations,
            skills = skills,
            commands = commands,
            queries = queries,
            operationKeys = WorkflowOperationKeySource { "s810-key-${sequence.incrementAndGet()}" },
            trace = trace,
        )
    }

    private fun traceEnabledHistory(): AgentRunHistory =
        AgentRunHistory(database, traces = AgentTraceStore(database.agentTraceDao()))

    private fun key(name: String): String = "s810-$name-tracked"

    private suspend fun prepare(
        source: String = SOURCE,
        draftMemo: String = "Coffee",
    ): Fixture {
        val captureOutcome = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest("s810-capture-${sequence.incrementAndGet()}", source),
        )
        check(captureOutcome is WorkflowCommandOutcome.Applied) { "capture refused: $captureOutcome" }
        val captureId = (captureOutcome.change as WorkflowChange.CaptureSaved).captureId
        val draftId = database.pendingBankDraftDao().store(
            PendingBankDraftEntity(
                deliveryId = "s810-draft-${sequence.incrementAndGet()}",
                sourcePackage = "synthetic.fixture",
                notificationKey = null,
                postedAtEpochMillis = System.currentTimeMillis(),
                bank = "Synthetic Bank",
                parseStatus = IntakeParseStatus.PARSED,
                amountState = IntakeFieldState.PRESENT,
                amountVnd = 25_000,
                amountUnresolvedReason = null,
                direction = IntakeDirection.DEBIT,
                momentState = IntakeFieldState.PRESENT,
                statedDate = "2026-10-22",
                statedTime = "09:15",
                momentUnresolvedReason = null,
                memoState = IntakeFieldState.PRESENT,
                memo = draftMemo,
                memoUnresolvedReason = null,
                createdAtEpochMillis = System.currentTimeMillis(),
            ),
        )
        val scope = WorkflowCallerScope.AgentSession(
            "s810-session-${sequence.incrementAndGet()}",
            setOf(captureId),
            setOf(draftId),
        )
        val boundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId))
        activeVault = boundary.vault
        val maskedSource = boundary.maskOutbound(source, MaskedPath.CONTEXT)
        return Fixture(captureId, draftId, scope, boundary, maskedSource)
    }

    private fun initialObservations(adapter: ScriptedAdapter): JSONArray {
        val prompt = adapter.prompts.first { it.contains("Trusted host observations") }
        val lines = prompt.lines()
        val start = lines.indexOfFirst { it.trimStart().startsWith("[") }
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
    private fun valueRef(source: JSONObject, role: String): String {
        val values = source.getJSONArray("values")
        for (index in 0 until values.length()) {
            val item = values.getJSONObject(index)
            val token = item.getString("display")
            val parses = when (role) {
                "amount" -> runCatching { activeVault.parseAmount(token) }.isSuccess
                "date" -> runCatching { activeVault.parseDate(token, 2026) }.isSuccess
                "time" -> runCatching { activeVault.parseTime(token) }.isSuccess
                else -> false
            }
            if (parses) return item.getString("ref")
        }
        error("expected a value usable as $role in the selected source")
    }

    private fun scripted(): MutableList<suspend () -> ScriptedTurn> = mutableListOf()
    private fun toolTurn(vararg calls: ScriptedCall) =
        ScriptedTurn(calls = calls.toList(), finish = HostedTurnFinish.ToolCalls)
    private fun complete(text: String) = ScriptedTurn(text = text, finish = HostedTurnFinish.Complete)
    private fun call(name: String, arguments: JSONObject) = ScriptedCall(name, arguments.toString())

    private data class Fixture(
        val captureId: Long,
        val draftId: Long,
        val scope: WorkflowCallerScope.AgentSession,
        val boundary: MaskedPromptBoundary,
        val maskedSource: String,
    )

    private class ScriptedCall(val name: String, val argumentsJson: String?)

    private class ScriptedTurn(
        val text: String? = null,
        val calls: List<ScriptedCall> = emptyList(),
        val finish: HostedTurnFinish,
        /** A provider failure instead of a model turn; used to drive retry admission. */
        val failure: HostedProviderFailure? = null,
    )

    private class ScriptedConversation(
        override val protocol: HostedProtocol,
        override val pendingCallIds: List<String>,
    ) : HostedConversation

    private class ScriptedAdapter(
        private val turns: List<suspend () -> ScriptedTurn>,
    ) : HostedGenerationAdapter {
        override val protocol: HostedProtocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS
        private var next = 0
        val prompts = mutableListOf<String>()
        val toolResultPayloads = mutableListOf<List<String>>()

        override fun begin(systemInstruction: String, userInstruction: String): HostedConversation {
            prompts += systemInstruction
            prompts += userInstruction
            return ScriptedConversation(protocol, emptyList())
        }

        override suspend fun generate(
            conversation: HostedConversation,
            tools: List<io.github.triet4p.aegisf.provider.HostedToolDeclaration>,
        ): HostedGenerationResult {
            val producer = turns.getOrNull(next) ?: error("no scripted turn ${next + 1}")
            next += 1
            val turn = producer()
            turn.failure?.let { failure ->
                return HostedGenerationResult.ProviderFailure(failure)
            }
            val calls = turn.calls.mapIndexed { index, call ->
                HostedToolCall(
                    callId = "call-$next-$index",
                    name = call.name,
                    argumentsJson = call.argumentsJson,
                    arguments = null,
                    disposition = ToolCallDisposition.READY,
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
            hostNotice: String?,
        ): HostedConversation {
            toolResultPayloads += results.map(HostedToolResult::outputJson)
            return ScriptedConversation(protocol, emptyList())
        }
    }

    private companion object {
        const val MODEL = "s810-scripted-model"
        const val SOURCE =
            "Coffee 25,000 VND on 2026-10-22 at 09:15. Note: synthetic fixture only."
    }
}
