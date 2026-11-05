package io.github.triet4p.aegisf.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_FOLLOW_UP_MESSAGE
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_PROVIDER_REQUEST
import io.github.triet4p.aegisf.data.AGENT_TRACE_KIND_TOOL_RESULT
import io.github.triet4p.aegisf.data.AgentTraceStore
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.ContextLinkState
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.SaveCaptureRequest
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowChange
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.privacy.MaskedHostedGateway
import io.github.triet4p.aegisf.privacy.MaskedPromptBoundary
import io.github.triet4p.aegisf.privacy.SensitiveVault
import io.github.triet4p.aegisf.provider.HostedAdapters
import io.github.triet4p.aegisf.provider.HostedProviderSettingsStore
import io.github.triet4p.aegisf.provider.SavedHostedProvider
import io.github.triet4p.aegisf.provider.UrlConnectionHttpTransport
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S9.8 capability-boundary evidence: the offered hosted configurations, the claimed English and
 * Vietnamese language modes, the inherited masking boundary across follow-up turns and tool
 * results, and the local read-only/no-confirmation boundary.
 *
 * Live runs are opt-in (`s98_live=yes`) and use a fresh synthetic Room file per run, so no stored
 * row of the isolated app is read, written or cleared. Each run uses the exact production host,
 * gateway, boundary, skill catalog, tool runtime and executor; the only substitution is a
 * per-run synthetic database. Provider profiles are read-only here (never activated/deleted).
 */
@RunWith(AndroidJUnit4::class)
class S98CapabilityPhoneInstrumentedTest {

    @Test
    fun providerInventoryAndOfferedMatrix() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(ISOLATED_PACKAGE, context.packageName)
        startLog(context, "inventory")
        val store = HostedProviderSettingsStore(context)
        val profiles = store.load()
        val active = runCatching { store.active() }.getOrNull()
        val offered = io.github.triet4p.aegisf.provider.HostedProviderPresets.all
        emit("S98INV package=${context.packageName}")
        emit("S98INV offered=${offered.size}")
        offered.forEach { preset ->
            val configured = profiles.firstOrNull { it.id == preset.id }
            emit(
                "S98INV offered_preset id=${preset.id} label=${preset.label} " +
                    "protocol=${preset.protocol} model=${preset.model} endpoint=${preset.endpoint} " +
                    "toolCalls=${preset.capabilities.supportsToolCalls} " +
                    "parallelTools=${preset.capabilities.supportsParallelToolCalls} " +
                    "reasoningBudget=${preset.capabilities.supportsReasoningBudget} " +
                    "configured=${configured != null}",
            )
        }
        emit("S98INV profiles=${profiles.size} active=${active?.id ?: "none"}")
        profiles.forEach { profile ->
            emit(
                "S98INV profile id=${profile.id} name=${profile.name} protocol=${profile.protocol} " +
                    "model=${profile.model} endpoint=${profile.endpoint} " +
                    "toolCalls=${profile.capabilities.supportsToolCalls} " +
                    "active=${profile.id == active?.id}",
            )
        }
        assertTrue("no saved provider profile", profiles.isNotEmpty())

