package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.triet4p.aegisf.provider.HostedProviderPresets
import io.github.triet4p.aegisf.provider.HostedProviderSettingsStore
import io.github.triet4p.aegisf.provider.SavedHostedProvider
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage-driven S9.8 device fixture and provider-configuration staging.
 *
 * Only synthetic, run-unique rows are added to the isolated app's own database; no existing row,
 * database or provider profile is cleared. Provider staging adds one temporary profile for an
 * offered preset (reusing the already configured credential in memory, never printing it) and
 * removes exactly that profile again, so the stored configuration returns to its prior state.
 */
@RunWith(AndroidJUnit4::class)
class S98DeviceFixtureInstrumentedTest {

    @Test
    fun stage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(ISOLATED_PACKAGE, context.packageName)
        when (val stage = InstrumentationRegistry.getArguments().getString(STAGE_ARGUMENT)) {
            "seed" -> seed(context)
            "verify_after_live_run" -> verifyAfterLiveRun(context)
            "verify_after_fallback" -> verifyAfterFallback(context)
            "add_profile" -> addOfferedProfile(context)
            "remove_profile" -> removeTemporaryProfile(
                context,
                requireNotNull(InstrumentationRegistry.getArguments().getString("s98_profile_id")),
            )
            "activate_profile" -> activateProfile(
                context,
                requireNotNull(InstrumentationRegistry.getArguments().getString("s98_profile_id")),
            )
            "restore_active" -> restoreActive(context)
            "verify_config" -> verifyConfig(context)
            "add_unreachable" -> addUnreachableProfile(context)
            "add_profiles_from_secret" -> addProfilesFromSecret(context)
            "trace_latest" -> traceLatest(context)
            "amount_schema" -> amountSchema(context)
            "note_schema" -> noteSchema()
            "amount_probe" -> amountProbe()
            "record_event_probe" -> recordEventProbe(context)
            "anchor_contract" -> anchorContractProbe(context)
            "seed_en_note" -> seedEnglishNote(context)
            "ui_seed_grounded" -> seedGroundedEnglishNote(context)
            "ui_verify" -> verifyGroundedUiRun(context)
            "db_state" -> databaseState(context)
            else -> error("unknown stage: $stage")
        }
    }

    private suspend fun seed(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val runId = UUID.randomUUID().toString().replace("-", "")
        val store = HostedProviderSettingsStore(context)
        val baselineProfiles = store.load()
        val baselineActive = runCatching { store.active() }.getOrNull()
        val database = openProductionDatabase(context)
        try {
            val commands = WorkflowCommandService(database, timezoneId = { "Asia/Ho_Chi_Minh" })
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
                SaveCaptureRequest("s98-$runId-capture", NOTE),
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
            val fuelDraft = draft("fuel", 50_000L, "15:00", "Petrolimex")
            val snackDraft = draft("snack", 20_000L, "15:15", "Circle K")
            val misleadDraft = draft("mislead", 25_000L, "15:10", "Highlands Coffee")
            preferences.edit()
                .putString(RUN_KEY, runId)
                .putLong(CAPTURE_KEY, captureId)
                .putLong(FUEL_DRAFT_KEY, fuelDraft)
                .putLong(SNACK_DRAFT_KEY, snackDraft)
                .putLong(MISLEAD_DRAFT_KEY, misleadDraft)
                .putLong(TRANSPORT_KEY, transport)
                .putLong(FOOD_KEY, food)
                .putInt(LEDGER_BASELINE_KEY, database.transactionDao().findAll().size)
                .putString(PROFILE_IDS_KEY, baselineProfiles.map { it.id }.joinToString(","))
                .putString(ACTIVE_ID_KEY, baselineActive?.id)
                .commit()
            emit(
                "S98SEED run=$runId capture=$captureId fuelDraft=$fuelDraft snackDraft=$snackDraft " +
                    "misleadDraft=$misleadDraft transport=$transport food=$food " +
                    "ledgerBaseline=${database.transactionDao().findAll().size} " +
                    "profiles=${baselineProfiles.size} active=${baselineActive?.id}",
            )
        } finally {
            database.close()
        }
    }

    private suspend fun verifyAfterLiveRun(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val runId = requireNotNull(preferences.getString(RUN_KEY, null))
        val captureId = preferences.getLong(CAPTURE_KEY, -1L)
        val fuelDraftId = preferences.getLong(FUEL_DRAFT_KEY, -1L)
        val snackDraftId = preferences.getLong(SNACK_DRAFT_KEY, -1L)
        val misleadDraftId = preferences.getLong(MISLEAD_DRAFT_KEY, -1L)
        val ledgerBaseline = preferences.getInt(LEDGER_BASELINE_KEY, -1)
        val database = openProductionDatabase(context)
        try {
            val queries = WorkflowQueryService(database)
            val capture = requireNotNull(database.captureMessageDao().findById(captureId))
            emit("S98VERIFY captureId=${capture.id} revision=${capture.revision} body=${capture.bodyText}")
            assertEquals("the Vietnamese note changed on the device", NOTE, capture.bodyText)
            val events = database.noteEventDao().findForCapture(captureId)
            emit("S98VERIFY events=${events.size}")
            events.forEach { event ->
                emit(
                    "S98VERIFY event id=${event.id} span=${event.spanStart}..${event.spanEnd} " +
                        "text=${event.normalizedText} amount=${event.amountVnd} merchant=${event.merchant} " +
                        "anchorDate=${event.anchorDate} anchorTime=${event.anchorTime} status=${event.status}",
                )
            }
            val drafts = listOf(fuelDraftId, snackDraftId, misleadDraftId).associateWith {
                database.pendingBankDraftDao().findById(it)
            }
            drafts.forEach { (id, draft) ->
                requireNotNull(draft)
                val links = queries.draftContextLinks(id, QueryPageRequest(20, 0)).items
                val entry = queries.reviewQueueEntry(id)
                emit(
                    "S98VERIFY draft id=$id status=${draft.reviewStatus} category=${draft.reviewCategoryId} " +
                        "origin=${draft.reviewCategoryOrigin} resolved=${draft.reviewCategoryResolved} " +
                        "links=${links.map { "${it.eventId}:${it.state}" }} group=${entry?.group}",
                )
                assertEquals(IntakeReviewStatus.PENDING, draft.reviewStatus)
            }
            val misleadLinks = queries.draftContextLinks(misleadDraftId, QueryPageRequest(20, 0)).items
            assertTrue(
                "the misleading nearby candidate was linked: $misleadLinks",
                misleadLinks.none { it.state == ContextLinkState.ACCEPTED },
            )
            val ledgerRows = database.transactionDao().findAll().size
            emit("S98VERIFY ledger=$ledgerRows baseline=$ledgerBaseline")
            assertEquals("a hosted run added a ledger row", ledgerBaseline, ledgerRows)

            val latestRunId = database.openHelper.readableDatabase
                .query("SELECT runId FROM agent_trace_events ORDER BY id DESC LIMIT 1")
                .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            assertNotNull("no production trace for the UI run", latestRunId)
            emit("S98VERIFY latestTraceRun=$latestRunId seededRun=$runId")
            val trace = io.github.triet4p.aegisf.data.AgentTraceStore(database.agentTraceDao())
                .findForRun(requireNotNull(latestRunId))
            val requests = trace.filter { it.kind == "PROVIDER_REQUEST" }
                .map { JSONObject(it.payloadJson).getString("body") }
            val toolResults = trace.filter { it.kind == "TOOL_RESULT" }
                .map { JSONObject(it.payloadJson).optString("resultJson") }
            val surfaces = listOf("50.000", "50000", "20.000", "20000", "15:00", "15:15")
            val tokens = requests.sumOf { body ->
                Regex("⟦V-[A-Za-z0-9]{4,12}-\\d{4}⟧").findAll(body).count()
            }
            val rawOnWire = requests.any { body -> surfaces.any(body::contains) }
            val toolResultRaw = toolResults.any { result -> surfaces.any(result::contains) }
            val toolResultMasked = toolResults.any { result -> result.contains("⟦V-") }
            val profile = requireNotNull(HostedProviderSettingsStore(context).active())
            val credentialOnWire = trace.any { it.payloadJson.contains(profile.credential) }
            emit(
                "S98VERIFY requests=${requests.size} tokens=$tokens rawOnWire=$rawOnWire " +
                    "toolResultMasked=$toolResultMasked toolResultRaw=$toolResultRaw " +
                    "credentialInTrace=$credentialOnWire " +
                    "followUpRows=${trace.count { it.kind == "FOLLOW_UP_MESSAGE" }}",
            )
            assertTrue("no masked token reached the UI run's wire", tokens > 0)
            assertFalse("a raw fixture surface reached the UI run's wire", rawOnWire)
            assertTrue("tool results did not carry masked egress", toolResultMasked)
            assertFalse("the provider credential was stored in the trace", credentialOnWire)
        } finally {
            database.close()
        }
    }

    private suspend fun verifyAfterFallback(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val captureId = preferences.getLong(CAPTURE_KEY, -1L)
        val ledgerBaseline = preferences.getInt(LEDGER_BASELINE_KEY, -1)
        val baselineProfiles = preferences.getString(PROFILE_IDS_KEY, "")!!.split(",").filter { it.isNotBlank() }
        val baselineActive = preferences.getString(ACTIVE_ID_KEY, null)
        val database = openProductionDatabase(context)
        try {
            val capture = requireNotNull(database.captureMessageDao().findById(captureId))
            emit("S98VERIFYFALLBACK captureRevision=${capture.revision} body=${capture.bodyText}")
            assertTrue(
                "the offline/missing-provider edit did not reach the stored note",
                capture.bodyText.orEmpty().contains(EDIT_MARKER),
            )
            val ledgerRows = database.transactionDao().findAll().size
            emit("S98VERIFYFALLBACK ledger=$ledgerRows baseline=$ledgerBaseline")
            assertEquals("a fallback path changed the ledger", ledgerBaseline, ledgerRows)
        } finally {
            database.close()
        }
        val store = HostedProviderSettingsStore(context)
        val profiles = store.load()
        val active = runCatching { store.active() }.getOrNull()
        emit(
            "S98VERIFYFALLBACK profiles=${profiles.map { it.id }} active=${active?.id} " +
                "baselineProfiles=$baselineProfiles baselineActive=$baselineActive",
        )
        assertEquals("the saved provider profiles changed", baselineProfiles, profiles.map { it.id })
        assertEquals("the active provider changed", baselineActive, active?.id)
    }

    private suspend fun traceLatest(context: Context) {
        val database = openProductionDatabase(context)
        try {
            val latestRunId = database.openHelper.readableDatabase
                .query("SELECT runId FROM agent_trace_events ORDER BY id DESC LIMIT 1")
                .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            requireNotNull(latestRunId) { "no trace rows" }
            val trace = AgentTraceStore(database.agentTraceDao()).findForRun(latestRunId)
            emit("S98TRACELATEST run=$latestRunId events=${trace.size}")
            trace.forEach { event ->
                emit(
                    "S98TRACELATEST kind=${event.kind} turn=${event.turn} call=${event.callId} " +
                        "completion=${event.completion} payload=${event.payloadJson.take(500)}",
                )
            }
        } finally {
            database.close()
        }
    }

    private suspend fun databaseState(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val captureId = preferences.getLong(CAPTURE_KEY, -1L)
        val database = openProductionDatabase(context)
        try {
            val queries = WorkflowQueryService(database)
            val capture = requireNotNull(database.captureMessageDao().findById(captureId))
            emit("S98DB capture=${capture.id} revision=${capture.revision} body=${capture.bodyText}")
            database.noteEventDao().findForCapture(captureId).forEach { event ->
                emit(
                    "S98DB event id=${event.id} span=${event.spanStart}..${event.spanEnd} " +
                        "text=${event.normalizedText} amount=${event.amountVnd} merchant=${event.merchant} " +
                        "date=${event.anchorDate} time=${event.anchorTime} status=${event.status} " +
                        "unresolved=${event.unresolvedReason}",
                )
            }
            listOf(
                "fuel" to preferences.getLong(FUEL_DRAFT_KEY, -1L),
                "snack" to preferences.getLong(SNACK_DRAFT_KEY, -1L),
                "mislead" to preferences.getLong(MISLEAD_DRAFT_KEY, -1L),
            ).forEach { (label, id) ->
                val draft = database.pendingBankDraftDao().findById(id) ?: return@forEach
                val links = queries.draftContextLinks(id, QueryPageRequest(20, 0)).items
                emit(
                    "S98DB draft=$label id=$id status=${draft.reviewStatus} amount=${draft.amountVnd} " +
                        "time=${draft.statedTime} memo=${draft.memo} category=${draft.reviewCategoryId} " +
                        "origin=${draft.reviewCategoryOrigin} links=${links.map { "e${it.eventId}:${it.state}" }} " +
                        "group=${queries.reviewQueueEntry(id)?.group}",
                )
            }
            emit("S98DB ledger=${database.transactionDao().findAll().size}")
            val unreachableRows = database.openHelper.readableDatabase
                .query("SELECT COUNT(*) FROM agent_trace_events WHERE payloadJson LIKE '%127.0.0.1:9%'")
                .use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else -1L }
            val unreachableRuns = database.openHelper.readableDatabase
                .query("SELECT COUNT(DISTINCT runId) FROM agent_trace_events WHERE payloadJson LIKE '%127.0.0.1:9%'")
                .use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else -1L }
            emit("S98DB unreachableTraceRows=$unreachableRows unreachableRuns=$unreachableRuns")
            emit("S98DB categories=" + database.categoryDao().findAll().joinToString(",") { "${it.id}:${it.name}" })
        } finally {
            database.close()
        }
    }

    private fun addOfferedProfile(context: Context) {
        val arguments = InstrumentationRegistry.getArguments()
        val presetId = requireNotNull(arguments.getString("s98_preset_id"))
        val profileId = requireNotNull(arguments.getString("s98_profile_id"))
        val store = HostedProviderSettingsStore(context)
        val profiles = store.load()
        if (profiles.any { it.id == profileId }) return
        val credentialSource = requireNotNull(profiles.firstOrNull()) { "no configured credential to reuse" }
        val preset = HostedProviderPresets.byId(presetId)
        val modelOverride = arguments.getString("s98_model_override")
        val added: SavedHostedProvider = preset.profile(
            name = "S98 ${preset.label}",
            credential = credentialSource.credential,
        ).copy(
            id = profileId,
            model = modelOverride ?: preset.model,
        )
        store.save(profiles + added)
        emit(
            "S98CONFIG added=$profileId model=${added.model} " +
                "activeBefore=${runCatching { store.active() }.getOrNull()?.id}",
        )
    }

    private suspend fun seedEnglishNote(context: Context) {
        val runId = UUID.randomUUID().toString().replace("-", "")
        val database = openProductionDatabase(context)
        try {
            val commands = WorkflowCommandService(database, timezoneId = { "Asia/Ho_Chi_Minh" })
            val saved = commands.saveCapture(
                WorkflowCallerScope.User,
                SaveCaptureRequest(
                    "s98en-$runId",
                    "Today fuel 50000d at 15:00 and snack at Circle K 20000d at 15:15.",
                ),
            ) as WorkflowCommandOutcome.Applied
            val captureId = (saved.change as WorkflowChange.CaptureSaved).captureId
            emit("S98ENNOTE capture=$captureId")
        } finally {
            database.close()
        }
    }

    /**
     * Seeds one day-grounded English note with its own run-unique pending drafts so the real Chat
     * UI can run the offered hosted workflow. Only synthetic rows are added; nothing is cleared.
     */
    private suspend fun seedGroundedEnglishNote(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val runId = UUID.randomUUID().toString().replace("-", "")
        val database = openProductionDatabase(context)
        try {
            val commands = WorkflowCommandService(database, timezoneId = { "Asia/Ho_Chi_Minh" })
            val saved = commands.saveCapture(
                WorkflowCallerScope.User,
                SaveCaptureRequest("s98ui-$runId", GROUNDED_EN_NOTE),
            ) as WorkflowCommandOutcome.Applied
            val captureId = (saved.change as WorkflowChange.CaptureSaved).captureId
            val today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).toString()
            suspend fun draft(suffix: String, amount: Long, time: String, memo: String): Long =
                database.pendingBankDraftDao().store(
                    PendingBankDraftEntity(
                        deliveryId = "s98ui-$runId-$suffix",
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
            val fuelDraft = draft("fuel", 50_000L, "15:00", "Petrolimex")
            val snackDraft = draft("snack", 20_000L, "15:15", "Circle K")
            val misleadDraft = draft("mislead", 25_000L, "15:10", "Highlands Coffee")
            val ledger = database.transactionDao().findAll().size
            preferences.edit()
                .putLong(CAPTURE_KEY, captureId)
                .putLong(FUEL_DRAFT_KEY, fuelDraft)
                .putLong(SNACK_DRAFT_KEY, snackDraft)
                .putLong(MISLEAD_DRAFT_KEY, misleadDraft)
                .putInt(LEDGER_BASELINE_KEY, ledger)
                .commit()
            emit(
                "S98UISEED run=$runId capture=$captureId note=$GROUNDED_EN_NOTE " +
                    "fuelDraft=$fuelDraft snackDraft=$snackDraft misleadDraft=$misleadDraft " +
                    "statedDate=$today ledgerBaseline=$ledger",
            )
        } finally {
            database.close()
        }
    }

    /** Reads the seeded UI run back from the isolated app's own database. */
    private suspend fun verifyGroundedUiRun(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val captureId = preferences.getLong(CAPTURE_KEY, -1L)
        val ledgerBaseline = preferences.getInt(LEDGER_BASELINE_KEY, -1)
        val database = openProductionDatabase(context)
        try {
            val queries = WorkflowQueryService(database)
            val capture = requireNotNull(database.captureMessageDao().findById(captureId)) {
                "no seeded capture"
            }
            emit("S98UIVERIFY capture=${capture.id} revision=${capture.revision} body=${capture.bodyText}")
            val events = database.noteEventDao().findForCapture(capture.id)
            emit("S98UIVERIFY events=${events.size}")
            events.forEach { event ->
                val links = queries.linkOperations(
                    io.github.triet4p.aegisf.data.LinkOperationQuery(
                        eventId = event.id,
                        bounds = QueryPageRequest(20, 0),
                    ),
                ).items
                emit(
                    "S98UIVERIFY event id=${event.id} amount=${event.amountVnd} merchant=${event.merchant} " +
                        "date=${event.anchorDate} time=${event.anchorTime} status=${event.status} " +
                        "unresolved=${event.unresolvedReason} links=${links.map { "d${it.draftId}:${it.state}" }}",
                )
            }
            listOf(
                "fuel" to preferences.getLong(FUEL_DRAFT_KEY, -1L),
                "snack" to preferences.getLong(SNACK_DRAFT_KEY, -1L),
                "mislead" to preferences.getLong(MISLEAD_DRAFT_KEY, -1L),
            ).forEach { (label, id) ->
                val draft = database.pendingBankDraftDao().findById(id) ?: return@forEach
                emit(
                    "S98UIVERIFY draft=$label id=$id status=${draft.reviewStatus} " +
                        "amount=${draft.amountVnd} memo=${draft.memo} category=${draft.reviewCategoryId} " +
                        "origin=${draft.reviewCategoryOrigin} " +
                        "group=${queries.reviewQueueEntry(id)?.group}",
                )
                assertEquals(IntakeReviewStatus.PENDING, draft.reviewStatus)
            }
            val acceptedLinks = events.flatMap { event ->
                queries.linkOperations(
                    io.github.triet4p.aegisf.data.LinkOperationQuery(
                        eventId = event.id,
                        bounds = QueryPageRequest(20, 0),
                    ),
                ).items.filter { it.state == ContextLinkState.ACCEPTED }
            }
            emit("S98UIVERIFY acceptedLinks=${acceptedLinks.map { "e${it.eventId}-d${it.draftId}" }}")
            val ledger = database.transactionDao().findAll().size
            emit("S98UIVERIFY ledger=$ledger baseline=$ledgerBaseline")
            assertEquals("the UI run changed the ledger", ledgerBaseline, ledger)
            assertEquals("the UI run did not record both readings", 2, events.size)
            assertTrue(
                "the UI run did not store both readings as interpreted: $events",
                events.all { it.status == io.github.triet4p.aegisf.data.NoteEventStatus.INTERPRETED },
            )
            assertEquals("the UI run did not accept both unique links", 2, acceptedLinks.size)
        } finally {
            database.close()
        }
    }

    private suspend fun recordEventProbe(context: Context) {
        val runId = UUID.randomUUID().toString().replace("-", "")
        val databaseName = "s98probe_${runId.replace("-", "")}.db"
        val database = Room.databaseBuilder(context, LedgerDatabase::class.java, databaseName).build()
        try {
            val commands = WorkflowCommandService(database, timezoneId = { "Asia/Ho_Chi_Minh" })
            val queries = WorkflowQueryService(database)
            val note = "Today fuel 50000d at 15:00 and snack at Circle K 20000d at 15:15."
            val saved = commands.saveCapture(
                WorkflowCallerScope.User,
                SaveCaptureRequest("s98probe-$runId", note),
            ) as WorkflowCommandOutcome.Applied
            val captureId = (saved.change as WorkflowChange.CaptureSaved).captureId
            val scope = WorkflowCallerScope.AgentSession("s98probe-$runId", setOf(captureId), emptySet())
            val boundary = io.github.triet4p.aegisf.privacy.MaskedPromptBoundary(
                io.github.triet4p.aegisf.privacy.SensitiveVault(scope.scopeId),
            )
            val registry = io.github.triet4p.aegisf.agent.AgentReferenceRegistry(scope.scopeId)
            val runtime = io.github.triet4p.aegisf.agent.AgentToolRuntime(
                commands = commands,
                queries = queries,
                session = io.github.triet4p.aegisf.agent.AgentToolSession(scope, boundary, registry),
                declarations = io.github.triet4p.aegisf.agent.AgentToolDeclarations.load(context.assets::open),
            )
            val capture = runtime.initialCaptureObservations(4).firstOrNull()
                ?: error("no capture observation")
            val sourceText = capture.getString("source_text")
            val split = sourceText.indexOf(" and snack")
            val quote = if (split > 0) sourceText.substring(0, split).trimEnd() else sourceText
            emit("S98RECORDPROBE quote=$quote")
            val select = runtime.execute(
                "select_source",
                JSONObject()
                    .put("capture_ref", capture.getString("ref"))
                    .put("quote", quote)
                    .put("occurrence", 1),
                "s98probe-$runId-select",
            )
            val data = select.envelope.optJSONObject("data")
            emit(
                "S98RECORDPROBE select outcome=${select.outcome} code=${select.code} " +
                    "values=${data?.optJSONArray("values")} relative_dates=${data?.optJSONArray("relative_dates")}",
            )
            val selected = requireNotNull(data) { "select_source failed: ${select.code}" }
            val values = selected.getJSONArray("values")
            var amountRef: String? = null
            var timeRef: String? = null
            for (index in 0 until values.length()) {
                val value = values.getJSONObject(index)
                val display = value.optString("display")
                val amount = runCatching { boundary.vault.parseAmount(display) }.getOrNull()
                val time = runCatching { boundary.vault.parseTime(display) }.getOrNull()
                if (amount == 50_000L) amountRef = value.getString("ref")
                if (time == "15:00") timeRef = value.getString("ref")
            }
            val relative = selected.optJSONArray("relative_dates")
                ?.takeIf { it.length() > 0 }?.getJSONObject(0)
            emit(
                "S98RECORDPROBE refs amount=${amountRef != null} time=${timeRef != null} " +
                    "relative_date=${relative?.getString("ref")}",
            )
            // An invented, never-issued handle: the production tool must refuse it exactly as it
            // refused the model's unknown end-date alias.
            val invented = runtime.execute(
                "make_time_anchor",
                JSONObject()
                    .put("date_ref", "r_s98neverissued")
                    .put("time_ref", JSONObject.NULL)
                    .put("end_date_ref", JSONObject.NULL)
                    .put("end_time_ref", JSONObject.NULL),
                "s98probe-$runId-anchor-invalid",
            )
            emit("S98RECORDPROBE invented_ref outcome=${invented.outcome} code=${invented.code}")
            val anchor = runtime.execute(
                "make_time_anchor",
                JSONObject()
                    .put("date_ref", requireNotNull(relative?.getString("ref")) { "no relative date ref" })
                    .put("time_ref", timeRef ?: JSONObject.NULL)
                    .put("end_date_ref", JSONObject.NULL)
                    .put("end_time_ref", JSONObject.NULL),
                "s98probe-$runId-anchor",
            )
            emit(
                "S98RECORDPROBE anchor outcome=${anchor.outcome} code=${anchor.code} " +
                    "data=${anchor.envelope.optJSONObject("data")}",
            )
            val anchorRef = anchor.envelope.optJSONObject("data")?.optString("anchor_ref")
            val record = runtime.execute(
                "record_event",
                JSONObject()
                    .put("source_ref", selected.getString("source_ref"))
                    .put("summary", "Fuel purchase")
                    .put("amount_ref", requireNotNull(amountRef) { "no amount ref" })
                    .put("anchor_ref", requireNotNull(anchorRef) { "no anchor ref" })
                    .put("merchant_ref", JSONObject.NULL)
                    .put("clarification", "none"),
                "s98probe-$runId-record",
            )
            emit("S98RECORDPROBE record outcome=${record.outcome} code=${record.code}")
            val events = database.noteEventDao().findForCapture(captureId)
            events.forEach { event ->
                emit(
                    "S98RECORDPROBE stored id=${event.id} amount=${event.amountVnd} " +
                        "date=${event.anchorDate} time=${event.anchorTime} status=${event.status}",
                )
            }
            assertEquals("record_event did not settle", "ok", record.outcome)
            assertEquals(1, events.size)
            assertEquals(50_000L, events.single().amountVnd)
            assertEquals("15:00", events.single().anchorTime)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    /**
     * S9.8-a2 no-model discriminator for the `make_time_anchor` reference contract.
     *
     * Drives the production [io.github.triet4p.aegisf.agent.AgentToolRuntime] on a synthetic
     * per-run database and maps the candidate values a model can supply for `end_date_ref` onto
     * the exact envelope outcome, code, path and constraint. It states what the host accepts and
     * refuses; it does not claim what a model supplied in any earlier run.
     */
    private suspend fun anchorContractProbe(context: Context) {
        val runId = UUID.randomUUID().toString().replace("-", "")
        val databaseName = "s98anchor_${runId.replace("-", "")}.db"
        val database = Room.databaseBuilder(context, LedgerDatabase::class.java, databaseName).build()
        try {
            val commands = WorkflowCommandService(database, timezoneId = { "Asia/Ho_Chi_Minh" })
            val queries = WorkflowQueryService(database)
            val note = "Today fuel 50000d at 15:00 and snack at Circle K 20000d at 15:15."
            val saved = commands.saveCapture(
                WorkflowCallerScope.User,
                SaveCaptureRequest("s98anchor-$runId", note),
            ) as WorkflowCommandOutcome.Applied
            val captureId = (saved.change as WorkflowChange.CaptureSaved).captureId
            val scope = WorkflowCallerScope.AgentSession("s98anchor-$runId", setOf(captureId), emptySet())
            val boundary = io.github.triet4p.aegisf.privacy.MaskedPromptBoundary(
                io.github.triet4p.aegisf.privacy.SensitiveVault(scope.scopeId),
            )
            val registry = io.github.triet4p.aegisf.agent.AgentReferenceRegistry(scope.scopeId)
            val runtime = io.github.triet4p.aegisf.agent.AgentToolRuntime(
                commands = commands,
                queries = queries,
                session = io.github.triet4p.aegisf.agent.AgentToolSession(scope, boundary, registry),
                declarations = io.github.triet4p.aegisf.agent.AgentToolDeclarations.load(context.assets::open),
            )
            val capture = runtime.initialCaptureObservations(4).firstOrNull()
                ?: error("no capture observation")
            emit("S98ANCHOR capture_observation=${capture}")
            val sourceText = capture.getString("source_text")
            val split = sourceText.indexOf(" and snack")
            val fuelQuote = if (split > 0) sourceText.substring(0, split).trimEnd() else sourceText
            val snackQuote = if (split > 0) sourceText.substring(split + 1).trim() else ""
            val fuel = runtime.execute(
                "select_source",
                JSONObject()
                    .put("capture_ref", capture.getString("ref"))
                    .put("quote", fuelQuote)
                    .put("occurrence", 1),
                "s98anchor-$runId-select-fuel",
            ).envelope
            val snack = if (snackQuote.isNotBlank()) {
                runtime.execute(
                    "select_source",
                    JSONObject()
                        .put("capture_ref", capture.getString("ref"))
                        .put("quote", snackQuote)
                        .put("occurrence", 1),
                    "s98anchor-$runId-select-snack",
                ).envelope
            } else {
                null
            }
            emit("S98ANCHOR selected_fuel=${fuel}")
            emit("S98ANCHOR selected_snack=$snack")
            val fuelData = requireNotNull(fuel.optJSONObject("data")) {
                "fuel select_source failed: ${fuel.optJSONObject("error")}"
            }
            fun refWhere(values: org.json.JSONArray?, predicate: (String) -> Boolean): JSONObject? {
                if (values == null) return null
                for (index in 0 until values.length()) {
                    val value = values.getJSONObject(index)
                    if (predicate(value.optString("display"))) return value
                }
                return null
            }
            val fuelValues = fuelData.optJSONArray("values")
            val amountValue = refWhere(fuelValues) {
                runCatching { boundary.vault.parseAmount(it) }.getOrNull() == 50_000L
            }
            val timeValue = refWhere(fuelValues) {
                runCatching { boundary.vault.parseTime(it) }.getOrNull() == "15:00"
            }
            val relative = fuelData.optJSONArray("relative_dates")
                ?.takeIf { it.length() > 0 }?.getJSONObject(0)
            val snackValues = snack?.optJSONObject("data")?.optJSONArray("values")
            val snackAmountValue = refWhere(snackValues) {
                runCatching { boundary.vault.parseAmount(it) }.getOrNull() == 20_000L
            }
            val relativeRef = requireNotNull(relative?.getString("ref")) { "no relative date alias" }
            val timeRef = requireNotNull(timeValue?.getString("ref")) { "no 15:00 value alias" }
            val amountRef = requireNotNull(amountValue?.getString("ref")) { "no 50000 value alias" }
            val amountToken = requireNotNull(amountValue.optString("display").ifBlank { null }) {
                "no masked display token"
            }
            val sourceRef = fuelData.getString("source_ref")
            val snackAmountRef = snackAmountValue?.getString("ref")
            emit(
                "S98ANCHOR refs relative_date=$relativeRef time=$timeRef amount=$amountRef " +
                    "amount_token=$amountToken source=$sourceRef snack_amount=$snackAmountRef",
            )
            suspend fun runCase(label: String, endDateRef: Any?, endTimeRef: Any? = null) {
                val arguments = JSONObject()
                    .put("date_ref", relativeRef)
                    .put("time_ref", timeRef)
                    .put("end_date_ref", endDateRef ?: JSONObject.NULL)
                    .put("end_time_ref", endTimeRef ?: JSONObject.NULL)
                val result = runtime.execute("make_time_anchor", arguments, "s98anchor-$runId-$label")
                emit(
                    "S98ANCHOR case=$label end_date_ref_type=" +
                        (endDateRef?.let { it::class.simpleName } ?: "null") +
                        " outcome=${result.outcome} code=${result.code} envelope=${result.envelope}",
                )
            }
            runCase("valid_null_ends", null)
            runCase("end_date_literal_null_text", "null")
            runCase("end_date_blank_text", "")
            runCase("end_date_display_token", amountToken)
            runCase("end_date_day_word", "Today")
            runCase("end_date_blank", "")
            runCase("end_date_fabricated_handle", "r_000000000000000000000000")
            runCase("end_date_truncated_handle", amountRef.dropLast(1))
            runCase("end_date_issued_amount_ref", amountRef)
            runCase("end_date_issued_time_ref", timeRef)
            runCase("end_date_same_relative_ref", relativeRef)
            runCase("end_date_source_ref", sourceRef)
            snackAmountRef?.let { runCase("end_date_other_occurrence_amount_ref", it) }
            val cursorCase = runtime.execute(
                "list_drafts",
                JSONObject()
                    .put("review_status", "PENDING")
                    .put("duplicates_only", false)
                    .put("cursor", "null")
                    .put("event_ref", JSONObject.NULL),
                "s98anchor-$runId-cursor-null",
            )
            emit(
                "S98ANCHOR case=list_drafts_cursor_literal_null_text outcome=${cursorCase.outcome} " +
                    "code=${cursorCase.code} envelope=${cursorCase.envelope}",
            )
            emit("S98ANCHOR registry_entries_issued_for_this_run=${registry.size()}")
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun noteSchema() {
        listOf(
            "vi" to "Hôm nay đổ xăng 50.000đ lúc 15:00 và ăn nhẹ ở Circle K 20.000đ lúc 15:15.",
            "en" to "Today fuel 50000d at 15:00 and snack at Circle K 20000d at 15:15.",
        ).forEach { (label, note) ->
            val vault = io.github.triet4p.aegisf.privacy.SensitiveVault("s98-schema-$label")
            val masked = vault.maskText(note)
            emit(
                "S98SCHEMA note=$label masked=$masked " +
                    "tokens=" + vault.issuedTokens().map { "${it.token}:${it.shape.wire}" },
            )
        }
    }

    private fun amountProbe() {
        listOf(
            "vi" to "Hôm nay đổ xăng 50.000đ lúc 15:00 và ăn nhẹ ở Circle K 20.000đ lúc 15:15.",
            "en" to "Today fuel 50000d at 15:00 and snack at Circle K 20000d at 15:15.",
            "en_grouped" to "Fuel 50,000d at 15:00 and snack 20,000 VND at 15:15.",
            "en_vnd" to "Fuel 50000 VND at 15:00 and snack 20000 VND at 15:15.",
            "short_d" to "Parking 3d.",
        ).forEach { (label, note) ->
            val vault = io.github.triet4p.aegisf.privacy.SensitiveVault("s98-probe-$label")
            val masked = vault.maskText(note)
            val results = vault.issuedTokens().map { token ->
                val outcome = try {
                    "whole_dong=" + vault.parseAmount(token.token)
                } catch (_: Exception) {
                    "rejected"
                }
                "${token.token}:${token.shape.wire}:$outcome"
            }
            emit("S98PROBE label=$label masked=$masked results=$results")
        }
    }

    private suspend fun amountSchema(context: Context) {
        val database = openProductionDatabase(context)
        try {
            val latestRunId = database.openHelper.readableDatabase
                .query("SELECT runId FROM agent_trace_events ORDER BY id DESC LIMIT 1")
                .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            requireNotNull(latestRunId) { "no trace rows" }
            val trace = AgentTraceStore(database.agentTraceDao()).findForRun(latestRunId)
            emit("S98SCHEMA run=$latestRunId events=${trace.size}")
            trace.filter { it.kind == "TOOL_RESULT" }.forEach { event ->
                val payload = JSONObject(event.payloadJson)
                val tool = payload.optString("name")
                val result = runCatching { JSONObject(payload.optString("resultJson")) }.getOrNull()
                    ?: return@forEach
                val data = result.optJSONObject("data") ?: return@forEach
                val values = data.optJSONArray("values")
                if (values != null) {
                    emit("S98SCHEMA tool=$tool capture_values=$values")
                }
                val amountRef = data.optJSONObject("event")?.optString("amount_ref")
                if (!amountRef.isNullOrBlank()) emit("S98SCHEMA tool=$tool event_amount_ref=$amountRef")
            }
        } finally {
            database.close()
        }
    }

    private fun addUnreachableProfile(context: Context) {
        val store = HostedProviderSettingsStore(context)
        val profiles = store.load()
        if (profiles.any { it.id == UNREACHABLE_PROFILE_ID }) return
        val preset = HostedProviderPresets.byId("google-gemini-flash-lite")
        val added: SavedHostedProvider = preset.profile(
            name = "S98 unreachable provider",
            credential = "synthetic-local-only-credential",
        ).copy(
            id = UNREACHABLE_PROFILE_ID,
            endpoint = "https://127.0.0.1:9/v1beta/models/{model}:generateContent",
            model = "s98-unreachable",
        )
        store.save(profiles + added)
        emit("S98CONFIG added=$UNREACHABLE_PROFILE_ID endpoint=loopback active=${runCatching { store.active() }.getOrNull()?.id}")
    }

    private fun addProfilesFromSecret(context: Context) {
        val secretFile = java.io.File(context.filesDir, SECRET_FILE)
        require(secretFile.isFile) { "no injected secret file" }
        val values = secretFile.readLines()
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.substring(0, index) to line.substring(index + 1).trim()
            }
            .toMap()
        val google = requireNotNull(values["GOOGLE_AI_STUDIO_KEY"]) { "GOOGLE_AI_STUDIO_KEY missing" }
        val opencode = requireNotNull(values["OPENCODE_GO_API_KEY"]) { "OPENCODE_GO_API_KEY missing" }
        require(google.isNotBlank() && opencode.isNotBlank()) { "injected key is blank" }
        try {
            val store = HostedProviderSettingsStore(context)
            val profiles = store.load()
            val additions = listOf(
                "google-gemma-4-26b-a4b-it" to ("s98-gemma" to google),
                "opencode-go-deepseek" to ("s98-deepseek" to opencode),
                "opencode-go-responses" to ("s98-luna" to opencode),
            ).filterNot { (_, target) -> profiles.any { it.id == target.first } }
                .map { (presetId, target) ->
                    val preset = HostedProviderPresets.byId(presetId)
                    val (id, credential) = target
                    preset.profile(name = "S98 ${preset.label}", credential = credential).copy(id = id)
                }
            if (additions.isNotEmpty()) store.save(profiles + additions)
            val reloaded = store.load()
            additions.forEach { added ->
                emit(
                    "S98CONFIG injected id=${added.id} model=${added.model} " +
                        "credentialMatch=${reloaded.first { it.id == added.id }.credential == added.credential}",
                )
            }
            emit("S98CONFIG injectedProfiles=${additions.size} active=${runCatching { store.active() }.getOrNull()?.id}")
        } finally {
            secretFile.delete()
            emit("S98CONFIG secretFileDeleted=${!secretFile.exists()}")
        }
    }

    private fun activateProfile(context: Context, profileId: String) {
        val store = HostedProviderSettingsStore(context)
        require(store.load().any { it.id == profileId }) { "no profile $profileId" }
        store.activate(profileId)
        emit("S98CONFIG activated=$profileId active=${runCatching { store.active() }.getOrNull()?.id}")
    }

    private fun removeTemporaryProfile(context: Context, profileId: String) {
        val store = HostedProviderSettingsStore(context)
        val profiles = store.load()
        store.save(profiles.filterNot { it.id == profileId })
        emit("S98CONFIG removed=$profileId remaining=${store.load().map { it.id }} active=${runCatching { store.active() }.getOrNull()?.id}")
    }

    private fun restoreActive(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val baselineActive = preferences.getString(ACTIVE_ID_KEY, null) ?: return
        val store = HostedProviderSettingsStore(context)
        if (store.load().any { it.id == baselineActive }) {
            store.activate(baselineActive)
        }
        emit("S98CONFIG activeRestored=${runCatching { store.active() }.getOrNull()?.id}")
    }

    private fun verifyConfig(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val baselineProfiles = preferences.getString(PROFILE_IDS_KEY, "")!!.split(",").filter { it.isNotBlank() }
        val baselineActive = preferences.getString(ACTIVE_ID_KEY, null)
        val store = HostedProviderSettingsStore(context)
        val profiles = store.load()
        val active = runCatching { store.active() }.getOrNull()
        emit("S98CONFIG profiles=${profiles.map { it.id }} active=${active?.id}")
        assertEquals(baselineProfiles, profiles.map { it.id })
        assertEquals(baselineActive, active?.id)
        emit("S98CONFIG restored=true")
    }

    private fun openProductionDatabase(context: Context): LedgerDatabase =
        Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, LedgerDatabase.NAME)
            .build()

    private fun emit(line: String) {
        android.util.Log.i("S98EVID", line)
        println(line)
        val file = java.io.File(
            ApplicationProvider.getApplicationContext<Context>().filesDir,
            "s98-evidence-device.log",
        )
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(line + "\n")
        }
    }

    private companion object {
        const val ISOLATED_PACKAGE = "io.github.triet4p.aegisf.s812evidence"
        const val STAGE_ARGUMENT = "s98_stage"
        const val PREFERENCES = "s98_device_fixture"
        const val RUN_KEY = "runId"
        const val CAPTURE_KEY = "captureId"
        const val FUEL_DRAFT_KEY = "fuelDraft"
        const val SNACK_DRAFT_KEY = "snackDraft"
        const val MISLEAD_DRAFT_KEY = "misleadDraft"
        const val TRANSPORT_KEY = "transport"
        const val FOOD_KEY = "food"
        const val LEDGER_BASELINE_KEY = "ledgerBaseline"
        const val PROFILE_IDS_KEY = "profileIds"
        const val ACTIVE_ID_KEY = "activeId"
        const val UNREACHABLE_PROFILE_ID = "s98-unreachable"
        const val SECRET_FILE = "s98_secret.env"
        const val NOTE = "Hôm nay đổ xăng 50.000đ lúc 15:00. Ăn nhẹ ở Circle K 20.000đ lúc 15:15."
        const val GROUNDED_EN_NOTE =
            "Today fuel 50000d at 15:00 and today snack at Circle K 20000d at 15:15."
        const val EDIT_MARKER = "s98edit"
    }
}
