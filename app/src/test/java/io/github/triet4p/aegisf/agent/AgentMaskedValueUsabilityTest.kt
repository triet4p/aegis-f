package io.github.triet4p.aegisf.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.data.CaptureKind
import io.github.triet4p.aegisf.data.CaptureMessageEntity
import io.github.triet4p.aegisf.data.CaptureSourceKind
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.ClassificationEngine
import io.github.triet4p.aegisf.data.ClassificationEngineResult
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.LinkEligibility
import io.github.triet4p.aegisf.data.AgentTraceStore
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.SystemLedgerClock
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.data.laya.LayaEffectiveContext
import io.github.triet4p.aegisf.privacy.MaskedHostedGateway
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
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The masked-value usability matrix the owner-reported failure belongs to.
 *
 * The owner's real note states an amount (`giá 10000`) and an informal interval (`Ngày 02/11`,
 * `10h-11h`). Before the correction the vault typed the plain amount as account-like and minted
 * no amount alias, so `record_event` could only be answered with `clarification=amount`; this
 * class proves the corrected alias flow end to end, then covers unit/grouping spellings, a
 * genuinely missing amount with clarification/resume, an unresolvable year, and the difference
 * between an ambiguous candidate match and a refusal to operate on masked values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentMaskedValueUsabilityTest {
    private lateinit var database: LedgerDatabase
    private lateinit var commands: WorkflowCommandService
    private lateinit var queries: WorkflowQueryService
    private lateinit var declarations: List<HostedToolDeclaration>
    private lateinit var skills: WorkflowSkillCatalog
    private val sequence = AtomicInteger()

    private val ownerNote =
        "Ngayd 02/11 lúc khoảng 10h-11h tôi đã mua bánh mì ở Bánh Mì Phố với giá 10000 "
    private val ownerInstant: Long = LocalDateTime.of(2026, 11, 2, 10, 0)
        .atZone(ZoneId.of("Asia/Ho_Chi_Minh"))
        .toInstant()
        .toEpochMilli()

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
            engine = UnavailableEngine,
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
    fun `owner note records the plain amount and informal interval without asking for the amount`() =
        runBlocking {
            val captureId = insertCapture(ownerNote, ownerInstant)
            val scope = WorkflowCallerScope.AgentSession(
                "pt2-owner-${sequence.incrementAndGet()}",
                setOf(captureId),
                emptySet(),
            )
            val boundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId))
            lateinit var adapter: ScriptedAdapter
            val script = scripted()
            script += {
                toolTurn(
                    call(
                        "select_source",
                        JSONObject()
                            .put("capture_ref", captureRef(adapter))
                            .put("quote", sourceText(adapter))
                            .put("occurrence", 1),
                    ),
                )
            }
            script += {
                val selected = responseData(adapter)
                val dates = dateRefs(selected, boundary.vault)
                val times = timeRefs(selected, boundary.vault)
                assertEquals("owner note has two interval times", 2, times.size)
                toolTurn(
                    call(
                        "make_time_anchor",
                        JSONObject()
                            .put("date_ref", dates.single())
                            .put("time_ref", times[0])
                            .put("end_date_ref", dates.single())
                            .put("end_time_ref", times[1]),
                    ),
                )
            }
            script += {
                val selected = envelopeData(adapter, turnsBack = 2)
                val anchor = responseData(adapter).getString("anchor_ref")
                toolTurn(
                    call(
                        "record_event",
                        JSONObject()
                            .put("source_ref", selected.getString("source_ref"))
                            .put("summary", "Mua bánh mì tại Bánh Mì Phố")
                            .put("amount_ref", amountRef(selected, boundary.vault))
                            .put("anchor_ref", anchor)
                            .put("merchant_ref", JSONObject.NULL)
                            .put("clarification", "none"),
                    ),
                )
            }
            script += { complete("Đã ghi nhận khoản mua bánh mì.") }
            adapter = ScriptedAdapter(script)

            // Run under the PT1 local production trace so the roundtrip is provable from the
            // same evidence a real run leaves behind.
            val store = AgentTraceStore(database.agentTraceDao())
            val history = AgentRunHistory(database, traces = store)
            val recorder = AgentTraceRecorder.enabled(store)
            val executor = executor(scope, boundary, adapter, recorder)
            val request = WorkflowRunRequest(
                intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
                userRequest = "Ghi lại việc mua bánh mì trong ghi chú.",
            )
            val admitted = history.admitRun(scope, request, boundary, null)
            val outcome = executor.startTracked(
                request = request,
                history = history,
                attemptKey = admitted.attemptKey,
            )

            assertEquals(outcome.toString(), true, outcome is WorkflowRunOutcome.Completed)
            val stored = database.noteEventDao().findForCapture(captureId).single()
            assertEquals(10_000L, stored.amountVnd)
            assertEquals("2026-11-02", stored.anchorDate)
            assertEquals("10:00", stored.anchorTime)
            assertEquals("2026-11-02", stored.intervalEndDate)
            assertEquals("11:00", stored.intervalEndTime)
            assertNull(stored.unresolvedReason)
            assertTrue(database.transactionDao().findAll().isEmpty())
            assertEquals(1L, database.workflowOperationDao().countOf("UPDATE_EVENT"))

            // PT1 trace proof: the model/tool boundary carried only masked tokens and issued
            // opaque references, and the local application resolved the numeric amount itself.
            val events = history.traceForRun(admitted.runId)
            val recordCall = events.first { it.kind == "TOOL_CALL" && it.payloadJson.contains("record_event") }
            val recordArguments = JSONObject(recordCall.payloadJson).getString("argumentsJson")
            assertFalse("the model must not submit a raw amount", recordArguments.contains("10000"))
            assertTrue(
                "the model submits an issued amount alias",
                JSONObject(recordArguments).getString("amount_ref").matches(Regex("^r_[0-9a-f]{24}$")),
            )
            assertTrue(
                "the model submits an issued anchor alias",
                JSONObject(recordArguments).getString("anchor_ref").matches(Regex("^r_[0-9a-f]{24}$")),
            )
            assertTrue(
                "the selected source quote was copied from the masked text",
                events.filter { it.kind == "TOOL_CALL" && it.payloadJson.contains("select_source") }
                    .all { !JSONObject(it.payloadJson).getString("argumentsJson").contains("10000") },
            )
            val toolResults = events.filter { it.kind == "TOOL_RESULT" }
            assertTrue(toolResults.isNotEmpty())
            assertTrue(toolResults.none { it.payloadJson.contains("10000") })
            val outcomeEvent = JSONObject(events.first { it.kind == "ATTEMPT_OUTCOME" }.payloadJson)
            assertEquals("COMPLETED", outcomeEvent.getString("attemptState"))

            // Nothing asked the user to repeat a value the note states: no ask_user call was
            // dispatched and no envelope carries one.
            assertFalse(
                adapter.toolResultPayloads.flatten().any { it.contains("\"tool\":\"ask_user\"") },
            )
            // Dynamic outbound payloads (user turn, tool results, notices) carry masked aliases only;
            // the static system prefix is host-authored instruction text, not user data.
            val dynamicEgress = adapter.prompts.drop(1) + adapter.toolResultPayloads.flatten()
            for (literal in listOf("10000", "02/11", "10h", "11h")) {
                assertFalse(
                    "raw '$literal' left the device",
                    dynamicEgress.any { SensitiveVault.TOKEN_PATTERN.replace(it, " ").contains(literal) },
                )
            }
            val firstUserTurn = adapter.prompts.first { it.contains("issued_mask_token_inventory") }
            assertTrue(firstUserTurn, firstUserTurn.contains("shape=numeric"))
            assertFalse(firstUserTurn, firstUserTurn.contains("kind=AMOUNT"))
            // The initial observation offers neutral value aliases with shape hints only.
            val initialCapture = initialObservations(adapter).let { observations ->
                (0 until observations.length()).map { observations.getJSONObject(it) }
                    .first { it.optString("kind") == "capture" }
            }
            assertAllValuesAreNeutralNumeric(initialCapture)
        }

    @Test
    fun `a uniquely matching draft is provable and a competing same-day amount is ambiguous`() =
        runBlocking {
            val captureId = insertCapture(ownerNote, ownerInstant)
            val draftId = insertDraft(amountVnd = 10_000, date = "2026-11-02", time = "10:46", memo = "BANH MI PHO")
            val scope = WorkflowCallerScope.AgentSession(
                "pt2-link-${sequence.incrementAndGet()}",
                setOf(captureId),
                setOf(draftId),
            )
            val boundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId))
            // The event is recorded through the same trusted masked interface; the app's own
            // link evidence then decides the pair.
            val eventId = recordOwnerEvent(scope, boundary, captureId)
            val unique = commands.inspectLinkEvidence(scope, eventId, draftId)
            assertEquals(LinkEligibility.ELIGIBLE, unique?.eligibility)
            assertNull(unique?.reason)

            // A second draft stating the same amount on the same day is a legitimate ambiguity,
            // not a refusal to operate on the masked amount: the app says the choice is the user's.
            val competingId = insertDraft(
                amountVnd = 10_000,
                date = "2026-11-02",
                time = "10:50",
                memo = "BANH MI KHAC",
            )
            val widened = WorkflowCallerScope.AgentSession(
                scope.scopeId,
                setOf(captureId),
                setOf(draftId, competingId),
            )
            val ambiguous = commands.inspectLinkEvidence(widened, eventId, draftId)
            assertEquals(LinkEligibility.AMBIGUOUS, ambiguous?.eligibility)
            assertEquals("COMPETING_CANDIDATE", ambiguous?.reason)
            // Nothing was linked: an ambiguous match stays the user's decision.
            assertFalse(
                queries.linkOperations(
                    io.github.triet4p.aegisf.data.LinkOperationQuery(
                        bounds = io.github.triet4p.aegisf.data.QueryPageRequest(50, 0),
                    ),
                ).items.any { it.eventId == eventId },
            )
        }

    @Test
    fun `a genuinely missing amount records a partial reading then resumes with the answer`() =
        runBlocking {
            val note = "Ngày 03/11 tôi đi bộ trong công viên"
            val captureId = insertCapture(note, ownerInstant)
            val scope = WorkflowCallerScope.AgentSession(
                "pt2-clarify-${sequence.incrementAndGet()}",
                setOf(captureId),
                emptySet(),
            )
            val boundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId))
            lateinit var adapter: ScriptedAdapter
            val script = scripted()
            script += {
                toolTurn(
                    call(
                        "select_source",
                        JSONObject()
                            .put("capture_ref", captureRef(adapter))
                            .put("quote", sourceText(adapter))
                            .put("occurrence", 1),
                    ),
                )
            }
            script += {
                val selected = responseData(adapter)
                assertTrue(amountRefs(selected, boundary.vault).isEmpty())
                toolTurn(
                    call(
                        "record_event",
                        JSONObject()
                            .put("source_ref", selected.getString("source_ref"))
                            .put("summary", "Đi bộ trong công viên")
                            .put("amount_ref", JSONObject.NULL)
                            .put("anchor_ref", JSONObject.NULL)
                            .put("merchant_ref", JSONObject.NULL)
                            .put("clarification", "amount"),
                    ),
                )
            }
            script += {
                val selected = envelopeData(adapter, turnsBack = 2)
                toolTurn(
                    call(
                        "ask_user",
                        JSONObject()
                            .put("subject_ref", selected.getString("source_ref"))
                            .put("field", "amount")
                            .put("question", "Số tiền của lần đi bộ này là bao nhiêu?"),
                    ),
                )
            }
            // Turn after the host resumes with the user's answer: re-observe, reselect the
            // occurrence, then record it with the clarification's amount alias.
            script += {
                toolTurn(
                    call(
                        "select_source",
                        JSONObject()
                            .put("capture_ref", captureRef(adapter))
                            .put("quote", sourceText(adapter))
                            .put("occurrence", 1),
                    ),
                )
            }
            script += {
                val clarification = clarificationValueRef(adapter)
                val selected = responseData(adapter)
                toolTurn(
                    call(
                        "record_event",
                        JSONObject()
                            .put("source_ref", selected.getString("source_ref"))
                            .put("summary", "Đi bộ trong công viên")
                            .put("amount_ref", clarification)
                            .put("anchor_ref", JSONObject.NULL)
                            .put("merchant_ref", JSONObject.NULL)
                            .put("clarification", "none"),
                    ),
                )
            }
            script += { complete("Đã ghi nhận khoản đi bộ.") }
            adapter = ScriptedAdapter(script)
            val executor = executor(scope, boundary, adapter)

            val first = executor.start(
                WorkflowRunRequest(
                    intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
                    userRequest = "Ghi lại lần đi bộ.",
                ),
            )
            val clarifying = first as? WorkflowRunOutcome.Clarifying
                ?: error("expected a clarification, got $first")
            assertEquals("amount", clarifying.field)
            assertNull(database.noteEventDao().findForCapture(captureId).single().amountVnd)

            val resumed = executor.resume(clarifying.continuation, "35000")
            assertTrue(resumed.toString(), resumed is WorkflowRunOutcome.Completed)
            val stored = database.noteEventDao().findForCapture(captureId).single()
            assertEquals(35_000L, stored.amountVnd)
            assertNull(stored.unresolvedReason)
            // The answer itself is masked outbound; only the issued token appears in the prompt.
            val resumePrompt = adapter.prompts.last { it.contains("Trusted host observations") }
            assertFalse(resumePrompt, resumePrompt.contains("35000"))
            assertTrue(resumePrompt, resumePrompt.contains("shape=numeric"))
        }

    @Test
    fun `a year-less date without a capture instant is clarified rather than guessed`() = runBlocking {
        val captureId = insertCapture(ownerNote, capturedAtMillis = null)
        val scope = WorkflowCallerScope.AgentSession(
            "pt2-yearless-${sequence.incrementAndGet()}",
            setOf(captureId),
            emptySet(),
        )
        val boundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId))
        val runtime = AgentToolRuntime(
            commands = commands,
            queries = queries,
            session = AgentToolSession(scope, boundary, AgentReferenceRegistry(scope.scopeId)),
            declarations = declarations,
        )
        val capture = runtime.initialCaptureObservations(4).single()
        val source = runtime.execute(
            "select_source",
            JSONObject()
                .put("capture_ref", capture.getString("ref"))
                .put("quote", capture.getString("source_text"))
                .put("occurrence", 1),
            "read-only",
        ).envelope.getJSONObject("data")
        val date = dateRefs(source, boundary.vault).single()
        // Without a trusted capture instant a year-less date cannot be resolved, so the anchor
        // builder refuses with a repairable temporal constraint instead of borrowing a year.
        val anchorEnvelope = runtime.execute(
            "make_time_anchor",
            JSONObject()
                .put("date_ref", date)
                .put("time_ref", JSONObject.NULL)
                .put("end_date_ref", JSONObject.NULL)
                .put("end_time_ref", JSONObject.NULL),
            "read-only",
        ).envelope
        assertEquals("error", anchorEnvelope.getString("outcome"))
        assertEquals("TEMPORAL_CONSTRAINT", anchorEnvelope.getJSONObject("error").getString("code"))
        assertTrue(anchorEnvelope.getJSONObject("error").getBoolean("retryable"))
        assertTrue(database.noteEventDao().findForCapture(captureId).isEmpty())
    }

    @Test
    fun `two stated amounts in one note stay two distinct usable aliases`() = runBlocking {
        val captureId = insertCapture("Mua vé giá 50000 và gửi xe giá 20000", ownerInstant)
        val scope = WorkflowCallerScope.AgentSession(
            "pt2-two-amounts-${sequence.incrementAndGet()}",
            setOf(captureId),
            emptySet(),
        )
        val boundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId))
        val runtime = AgentToolRuntime(
            commands = commands,
            queries = queries,
            session = AgentToolSession(scope, boundary, AgentReferenceRegistry(scope.scopeId)),
            declarations = declarations,
        )
        val capture = runtime.initialCaptureObservations(4).single()
        val source = runtime.execute(
            "select_source",
            JSONObject()
                .put("capture_ref", capture.getString("ref"))
                .put("quote", capture.getString("source_text"))
                .put("occurrence", 1),
            "read-only",
        ).envelope.getJSONObject("data")
        val amounts = observationValues(source).filter {
            runCatching { boundary.vault.parseAmount(it.getString("display")) }.isSuccess
        }
        assertEquals(2, amounts.size)
        val raw = SensitiveVault.TOKEN_PATTERN.findAll(source.getString("quote"))
            .map { it.value }
            .toList()
        assertEquals(2, raw.size)
        // The two tokens are distinct and resolve locally to the two stated amounts.
        val resolved = amounts.map { boundary.vault.parseAmount(it.getString("display")) }.toSet()
        assertEquals(setOf(50_000L, 20_000L), resolved)
        assertTrue(
            boundary.vault.compareAmounts(amounts[0].getString("display"), amounts[1].getString("display")) != 0,
        )
    }

    @Test
    fun `a numeric price and a numeric account in one note do not force a clarification`() =
        runBlocking {
            val captureId = insertCapture("Mua sách giá 10000, tài khoản 4821", ownerInstant)
            val scope = WorkflowCallerScope.AgentSession(
                "pt2-mixed-${sequence.incrementAndGet()}",
                setOf(captureId),
                emptySet(),
            )
            val boundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId))
            val runtime = AgentToolRuntime(
                commands = commands,
                queries = queries,
                session = AgentToolSession(scope, boundary, AgentReferenceRegistry(scope.scopeId)),
                declarations = declarations,
            )
            val capture = runtime.initialCaptureObservations(4).single()
            val source = runtime.execute(
                "select_source",
                JSONObject()
                    .put("capture_ref", capture.getString("ref"))
                    .put("quote", capture.getString("source_text"))
                    .put("occurrence", 1),
                "read-only",
            ).envelope.getJSONObject("data")
            val values = observationValues(source)
            assertEquals("both numeric values are selectable", 2, values.size)
            assertTrue(values.all { it.getString("kind") == "value" })
            assertTrue(values.all { it.getString("shape") == "numeric" })

            // The model distinguishes the roles from the sentence: the value after "giá" is the
            // price, the value after "tài khoản" is the account. The host does not need another
            // clarification merely because two numeric values exist.
            val maskedQuote = source.getString("quote")
            val priceToken = SensitiveVault.TOKEN_PATTERN.find(maskedQuote.substringAfter("giá "))!!.value
            val priceRef = values.single { it.getString("display") == priceToken }.getString("ref")
            val recorded = runtime.execute(
                "record_event",
                JSONObject()
                    .put("source_ref", source.getString("source_ref"))
                    .put("summary", "Mua sách")
                    .put("amount_ref", priceRef)
                    .put("anchor_ref", JSONObject.NULL)
                    .put("merchant_ref", JSONObject.NULL)
                    .put("clarification", "none"),
                "pt2-mixed-record",
            ).envelope
            assertEquals(recorded.toString(), "ok", recorded.getString("outcome"))
            assertEquals(10_000L, database.noteEventDao().findForCapture(captureId).single().amountVnd)
        }

    // ---- Fixtures -------------------------------------------------------------------------

    private suspend fun insertCapture(text: String, capturedAtMillis: Long?): Long =
        database.captureMessageDao().insert(
            CaptureMessageEntity(
                captureKind = CaptureKind.USER_NOTE,
                sourceKind = CaptureSourceKind.USER_QUICK_CAPTURE,
                sourceIdentity = "pt2-capture-${sequence.incrementAndGet()}",
                bodyText = text,
                capturedAtEpochMillis = capturedAtMillis,
                captureTimezone = "Asia/Ho_Chi_Minh",
                revision = 0,
                originRef = null,
            ),
        )

    private fun insertDraft(amountVnd: Long, date: String, time: String, memo: String): Long =
        runBlocking {
            database.pendingBankDraftDao().store(
                PendingBankDraftEntity(
                    deliveryId = "pt2-draft-${sequence.incrementAndGet()}",
                    sourcePackage = "synthetic.fixture",
                    notificationKey = null,
                    postedAtEpochMillis = ownerInstant,
                    bank = "Synthetic Bank",
                    parseStatus = IntakeParseStatus.PARSED,
                    amountState = IntakeFieldState.PRESENT,
                    amountVnd = amountVnd,
                    amountUnresolvedReason = null,
                    direction = IntakeDirection.DEBIT,
                    momentState = IntakeFieldState.PRESENT,
                    statedDate = date,
                    statedTime = time,
                    momentUnresolvedReason = null,
                    memoState = IntakeFieldState.PRESENT,
                    memo = memo,
                    memoUnresolvedReason = null,
                    createdAtEpochMillis = ownerInstant,
                ),
            )
        }

    private fun executor(
        scope: WorkflowCallerScope.AgentSession,
        boundary: MaskedPromptBoundary,
        adapter: ScriptedAdapter,
        recorder: AgentTraceRecorder = AgentTraceRecorder.disabled(),
    ): BoundedWorkflowExecutor {
        val host = WorkflowRunHost(
            scope = scope,
            boundary = boundary,
            gateway = MaskedHostedGateway(adapter, boundary),
            declarations = declarations,
            skills = skills,
            commands = commands,
            queries = queries,
            operationKeys = WorkflowOperationKeySource { "pt2-key-${sequence.incrementAndGet()}" },
            trace = recorder,
        )
        return BoundedWorkflowExecutor(host)
    }

    /** Records the owner note through the trusted runtime and returns the stored event id. */
    private suspend fun recordOwnerEvent(
        scope: WorkflowCallerScope.AgentSession,
        boundary: MaskedPromptBoundary,
        captureId: Long,
    ): Long {
        val runtime = AgentToolRuntime(
            commands = commands,
            queries = queries,
            session = AgentToolSession(scope, boundary, AgentReferenceRegistry(scope.scopeId)),
            declarations = declarations,
        )
        val capture = runtime.initialCaptureObservations(4).single()
        val source = runtime.execute(
            "select_source",
            JSONObject()
                .put("capture_ref", capture.getString("ref"))
                .put("quote", capture.getString("source_text"))
                .put("occurrence", 1),
            "read-only",
        ).envelope.getJSONObject("data")
        val dates = dateRefs(source, boundary.vault)
        val times = timeRefs(source, boundary.vault)
        val anchor = runtime.execute(
            "make_time_anchor",
            JSONObject()
                .put("date_ref", dates.single())
                .put("time_ref", times[0])
                .put("end_date_ref", dates.single())
                .put("end_time_ref", times[1]),
            "read-only",
        ).envelope.getJSONObject("data").getString("anchor_ref")
        val recorded = runtime.execute(
            "record_event",
            JSONObject()
                .put("source_ref", source.getString("source_ref"))
                .put("summary", "Mua bánh mì tại Bánh Mì Phố")
                .put("amount_ref", amountRef(source, boundary.vault))
                .put("anchor_ref", anchor)
                .put("merchant_ref", JSONObject.NULL)
                .put("clarification", "none"),
            "pt2-owner-record",
        ).envelope
        assertEquals(recorded.toString(), "ok", recorded.getString("outcome"))
        return database.noteEventDao().findForCapture(captureId).single().id
    }

    // ---- Scripted provider ----------------------------------------------------------------

    private fun scripted(): MutableList<suspend () -> ScriptedTurn> = mutableListOf()

    private fun toolTurn(vararg calls: ScriptedCall): ScriptedTurn =
        ScriptedTurn(calls = calls.toList(), finish = HostedTurnFinish.ToolCalls)

    private fun complete(text: String): ScriptedTurn =
        ScriptedTurn(text = text, finish = HostedTurnFinish.Complete)

    private fun call(
        name: String,
        arguments: JSONObject,
        disposition: ToolCallDisposition = ToolCallDisposition.READY,
    ): ScriptedCall = ScriptedCall(name, arguments.toString(), disposition)

    private data class ScriptedCall(
        val name: String,
        val argumentsJson: String,
        val disposition: ToolCallDisposition,
    )

    private data class ScriptedTurn(
        val text: String? = null,
        val calls: List<ScriptedCall> = emptyList(),
        val finish: HostedTurnFinish,
    )

    private class Conversation(override val pendingCallIds: List<String>) : HostedConversation {
        override val protocol: HostedProtocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS
    }

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
            return Conversation(emptyList())
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
                )
            }
            val continuation = if (turn.finish == HostedTurnFinish.ToolCalls) {
                Conversation(calls.map(HostedToolCall::callId))
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
            hostNotice?.let { prompts += it }
            return Conversation(emptyList())
        }

        override fun appendUserNotice(
            conversation: HostedConversation,
            notice: String,
        ): HostedConversation {
            prompts += notice
            return Conversation(emptyList())
        }

        fun egress(): List<String> = prompts + toolResultPayloads.flatten()
    }

    private object UnavailableEngine : ClassificationEngine {
        override suspend fun classify(
            context: LayaEffectiveContext,
            categories: List<CategoryEntity>,
        ): ClassificationEngineResult =
            ClassificationEngineResult.Failed("Laya is not available in this unit test.")
    }

    // ---- Envelope helpers -----------------------------------------------------------------

    private fun initialObservations(adapter: ScriptedAdapter): JSONArray =
        observationBlock(adapter.prompts.last { it.contains("Trusted host observations") })

    private fun observationBlock(prompt: String): JSONArray {
        val lines = prompt.lines()
        val label = lines.indexOfFirst { it.contains("Trusted host observations") }
        check(label >= 0) { "no observation label in the prompt" }
        val start = (label + 1 until lines.size).firstOrNull { lines[it].trimStart().startsWith("[") }
            ?: error("no observation block in the prompt")
        return JSONArray(lines[start].trim())
    }

    private fun captureRef(adapter: ScriptedAdapter): String = initialObservations(adapter)
        .let { observations ->
            (0 until observations.length()).map { observations.getJSONObject(it) }
                .first { it.optString("kind") == "capture" }
                .getString("ref")
        }

    private fun sourceText(adapter: ScriptedAdapter): String = initialObservations(adapter)
        .let { observations ->
            (0 until observations.length()).map { observations.getJSONObject(it) }
                .first { it.optString("kind") == "capture" }
                .getString("source_text")
        }

    private fun clarificationValueRef(adapter: ScriptedAdapter): String {
        val observations = initialObservations(adapter)
        for (index in 0 until observations.length()) {
            val item = observations.getJSONObject(index)
            if (item.optString("kind") == "clarification") return item.getString("value_ref")
        }
        error("no clarification value alias in the resume observations")
    }

    /** The last tool-result envelope's `data` object. */
    private fun responseData(adapter: ScriptedAdapter): JSONObject = lastEnvelope(adapter).getJSONObject("data")

    /** The `data` object of the envelope from [turnsBack] continuations ago (1 = last). */
    private fun envelopeData(adapter: ScriptedAdapter, turnsBack: Int): JSONObject {
        val payloads = adapter.toolResultPayloads
        val index = if (turnsBack == Int.MAX_VALUE) 0 else payloads.size - turnsBack
        return JSONObject(payloads[index].single()).getJSONObject("data")
    }

    private fun lastEnvelope(adapter: ScriptedAdapter): JSONObject =
        JSONObject(adapter.toolResultPayloads.last().single())

    /**
     * The test model's semantic choice, standing in for reading the sentence: the one alias
     * whose value parses as a whole-dong amount, date or time. The host gate itself has no such
     * preference; it parses whichever alias the model passes to the argument.
     */
    private fun observationValues(source: JSONObject): List<JSONObject> =
        source.getJSONArray("values").let { values ->
            (0 until values.length()).map { values.getJSONObject(it) }
        }

    private fun amountRef(source: JSONObject, vault: SensitiveVault): String =
        amountRefs(source, vault).single()

    private fun amountRefs(source: JSONObject, vault: SensitiveVault): List<String> =
        observationValues(source)
            .filter { runCatching { vault.parseAmount(it.getString("display")) }.isSuccess }
            .map { it.getString("ref") }

    private fun dateRefs(source: JSONObject, vault: SensitiveVault): List<String> =
        observationValues(source)
            .filter {
                runCatching { vault.parseDate(it.getString("display"), 2026) }.isSuccess
            }
            .map { it.getString("ref") }

    private fun timeRefs(source: JSONObject, vault: SensitiveVault): List<String> =
        observationValues(source)
            .filter { runCatching { vault.parseTime(it.getString("display")) }.isSuccess }
            .map { it.getString("ref") }

    private fun assertAllValuesAreNeutralNumeric(source: JSONObject) {
        val values = observationValues(source)
        assertTrue("expected value aliases", values.isNotEmpty())
        assertTrue(
            values.toString(),
            values.all { it.getString("kind") == "value" && it.getString("shape") == "numeric" },
        )
    }

    private companion object {
        const val MODEL = "synthetic-model"
    }
}