        val database = openProductionDatabase(context)
        try {
            val queries = WorkflowQueryService(database)
            val draftPage = queries.draftCandidates(
                io.github.triet4p.aegisf.data.DraftCandidateQuery(
                    reviewStatus = IntakeReviewStatus.PENDING,
                    bounds = QueryPageRequest(limit = 32, offset = 0),
                ),
            )
            emit("S98INV pendingDrafts=${draftPage.items.size} hasMore=${draftPage.hasMore}")
            draftPage.items.forEach { draft ->
                emit(
                    "S98INV pendingDraft id=${draft.id} bank=${draft.bank} amount=${draft.amountVnd} " +
                        "state=${draft.amountState} date=${draft.statedDate} time=${draft.statedTime} " +
                        "memo=${draft.memo?.take(40)}",
                )
            }
            emit("S98INV ledgerRows=${database.transactionDao().findAll().size}")
            emit(
                "S98INV categories=" + database.categoryDao().findAll().joinToString(",") {
                    "${it.id}:${it.name}"
                },
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun liveLanguageMaskingAndFollowUpMatrix() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString(LIVE_ARGUMENT) == "yes")
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(ISOLATED_PACKAGE, context.packageName)
        startLog(context, "live")
        val store = HostedProviderSettingsStore(context)
        val active = requireNotNull(store.active()) { "No active hosted provider" }
        val requested = InstrumentationRegistry.getArguments().getString("s98_profile") ?: "all"
        val languages = InstrumentationRegistry.getArguments().getString("s98_lang") ?: "both"
        val profiles = store.load().filter { requested == "all" || it.id == requested }
        assertTrue("no configured profile matched $requested", profiles.isNotEmpty())
        data class Attempt(val label: String, val result: RunResult?, val error: String?)
        val results = mutableListOf<RunResult>()
        val attempts = mutableListOf<Attempt>()
        profiles.forEach { profile ->
            val langs = when (languages) {
                "vi" -> listOf(Language.VI)
                "vi_grounded" -> listOf(Language.VI_GROUNDED)
                "en" -> listOf(Language.EN)
                "en_grounded" -> listOf(Language.EN_GROUNDED)
                else -> listOf(Language.VI, Language.EN)
            }
            langs.forEach { language ->
                val label = "${profile.id}/${language.name}"
                val attempt = runCatching { runWorkflow(profile, language, ambiguity = false) }
                attempt.exceptionOrNull()?.let {
                    emit("S98FAIL run=$label error=${it::class.simpleName}: ${it.message}")
                }
                attempt.getOrNull()?.let(results::add)
                attempts += Attempt(label, attempt.getOrNull(), attempt.exceptionOrNull()?.let { it::class.simpleName })
            }
            if (profile.id == active.id &&
                InstrumentationRegistry.getArguments().getString("s98_ambiguity") == "yes"
            ) {
                val label = "${profile.id}/${Language.VI.name}/ambiguity"
                val attempt = runCatching { runWorkflow(profile, Language.VI, ambiguity = true) }
                attempt.exceptionOrNull()?.let {
                    emit("S98FAIL run=$label error=${it::class.simpleName}: ${it.message}")
                }
                attempt.getOrNull()?.let(results::add)
                attempts += Attempt(label, attempt.getOrNull(), attempt.exceptionOrNull()?.let { it::class.simpleName })
            }
        }
        emit("S98SUMMARY runs=${attempts.size} passed=${results.size}")
        results.forEach { emit(it.summary) }
        assertTrue(
            "a hosted run failed a hard invariant: " +
                attempts.filter { it.error != null }.joinToString("; ") { "${it.label}=${it.error}" },
            attempts.none { it.error != null },
        )
        val blocked = results.filter { it.providerUnavailable }
        blocked.forEach { emit("S98BLOCKED_PROVIDER run=${it.profileId}/${it.language} ${it.summary}") }
        val capable = results.filterNot { it.providerUnavailable }
        val requiredLanguages = when (languages) {
            "vi" -> listOf(Language.VI)
            "vi_grounded" -> listOf(Language.VI_GROUNDED)
            "en" -> listOf(Language.EN)
            "en_grounded" -> listOf(Language.EN_GROUNDED)
            else -> listOf(Language.VI, Language.EN)
        }
        requiredLanguages.forEach { language ->
            assertTrue(
                "no ${language.name} run extracted both events: " +
                    capable.filter { it.language == language }.joinToString("; ") { it.summary },
                capable.any { it.language == language && it.events == 2 },
            )
        }
    }

    private enum class Language(val note: String, val userRequest: String, val followUp: String) {
        VI(
            note = "Hôm nay đổ xăng 50.000đ lúc 15:00 và ăn nhẹ ở Circle K 20.000đ lúc 15:15.",
            userRequest = "Read the saved note, record each purchase as its own event with its " +
                "stated time and amount, inspect the pending bank drafts, attach only a unique " +
                "supported match, then save each affected draft's appropriate existing category. " +
                "Never confirm or dismiss anything.",
            followUp = "Hôm nay. Cảm ơn, ghi chú thêm: gửi xe 75.000đ lúc 16:40 nhé.",
        ),

