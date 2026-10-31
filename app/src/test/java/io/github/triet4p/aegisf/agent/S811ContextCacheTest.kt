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
        declarations = AgentToolDeclarations.load(context.assets::open)
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
        assertTrue(firstPrompts.first().contains("context_block_version: s8-agent-context/v6"))
        assertTrue(firstPrompts.first().contains("token_syntax_rule"))
        assertTrue(firstPrompts.first().contains("token_inventory_rule"))
        assertTrue(firstPrompts.first().contains("mutable_state_rule"))
        assertTrue(firstPrompts.first().contains("skill_id: draft-search"))
        assertFalse(firstPrompts.first().contains("skill_id: local-review"))
        assertTrue(
            "the initial prompt must stay a small fraction of the 160000-character turn budget",
            firstPrompts.first().length < 40_000,
        )

        // Different intents select different skills: the prefix is stable per workflow, not global.
        val laya = prepare()
        val layaAdapter = ScriptedAdapter(scripted() + { complete("done") }, usage = null)
        val layaPrompts = promptsOf(
            laya,
            layaAdapter,
            intents = setOf(WorkflowIntent.PREPARE_LAYA_REVIEW),
        )
        assertFalse(layaPrompts.first() == firstPrompts.first())
        assertTrue(layaPrompts.first().contains("skill_id: local-review"))
    }

    @Test
    fun `token syntax rule stays inside the measured context budget`() {
        val stable = AgentContextManager.stableRuntimeBlock()
        assertTrue(stable.contains("context_block_version: s8-agent-context/v6"))
        val lines = stable.lines()
        val start = lines.indexOfFirst { it.startsWith("token_syntax_rule") }
        val end = lines.indexOfFirst { it.startsWith("reference_rule") }
        assertTrue("the token-syntax invariant must be present", start >= 0 && end > start)
        assertTrue(
            "the inventory rule must state that the current list is authoritative",
            lines.subList(start, end).any { it.startsWith("token_inventory_rule") },
        )
        val ruleCharacters = lines.subList(start, end).sumOf(String::length)
        assertTrue("the token rule must stay a short invariant, was $ruleCharacters", ruleCharacters in 1..600)
        assertTrue("the stable runtime block must stay a small fraction of the turn budget, was ${stable.length}",
            stable.length < 6_000)
        // The measured budget behavior is unchanged: compaction only above the limit and only when
        // an older payload exists to drop.
        assertNull(AgentContextManager.compactForBudget(159_999, 160_000, 1, 1, 1))
        assertTrue(AgentContextManager.compactForBudget(160_001, 160_000, 1, 1, 1) != null)
        assertNull(AgentContextManager.compactForBudget(160_001, 160_000, 0, 0, 1))
    }

    // ---- Authoritative issued-token inventory ---------------------------------------------

    @Test
    fun `the issued token inventory is versioned complete and value free`() {
        val vault = SensitiveVault("s8.11-inventory-block")
        assertTrue(AgentContextManager.tokenInventoryBlock(vault.issuedTokens()).contains("0 token(s)"))
        val amount = vault.maskText("25,000 VND").substringBefore(' ')
        val date = vault.maskText("2026-10-22").substringBefore(' ')
        val block = AgentContextManager.tokenInventoryBlock(vault.issuedTokens())
        assertTrue(block.contains(AgentContextManager.TOKEN_INVENTORY_VERSION))
        assertTrue(block.contains("$amount kind=AMOUNT"))
        assertTrue(block.contains("$date kind=DATE"))
        assertTrue(block.contains("2 token(s)"))
        // No raw value, canonical form or rehydration hint is ever rendered.
        assertFalse(block.contains("25,000"))
        assertFalse(block.contains("25000"))
        assertFalse(block.contains("2026-10-22"))
        assertEquals(2, vault.issuedTokens().size)
        // Repeating an already mapped value does not extend the list.
        vault.maskText("25,000 VND")
        assertEquals(2, vault.issuedTokens().size)
    }

    @Test
    fun `every turn carries the complete current issued token inventory`() = runBlocking {
        val fixture = prepare()
        val script = scripted()
        script += { toolTurn(call("list_drafts", draftQueryArgs())) }
        script += { complete("Found the synthetic drafts.") }
        val adapter = ScriptedAdapter(script, usage = null)
        val outcome = run(fixture, adapter, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
            userRequest = "Find the pending drafts.",
        ))
        assertTrue("expected a verified completion, got $outcome", outcome is WorkflowRunOutcome.Completed)
        val initial = adapter.userInstructions().single()
        val initialTokens = tokenInventoryOf(initial)
        assertTrue("the initial turn must list the run inventory", initialTokens.isNotEmpty())
        val continuation = adapter.inventoryNotices.single()
        val continuationTokens = tokenInventoryOf(continuation)
        assertTrue(
            "the continuation keeps every token issued for the initial prompt",
            continuationTokens.containsAll(initialTokens),
        )
        assertEquals(
            "each turn lists exactly the run's issued tokens, in issuance order",
            fixture.boundary.vault.issuedTokens().map { it.token },
            continuationTokens,
        )
        assertFalse(initial.contains("25,000"))
        assertFalse(continuation.contains("25,000"))
    }

    @Test
    fun `a new run and provider switch never inherit another run's inventory`() = runBlocking {
        val first = prepare()
        val firstAdapter = ScriptedAdapter(scripted() + { complete("first done") }, usage = null)
        run(first, firstAdapter, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
            userRequest = "Find the pending drafts.",
        ))
        val firstTokens = tokenInventoryOf(firstAdapter.userInstructions().single())
        assertTrue(firstTokens.isNotEmpty())

        val second = prepare()
        val secondAdapter = ScriptedAdapter(
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
        run(
            second,
            secondAdapter,
            WorkflowRunRequest(
                intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
                userRequest = "Find the pending drafts.",
            ),
            protocol = HostedProtocol.OPENAI_RESPONSES,
        )
        val secondPrompt = secondAdapter.userInstructions().single()
        val secondTokens = tokenInventoryOf(secondPrompt)
        assertTrue(secondTokens.isNotEmpty())
        assertTrue("another run's tokens must never be listed", secondTokens.none { it in firstTokens })
        assertEquals(
            "the switched run lists exactly its own issued tokens",
            second.boundary.vault.issuedTokens().map { it.token },
            secondTokens,
        )
    }

    // ---- Actual usage, never estimated -------------------------------------------------

    @Test
    fun `per-turn usage is recorded exactly and classified as cached on a hit`() = runBlocking {
        val fixture = prepare()
        val script = scripted()
        script += { toolTurn(call("list_drafts", draftQueryArgs())) }
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
        script += { toolTurn(call("list_drafts", draftQueryArgs())) }
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
        script += { toolTurn(call("list_drafts", draftQueryArgs())) }
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
        assertEquals(AgentContextManager.CONTEXT_BLOCK_VERSION, base.promptContract)
        assertFalse(
            "a prompt-contract change must invalidate the identity",
            base.fingerprint() == base.copy(promptContract = "s8-agent-context/other").fingerprint(),
        )
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
        script += { toolTurn(call("list_drafts", draftQueryArgs())) }
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
    fun `compaction keeps verified effects while re-reading live state`() = runBlocking {
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
        script += { complete("Stored.") }
        adapter = ScriptedAdapter(script, usage = null)
        val outcome = run(
            fixture,
            adapter,
            WorkflowRunRequest(
                intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
                userRequest = "Interpret the synthetic note.",
                limits = WorkflowRunLimits(maxContextCharacters = runBoundForCompaction(fixture)),
            ),
        )
        assertTrue("compaction must preserve the verified completion, got $outcome", outcome is WorkflowRunOutcome.Completed)
        outcome as WorkflowRunOutcome.Completed
        assertTrue("compaction must fire under the sized bound, got " + outcome.compaction, outcome.compaction != null)
        assertTrue(outcome.compaction!!.droppedToolResults > 0)
        assertTrue(outcome.verifiedEffects.any { it is WorkflowVerifiedEffect.EventReading })
        assertEquals(25_000L, database.noteEventDao().findForCapture(fixture.captureId).single().amountVnd)
        assertTrue("no raw amount may leave the device", adapter.egress().none { it.contains("25,000") })
        // The post-compaction turns still carry the complete issued-token inventory.
        val notices = adapter.inventoryNotices
        assertTrue("compaction must never drop the inventory", notices.isNotEmpty())
        assertTrue(notices.all { it.contains(AgentContextManager.TOKEN_INVENTORY_VERSION) })
        assertEquals(
            "the last continuation lists exactly the run's issued tokens",
            fixture.boundary.vault.issuedTokens().map { it.token },
            tokenInventoryOf(notices.last()),
        )
        assertTrue(
            "the initial turn already carried an inventory",
            tokenInventoryOf(adapter.userInstructions().first()).isNotEmpty(),
        )
    }

    /**
     * Behavioral bound for the compaction test: the measured stable prompt plus one fresh
     * turn of results and the complete inventory must fit, while the full three-turn
     * cumulative chain must force the drive loop through real compaction. A probe turn
     * against this fixture measures exactly one turn of fresh payloads plus the run's
     * current inventory; the bound sits 1 char below the cumulative total so compaction
     * is exercised deterministically, never by magic constant.
     */
    private fun runBoundForCompaction(fixture: Fixture): Int {
        val initialPrompt = measureInitialPrompt(
            fixture,
            intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
            userRequest = "Interpret the synthetic note.",
        )
        val probeScript = scripted()
        probeScript += { toolTurn(call("select_source", draftQueryArgs())) }
        probeScript += { complete("probe") }
        val probe = ScriptedAdapter(probeScript, usage = null)
        runBlocking {
            executor(fixture, probe).start(WorkflowRunRequest(
                intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
                userRequest = "Interpret the synthetic note.",
            ))
        }
        val oneFreshPayload = probe.toolResultPayloads.flatten().sumOf { it.length }
        val probeInventory = AgentContextManager.tokenInventoryBlock(fixture.boundary.vault.issuedTokens()).length
        check(oneFreshPayload > 0 && probeInventory > 0) { "the fixture must mint masked payloads and inventory" }
        return initialPrompt + 2 * oneFreshPayload + 2 * probeInventory - 1
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
    fun `inventory-driven overflow drops stale payloads and never resets the counter`() = runBlocking {
        val fixture = prepare()
        lateinit var adapter: ScriptedAdapter
        val script = scripted()
        script += { toolTurn(call("list_drafts", draftQueryArgs())) }
        script += { toolTurn(call("list_drafts", draftQueryArgs())) }
        script += { complete("Found the synthetic drafts.") }
        adapter = ScriptedAdapter(script, usage = null)
        val initialPrompt = measureInitialPrompt(
            fixture,
            intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
            userRequest = "Find the pending drafts.",
        )
        // Drive the second tool-result continuation over budget while the fresh chain (stable
        // prompt plus current results plus the complete inventory) still fits. The probe measures
        // one masked payload and the current inventory against the same fixture.
        val probeScript = scripted()
        probeScript += { toolTurn(call("list_drafts", draftQueryArgs())) }
        probeScript += { complete("probe") }
        val probe = ScriptedAdapter(probeScript, usage = null)
        run(fixture, probe, WorkflowRunRequest(
            intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
            userRequest = "Find the pending drafts.",
        ))
        val onePayload = probe.toolResultPayloads.flatten().sumOf { it.length }
        val inventoryNow = AgentContextManager.tokenInventoryBlock(fixture.boundary.vault.issuedTokens()).length
        assertTrue("the fixture must mint masked payloads and inventory", onePayload > 0 && inventoryNow > 0)
        val runningTotal = initialPrompt + 2 * onePayload + inventoryNow
        val freshChain = initialPrompt + onePayload + inventoryNow
        assertTrue("the probe must overflow the small bound", runningTotal > freshChain)
        val bound = runningTotal - 1
        assertTrue("the fresh chain must still fit", freshChain <= bound)
        val outcome = run(
            fixture,
            adapter,
            WorkflowRunRequest(
                intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
                userRequest = "Find the pending drafts.",
                limits = WorkflowRunLimits(maxContextCharacters = bound),
            ),
        )
        assertTrue("expected a verified completion, got $outcome", outcome is WorkflowRunOutcome.Completed)
        outcome as WorkflowRunOutcome.Completed
        assertTrue("overflow must record a real compaction", outcome.compaction != null)
        assertTrue(outcome.compaction!!.droppedToolResults > 0)
        val lastInventory = tokenInventoryOf(adapter.inventoryNotices.last())
        assertEquals(
            "the continuation lists exactly the run's issued tokens",
            fixture.boundary.vault.issuedTokens().map { it.token },
            lastInventory,
        )
        assertEquals(lastInventory.distinct().size, lastInventory.size)
    }

    @Test
    fun `an oversized complete inventory stops before any provider request`() = runBlocking {
        val fixture = prepare()
        val adapter = ScriptedAdapter(scripted() + { complete("unreachable") }, usage = null)
        // Mint enough tokens that the initial prompt plus the complete inventory cannot fit.
        repeat(120) { index -> fixture.boundary.maskOutbound("Dinner ${(10_000 + index * 1_000)} VND.", MaskedPath.CONTEXT) }
        val inventoryLength =
            AgentContextManager.tokenInventoryBlock(fixture.boundary.vault.issuedTokens()).length
        assertTrue("inventory must exceed the small bound, was $inventoryLength", inventoryLength > 4_096)
        val outcome = run(
            fixture,
            adapter,
            WorkflowRunRequest(
                intents = setOf(WorkflowIntent.SEARCH_DRAFTS),
                userRequest = "Find the pending drafts.",
                limits = WorkflowRunLimits(maxContextCharacters = 4_096),
            ),
        )
        outcome as WorkflowRunOutcome.Incomplete
        assertEquals(WorkflowRunStopReason.CONTEXT_BUDGET, outcome.reason)
        assertEquals("no provider request may exist when the inventory cannot fit", 0, adapter.nextCountForTest())
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
                            "list_drafts",
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
     * Measures the real initial prompt for the same fixture, scope and intents by running a
     * completion-only probe against the same services. The probe dispatches no tool and
     * writes nothing; it records exactly the system and initial user instruction that the
     * measured run will send, so the bound is neither guessed nor shape-mismatched.
     */
    private fun measureInitialPrompt(
        fixture: Fixture,
        intents: Set<WorkflowIntent>,
        userRequest: String,
    ): Int {
        val probeAdapter = ScriptedAdapter(scripted() + { complete("probe") }, usage = null)
        runBlocking {
            executor(fixture, probeAdapter).start(
                WorkflowRunRequest(intents = intents, userRequest = userRequest),
            )
        }
        return probeAdapter.prompts.sumOf { it.length }
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

    private fun draftQueryArgs(): JSONObject = JSONObject()
        .put("review_status", "PENDING")
        .put("duplicates_only", false)
        .put("cursor", JSONObject.NULL)

    private fun initialObservations(adapter: ScriptedAdapter): JSONArray {
        val prompt = adapter.prompts.last { it.contains("Trusted host observations") }
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

    private fun lastEnvelope(adapter: ScriptedAdapter): JSONObject =
        JSONObject(adapter.toolResultPayloads.last().single())

    /** The tokens listed by the host inventory block of one prompt, in listed order. */
    private fun tokenInventoryOf(text: String): List<String> {
        val start = text.indexOf("issued_mask_token_inventory")
        if (start < 0) return emptyList()
        return Regex("⟦[^\\s⟦⟧]+⟧")
            .findAll(text.substring(start))
            .map { it.value }
            .toList()
    }

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
        fun nextCountForTest(): Int = next
        val prompts = mutableListOf<String>()
        val toolResultPayloads = mutableListOf<List<String>>()
        /** Issued-token inventories appended to tool-result continuations, in order. */
        val inventoryNotices = mutableListOf<String>()

        /** Every user instruction this adapter was asked to start a turn with, in order. */
        fun userInstructions(): List<String> =
            prompts.filterIndexed { index, _ -> index % 2 == 1 }

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
            hostNotice: String?,
        ): HostedConversation {
            toolResultPayloads += results.map(HostedToolResult::outputJson)
            if (hostNotice != null) inventoryNotices += hostNotice
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
