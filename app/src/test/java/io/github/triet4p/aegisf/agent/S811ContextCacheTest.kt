package io.github.triet4p.aegisf.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.data.ContextLinkState
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.LinkOperationQuery
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.SaveCaptureRequest
import io.github.triet4p.aegisf.data.SystemLedgerClock
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
import io.github.triet4p.aegisf.provider.HostedProtocol
import io.github.triet4p.aegisf.provider.HostedProviderIdentity
import io.github.triet4p.aegisf.provider.HostedToolCall
import io.github.triet4p.aegisf.provider.HostedToolDeclaration
import io.github.triet4p.aegisf.provider.HostedGenerationResult
import io.github.triet4p.aegisf.provider.HostedToolResult
import io.github.triet4p.aegisf.provider.HostedTurn
import io.github.triet4p.aegisf.provider.HostedTurnFinish
import io.github.triet4p.aegisf.provider.HostedUsage
import io.github.triet4p.aegisf.provider.ToolCallDisposition
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
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
 * The S8.11 focused context/cache regression tests.
 *
 * Real Room/services/history/boundary/tools; only the provider is scripted at the
 * adapter seam, and the scripted adapter reports caller-supplied [HostedUsage] exactly
 * like a real adapter parses it from the wire. Every test asserts consumer-visible
 * behavior: same intents produce the same stable prefix; usage is recorded exactly
 * (never estimated); cache misses, expiry and provider switches preserve behavior and
 * isolation; compaction keeps verified effects while re-reading mutable state fresh;
 * no secrets or raw-value mappings ever enter the cache identity or the prompt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class S811ContextCacheTest {
    private lateinit var database: LedgerDatabase
    private lateinit var commands: WorkflowCommandService
    private lateinit var queries: WorkflowQueryService
    private lateinit var declarations: List<HostedToolDeclaration>
    private lateinit var skills: WorkflowSkillCatalog
    private val sequence = AtomicInteger()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val laya = LayaInferenceLifecycle(File("build/s811-test-missing-model"))
        commands = WorkflowCommandService(
            database = database,
            clock = SystemLedgerClock,
            timezoneId = { "Asia/Ho_Chi_Minh" },
            engine = LayaClassificationEngine(laya),
        )
        queries = WorkflowQueryService(database)
        declarations = S8AgentToolDeclarations.load(context.assets::open)
        skills = WorkflowSkillCatalog.load(context.assets::open)
    }

    @After
    fun tearDown() {
        WorkflowClaimGuard.reset()
        database.close()
    }

    // ---- Stable versioned prefix -----------------------------------------------------

    @Test
    fun `same intents produce the same stable system prefix across runs`() = runBlocking {
        val first = prepare()
        val second = prepare()
        val adapter = ScriptedAdapter(scripted() + { complete("done") }, usage = null)
        val firstPrompts = promptsOf(first, adapter)
        val secondAdapter = ScriptedAdapter(scripted() + { complete("done") }, usage = null)
        val secondPrompts = promptsOf(second, secondAdapter)
        assertEquals("the stable prefix must be deterministic", firstPrompts.first(), secondPrompts.first())
        assertTrue(firstPrompts.first().contains("context_block_version: s8-agent-context/v1"))
        assertTrue(firstPrompts.first().contains("mutable_state_rule"))
        assertTrue(firstPrompts.first().contains("skill_id: s8-draft-search"))
        assertFalse(firstPrompts.first().contains("skill_id: s8-laya-review"))

        // Different intents select different skills: the prefix is stable per workflow, not global.
        val laya = prepare()
        val layaAdapter = ScriptedAdapter(scripted() + { complete("done") }, usage = null)
        val layaPrompts = promptsOf(
            laya,
            layaAdapter,
            intents = setOf(WorkflowIntent.PREPARE_LAYA_REVIEW),
        )
        assertFalse(layaPrompts.first() == firstPrompts.first())
        assertTrue(layaPrompts.first().contains("skill_id: s8-laya-review"))
    }

    // ---- Actual usage, never estimated -------------------------------------------------

    @Test
    fun `per-turn usage is recorded exactly and classified as cached on a hit`() = runBlocking {
        val fixture = prepare()
        val script = scripted()
        script += { toolTurn(call(BoundedWorkflowToolAdapter.QUERY_DRAFT_CANDIDATES, draftQueryArgs())) }
        script += { complete("Found the synthetic drafts.") }
        val adapter = ScriptedAdapter(
            script,
            usage = HostedUsage(inputTokens = 1_200, outputTokens = 40, cachedInputTokens = 1_024),
        )
        val outcome = run(fixture, adapter, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
            userRequest = "Find the pending drafts.",
        ))
        assertTrue("expected a verified completion, got $outcome", outcome is WorkflowRunOutcome.Completed)
        outcome as WorkflowRunOutcome.Completed
        assertEquals(2, outcome.usage.size)
        assertTrue(outcome.usage.all { it.inputTokens == 1_200 && it.cachedInputTokens == 1_024 })
        assertEquals(AgentCacheSupport.CACHED_PREFIX_REUSE, outcome.cacheSupport)
        assertTrue("the cache identity must be present", outcome.cacheKey != null)
        assertEquals(HostedProtocol.OPENAI_CHAT_COMPLETIONS, outcome.cacheKey!!.protocol)
    }

    @Test
    fun `absent usage is reported unsupported never fabricated`() = runBlocking {
        val fixture = prepare()
        val script = scripted()
        script += { toolTurn(call(BoundedWorkflowToolAdapter.QUERY_DRAFT_CANDIDATES, draftQueryArgs())) }
        script += { complete("Found the synthetic drafts.") }
        val adapter = ScriptedAdapter(script, usage = null)
        val outcome = run(fixture, adapter, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
            userRequest = "Find the pending drafts.",
        ))
        assertTrue(outcome is WorkflowRunOutcome.Completed)
        outcome as WorkflowRunOutcome.Completed
        assertTrue(outcome.usage.isEmpty())
        assertEquals(AgentCacheSupport.USAGE_UNSUPPORTED, outcome.cacheSupport)
    }

    @Test
    fun `usage without cached input reports no cache hit`() = runBlocking {
        val fixture = prepare()
        val script = scripted()
        script += { toolTurn(call(BoundedWorkflowToolAdapter.QUERY_DRAFT_CANDIDATES, draftQueryArgs())) }
        script += { complete("Found the synthetic drafts.") }
        val adapter = ScriptedAdapter(
            script,
            usage = HostedUsage(inputTokens = 900, outputTokens = 30, cachedInputTokens = 0),
        )
        val outcome = run(fixture, adapter, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
            userRequest = "Find the pending drafts.",
        ))
        assertTrue(outcome is WorkflowRunOutcome.Completed)
        outcome as WorkflowRunOutcome.Completed
        assertEquals(AgentCacheSupport.NO_CACHE_REPORTED, outcome.cacheSupport)
    }

    // ---- Cache-key isolation ------------------------------------------------------------

    @Test
    fun `cache identities isolate provider model config skill and privacy context`() = runBlocking {
        val fixture = prepare()
        val adapter = ScriptedAdapter(scripted() + { complete("done") }, usage = null)
        run(fixture, adapter, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
            userRequest = "Find the pending drafts.",
        ))
        // The run already proved the identity exists; now check isolation rules directly.
        val base = AgentContextManager.cacheKey(
            protocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            endpointHost = "opencode.ai",
            model = "deepseek-v4.1-flash",
            generationBudget = "maxOutputTokens=8192;reasoningEffort=-;temperature=-;parallelToolCalls=false",
            declarations = declarations,
            skills = skills.select(setOf(WorkflowIntent.SEARCH_DRAFTS)),
            privacyScopeTag = fixture.boundary.vault.scopeTag,
        )
        val otherModel = base.copy(model = "other-model")
        val otherScope = base.copy(privacyContext = "other-scope")
        val otherSkills = AgentContextManager.cacheKey(
            protocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            endpointHost = "opencode.ai",
            model = "deepseek-v4.1-flash",
            generationBudget = "maxOutputTokens=8192;reasoningEffort=-;temperature=-;parallelToolCalls=false",
            declarations = declarations,
            skills = skills.select(setOf(WorkflowIntent.PREPARE_LAYA_REVIEW)),
            privacyScopeTag = fixture.boundary.vault.scopeTag,
        )
        assertFalse(base.fingerprint() == otherModel.fingerprint())
        assertFalse(base.fingerprint() == otherScope.fingerprint())
        assertFalse(base.fingerprint() == otherSkills.fingerprint())
        assertFalse("no raw value may enter the identity", base.fingerprint().contains("25,000"))
        val rendered = AgentContextManager.canonicalToolSchema(declarations)
        assertFalse(rendered.contains("25,000"))
    }

    @Test
    fun `provider switch starts a fresh chain and never reuses the other identity`() = runBlocking {
        val fixture = prepare()
        val first = ScriptedAdapter(scripted() + { complete("first done") }, usage = null)
        val firstOutcome = run(
            fixture,
            first,
            WorkflowRunRequest(
                intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
                userRequest = "Find the pending drafts.",
            ),
            protocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
        )
        assertTrue(firstOutcome is WorkflowRunOutcome.Completed)
        firstOutcome as WorkflowRunOutcome.Completed
        // A switch means a new executor/gateway over a different protocol: the second run
        // builds its own conversation from the same stable prefix, never resumes the first
        // chain, and carries the other protocol's identity.
        val second = ScriptedAdapter(
            scripted() + { complete("second done") },
            usage = null,
            protocol = HostedProtocol.OPENAI_RESPONSES,
            identity = HostedProviderIdentity(
                protocol = HostedProtocol.OPENAI_RESPONSES,
                endpointHost = "scripted.local",
                model = MODEL,
                budget = io.github.triet4p.aegisf.provider.HostedGenerationBudget(maxOutputTokens = 8_192),
            ),
        )
        val secondOutcome = run(
            fixture,
            second,
            WorkflowRunRequest(
                intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
                userRequest = "Find the pending drafts.",
            ),
            protocol = HostedProtocol.OPENAI_RESPONSES,
        )
        assertTrue(secondOutcome is WorkflowRunOutcome.Completed)
        secondOutcome as WorkflowRunOutcome.Completed
        assertEquals(HostedProtocol.OPENAI_CHAT_COMPLETIONS, firstOutcome.cacheKey!!.protocol)
        assertEquals(HostedProtocol.OPENAI_RESPONSES, secondOutcome.cacheKey!!.protocol)
        assertFalse(firstOutcome.cacheKey!!.fingerprint() == secondOutcome.cacheKey!!.fingerprint())
    }

    // ---- Expiry, invalidation, compaction ---------------------------------------------------

    @Test
    fun `a cache miss after earlier hits still verifies the same effects`() = runBlocking {
        val fixture = prepare()
        val script = scripted()
        script += { toolTurn(call(BoundedWorkflowToolAdapter.QUERY_DRAFT_CANDIDATES, draftQueryArgs())) }
        script += { complete("Found the synthetic drafts.") }
        // First turn reports a cached prefix; the second reports none (expiry). The run
        // must still complete with the same verified behavior.
        val adapter = ScriptedAdapter(
            script,
            usageSequence = listOf(
                HostedUsage(inputTokens = 2_000, outputTokens = 20, cachedInputTokens = 1_536),
                HostedUsage(inputTokens = 2_100, outputTokens = 10, cachedInputTokens = 0),
            ),
        )
        val outcome = run(fixture, adapter, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
            userRequest = "Find the pending drafts.",
        ))
        assertTrue(outcome is WorkflowRunOutcome.Completed)
        outcome as WorkflowRunOutcome.Completed
        // One turn still saw a hit: the run honestly reports reuse, not expiry-as-failure.
        assertEquals(AgentCacheSupport.CACHED_PREFIX_REUSE, outcome.cacheSupport)
        assertEquals(2, outcome.usage.size)
        assertEquals(1_536, outcome.usage.first().cachedInputTokens)
        assertEquals(0, outcome.usage.last().cachedInputTokens)
    }

    @Test
    fun `stale mutable state never survives compaction without a fresh re-read`() = runBlocking {
        val fixture = prepare()
        var observedEventId = 0L
        val script = scripted()
        script += { toolTurn(call(BoundedWorkflowToolAdapter.UPDATE_EVENT_INTERPRETATION, updateArgs(fixture))) }
        script += {
            observedEventId = queries.captureEvents(
                io.github.triet4p.aegisf.data.CaptureEventQuery(fixture.captureId, QueryPageRequest(10, 0)),
            ).items.single().id
            toolTurn(
                call(
                    BoundedWorkflowToolAdapter.QUERY_DRAFT_CANDIDATES,
                    JSONObject().put(
                        "query",
                        JSONObject()
                            .put("reviewStatus", "PENDING")
                            .put("onlyPossibleDuplicates", false)
                            .put("bounds", JSONObject().put("limit", 10).put("offset", 0)),
                    ),
                ),
            )
        }
        script += {
            toolTurn(
                call(
                    BoundedWorkflowToolAdapter.PROPOSE_LINK,
                    JSONObject().put("eventId", observedEventId).put("draftId", fixture.draftId),
                ),
            )
        }
        script += {
            toolTurn(
                call(
                    BoundedWorkflowToolAdapter.ATTACH_LINK,
                    JSONObject()
                        .put("eventId", observedEventId)
                        .put("draftId", fixture.draftId)
                        .put("expectedRevision", 0)
                        .put("userOverride", JSONObject.NULL),
                ),
            )
        }
        script += { complete("The synthetic event is attached to the synthetic draft.") }
        // Measure the initial prompt in an isolated throwaway database, then set the bound
        // just above it so the first turns fit but the accumulated tool results force a
        // compaction. Isolation matters: a second same-text event in the real database
        // would make the link evidence AMBIGUOUS and the attach honestly denied.
        // Every mutation must still verify against freshly re-read state, and the link
        // must still attach exactly once.
        val initialPrompt = measureInitialPrompt(
            intents = setOf(
                WorkflowIntent.SEARCH_DRAFTS,
                WorkflowIntent.INTERPRET_EVENTS,
                WorkflowIntent.LINK_EVENT_TO_DRAFT,
            ),
            userRequest = "Interpret the synthetic note, find the pending draft, and attach it.",
        )
        val adapter = ScriptedAdapter(script, usage = null)
        val outcome = run(
            fixture,
            adapter,
            WorkflowRunRequest(
                intents = setOf(
                    WorkflowIntent.SEARCH_DRAFTS,
                    WorkflowIntent.INTERPRET_EVENTS,
                    WorkflowIntent.LINK_EVENT_TO_DRAFT,
                ),
                userRequest = "Interpret the synthetic note, find the pending draft, and attach it.",
                limits = WorkflowRunLimits(maxContextCharacters = initialPrompt + 400),
            ),
        )
        assertTrue("compaction must preserve the verified completion, got $outcome", outcome is WorkflowRunOutcome.Completed)
        outcome as WorkflowRunOutcome.Completed
        assertTrue(outcome.compaction != null)
        assertTrue(outcome.compaction!!.droppedToolResults > 0)
        val link = outcome.verifiedEffects.filterIsInstance<WorkflowVerifiedEffect.LinkState>().single()
        assertEquals(ContextLinkState.ACCEPTED, link.state)
        val stored = queries.linkOperations(
            LinkOperationQuery(eventId = observedEventId, draftId = fixture.draftId, bounds = QueryPageRequest(1, 0)),
        ).items.single()
        assertEquals(ContextLinkState.ACCEPTED, stored.state)
        assertTrue("no raw amount may leave the device", adapter.egress().none { it.contains("25,000") })
    }

    @Test
    fun `compaction with nothing older to drop stops honestly`() {
        val decision = AgentContextManager.compactForBudget(
            contextCharacters = 9_000,
            maxContextCharacters = 4_096,
            priorPayloads = 0,
            carriedDispatches = 0,
            completedTurns = 0,
        )
        assertNull("no older payload means no compaction can help", decision)
    }

    @Test
    fun `bounded history keeps the most recent dispatch entries only`() {
        val entries = (1..40).map {
            AgentContextManager.DispatchSummaryEntry("tool_$it", "applied", "eventId=$it")
        }
        val rendered = AgentContextManager.boundedDispatchSummary(entries)
        assertTrue(rendered.contains("tool_40"))
        assertTrue(rendered.contains("tool_9"))
        assertFalse(rendered.contains("tool_1\""))
        assertFalse(rendered.contains("tool_8\""))
    }

    // ---- Failure fallback ------------------------------------------------------------------

    @Test
    fun `a provider failure keeps usage recorded and cache behavior honest`() = runBlocking {
        val fixture = prepare()
        val adapter = ScriptedAdapter(
            scripted() + {
                ScriptedTurn(
                    calls = listOf(
                        ScriptedCall(
                            BoundedWorkflowToolAdapter.QUERY_DRAFT_CANDIDATES,
                            draftQueryArgs().toString(),
                            ToolCallDisposition.TRUNCATED_TURN,
                        ),
                    ),
                    finish = HostedTurnFinish.Incomplete("max_output_tokens"),
                )
            },
            usage = HostedUsage(inputTokens = 1_000, outputTokens = 5, cachedInputTokens = null),
        )
        val outcome = run(fixture, adapter, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
            userRequest = "Find the pending drafts.",
        ))
        assertTrue(outcome is WorkflowRunOutcome.Incomplete)
        outcome as WorkflowRunOutcome.Incomplete
        assertEquals(WorkflowRunStopReason.TRUNCATED_TURN, outcome.reason)
        assertEquals(1, outcome.usage.size)
        assertEquals(1_000, outcome.usage.single().inputTokens)
        assertEquals(AgentCacheSupport.NO_CACHE_REPORTED, outcome.cacheSupport)
        assertEquals(0, outcome.toolCalls)
    }

    /**
     * Measures the initial prompt size for a request shape in an isolated throwaway
     * database, so link-evidence uniqueness in the real fixture is untouched.
     */
    private fun measureInitialPrompt(intents: Set<WorkflowIntent>, userRequest: String): Int {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val probeDb = Room.inMemoryDatabaseBuilder(appContext, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val probeCommands = WorkflowCommandService(
                database = probeDb,
                clock = SystemLedgerClock,
                timezoneId = { "Asia/Ho_Chi_Minh" },
                engine = LayaClassificationEngine(LayaInferenceLifecycle(File("build/s811-test-missing-model"))),
            )
            val probeQueries = WorkflowQueryService(probeDb)
            val captureOutcome = runBlocking { probeCommands.saveCapture(
                WorkflowCallerScope.User,
                SaveCaptureRequest("s811-probe-capture", SOURCE),
            ) } as WorkflowCommandOutcome.Applied
            val captureId = (captureOutcome.change as WorkflowChange.CaptureSaved).captureId
            val scope = WorkflowCallerScope.AgentSession("s811-probe-session", setOf(captureId), emptySet())
            val probeBoundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId))
            val probeAdapter = ScriptedAdapter(scripted() + { complete("probe") }, usage = null)
            val host = WorkflowRunHost(
                scope = scope,
                boundary = probeBoundary,
                gateway = MaskedHostedGateway(probeAdapter, probeBoundary),
                declarations = declarations,
                skills = skills,
                commands = probeCommands,
                queries = probeQueries,
                operationKeys = WorkflowOperationKeySource { "s811-probe-${sequence.incrementAndGet()}" },
                clock = WorkflowRunClock { System.currentTimeMillis() },
            )
            runBlocking {
                BoundedWorkflowExecutor(host).start(
                    WorkflowRunRequest(intents = intents, userRequest = userRequest),
                )
            }
            return probeAdapter.prompts.sumOf { it.length }
        } finally {
            WorkflowClaimGuard.reset()
            probeDb.close()
        }
    }

    // ---- Harness -------------------------------------------------------------------------

    private fun promptsOf(
        fixture: Fixture,
        adapter: ScriptedAdapter,
        intents: Set<WorkflowIntent> = setOf(WorkflowIntent.SEARCH_DRAFTS),
    ): List<String> = runBlocking {
        run(fixture, adapter, WorkflowRunRequest(
            intents = intents,
            userRequest = "Find the pending drafts.",
        ))
        adapter.prompts.toList()
    }

    private fun run(
        fixture: Fixture,
        adapter: ScriptedAdapter,
        request: WorkflowRunRequest,
        protocol: HostedProtocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
    ): WorkflowRunOutcome = runBlocking {
        executor(fixture, adapter, protocol).start(request)
    }

    private fun executor(
        fixture: Fixture,
        adapter: ScriptedAdapter,
        protocol: HostedProtocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
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
            operationKeys = WorkflowOperationKeySource { "s811-key-${sequence.incrementAndGet()}" },
            clock = WorkflowRunClock { System.currentTimeMillis() },
        )
        check(protocol == adapter.protocol) { "scripted protocol must match the requested one" }
        return BoundedWorkflowExecutor(host)
    }

    private suspend fun prepare(
        source: String = SOURCE,
        draftMemo: String = "Coffee",
    ): Fixture {
        val captureOutcome = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest("s811-capture-${sequence.incrementAndGet()}", source),
        )
        check(captureOutcome is WorkflowCommandOutcome.Applied) { "capture refused: $captureOutcome" }
        val captureId = (captureOutcome.change as WorkflowChange.CaptureSaved).captureId
        val draftId = database.pendingBankDraftDao().store(
            PendingBankDraftEntity(
                deliveryId = "s811-draft-${sequence.incrementAndGet()}",
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
            "s811-session-${sequence.incrementAndGet()}",
            setOf(captureId),
            setOf(draftId),
        )
        val boundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId))
        val maskedSource = boundary.maskOutbound(source, MaskedPath.CONTEXT)
        return Fixture(captureId, draftId, scope, boundary, maskedSource)
    }

    private fun updateArgs(fixture: Fixture): JSONObject {
        val masked = fixture.maskedSource
        val amount = token(masked, "AMT")
        val date = token(masked, "DATE")
        val time = token(masked, "TIME")
        return JSONObject()
            .put("captureId", fixture.captureId)
            .put("expectedCaptureRevision", 0)
            .put("expectedEventRevision", JSONObject.NULL)
            .put("extractionVersion", "s8.11-test")
            .put(
                "interpretation",
                JSONObject()
                    .put("spanStart", 0)
                    .put("spanEnd", masked.length)
                    .put("normalizedText", masked)
                    .put("maskedSpanText", masked)
                    .put("amountVnd", amount)
                    .put("merchant", JSONObject.NULL)
                    .put("unresolvedReason", JSONObject.NULL)
                    .put(
                        "timeAnchor",
                        JSONObject()
                            .put("date", date)
                            .put("time", time)
                            .put("timezoneId", JSONObject.NULL)
                            .put("intervalEndDate", JSONObject.NULL)
                            .put("intervalEndTime", JSONObject.NULL),
                    ),
            )
    }

    private fun draftQueryArgs(): JSONObject = JSONObject().put(
        "query",
        JSONObject()
            .put("reviewStatus", "PENDING")
            .put("onlyPossibleDuplicates", false)
            .put("bounds", JSONObject().put("limit", 10).put("offset", 0)),
    )

    private fun token(masked: String, prefix: String): String =
        Regex("⟦$prefix-[^⟧]+⟧").find(masked)?.value ?: error("expected a $prefix token in the masked source")

    private fun scripted(): MutableList<suspend () -> ScriptedTurn> = mutableListOf()
    private fun toolTurn(vararg calls: ScriptedCall) = ScriptedTurn(calls = calls.toList(), finish = HostedTurnFinish.ToolCalls)
    private fun complete(text: String) = ScriptedTurn(text = text, finish = HostedTurnFinish.Complete)
    private fun call(name: String, arguments: JSONObject) = ScriptedCall(name, arguments.toString())

    private data class Fixture(
        val captureId: Long,
        val draftId: Long,
        val scope: WorkflowCallerScope.AgentSession,
        val boundary: MaskedPromptBoundary,
        val maskedSource: String,
    )

    private class ScriptedCall(
        val name: String,
        val argumentsJson: String?,
        val disposition: ToolCallDisposition = ToolCallDisposition.READY,
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

    /**
     * A scripted protocol adapter that reports caller-supplied usage exactly like a real
     * adapter parses it from the wire: [usage] applies to every turn, [usageSequence]
     * applies per turn in order (extra turns repeat the last entry).
     */
    private class ScriptedAdapter(
        private val turns: List<suspend () -> ScriptedTurn>,
        private val usage: HostedUsage? = null,
        private val usageSequence: List<HostedUsage?> = emptyList(),
        override val protocol: HostedProtocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
        private val identity: HostedProviderIdentity? = HostedProviderIdentity(
            protocol = protocol,
            endpointHost = "scripted.local",
            model = MODEL,
            budget = io.github.triet4p.aegisf.provider.HostedGenerationBudget(maxOutputTokens = 8_192),
        ),
    ) : HostedGenerationAdapter {
        private var next = 0
        val prompts = mutableListOf<String>()
        val toolResultPayloads = mutableListOf<List<String>>()

        override fun providerIdentity(): HostedProviderIdentity? = identity

        override fun begin(systemInstruction: String, userInstruction: String): HostedConversation {
            prompts += systemInstruction
            prompts += userInstruction
            return ScriptedConversation(protocol, emptyList())
        }

        override suspend fun generate(
            conversation: HostedConversation,
            tools: List<HostedToolDeclaration>,
        ): HostedGenerationResult {
            val producer = turns.getOrNull(next) ?: error("the scripted provider has no turn ${next + 1}")
            val turnUsage = usageSequence.getOrNull(next) ?: usage
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
            return HostedGenerationResult.Turn(
                HostedTurn(
                    protocol = protocol,
                    requestedModel = MODEL,
                    reportedModel = MODEL,
                    assistantText = turn.text,
                    toolCalls = calls,
                    finish = turn.finish,
                    usage = turnUsage,
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
        const val MODEL = "s811-scripted-model"
        const val SOURCE =
            "Coffee 25,000 VND on 2026-10-22 at 09:15. Note: synthetic fixture only."
    }
}