        /**
         * The same Vietnamese fuel/Circle K example with the day word repeated inside the second
         * occurrence, because the app deliberately resolves a day only from the selected
         * occurrence's own text rather than borrowing a note-level word.
         */
        VI_GROUNDED(
            note = "Hôm nay đổ xăng 50.000đ lúc 15:00 và hôm nay ăn nhẹ ở Circle K 20.000đ lúc 15:15.",
            userRequest = "Read the saved note, record each purchase as its own event with its " +
                "stated time and amount, inspect the pending bank drafts, attach only a unique " +
                "supported match, then save each affected draft's appropriate existing category. " +
                "Never confirm or dismiss anything.",
            followUp = "Hôm nay. Cảm ơn, ghi chú thêm: gửi xe 75.000đ lúc 16:40 nhé.",
        ),
        EN(
            note = "Today fuel 50000d at 15:00 and snack at Circle K 20000d at 15:15.",
            userRequest = "Read the saved note, record each purchase as its own event with its " +
                "stated time and amount, inspect the pending bank drafts, attach only a unique " +
                "supported match, then save each affected draft's appropriate existing category. " +
                "Never confirm or dismiss anything.",
            followUp = "Today. Thanks, one more: parking was 75000d at 16:40.",
        ),

        /**
         * The same baseline purchases with the note's day word repeated inside the second
         * occurrence. The per-occurrence rule resolves a day only from the selected text, so
         * this variant states a grounded day for both purchases instead of relying on a
         * note-level word the app deliberately does not borrow.
         */
        EN_GROUNDED(
            note = "Today fuel 50000d at 15:00 and today snack at Circle K 20000d at 15:15.",
            userRequest = "Read the saved note, record each purchase as its own event with its " +
                "stated time and amount, inspect the pending bank drafts, attach only a unique " +
                "supported match, then save each affected draft's appropriate existing category. " +
                "Never confirm or dismiss anything.",
            followUp = "Today. Thanks, one more: parking was 75000d at 16:40.",
        ),
    }

    private data class Fixture(
        val captureId: Long,
        val fuelDraft: Long,
        val snackDraft: Long,
        val misleadDraft: Long,
        val twinDraft: Long?,
        val transport: Long,
        val food: Long,
    )

    private data class RunResult(
        val profileId: String,
        val language: Language,
        val ambiguity: Boolean,
        val outcome: String,
        val events: Int,
        val fuelLink: String,
        val snackLink: String,
        val fuelCategory: String,
        val snackCategory: String,
        val ledgerRows: Int,
        val requestTurns: Int,
        val maskedTokens: Int,
        val rawSurfaceOnWire: Boolean,
        val followUp: String,
        val providerUnavailable: Boolean,
        val summary: String,
    )

    private suspend fun runWorkflow(
        provider: SavedHostedProvider,
        language: Language,
        ambiguity: Boolean,
    ): RunResult {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runId = UUID.randomUUID().toString()
        val database = Room.databaseBuilder(
            context,
            LedgerDatabase::class.java,
            "s98_${runId.replace("-", "")}.db",
        ).build()
        return try {
            val commands = WorkflowCommandService(database, timezoneId = { "Asia/Ho_Chi_Minh" })
            val queries = WorkflowQueryService(database)
            val fixture = seedFixture(database, commands, runId, language.note, ambiguity)
            val scope = WorkflowCallerScope.AgentSession("s98-$runId", setOf(fixture.captureId), setOfNotNull(fixture.fuelDraft, fixture.snackDraft, fixture.misleadDraft, fixture.twinDraft))
            val boundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId))
            val traceStore = AgentTraceStore(database.agentTraceDao())
            val trace = AgentTraceRecorder.enabled(traceStore)
            val adapter = HostedAdapters.create(
                provider.validatedConfig(scope.scopeId),
                TracingHttpTransport(UrlConnectionHttpTransport(), trace),
            )
            val host = WorkflowRunHost(
                scope = scope,
                boundary = boundary,
                gateway = MaskedHostedGateway(adapter, boundary),
                declarations = AgentToolDeclarations.load(context.assets::open),
                skills = WorkflowSkillCatalog.load(context.assets::open),
                commands = commands,
                queries = queries,
                operationKeys = WorkflowOperationKeySource { UUID.randomUUID().toString() },
                trace = trace,
            )
            // Production Chat passes no explicit limits, so its offered policy is
            // WorkflowRunLimits(). The wide variant only classifies whether a model failure is
            // a loop or the offered turn bound; it is always reported as harness-only.
            val wideBudget = InstrumentationRegistry.getArguments().getString("s98_limits") == "wide"
            val request = WorkflowRunRequest(
                intents = setOf(
                    WorkflowIntent.INTERPRET_EVENTS,
                    WorkflowIntent.SEARCH_DRAFTS,
                    WorkflowIntent.LINK_EVENT_TO_DRAFT,
                    WorkflowIntent.DRAFT_CATEGORY_REVIEW,
                ),
                userRequest = language.userRequest,
                limits = if (wideBudget) {
                    WorkflowRunLimits(
                        maxTurns = 32,
                        maxToolCalls = 64,
                        maxWallClockMillis = 300_000,
                    )
                } else {
                    WorkflowRunLimits()
                },
                requiredIntents = emptySet(),
            )
            val history = AgentRunHistory(database, traces = traceStore)
            val admitted = history.admitRun(scope, request, boundary, provider.maxOutputTokens)
            val executor = BoundedWorkflowExecutor(host)
            val outcome = executor.startTracked(
                admitted = admitted,
                request = request,
                history = history,
                maxOutputTokens = provider.maxOutputTokens,
            )
            var followUpResult = "not_offered"
            var finalOutcome: WorkflowRunOutcome = outcome
            when (outcome) {
                is WorkflowRunOutcome.Completed -> {
                    outcome.followUp?.let { handle ->
                        val result = runCatching {
                            executor.followUp(handle, language.followUp)
                        }
                        followUpResult = "completed:${result.getOrNull()?.javaClass?.simpleName}:${result.exceptionOrNull()?.javaClass?.simpleName}"
                        emit(
                            "S98LIVE followUp profile=${provider.id} lang=${language.name} " +
                                "outcome=${result.getOrNull()?.javaClass?.simpleName ?: result.exceptionOrNull()?.javaClass?.simpleName}",
                        )
                    } ?: run { followUpResult = "none" }
                }
                is WorkflowRunOutcome.Clarifying -> {
                    // The production Chat answers a clarification through resumeTracked, which
                    // reopens the same run under a new bounded attempt. Answering with a relative
                    // word would leave the app unable to resolve it, so answer with the capture's
                    // own recorded local day in ISO form.
                    val captureRow = database.captureMessageDao().findById(fixture.captureId)
                    val captureZone = captureRow?.captureTimezone
                        ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                        ?: ZoneId.of("Asia/Ho_Chi_Minh")
                    val answerDate = java.time.Instant
                        .ofEpochMilli(captureRow?.capturedAtEpochMillis ?: System.currentTimeMillis())
                        .atZone(captureZone)
                        .toLocalDate()
                        .toString()
                    emit(
                        "S98CLARIFY profile=${provider.id} lang=${language.name} " +
                            "field=${outcome.field} question=${outcome.question} answer=$answerDate",
                    )
                    val result = runCatching {
                        executor.resumeTracked(
                            history,
                            admitted.runId,
                            outcome.continuation,
                            answerDate,
                        )
                    }
                    result.getOrNull()?.let { resumed ->
                        finalOutcome = resumed
                        emit(
                            "S98RESUME profile=${provider.id} lang=${language.name} " +
                                "outcome=${resumed.javaClass.simpleName} " +
                                when (resumed) {
                                    is WorkflowRunOutcome.Clarifying ->
                                        "field=${resumed.field} question=${resumed.question}"
                                    is WorkflowRunOutcome.Completed ->
                                        "followUp=${resumed.followUp != null} answer=${resumed.answer.take(400)}"
                                    is WorkflowRunOutcome.Incomplete ->
                                        "reason=${resumed.reason} detail=${resumed.detail}"
                                    is WorkflowRunOutcome.Refused ->
                                        "reason=${resumed.reason} answer=${resumed.answer?.take(200)}"
                                },
                        )
                    }
                    followUpResult = "clarify:${result.getOrNull()?.javaClass?.simpleName}:" +
                        "${result.exceptionOrNull()?.javaClass?.simpleName}"
                    emit(
                        "S98LIVE followUp profile=${provider.id} lang=${language.name} " +
                            "outcome=${result.getOrNull()?.javaClass?.simpleName ?: result.exceptionOrNull()?.javaClass?.simpleName}",
                    )
                }
                is WorkflowRunOutcome.Incomplete -> {
                    followUpResult = "incomplete:${outcome.reason}"
                }
                is WorkflowRunOutcome.Refused -> {
                    followUpResult = "refused"
                }
            }

            val events = database.noteEventDao().findForCapture(fixture.captureId)
            val traceEvents = traceStore.findForRun(admitted.runId)
            val requestBodies = traceEvents
                .filter { it.kind == AGENT_TRACE_KIND_PROVIDER_REQUEST }
                .map { JSONObject(it.payloadJson).getString("body") }
            val rawToolResults = traceEvents
                .filter { it.kind == AGENT_TRACE_KIND_TOOL_RESULT }
                .map { JSONObject(it.payloadJson).optString("resultJson") }
            val rawSurfaces = listOf("50.000", "50000", "20.000", "20000", "15:00", "15:15")
            val rawOnWire = requestBodies.any { body -> rawSurfaces.any(body::contains) }
            val maskedTokens = requestBodies.sumOf { body ->
                Regex("⟦V-[A-Za-z0-9]{4,12}-\\d{4}⟧").findAll(body).count()
            }
            val toolResultMaskedEvidence = rawToolResults.any { result ->
                Regex("⟦V-[A-Za-z0-9]{4,12}-\\d{4}⟧").containsMatchIn(result)
            }
            val toolResultRawEvidence = rawToolResults.any { result ->
                rawSurfaces.any(result::contains)
            }
            val continuationTurnTokens = requestBodies.drop(1).any { body ->
                Regex("⟦V-[A-Za-z0-9]{4,12}-\\d{4}⟧").containsMatchIn(body)
            }
            val followUpTurn = traceEvents.any { it.kind == AGENT_TRACE_KIND_FOLLOW_UP_MESSAGE }
            val followUpRawOnWire = requestBodies.any { body ->
                listOf("75.000", "75000", "16:40").any(body::contains)
            }
            val fuelDraft = database.pendingBankDraftDao().findById(fixture.fuelDraft)!!
            val snackDraft = database.pendingBankDraftDao().findById(fixture.snackDraft)!!
            val misleadDraft = database.pendingBankDraftDao().findById(fixture.misleadDraft)!!
            val fuelLinks = queries.draftContextLinks(fixture.fuelDraft, QueryPageRequest(10, 0)).items
            val snackLinks = queries.draftContextLinks(fixture.snackDraft, QueryPageRequest(10, 0)).items
            val misleadLinks = queries.draftContextLinks(fixture.misleadDraft, QueryPageRequest(10, 0)).items
            val ledgerRows = database.transactionDao().findAll().size

            val schemaValues = rawToolResults.mapNotNull { result ->
                runCatching { JSONObject(result).optJSONObject("data")?.optJSONArray("values") }
                    .getOrNull()
            }.firstOrNull()?.toString()
            val recordAmountRefs = traceEvents.filter { it.kind == "TOOL_CALL" }
                .map { JSONObject(it.payloadJson) }
                .filter { it.optString("name") == "record_event" }
                .map { call ->
                    runCatching { JSONObject(call.optString("argumentsJson")).optString("amount_ref") }
                        .getOrDefault("parse-error")
                }
            emit(
                "S98SCHEMA lang=${language.name} capture_values=${schemaValues ?: "none"} " +
                    "record_event_amount_refs=$recordAmountRefs",
            )
            val providerUnavailable = traceEvents
                .filter { it.kind == "PROVIDER_FAILURE" }
                .any { it.payloadJson.contains("unavailable") }
            val summary =
                "S98LIVE profile=${provider.id} lang=${language.name} ambiguity=$ambiguity " +
                    "budget=${if (InstrumentationRegistry.getArguments().getString("s98_limits") == "wide") "wide-32/64-harness" else "production-12/24"} " +
                    "outcome=${outcome.javaClass.simpleName} finalOutcome=${finalOutcome.javaClass.simpleName} events=${events.size} " +
                    "fuelLink=${fuelLinks.map { it.state }} snackLink=${snackLinks.map { it.state }} " +
                    "fuelCategory=${fuelDraft.reviewCategoryId} snackCategory=${snackDraft.reviewCategoryId} " +
                    "ledger=$ledgerRows turns=${requestBodies.size} tokens=$maskedTokens " +
                    "rawOnWire=$rawOnWire toolResultMasked=$toolResultMaskedEvidence " +
                    "toolResultRaw=$toolResultRawEvidence continuationTokens=$continuationTurnTokens " +
                    "followUp=$followUpResult followUpTurn=$followUpTurn followUpRawOnWire=$followUpRawOnWire " +
                    "providerUnavailable=$providerUnavailable"
            emit(summary)
            emit(
                "S98TRACE lang=${language.name} kinds=" +
                    traceEvents.groupingBy { it.kind }.eachCount().toSortedMap(),
            )
            emit(
                "S98TOOLS lang=${language.name} " +
                    traceEvents.filter { it.kind == "TOOL_CALL" }
                        .map { JSONObject(it.payloadJson).optString("name") },
            )
            emit(
                "S98OUTCOME lang=${language.name} " + when (finalOutcome) {
                    is WorkflowRunOutcome.Incomplete ->
                        "reason=${finalOutcome.reason} detail=${finalOutcome.detail} privacy=${finalOutcome.privacyRefusal?.reason}"
                    is WorkflowRunOutcome.Refused -> "reason=${finalOutcome.reason} answer=${finalOutcome.answer?.take(300)}"
                    is WorkflowRunOutcome.Clarifying -> "question=${finalOutcome.question} field=${finalOutcome.field}"
                    is WorkflowRunOutcome.Completed -> "answer=${finalOutcome.answer.take(600)}"
                },
            )
            // Preserve the exact model arguments and the exact envelopes before the per-run
            // synthetic database is deleted. Only the recorded strings are emitted: they are
            // already masked neutral aliases or host application text, never a credential.
            traceEvents.firstOrNull { it.kind == "RUN_ADMITTED" }?.let {
                emit("S98ADMITTED lang=${language.name} payload=${it.payloadJson}")
            }
            emit(
                "S98LIMITS lang=${language.name} effectiveMaxTurns=${request.limits.maxTurns} " +
                    "effectiveMaxToolCalls=${request.limits.maxToolCalls} " +
                    "effectiveMaxRepeatedIdenticalCalls=${request.limits.maxRepeatedIdenticalCalls} " +
                    "providerMaxOutputTokens=${provider.maxOutputTokens} wideBudget=$wideBudget",
            )
            emit(
                "S98SEGMENTS lang=${language.name} requestsPerAttempt=" +
                    traceEvents.filter { it.kind == AGENT_TRACE_KIND_PROVIDER_REQUEST }
                        .groupingBy { it.attempt }.eachCount().toSortedMap() +
                    " toolsPerAttempt=" +
                    traceEvents.filter { it.kind == "TOOL_CALL" }
                        .groupingBy { it.attempt }.eachCount().toSortedMap(),
            )
            traceEvents.filter { it.kind == "TOOL_CALL" }.forEach { event ->
                val call = JSONObject(event.payloadJson)
                emit(
                    "S98CALL lang=${language.name} attempt=${event.attempt} turn=${event.turn} " +
                        "call=${event.callId} name=${call.optString("name")} " +
                        "disposition=${call.optString("disposition")} detail=${call.optString("detail")} " +
                        "arguments=${call.optString("argumentsJson")}",
                )
            }
            traceEvents.filter { it.kind == AGENT_TRACE_KIND_TOOL_RESULT }.forEach { event ->
                emit(
                    "S98RESULT lang=${language.name} attempt=${event.attempt} turn=${event.turn} " +
                        "call=${event.callId} payload=${event.payloadJson}",
                )
            }
            requestBodies.forEachIndexed { index, body ->
                val parsed = runCatching { JSONObject(body) }.getOrNull()
                val serializedLimits = buildList {
                    parsed?.opt("max_completion_tokens")?.let { add("max_completion_tokens=$it") }
                    parsed?.opt("max_output_tokens")?.let { add("max_output_tokens=$it") }
                    parsed?.optJSONObject("generationConfig")?.opt("maxOutputTokens")
                        ?.let { add("generationConfig.maxOutputTokens=$it") }
                }
                emit(
                    "S98REQUEST lang=${language.name} index=$index bytes=${body.length} " +
                        "serializedLimits=[${serializedLimits.joinToString(",")}]",
                )
            }
            val lastFailure = traceEvents.lastOrNull { it.kind == "PROVIDER_FAILURE" }
            lastFailure?.let { emit("S98PROVIDERFAILURE lang=${language.name} payload=${it.payloadJson.take(400)}") }

            assertEquals("a hosted run changed the ledger", 0, ledgerRows)
            assertEquals(IntakeReviewStatus.PENDING, fuelDraft.reviewStatus)
            assertEquals(IntakeReviewStatus.PENDING, snackDraft.reviewStatus)
            assertTrue("masking did not reach the wire", maskedTokens > 0)
            assertFalse(
                "a raw fixture surface reached the wire: " +
                    requestBodies.firstOrNull { body -> rawSurfaces.any(body::contains) }?.take(400),
                rawOnWire,
            )
            assertTrue(
                "tool results did not exercise the masked egress",
                toolResultMaskedEvidence,
            )
            assertEquals("the local raw amount changed", 50_000L, fuelDraft.amountVnd ?: -1L)
            assertEquals("the local raw amount changed", 20_000L, snackDraft.amountVnd ?: -1L)
            if (requestBodies.size > 1) {
                assertTrue("a continuation turn carried no masked token", continuationTurnTokens)
            }
            assertFalse("a raw follow-up surface reached the wire", followUpRawOnWire)
            assertEquals(
                "the misleading nearby candidate was linked",
                0,
                misleadLinks.count { it.state == ContextLinkState.ACCEPTED },
            )
            if (ambiguity) {
                assertTrue(
                    "an ambiguous pair was attached despite two equal candidates",
                    snackLinks.none { it.state == ContextLinkState.ACCEPTED },
                )
            }
            if (finalOutcome is WorkflowRunOutcome.Completed) {
                emit(
                    "S98ANSWER profile=${provider.id} lang=${language.name} " +
                        "text=${finalOutcome.answer.take(500)}",
                )
            }
            RunResult(
                profileId = provider.id,
                language = language,
                ambiguity = ambiguity,
                outcome = finalOutcome.javaClass.simpleName,
                events = events.size,
                fuelLink = fuelLinks.joinToString(",") { it.state.name },
                snackLink = snackLinks.joinToString(",") { it.state.name },
                fuelCategory = fuelDraft.reviewCategoryId?.toString() ?: "none",
                snackCategory = snackDraft.reviewCategoryId?.toString() ?: "none",
                ledgerRows = ledgerRows,
                requestTurns = requestBodies.size,
                maskedTokens = maskedTokens,
                rawSurfaceOnWire = rawOnWire,
                followUp = followUpResult,
                providerUnavailable = providerUnavailable,
                summary = summary,
            )
        } finally {
            runCatching { exportRunTrace(context, database, "$runId-${language.name}") }
                .onFailure { emit("S98EXPORT failed=${it::class.simpleName}: ${it.message}") }
            database.close()
            // The synthetic file is the whole per-run scope; deleting only this run's own file
            // keeps every stored row and provider profile of the isolated app untouched.
            context.deleteDatabase("s98_${runId.replace("-", "")}.db")
        }
    }

    /**
     * Writes every stored trace row of one synthetic run to a durable JSON file inside the
     * isolated app's private files directory, so a2 keeps the exact model arguments and host
     * envelopes after the per-run database is deleted. Rows are the app's own trace: masked
     * provider bodies and neutral aliases, never a credential or an owner-app row.
     */
    private fun exportRunTrace(context: Context, database: LedgerDatabase, runTag: String) {
        val rows = database.openHelper.readableDatabase.query(
            "SELECT id, runId, scopeId, attempt, kind, direction, completion, turn, callId, payloadJson " +
                "FROM agent_trace_events ORDER BY id ASC",
        ).use { cursor ->
            JSONArray().also { array ->
                while (cursor.moveToNext()) {
                    array.put(
                        JSONObject()
                            .put("id", cursor.getLong(0))
                            .put("runId", cursor.getString(1))
                            .put("scopeId", cursor.getString(2))
                            .put("attempt", cursor.getInt(3))
                            .put("kind", cursor.getString(4))
                            .put("direction", cursor.getString(5))
                            .put("completion", cursor.getString(6))
                            .put("turn", if (cursor.isNull(7)) JSONObject.NULL else cursor.getInt(7))
                            .put("callId", if (cursor.isNull(8)) JSONObject.NULL else cursor.getString(8))
                            .put(
                                "payload",
                                runCatching { JSONObject(cursor.getString(9)) }.getOrNull()
                                    ?: cursor.getString(9),
                            ),
                    )
                }
            }
        }
        val file = java.io.File(context.filesDir, "s98-run-$runTag.trace.json")
        file.writeText(rows.toString())
        emit("S98EXPORT file=${file.name} rows=${rows.length()} bytes=${file.length()}")
    }

    private suspend fun seedFixture(
        database: LedgerDatabase,
        commands: WorkflowCommandService,
        runId: String,
        note: String,
        ambiguity: Boolean,
    ): Fixture {
        val transport = database.categoryDao().findActiveIdByName("Transport")
            ?: database.categoryDao().insert(
                CategoryEntity(
                    name = "Transport",
                    createdAtEpochMillis = System.currentTimeMillis(),
                    updatedAtEpochMillis = System.currentTimeMillis(),
                ),
            )
        val food = database.categoryDao().findActiveIdByName("Food")
            ?: database.categoryDao().insert(
                CategoryEntity(
                    name = "Food",
                    createdAtEpochMillis = System.currentTimeMillis(),
                    updatedAtEpochMillis = System.currentTimeMillis(),
                ),
            )
        val saved = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest("s98-$runId-capture", note),
        ) as WorkflowCommandOutcome.Applied
        val captureId = (saved.change as WorkflowChange.CaptureSaved).captureId
        val today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).toString()
        suspend fun draft(suffix: String, amount: Long, time: String, memo: String): Long =
            database.pendingBankDraftDao().store(
                PendingBankDraftEntity(
                    deliveryId = "s98-$runId-$suffix",
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
                    statedDate = today,
                    statedTime = time,
                    momentUnresolvedReason = null,
                    memoState = IntakeFieldState.PRESENT,
                    memo = memo,
                    memoUnresolvedReason = null,
                    createdAtEpochMillis = System.currentTimeMillis(),
                ),
            )
        return Fixture(
            captureId = captureId,
            fuelDraft = draft("fuel", 50_000L, "15:00", "Petrolimex"),
            snackDraft = draft("snack", 20_000L, "15:15", "Circle K"),
            misleadDraft = draft("mislead", 25_000L, "15:10", "Highlands Coffee"),
            twinDraft = if (ambiguity) draft("twin", 20_000L, "15:16", "Highlands Coffee") else null,
            transport = transport,
            food = food,
        )
    }

    private fun openProductionDatabase(context: Context): LedgerDatabase =
        Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, LedgerDatabase.NAME)
            .build()

    private var logFile: java.io.File? = null

    private fun emit(line: String) {
        android.util.Log.i("S98EVID", line)
        println(line)
        logFile?.let { file ->
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(line + "\n")
            }
        }
    }

    private fun startLog(context: Context, name: String) {
        logFile = java.io.File(context.filesDir, "s98-evidence-$name.log")
        runCatching {
            logFile?.parentFile?.mkdirs()
            logFile?.writeText("")
        }
    }

    private companion object {
        const val ISOLATED_PACKAGE = "io.github.triet4p.aegisf.s812evidence"
        const val LIVE_ARGUMENT = "s98_live"
    }
}
