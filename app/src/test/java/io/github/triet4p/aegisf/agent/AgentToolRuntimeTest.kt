package io.github.triet4p.aegisf.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.data.AddCategoryRequest
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.CaptureKind
import io.github.triet4p.aegisf.data.CaptureMessageEntity
import io.github.triet4p.aegisf.data.CaptureSourceKind
import io.github.triet4p.aegisf.data.ContextLinkState
import io.github.triet4p.aegisf.data.ContextLinkResult
import io.github.triet4p.aegisf.data.EventContextRepository
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.NoteEventInterpretation
import io.github.triet4p.aegisf.data.NoteEventTimeAnchor
import io.github.triet4p.aegisf.data.NoteEventWriteResult
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.ReviewCategoryOrigin
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.LinkOperationQuery
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.RejectLinkRequest
import io.github.triet4p.aegisf.data.ReviseCaptureRequest
import io.github.triet4p.aegisf.data.SaveCaptureRequest
import io.github.triet4p.aegisf.data.SystemLedgerClock
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowChange
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.data.workingDescription
import io.github.triet4p.aegisf.data.BankDraftReviewEdits
import io.github.triet4p.aegisf.data.BankDraftReviewResult
import io.github.triet4p.aegisf.data.TransactionEntity
import io.github.triet4p.aegisf.data.TransactionType
import io.github.triet4p.aegisf.privacy.MaskedPromptBoundary
import io.github.triet4p.aegisf.privacy.SensitiveVault
import io.github.triet4p.aegisf.provider.HostedToolDeclaration
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Boundary tests of the closed agent interface over the real Room services: opaque references,
 * exact source selection, atomic event CAS, clarification evidence, shared link policy, journal
 * replay, bounded pagination and terminal/local model outcomes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentToolRuntimeTest {
    private lateinit var context: Context
    private lateinit var database: LedgerDatabase
    private lateinit var commands: WorkflowCommandService
    private lateinit var queries: WorkflowQueryService
    private lateinit var scope: WorkflowCallerScope.AgentSession
    private lateinit var boundary: MaskedPromptBoundary
    private lateinit var declarations: List<HostedToolDeclaration>
    private var captureId: Long = 0L
    private val sequence = java.util.concurrent.atomic.AtomicInteger()

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        commands = WorkflowCommandService(
            database = database,
            clock = SystemLedgerClock,
            timezoneId = { "Asia/Ho_Chi_Minh" },
        )
        queries = WorkflowQueryService(database)
        val saved = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest("s8.13-pre-runtime-capture", SOURCE_TEXT),
        ) as WorkflowCommandOutcome.Applied
        captureId = (saved.change as WorkflowChange.CaptureSaved).captureId
        scope = WorkflowCallerScope.AgentSession(
            scopeId = "s8.13-pre-runtime-session",
            captureIds = setOf(captureId),
            draftIds = emptySet(),
        )
        boundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId))
        declarations = AgentToolDeclarations.load(context.assets::open)
        assertEquals(11, declarations.size)
    }

    @Test
    fun `draft review declaration permits only the draft and a category reference`() {
        val declaration = declarations.single { it.name == "update_draft_review" }
        val parameters = declaration.parameters
        val properties = parameters.getJSONObject("properties")
        val required = parameters.getJSONArray("required")

        assertFalse(parameters.getBoolean("additionalProperties"))
        assertEquals(2, properties.length())
        assertEquals(2, required.length())
        assertEquals("draft_ref", required.getString(0))
        assertEquals("category_ref", required.getString(1))
        assertEquals("string", properties.getJSONObject("draft_ref").getString("type"))
        assertEquals("string", properties.getJSONObject("category_ref").getString("type"))
        assertFalse(properties.has("description"))
        assertFalse(properties.has("category_id"))
        assertFalse(declarations.any { it.name == "classify_draft" })
    }

    @Test
    fun `financial confirmation and deletion tools remain unavailable`() = runBlocking {
        val forbiddenNames = setOf(
            "confirm_draft",
            "delete_ledger",
            "delete_transaction",
            "delete_draft",
        )
        assertTrue(declarations.none { it.name in forbiddenNames })
        val before = database.transactionDao().findAll()
        val runtime = runtimeFor(scope)
        forbiddenNames.forEach { name ->
            val result = runtime.execute(name, JSONObject(), "forbidden-$name").envelope
            assertEquals("error", result.getString("outcome"))
            assertEquals("not_dispatched", result.getString("dispatch"))
        }
        assertEquals(before, database.transactionDao().findAll())
    }


    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun `bounded capture observations follow capture timestamps and keep references bound`() = runBlocking {
        val timestamps = listOf(1_000L, 5_000L, 2_000L, 4_000L, 3_000L)
        val captureIds = timestamps.mapIndexed { index, timestamp ->
            database.captureMessageDao().insert(
                CaptureMessageEntity(
                    captureKind = CaptureKind.USER_NOTE,
                    sourceKind = CaptureSourceKind.USER_QUICK_CAPTURE,
                    sourceIdentity = "s8.14-observation-order-$index",
                    bodyText = "synthetic capture $index",
                    capturedAtEpochMillis = timestamp,
                    captureTimezone = "UTC",
                    revision = 0,
                    originRef = null,
                ),
            )
        }
        val session = WorkflowCallerScope.AgentSession(scope.scopeId, captureIds.toSet(), emptySet())
        val registry = AgentReferenceRegistry(session.scopeId)
        val runtime = AgentToolRuntime(
            commands = commands,
            queries = queries,
            session = AgentToolSession(session, boundary, registry),
            declarations = declarations,
        )

        val observations = runtime.initialCaptureObservations(2)
        val expectedCaptureIds = listOf(captureIds[1], captureIds[3])
        val observedCaptureIds = observations.map {
            registry.resolve(it.getString("ref"), AgentReferenceKind.CAPTURE, "$.ref").captureId
        }
        assertEquals(expectedCaptureIds, observedCaptureIds)

        val newest = observations.first()
        val source = selectSource(runtime, newest.getString("ref"), newest.getString("source_text"))
        val sourceRef = source.getString("source_ref")
        val newestCaptureId = expectedCaptureIds.first()
        assertEquals(
            newestCaptureId,
            registry.resolve(sourceRef, AgentReferenceKind.SOURCE, "$.source_ref").captureId,
        )

        val revised = commands.reviseCapture(
            WorkflowCallerScope.User,
            ReviseCaptureRequest(
                idempotencyKey = "s8.14-observation-order-revision",
                captureId = newestCaptureId,
                expectedRevision = 0,
                bodyText = "synthetic revised capture",
            ),
        )
        assertTrue(revised is WorkflowCommandOutcome.Applied)
        val stale = record(runtime, sourceRef, null, null, "synthetic reading", "amount")
        assertEquals("STALE_OBSERVATION", stale.getJSONObject("error").getString("code"))
        assertEquals(
            newestCaptureId,
            registry.resolve(sourceRef, AgentReferenceKind.SOURCE, "$.source_ref").captureId,
        )
        assertTrue(database.noteEventDao().findForCapture(newestCaptureId).isEmpty())
    }

    @Test
    fun `normal reading leaves optional merchant absent and is not unresolved`() = runBlocking {
        val runtime = runtimeFor(scope)
        val capture = observeCapture(runtime)
        val maskedSource = capture.getString("source_text")
        assertFalse(maskedSource.contains("28,000"))
        assertFalse(maskedSource.contains("2026-10-21"))
        assertNotEquals(SOURCE_TEXT.length, maskedSource.length)

        val source = selectSource(runtime, capture.getString("ref"), maskedSource)
        assertEquals(maskedSource, source.getString("quote"))
        val amountRef = valueRef(source, "amount")
        val dateRef = valueRef(source, "date")
        val timeRef = valueRef(source, "time")
        val anchor = makeAnchor(runtime, dateRef, timeRef)

        val recorded = record(runtime, source.getString("source_ref"), amountRef, anchor, "Lunch", "none")
        assertEquals(recorded.toString(), "ok", recorded.getString("outcome"))
        assertEquals("applied", recorded.getString("effect"))
        assertTrue(recorded.getBoolean("verified"))
        val event = recorded.getJSONObject("data").getJSONObject("event")
        assertEquals("INTERPRETED", event.getString("status"))
        assertEquals("none", recorded.getJSONObject("data").getString("clarification"))
        assertEquals(JSONObject.NULL, event.get("merchant"))
        assertTrue(recorded.getString("operation_ref").startsWith("r_"))

        val stored = database.noteEventDao().findForCapture(captureId).single()
        assertEquals(28_000L, stored.amountVnd)
        assertEquals("2026-10-21", stored.anchorDate)
        assertEquals("12:30", stored.anchorTime)
        assertNull(stored.merchant)
        assertNull(stored.unresolvedReason)
        // The stored normalized text is the model's grounded normalized meaning, not the raw span.
        assertEquals("Lunch", stored.normalizedText)
        assertTrue(database.transactionDao().findAll().isEmpty())
        assertEquals(1L, database.workflowOperationDao().countOf("UPDATE_EVENT"))
    }

    @Test
    fun `references are opaque and only current issued kinds are accepted`() = runBlocking {
        val runtime = runtimeFor(scope)
        val capture = observeCapture(runtime)
        val maskedSource = capture.getString("source_text")
        val source = selectSource(runtime, capture.getString("ref"), maskedSource)
        val amountRef = valueRef(source, "amount")
        val anchor = makeAnchor(runtime, valueRef(source, "date"), null)

        // A kind mismatch is actionable and names the field, never the submitted value.
        val wrongKind = record(runtime, capture.getString("ref"), amountRef, anchor, "Lunch", "none")
        assertEquals("REFERENCE_KIND_MISMATCH", wrongKind.getJSONObject("error").getString("code"))
        assertEquals("$.source_ref", wrongKind.getJSONObject("error").getJSONArray("issues").getJSONObject(0).getString("path"))
        assertFalse(wrongKind.toString().contains(amountRef))

        // A handle minted by another session is not resolvable here.
        val foreignRegistry = AgentReferenceRegistry("another-session")
        val foreignHandle = foreignRegistry.issue(
            AgentReference(
                handle = foreignRegistry.nextHandle(),
                kind = AgentReferenceKind.VALUE,
                scopeId = "another-session",
                valueToken = "⟦V-forign-0001⟧",
            ),
        )
        val foreign = record(runtime, source.getString("source_ref"), foreignHandle, anchor, "Lunch", "none")
        assertEquals("UNKNOWN_REFERENCE", foreign.getJSONObject("error").getString("code"))

        // Handles are random and carry no kind or subject text.
        assertTrue(source.getString("source_ref").matches(Regex("^r_[0-9a-f]{24}$")))
        assertFalse(source.getString("source_ref").contains("source"))
    }

    @Test
    fun `the documented event-reading flow accepts only source-issued value and anchor aliases`() = runBlocking {
        val runtime = runtimeFor(scope)
        val capture = observeCapture(runtime)
        val captureAmount = valueRef(capture, "amount")
        val captureDate = valueRef(capture, "date")
        val source = selectSource(runtime, capture.getString("ref"), capture.getString("source_text"))
        val sourceAmount = valueRef(source, "amount")
        val anchor = makeAnchor(runtime, valueRef(source, "date"), valueRef(source, "time"))

        // A capture-page alias is display-only: it never becomes evidence for the selected
        // occurrence, even though both aliases live in the same session and quote the same text.
        val captureOnlyAmount = record(
            runtime, source.getString("source_ref"), captureAmount, anchor, "Lunch", "none",
        )
        assertEquals("REFERENCE_KIND_MISMATCH", captureOnlyAmount.getJSONObject("error").getString("code"))
        assertEquals("none", captureOnlyAmount.getString("effect"))
        assertEquals(
            "$.amount_ref",
            captureOnlyAmount.getJSONObject("error").getJSONArray("issues").getJSONObject(0).getString("path"),
        )

        val captureOnlyDate = runtime.execute(
            "make_time_anchor",
            JSONObject()
                .put("date_ref", captureDate)
                .put("time_ref", JSONObject.NULL)
                .put("end_date_ref", JSONObject.NULL)
                .put("end_time_ref", JSONObject.NULL),
            "read-only",
        ).envelope
        assertEquals("REFERENCE_KIND_MISMATCH", captureOnlyDate.getJSONObject("error").getString("code"))
        assertEquals("none", captureOnlyDate.getString("effect"))
        assertTrue(database.noteEventDao().findForCapture(captureId).isEmpty())

        // A fabricated reference never resolves and cannot bypass the selected source either.
        val fabricated = record(
            runtime, source.getString("source_ref"), "r_" + "0".repeat(24), anchor, "Lunch", "none",
        )
        assertEquals("UNKNOWN_REFERENCE", fabricated.getJSONObject("error").getString("code"))
        assertEquals("none", fabricated.getString("effect"))
        assertTrue(database.noteEventDao().findForCapture(captureId).isEmpty())

        // The corrected example's flow — source alias, then that source's own amount and
        // anchor aliases — records and verifies the reading.
        val applied = record(
            runtime, source.getString("source_ref"), sourceAmount, anchor, "Lunch", "none",
        )
        assertEquals(applied.toString(), "ok", applied.getString("outcome"))
        assertEquals("applied", applied.getString("effect"))
        assertTrue(applied.getBoolean("verified"))
        val stored = database.noteEventDao().findForCapture(captureId).single()
        assertEquals(28_000L, stored.amountVnd)
        assertEquals("2026-10-21", stored.anchorDate)
        assertEquals("12:30", stored.anchorTime)
        assertEquals("Lunch", stored.normalizedText)
    }

    @Test
    fun `an unissued alias is refused without mutation and restates only issued handles`() = runBlocking {
        val registry = AgentReferenceRegistry(scope.scopeId)
        val runtime = runtimeFor(scope, registry)
        val capture = observeCapture(runtime)
        val validCaptureRef = capture.getString("ref")
        val maskedSource = capture.getString("source_text")
        // The observed failure: an issued handle copied with one wrong character.
        val wrong = validCaptureRef.dropLast(1) +
            if (validCaptureRef.last() == '0') '1' else '0'
        assertNotEquals(validCaptureRef, wrong)

        val refused = runtime.execute(
            "select_source",
            JSONObject().put("capture_ref", wrong).put("quote", maskedSource).put("occurrence", 1),
            "read-only",
        ).envelope

        // The same result the provider receives: serialized, then masked by the egress boundary.
        val serialized = JSONObject(refused.toString())
        assertEquals("error", serialized.getString("outcome"))
        assertEquals("UNKNOWN_REFERENCE", serialized.getJSONObject("error").getString("code"))
        assertEquals("none", serialized.getString("effect"))
        assertTrue(database.noteEventDao().findForCapture(captureId).isEmpty())
        assertTrue(database.transactionDao().findAll().isEmpty())
        assertEquals(0L, database.workflowOperationDao().countOf("UPDATE_EVENT"))

        val issue = serialized.getJSONObject("error").getJSONArray("issues").getJSONObject(0)
        assertEquals("$.capture_ref", issue.getString("path"))
        val expected = issue.getJSONObject("expected")
        assertEquals("capture", expected.getString("kind"))
        val issued = expected.getJSONArray("issued")
        assertTrue(issued.length() > 0)
        val entries = (0 until issued.length()).map { issued.getJSONObject(it) }
        assertTrue(entries.all { it.getString("kind") == "capture" })
        val handles = entries.map { it.getString("ref") }
        assertEquals(listOf(validCaptureRef), handles)
        assertFalse("the refused alias is never echoed as a candidate", handles.contains(wrong))
        handles.forEach { handle ->
            assertEquals(
                AgentReferenceKind.CAPTURE,
                registry.resolve(handle, AgentReferenceKind.CAPTURE, "$.capture_ref").kind,
            )
        }

        val masked = boundary.maskOutbound(
            serialized.toString(),
            io.github.triet4p.aegisf.privacy.MaskedPath.TOOL_RESULT,
        )
        assertTrue("issued handles survive the egress boundary verbatim", masked.contains(validCaptureRef))
        assertFalse(masked.contains(wrong))
        assertFalse(masked.contains("28,000"))

        // The deliberate recovery: copy the restated handle exactly and continue.
        val recovered = runtime.execute(
            "select_source",
            JSONObject().put("capture_ref", handles.single())
                .put("quote", maskedSource)
                .put("occurrence", 1),
            "read-only",
        ).envelope
        assertEquals("ok", recovered.getString("outcome"))
        assertTrue(
            recovered.getJSONObject("data").getString("source_ref")
                .matches(Regex("^r_[0-9a-f]{24}$")),
        )
    }

    @Test
    fun `an unissued observe alias lists the observable inventory and each allowed tool once`() = runBlocking {
        val runtime = runtimeFor(scope)
        val capture = observeCapture(runtime)
        val valid = capture.getString("ref")
        val wrong = valid.dropLast(1) + if (valid.last() == '0') '1' else '0'

        val refused = runtime.execute("observe", JSONObject().put("ref", wrong), "read-only").envelope
        val error = refused.getJSONObject("error")
        assertEquals("UNKNOWN_REFERENCE", error.getString("code"))
        assertEquals("none", refused.getString("effect"))
        val expected = error.getJSONArray("issues").getJSONObject(0).getJSONObject("expected")
        val kinds = (0 until expected.getJSONArray("kinds").length())
            .map { expected.getJSONArray("kinds").getString(it) }
        assertTrue(kinds.contains("capture"))
        val handles = (0 until expected.getJSONArray("issued").length())
            .map { expected.getJSONArray("issued").getJSONObject(it).getString("ref") }
        assertTrue(handles.contains(valid))
        assertFalse(handles.contains(wrong))
        // An alias refusal names the supported reads without repeating a tool name.
        val allowed = (0 until error.getJSONArray("allowed_tools").length())
            .map { error.getJSONArray("allowed_tools").getString(it) }
        assertEquals(listOf("observe"), allowed)
        assertTrue(database.noteEventDao().findForCapture(captureId).isEmpty())
    }

    @Test
    fun `the issued inventory keeps one newest handle per subject in issuance order`() {
        val registry = AgentReferenceRegistry("s9.9-inventory-order")
        registry.issue(
            AgentReference(
                handle = registry.nextHandle(),
                kind = AgentReferenceKind.DRAFT,
                scopeId = "s9.9-inventory-order",
                subjectId = 2L,
                draftId = 2L,
            ),
        )
        registry.issue(
            AgentReference(
                handle = registry.nextHandle(),
                kind = AgentReferenceKind.CAPTURE,
                scopeId = "s9.9-inventory-order",
                subjectId = 7L,
                captureId = 7L,
            ),
        )
        val newestCapture = registry.issue(
            AgentReference(
                handle = registry.nextHandle(),
                kind = AgentReferenceKind.CAPTURE,
                scopeId = "s9.9-inventory-order",
                subjectId = 7L,
                captureId = 7L,
            ),
        )
        val captures = registry.issuedFor(setOf(AgentReferenceKind.CAPTURE))
        assertEquals(
            listOf(newestCapture),
            (0 until captures.length()).map { captures.getJSONObject(it).getString("ref") },
        )
        val newestDraft = registry.issue(
            AgentReference(
                handle = registry.nextHandle(),
                kind = AgentReferenceKind.DRAFT,
                scopeId = "s9.9-inventory-order",
                subjectId = 2L,
                draftId = 2L,
            ),
        )
        val drafts = registry.issuedFor(setOf(AgentReferenceKind.DRAFT))
        assertEquals(
            listOf(newestDraft),
            (0 until drafts.length()).map { drafts.getJSONObject(it).getString("ref") },
        )
    }

    @Test
    fun `the issued inventory is bounded and never lists an evicted handle`() {
        val registry = AgentReferenceRegistry("s9.9-inventory-bound", maxEntries = 16)
        val handles = (1..20).map { index ->
            registry.issue(
                AgentReference(
                    handle = registry.nextHandle(),
                    kind = AgentReferenceKind.CAPTURE,
                    scopeId = "s9.9-inventory-bound",
                    subjectId = index.toLong(),
                    captureId = index.toLong(),
                ),
            )
        }
        val issued = registry.issuedFor(setOf(AgentReferenceKind.CAPTURE))
        assertEquals(AgentReferenceRegistry.MAX_INVENTORY_PER_KIND, issued.length())
        val listed = (0 until issued.length()).map { issued.getJSONObject(it).getString("ref") }
        assertFalse(listed.any { it in handles.take(4) })
        assertEquals(handles[4], listed.first())
        listed.forEach {
            assertEquals(
                AgentReferenceKind.CAPTURE,
                registry.resolve(it, AgentReferenceKind.CAPTURE, "$.ref").kind,
            )
        }
        assertEquals(0, registry.issuedFor(setOf(AgentReferenceKind.DRAFT)).length())
    }

    @Test
    fun `strict flat schema reports every known argument issue without dispatch`() = runBlocking {
        val runtime = runtimeFor(scope)
        val capture = observeCapture(runtime)
        val maskedSource = capture.getString("source_text")
        val source = selectSource(runtime, capture.getString("ref"), maskedSource)
        val invalid = JSONObject()
            .put("source_ref", source.getString("source_ref"))
            .put("summary", "Lunch")
            .put("anchor_ref", JSONObject.NULL)
            .put("merchant_ref", JSONObject.NULL)
            .put("clarification", "none")
            .put("extra", "not allowed")
        val result = runtime.execute("record_event", invalid, "operation-key").envelope
        assertEquals("error", result.getString("outcome"))
        assertEquals("not_dispatched", result.getString("dispatch"))
        val error = result.getJSONObject("error")
        assertEquals("SCHEMA_INVALID", error.getString("code"))
        val paths = (0 until error.getJSONArray("issues").length())
            .map { error.getJSONArray("issues").getJSONObject(it).getString("path") }
            .toSet()
        assertTrue(paths.containsAll(setOf("$.amount_ref", "$.extra")))
        assertTrue(database.noteEventDao().findForCapture(captureId).isEmpty())
        assertEquals(0L, database.workflowOperationDao().countOf("UPDATE_EVENT"))
    }

    @Test
    fun `stale source and a concurrent expected-absence conflict never overwrite`() = runBlocking {
        val runtime = runtimeFor(scope)
        val capture = observeCapture(runtime)
        val maskedSource = capture.getString("source_text")
        val source = selectSource(runtime, capture.getString("ref"), maskedSource)
        val amountRef = valueRef(source, "amount")
        val anchor = makeAnchor(runtime, valueRef(source, "date"), null)

        // The source text moves on after selection: the old binding is refused.
        val revised = commands.reviseCapture(
            WorkflowCallerScope.User,
            io.github.triet4p.aegisf.data.ReviseCaptureRequest(
                idempotencyKey = "revise-after-selection",
                captureId = captureId,
                expectedRevision = 0,
                bodyText = "Lunch 28,000 VND on 2026-10-21 at 12:30 (edited)",
            ),
        )
        assertTrue(revised is WorkflowCommandOutcome.Applied)
        val stale = record(runtime, source.getString("source_ref"), amountRef, anchor, "Lunch", "none")
        assertEquals("STALE_OBSERVATION", stale.getJSONObject("error").getString("code"))
        assertEquals("none", stale.getString("effect"))
        assertTrue(stale.getJSONArray("observations").length() >= 1)
        assertTrue(database.noteEventDao().findForCapture(captureId).isEmpty())
    }

    @Test
    fun `event appearing after selection is a stale absence rather than a silent replacement`() = runBlocking {
        val runtime = runtimeFor(scope)
        val capture = observeCapture(runtime)
        val maskedSource = capture.getString("source_text")
        val source = selectSource(runtime, capture.getString("ref"), maskedSource)

        // Another writer stores a reading at the same span after the source handle was issued.
        val competing = commands.updateEventInterpretation(
            WorkflowCallerScope.User,
            io.github.triet4p.aegisf.data.UpdateEventRequest(
                idempotencyKey = "competing-writer",
                captureId = captureId,
                expectedCaptureRevision = 0,
                expectedEventRevision = null,
                extractionVersion = "competing/v1",
                interpretation = io.github.triet4p.aegisf.data.NoteEventInterpretation(
                    spanStart = 0,
                    spanEnd = SOURCE_TEXT.length,
                    normalizedText = "Competing reading",
                    amountVnd = 28_000L,
                ),
            ),
        )
        assertTrue(competing is WorkflowCommandOutcome.Applied)

        val result = record(
            runtime,
            source.getString("source_ref"),
            valueRef(source, "amount"),
            makeAnchor(runtime, valueRef(source, "date"), null),
            "Lunch",
            "none",
        )
        assertEquals("STALE_OBSERVATION", result.getJSONObject("error").getString("code"))
        assertEquals("Competing reading", database.noteEventDao().findForCapture(captureId).single().normalizedText)
    }

    @Test
    fun `two occurrences in one capture become two distinct events`() = runBlocking {
        val second = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest("s8.13-pre-runtime-capture-two", TWO_TEXT),
        ) as WorkflowCommandOutcome.Applied
        val secondId = (second.change as WorkflowChange.CaptureSaved).captureId
        val multiScope = WorkflowCallerScope.AgentSession(
            scopeId = scope.scopeId,
            captureIds = setOf(captureId, secondId),
            draftIds = emptySet(),
        )
        val runtime = runtimeFor(multiScope)
        val capture = observeCapture(runtime, secondId)
        val maskedSource = capture.getString("source_text")
        val firstQuote = maskedSource.substringBefore(" | ").trim()
        val secondQuote = maskedSource.substringAfter(" | ").trim()
        val firstSource = selectSource(runtime, capture.getString("ref"), firstQuote)
        val secondSource = selectSource(runtime, capture.getString("ref"), secondQuote)
        assertNotEquals(firstSource.getString("source_ref"), secondSource.getString("source_ref"))

        val first = record(
            runtime, firstSource.getString("source_ref"), valueRef(firstSource, "amount"),
            makeAnchor(runtime, valueRef(firstSource, "date"), null), "Coffee", "none",
        )
        val two = record(
            runtime, secondSource.getString("source_ref"), valueRef(secondSource, "amount"),
            makeAnchor(runtime, valueRef(secondSource, "date"), null), "Taxi", "none",
        )
        assertEquals(first.toString(), "ok", first.getString("outcome"))
        assertEquals(two.toString(), "ok", two.getString("outcome"))
        val rows = database.noteEventDao().findForCapture(secondId)
        assertEquals(2, rows.size)
        assertEquals(setOf(15_000L, 40_000L), rows.mapNotNull { it.amountVnd }.toSet())
    }

    @Test
    fun `a stated merchant is recorded through an exact substring source handle`() = runBlocking {
        val merchantId = storeCapture("s8.13-pre-runtime-merchant", MERCHANT_TEXT)
        val runtime = runtimeFor(
            WorkflowCallerScope.AgentSession(scope.scopeId, setOf(captureId, merchantId), emptySet()),
        )
        val capture = observeCapture(runtime, merchantId)
        val maskedSource = capture.getString("source_text")
        val occurrence = selectSource(runtime, capture.getString("ref"), maskedSource.substringBefore(';').trim())
        val merchant = selectSource(runtime, capture.getString("ref"), "Highlands")
        assertNotEquals("the merchant needs its own handle", occurrence.getString("source_ref"), merchant.getString("source_ref"))
        assertEquals("Highlands", merchant.getString("quote"))

        val recorded = record(
            runtime, occurrence.getString("source_ref"), valueRef(occurrence, "amount"),
            makeAnchor(runtime, valueRef(occurrence, "date"), valueRef(occurrence, "time")),
            "Lunch at Highlands", "none", merchant.getString("source_ref"),
        )
        assertEquals(recorded.toString(), "ok", recorded.getString("outcome"))
        assertEquals("applied", recorded.getString("effect"))
        assertTrue(recorded.getBoolean("verified"))
        val stored = database.noteEventDao().findForCapture(merchantId).single()
        assertEquals("Highlands", stored.merchant)
        assertEquals(28_000L, stored.amountVnd)
        assertEquals("2026-10-21", stored.anchorDate)
        assertEquals("12:30", stored.anchorTime)
        assertNull(stored.unresolvedReason)
        assertTrue("a merchant selection is not a business write", database.transactionDao().findAll().isEmpty())
        assertEquals(1L, database.workflowOperationDao().countOf("UPDATE_EVENT"))
    }

    @Test
    fun `two occurrences keep their own merchant mapping without a third event`() = runBlocking {
        val multiId = storeCapture("s8.13-pre-runtime-merchant-multi", MERCHANT_TEXT)
        val runtime = runtimeFor(
            WorkflowCallerScope.AgentSession(scope.scopeId, setOf(captureId, multiId), emptySet()),
        )
        val capture = observeCapture(runtime, multiId)
        val maskedSource = capture.getString("source_text")
        val conversation = maskedSource.substringBefore(';').trim()
        val taxi = maskedSource.substringAfter(';').trim()
        val lunch = selectSource(runtime, capture.getString("ref"), conversation)
        val merchant = selectSource(runtime, capture.getString("ref"), "Highlands")
        val taxiSource = selectSource(runtime, capture.getString("ref"), taxi)

        val first = record(
            runtime, lunch.getString("source_ref"), valueRef(lunch, "amount"),
            makeAnchor(runtime, valueRef(lunch, "date"), valueRef(lunch, "time")),
            "Lunch at Highlands", "none", merchant.getString("source_ref"),
        )
        val second = record(
            runtime, taxiSource.getString("source_ref"), valueRef(taxiSource, "amount"),
            makeAnchor(runtime, valueRef(taxiSource, "date"), valueRef(taxiSource, "time")),
            "Taxi", "none",
        )
        assertEquals(first.toString(), "ok", first.getString("outcome"))
        assertEquals(second.toString(), "ok", second.getString("outcome"))
        val rows = database.noteEventDao().findForCapture(multiId).sortedBy { it.spanStart }
        assertEquals(2, rows.size)
        assertEquals("Highlands", rows[0].merchant)
        assertNull(rows[1].merchant)
        assertEquals(setOf(28_000L, 65_000L), rows.mapNotNull { it.amountVnd }.toSet())
        assertEquals(2L, database.workflowOperationDao().countOf("UPDATE_EVENT"))
        assertTrue(database.transactionDao().findAll().isEmpty())
    }

    @Test
    fun `a merchant handle outside the recorded occurrence or capture is refused`() = runBlocking {
        val merchantId = storeCapture("s8.13-pre-runtime-merchant-guard", MERCHANT_TEXT)
        val runtime = runtimeFor(
            WorkflowCallerScope.AgentSession(scope.scopeId, setOf(captureId, merchantId), emptySet()),
        )
        val capture = observeCapture(runtime, merchantId)
        val maskedSource = capture.getString("source_text")
        val lunch = selectSource(runtime, capture.getString("ref"), maskedSource.substringBefore(';').trim())
        val taxi = selectSource(runtime, capture.getString("ref"), maskedSource.substringAfter(';').trim())
        val merchant = selectSource(runtime, capture.getString("ref"), "Highlands")

        // The merchant span is inside the first occurrence, not the second one.
        val outOfSpan = record(
            runtime, taxi.getString("source_ref"), valueRef(taxi, "amount"),
            makeAnchor(runtime, valueRef(taxi, "date"), valueRef(taxi, "time")),
            "Taxi", "none", merchant.getString("source_ref"),
        )
        assertEquals("error", outOfSpan.getString("outcome"))
        assertEquals("REFERENCE_KIND_MISMATCH", outOfSpan.getJSONObject("error").getString("code"))
        assertEquals("none", outOfSpan.getString("effect"))
        assertEquals(
            "$.merchant_ref",
            outOfSpan.getJSONObject("error").getJSONArray("issues").getJSONObject(0).getString("path"),
        )
        assertTrue(database.noteEventDao().findForCapture(merchantId).isEmpty())

        // A handle issued for another capture never becomes merchant evidence here.
        val foreignCapture = observeCapture(runtime)
        val foreignSource = selectSource(runtime, foreignCapture.getString("ref"), foreignCapture.getString("source_text"))
        val foreignMerchant = record(
            runtime, foreignSource.getString("source_ref"), valueRef(foreignSource, "amount"),
            makeAnchor(runtime, valueRef(foreignSource, "date"), null), "Lunch", "none",
            merchant.getString("source_ref"),
        )
        assertEquals("error", foreignMerchant.getString("outcome"))
        assertEquals("REFERENCE_KIND_MISMATCH", foreignMerchant.getJSONObject("error").getString("code"))
        assertEquals("none", foreignMerchant.getString("effect"))
        assertTrue(database.noteEventDao().findForCapture(merchantId).isEmpty())
        assertTrue(database.noteEventDao().findForCapture(captureId).isEmpty())
        assertEquals(0L, database.workflowOperationDao().countOf("UPDATE_EVENT"))
    }

    @Test
    fun `partial reading plus linked clarification resumes and replaces the value`() = runBlocking {
        val taxiCapture = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest("s8.13-pre-runtime-taxi", "Taxi ride on 2026-10-21"),
        ) as WorkflowCommandOutcome.Applied
        val taxiId = (taxiCapture.change as WorkflowChange.CaptureSaved).captureId
        val widened = WorkflowCallerScope.AgentSession(
            scopeId = scope.scopeId,
            captureIds = setOf(captureId, taxiId),
            draftIds = emptySet(),
        )
        val runtime = runtimeFor(widened)
        val capture = observeCapture(runtime, taxiId)
        val maskedSource = capture.getString("source_text")
        val source = selectSource(runtime, capture.getString("ref"), maskedSource)
        val anchor = makeAnchor(runtime, valueRef(source, "date"), null)
        val partial = record(runtime, source.getString("source_ref"), null, anchor, "Taxi ride", "amount")
        assertEquals(partial.toString(), "ok", partial.getString("outcome"))
        assertEquals("applied", partial.getString("effect"))
        assertEquals("amount", partial.getJSONObject("data").getString("clarification"))
        assertTrue(partial.getJSONObject("data").getBoolean("clarification_pending"))
        val partialStored = database.noteEventDao().findForCapture(taxiId).single()
        assertNull(partialStored.amountVnd)
        assertEquals("UNRESOLVED", partialStored.status.name)
        assertEquals("Clarification required: amount", partialStored.unresolvedReason)

        val ask = runtime.execute(
            "ask_user",
            JSONObject()
                .put("subject_ref", source.getString("source_ref"))
                .put("field", "amount")
                .put("question", "What was the amount for the taxi ride?"),
            "read-only",
        ).envelope
        assertEquals("clarifying", ask.getString("outcome"))
        assertEquals("await_user", ask.getJSONObject("control").getString("action"))

        val evidence = runtime.registerClarification(
            subjectRef = source.getString("source_ref"),
            field = "amount",
            userAnswer = "25000",
        )
        assertEquals("clarification", evidence.getString("kind"))
        assertEquals("numeric", evidence.getString("value_shape"))
        assertFalse("the answer must mint a neutral value alias", evidence.isNull("value_ref"))
        val amountRef = evidence.getString("value_ref")

        val fresh = observeCapture(runtime, taxiId)
        val freshSource = selectSource(runtime, fresh.getString("ref"), maskedSource)
        val corrected = record(runtime, freshSource.getString("source_ref"), amountRef, anchor, "Taxi ride", "none")
        assertEquals(corrected.toString(), "ok", corrected.getString("outcome"))
        val stored = database.noteEventDao().findForCapture(taxiId).single()
        assertEquals(25_000L, stored.amountVnd)
        assertNull(stored.unresolvedReason)
        assertEquals("INTERPRETED", stored.status.name)
    }

    @Test
    fun `clarification-supplied day grounds the selected unresolved occurrence`() = runBlocking {
        val dayId = storeCapture(
            "s9.9-clarification-day",
            "On 2026-10-21: fuel 50,000 VND at 15:00 and snack 20,000 VND at 15:15",
        )
        val widened = WorkflowCallerScope.AgentSession(
            scopeId = scope.scopeId,
            captureIds = setOf(captureId, dayId),
            draftIds = emptySet(),
        )
        val runtime = runtimeFor(widened)
        val capture = observeCapture(runtime, dayId)
        val masked = capture.getString("source_text")
        val fuelSource = selectSource(runtime, capture.getString("ref"), masked.substringBefore(" and "))
        val fuel = record(
            runtime,
            fuelSource.getString("source_ref"),
            valueRef(fuelSource, "amount"),
            makeAnchor(runtime, valueRef(fuelSource, "date"), valueRef(fuelSource, "time")),
            "Fuel purchase",
            "none",
        )
        assertEquals(fuel.toString(), "ok", fuel.getString("outcome"))

        // The snack clause states neither day nor year, so the reading is stored as a grounded
        // partial that names its clarification need instead of guessing a date.
        val snackSource = selectSource(runtime, capture.getString("ref"), masked.substringAfter(" and "))
        val partial = record(
            runtime,
            snackSource.getString("source_ref"),
            valueRef(snackSource, "amount"),
            null,
            "Snack purchase",
            "date",
        )
        assertEquals(partial.toString(), "ok", partial.getString("outcome"))
        val snackEventRef = partial.getJSONObject("data").getJSONObject("event").getString("ref")

        val ask = runtime.execute(
            "ask_user",
            JSONObject()
                .put("subject_ref", snackEventRef)
                .put("field", "date")
                .put("question", "What calendar date was the snack purchase made?"),
            "read-only",
        ).envelope
        assertEquals("clarifying", ask.getString("outcome"))

        val evidence = runtime.registerClarification(
            subjectRef = snackEventRef,
            field = "date",
            userAnswer = "2026-10-21",
        )
        assertEquals("clarification", evidence.getString("kind"))
        assertFalse("the answer must mint a neutral value alias", evidence.isNull("value_ref"))
        val dayRef = evidence.getString("value_ref")

        // The same occurrence is re-read and now carries the unresolved reading the answer belongs
        // to; its own time plus the clarified day must form one legal anchor for that reading.
        val fresh = observeCapture(runtime, dayId)
        val freshSource = selectSource(runtime, fresh.getString("ref"), masked.substringAfter(" and "))
        val dateOnly = makeAnchor(runtime, dayRef, null)
        val anchor = makeAnchor(runtime, dayRef, valueRef(freshSource, "time"))
        val corrected = record(
            runtime,
            freshSource.getString("source_ref"),
            valueRef(freshSource, "amount"),
            anchor,
            "Snack purchase",
            "none",
        )
        assertEquals(corrected.toString(), "ok", corrected.getString("outcome"))
        assertEquals("applied", corrected.getString("effect"))
        val grounded = database.noteEventDao().findForCapture(dayId)
            .single { it.amountVnd == 20_000L && it.unresolvedReason == null }
        assertFalse("the clarified day must be stored", grounded.anchorDate.isNullOrBlank())
        assertEquals("INTERPRETED", grounded.status.name)

        // Provenance stays closed: the snack answer cannot label another reading, whether it is
        // mixed with that reading's temporal value or used as a date-only anchor.
        val foreignMix = makeIntervalAnchor(runtime, dayRef, valueRef(fuelSource, "time"), null, null)
        assertEquals("error", foreignMix.getString("outcome"))
        assertEquals(
            "REFERENCE_KIND_MISMATCH",
            foreignMix.getJSONObject("error").getString("code"),
        )
        val fuelAgain = observeCapture(runtime, dayId)
        val fuelFresh = selectSource(runtime, fuelAgain.getString("ref"), masked.substringBefore(" and "))
        val foreignRecord = record(
            runtime,
            fuelFresh.getString("source_ref"),
            valueRef(fuelFresh, "amount"),
            dateOnly,
            "Fuel purchase",
            "none",
        )
        assertEquals("error", foreignRecord.getString("outcome"))
        assertEquals(
            "REFERENCE_KIND_MISMATCH",
            foreignRecord.getJSONObject("error").getString("code"),
        )
        assertEquals(
            "anchor_from_selected_occurrence",
            foreignRecord.getJSONObject("error").getJSONArray("issues").getJSONObject(0)
                .getString("constraint"),
        )
    }

    @Test
    fun `eligible link proposes attaches and an exact replay is unchanged`() = runBlocking {
        val recordingRegistry = AgentReferenceRegistry(scope.scopeId)
        val recordingRuntime = runtimeFor(scope, recordingRegistry)
        val capture = observeCapture(recordingRuntime)
        val maskedSource = capture.getString("source_text")
        val source = selectSource(recordingRuntime, capture.getString("ref"), maskedSource)
        val recorded = record(
            recordingRuntime, source.getString("source_ref"), valueRef(source, "amount"),
            makeAnchor(recordingRuntime, valueRef(source, "date"), null), "Lunch", "none",
        )
        val event = recorded.getJSONObject("data").getJSONObject("event")
        // Keep this manual tool-policy scenario separate from the production arrival smoke.
        val draftId = storeDraft(amount = 28_000L, date = "2026-10-21")
        val toolScope = WorkflowCallerScope.AgentSession(
            scope.scopeId,
            scope.captureIds,
            setOf(draftId),
        )
        val runtime = runtimeFor(toolScope, recordingRegistry)
        val draft = draftPage(runtime, draftId)

        val inspected = runtime.execute(
            "inspect_link",
            JSONObject().put("event_ref", event.getString("ref")).put("draft_ref", draft.getString("ref")),
            "read-only",
        ).envelope
        assertEquals(inspected.toString(), "ok", inspected.getString("outcome"))
        assertEquals("eligible", inspected.getJSONObject("data").getString("eligibility"))
        val pairRef = inspected.getJSONObject("data").getString("pair_ref")

        val proposed = runtime.execute(
            "propose_link", JSONObject().put("pair_ref", pairRef), "propose-key",
        ).envelope
        assertEquals(proposed.toString(), "ok", proposed.getString("outcome"))
        assertEquals("PROPOSED", proposed.getJSONObject("data").getJSONObject("link").getString("state"))
        val proposalRef = proposed.getJSONObject("data").getString("proposal_ref")

        val attached = runtime.execute(
            "attach_link", JSONObject().put("proposal_ref", proposalRef), "attach-key",
        ).envelope
        assertEquals(attached.toString(), "ok", attached.getString("outcome"))
        assertEquals("ACCEPTED", attached.getJSONObject("data").getJSONObject("link").getString("state"))
        val storedEvent = database.noteEventDao().findForCapture(captureId).single()
        val pair = queries.linkOperations(
            LinkOperationQuery(eventId = storedEvent.id, draftId = draftId, bounds = QueryPageRequest(1, 0)),
        ).items.single()
        assertEquals(ContextLinkState.ACCEPTED, pair.state)
        assertTrue(database.transactionDao().findAll().isEmpty())

        val operationRef = recorded.getString("operation_ref")
        val replay = runtime.execute(
            "replay_operation", JSONObject().put("operation_ref", operationRef), "read-only",
        ).envelope
        assertEquals(replay.toString(), "ok", replay.getString("outcome"))
        assertEquals("unchanged", replay.getString("effect"))
        assertTrue(replay.getBoolean("verified"))
        assertEquals("no_change", replay.getJSONObject("data").getString("journal_outcome"))
        assertEquals(1, database.noteEventDao().findForCapture(captureId).size)
        assertEquals(1L, database.workflowOperationDao().countOf("UPDATE_EVENT"))
        assertFalse(event.getString("ref").isBlank())
    }

    @Test
    fun `observations carry the application row labels a user-visible question must copy`() = runBlocking {
        val recordingRegistry = AgentReferenceRegistry(scope.scopeId)
        val recordingRuntime = runtimeFor(scope, recordingRegistry)
        val capture = observeCapture(recordingRuntime)
        val source = selectSource(recordingRuntime, capture.getString("ref"), capture.getString("source_text"))
        val recorded = record(
            recordingRuntime, source.getString("source_ref"), valueRef(source, "amount"),
            makeAnchor(recordingRuntime, valueRef(source, "date"), null), "Lunch", "none",
        )
        val event = recorded.getJSONObject("data").getJSONObject("event")
        val storedEventId = database.noteEventDao().findForCapture(captureId).single().id
        assertEquals("event #$storedEventId", event.getString("user_label"))
        val draftId = storeDraft(amount = 28_000L, date = "2026-10-21")
        val toolScope = WorkflowCallerScope.AgentSession(
            scope.scopeId,
            scope.captureIds,
            setOf(draftId),
        )
        val runtime = runtimeFor(toolScope, recordingRegistry)
        val draft = draftPage(runtime, draftId)
        assertEquals("draft #$draftId", draft.getString("user_label"))

        val inspected = runtime.execute(
            "inspect_link",
            JSONObject().put("event_ref", event.getString("ref")).put("draft_ref", draft.getString("ref")),
            "read-only",
        ).envelope
        val pairObservation = inspected.getJSONArray("observations").getJSONObject(0)
        assertEquals("event #$storedEventId", pairObservation.getString("event_label"))
        assertEquals("draft #$draftId", pairObservation.getString("draft_label"))

        val proposed = runtime.execute(
            "propose_link",
            JSONObject().put("pair_ref", inspected.getJSONObject("data").getString("pair_ref")),
            "propose-key",
        ).envelope
        assertEquals("PROPOSED", proposed.getJSONObject("data").getJSONObject("link").getString("state"))
        val proposedPair = proposed.getJSONObject("data").getJSONObject("pair")
        assertEquals("draft #$draftId", proposedPair.getString("draft_label"))
        assertEquals("event #$storedEventId", proposedPair.getString("event_label"))
    }

    @Test
    fun `ineligible and stale link pairs are never attached`() = runBlocking {
        val draftId = storeDraft(amount = 99_000L, date = "2026-10-21")
        val runtime = runtimeFor(
            WorkflowCallerScope.AgentSession(scope.scopeId, scope.captureIds, setOf(draftId)),
        )
        val capture = observeCapture(runtime)
        val maskedSource = capture.getString("source_text")
        val source = selectSource(runtime, capture.getString("ref"), maskedSource)
        val event = record(
            runtime, source.getString("source_ref"), valueRef(source, "amount"),
            makeAnchor(runtime, valueRef(source, "date"), null), "Lunch", "none",
        ).getJSONObject("data").getJSONObject("event")
        val storedEvent = database.noteEventDao().findForCapture(captureId).single()
        val draft = draftPage(runtime, draftId)

        val inspected = runtime.execute(
            "inspect_link",
            JSONObject().put("event_ref", event.getString("ref")).put("draft_ref", draft.getString("ref")),
            "read-only",
        ).envelope
        assertEquals("ineligible", inspected.getJSONObject("data").getString("eligibility"))
        assertEquals("AMOUNT_MISMATCH", inspected.getJSONObject("data").getString("reason"))
        val pairRef = inspected.getJSONObject("data").getString("pair_ref")
        val proposed = runtime.execute(
            "propose_link", JSONObject().put("pair_ref", pairRef), "propose-ineligible",
        ).envelope
        assertEquals(proposed.toString(), "ok", proposed.getString("outcome"))
        val proposalRef = proposed.getJSONObject("data").getString("proposal_ref")
        val denied = runtime.execute(
            "attach_link", JSONObject().put("proposal_ref", proposalRef), "attach-ineligible",
        ).envelope
        assertEquals("error", denied.getString("outcome"))
        assertEquals("LINK_INELIGIBLE", denied.getJSONObject("error").getString("code"))
        assertEquals(ContextLinkState.PROPOSED, queries.linkOperations(
            LinkOperationQuery(eventId = storedEvent.id, draftId = draftId, bounds = QueryPageRequest(1, 0)),
        ).items.single().state)

        val rejected = commands.rejectLink(
            WorkflowCallerScope.User,
            RejectLinkRequest("reject-after-proposal", storedEvent.id, draftId, 0),
        )
        assertTrue(rejected is WorkflowCommandOutcome.Applied)
        val stale = runtime.execute(
            "attach_link", JSONObject().put("proposal_ref", proposalRef), "attach-stale",
        ).envelope
        assertEquals("STALE_OBSERVATION", stale.getJSONObject("error").getString("code"))
    }

    @Test
    fun `replay after a later correction reports the historical effect as not current`() = runBlocking {
        val runtime = runtimeFor(scope)
        val capture = observeCapture(runtime)
        val maskedSource = capture.getString("source_text")
        val source = selectSource(runtime, capture.getString("ref"), maskedSource)
        val first = record(
            runtime, source.getString("source_ref"), valueRef(source, "amount"),
            makeAnchor(runtime, valueRef(source, "date"), null), "Lunch", "none",
        )
        val operationRef = first.getString("operation_ref")

        val fresh = observeCapture(runtime)
        val freshSource = selectSource(runtime, fresh.getString("ref"), maskedSource)
        val corrected = record(
            runtime, freshSource.getString("source_ref"), valueRef(freshSource, "amount"),
            makeAnchor(runtime, valueRef(freshSource, "date"), null), "Lunch with a colleague", "none",
        )
        assertEquals(corrected.toString(), "ok", corrected.getString("outcome"))
        assertEquals(1, database.noteEventDao().findForCapture(captureId).single().revision)

        val replay = runtime.execute(
            "replay_operation", JSONObject().put("operation_ref", operationRef), "read-only",
        ).envelope
        assertEquals(replay.toString(), "ok", replay.getString("outcome"))
        assertEquals("unchanged", replay.getString("effect"))
        assertFalse(replay.getBoolean("verified"))
        assertFalse(replay.getJSONObject("data").getBoolean("current_effect_matches"))
    }

    @Test
    fun `a restarted host re-establishes operation receipts from the journal`() = runBlocking {
        val runtime = runtimeFor(scope)
        val capture = observeCapture(runtime)
        val source = selectSource(runtime, capture.getString("ref"), capture.getString("source_text"))
        record(
            runtime, source.getString("source_ref"), valueRef(source, "amount"),
            makeAnchor(runtime, valueRef(source, "date"), null), "Lunch", "none",
        )

        // A new runtime has an empty registry: only the journal can re-establish an alias.
        val restarted = runtimeFor(scope)
        val receipts = restarted.initialOperationObservations(4)
        assertEquals(1, receipts.size)
        val operationRef = receipts.single().getString("ref")
        assertEquals("APPLIED", receipts.single().getString("journal_outcome"))
        val replay = restarted.execute(
            "replay_operation", JSONObject().put("operation_ref", operationRef), "read-only",
        ).envelope
        assertEquals(replay.toString(), "ok", replay.getString("outcome"))
        assertTrue(replay.getBoolean("verified"))
        assertEquals(1, database.noteEventDao().findForCapture(captureId).size)
    }

    @Test
    fun `bounded pages refuse a cursor reused with different filters`() = runBlocking {
        val drafts = (1..12).map { index -> storeDraft(amount = 1_000L * index, date = "2026-10-21") }
        val runtime = runtimeFor(
            WorkflowCallerScope.AgentSession(scope.scopeId, scope.captureIds, drafts.toSet()),
        )
        val first = runtime.execute(
            "list_drafts",
            JSONObject().put("review_status", "PENDING").put("duplicates_only", false).put("cursor", JSONObject.NULL),
            "read-only",
        ).envelope.getJSONObject("data")
        assertEquals(10, first.getJSONArray("items").length())
        assertFalse(first.getBoolean("scope_exhausted"))
        val nextCursor = first.getString("next_cursor")
        assertTrue(nextCursor.startsWith("r_"))

        val mismatched = runtime.execute(
            "list_drafts",
            JSONObject().put("review_status", "ALL").put("duplicates_only", false).put("cursor", nextCursor),
            "read-only",
        ).envelope
        assertEquals("CURSOR_STALE", mismatched.getJSONObject("error").getString("code"))

        val second = runtime.execute(
            "list_drafts",
            JSONObject().put("review_status", "PENDING").put("duplicates_only", false).put("cursor", nextCursor),
            "read-only",
        ).envelope.getJSONObject("data")
        assertEquals(2, second.getJSONArray("items").length())
        assertTrue(second.getBoolean("scope_exhausted"))
    }

    @Test
    fun `event draft retrieval ranks parsed evidence honors corrections and excludes terminal rows`() = runBlocking {
        val eventCaptureId = storeCapture("s9.2-merchant-event", MERCHANT_TEXT)
        val receiptAfterMidnight = LocalDateTime.parse("2026-10-22T00:15")
            .atZone(ZoneId.of("Asia/Ho_Chi_Minh"))
            .toInstant()
            .toEpochMilli()
        val delayed = storeDraft(
            amount = 28_000L,
            date = "2026-10-21",
            time = "12:29",
            memo = "Highlands Coffee",
            postedAtEpochMillis = receiptAfterMidnight,
        )
        val nearby = storeDraft(
            amount = 28_000L,
            date = "2026-10-21",
            time = "12:35",
            memo = "Highlands Market",
        )
        val corrected = storeDraft(
            amount = 99_000L,
            date = "2026-10-22",
            time = "20:00",
            memo = "Circle K",
            direction = IntakeDirection.CREDIT,
        )
        assertEquals(
            BankDraftReviewResult.Saved(1),
            database.bankDraftReviewDao().saveReview(
                corrected,
                expectedRevision = 0,
                edits = BankDraftReviewEdits(
                    amountText = "28000",
                    dateText = "2026-10-21",
                    timeText = "12:32",
                    memoText = "Highlands Shop",
                    type = TransactionType.EXPENSE,
                    categoryId = null,
                    categoryResolved = true,
                ),
                categoryOrigin = ReviewCategoryOrigin.USER,
            ),
        )
        val missing = storeDraft(
            amount = null,
            date = null,
            time = null,
            memo = "Highlands purchase with missing amount and time",
        )
        val tied = storeDraft(
            amount = 28_000L,
            date = "2026-10-21",
            time = "12:29",
            memo = "Highlands Cafe",
        )
        val wrongAmount = storeDraft(
            amount = 29_000L,
            date = "2026-10-21",
            time = "12:30",
            memo = "Highlands Coffee",
        )
        val wrongDate = storeDraft(
            amount = 28_000L,
            date = "2026-10-22",
            time = "12:30",
            memo = "Highlands Coffee",
        )
        val wrongMerchant = storeDraft(
            amount = 28_000L,
            date = "2026-10-21",
            time = "12:30",
            memo = "Circle K",
        )
        val confirmed = storeDraft(
            amount = 28_000L,
            date = "2026-10-21",
            time = "12:30",
            memo = "Highlands confirmed",
        )
        val confirmation = database.bankDraftReviewDao().confirm(
            id = confirmed,
            expectedRevision = 0,
            edits = BankDraftReviewEdits(
                amountText = "28000",
                dateText = "2026-10-21",
                timeText = "12:30",
                memoText = "Highlands confirmed",
                type = TransactionType.EXPENSE,
                categoryId = null,
                categoryResolved = true,
            ),
            transaction = TransactionEntity(
                type = TransactionType.EXPENSE,
                amountVnd = 28_000L,
                occurredAtEpochMillis = receiptAfterMidnight,
                description = "Highlands confirmed",
                createdAtEpochMillis = receiptAfterMidnight,
                updatedAtEpochMillis = receiptAfterMidnight,
            ),
            categoryOrigin = ReviewCategoryOrigin.USER,
        )
        assertTrue(confirmation is BankDraftReviewResult.Confirmed)
        val dismissed = storeDraft(
            amount = 28_000L,
            date = "2026-10-21",
            time = "12:30",
            memo = "Highlands dismissed",
        )
        assertEquals(BankDraftReviewResult.Dismissed, database.bankDraftReviewDao().dismiss(dismissed))

        val draftIds = setOf(
            delayed, nearby, corrected, missing, tied, wrongAmount, wrongDate, wrongMerchant,
            confirmed, dismissed,
        )
        val runtime = runtimeFor(
            WorkflowCallerScope.AgentSession(scope.scopeId, setOf(eventCaptureId), draftIds),
        )
        val capture = observeCapture(runtime, eventCaptureId)
        val occurrence = selectSource(
            runtime,
            capture.getString("ref"),
            capture.getString("source_text").substringBefore(';').trim(),
        )
        val merchant = selectSource(runtime, capture.getString("ref"), "Highlands")
        val anchor = makeAnchor(
            runtime,
            valueRef(occurrence, "date"),
            valueRef(occurrence, "time"),
        )
        val recorded = record(
            runtime,
            occurrence.getString("source_ref"),
            valueRef(occurrence, "amount"),
            anchor,
            "Lunch purchase",
            "none",
            merchantRef = merchant.getString("source_ref"),
        )
        assertEquals("ok", recorded.getString("outcome"))
        val eventRef = recorded.getJSONObject("data").getJSONObject("event").getString("ref")
        val arguments = JSONObject()
            .put("event_ref", eventRef)
            .put("review_status", "PENDING")
            .put("duplicates_only", false)
            .put("cursor", JSONObject.NULL)

        val first = runtime.execute("list_drafts", arguments, "read-only").envelope
            .getJSONObject("data")
        val match = first.getJSONObject("match")
        assertEquals("ambiguous", match.getString("outcome"))
        assertEquals(5, match.getInt("candidate_count"))
        assertEquals(3, match.getInt("conflicting_count"))
        assertTrue(match.getBoolean("candidate_set_complete_within_granted_scope"))
        assertTrue(match.getBoolean("candidate_page_complete"))
        assertTrue(match.getBoolean("pending_database_complete_at_recheck"))
        assertFalse(match.getBoolean("receipt_time_used"))
        assertEquals(
            setOf("account", "transaction_type"),
            (0 until match.getJSONArray("unknown_dimensions").length())
                .map { match.getJSONArray("unknown_dimensions").getString(it) }
                .toSet(),
        )

        val items = first.getJSONArray("items")
        val labels = (0 until items.length()).map { items.getJSONObject(it).getString("user_label") }
        assertEquals(
            listOf(tied, delayed, corrected, nearby, missing).map { "draft #$it" },
            labels,
        )
        val excludedLabels = listOf(wrongAmount, wrongDate, wrongMerchant, confirmed, dismissed)
            .map { "draft #$it" }
            .toSet()
        assertTrue(labels.none { it in excludedLabels })
        assertTrue(first.getBoolean("scope_exhausted"))
        val correctedEvidence = (0 until items.length())
            .map { items.getJSONObject(it) }
            .single { it.getString("user_label") == "draft #$corrected" }
            .getJSONObject("match_evidence")
        assertEquals("match", correctedEvidence.getString("amount"))
        assertEquals("match", correctedEvidence.getString("transaction_time_compatibility"))
        assertEquals("match", correctedEvidence.getString("merchant"))

        val repeated = runtime.execute("list_drafts", arguments, "read-only").envelope
            .getJSONObject("data")
            .getJSONArray("items")
        assertEquals(
            labels,
            (0 until repeated.length()).map { repeated.getJSONObject(it).getString("user_label") },
        )
        assertEquals("CREDIT", database.pendingBankDraftDao().findById(corrected)!!.direction.name)
    }

    @Test
    fun `missing event merchant leaves equal amount nearby purchases ambiguous`() = runBlocking {
        val receiptAfterMidnight = LocalDateTime.parse("2026-10-22T00:15")
            .atZone(ZoneId.of("Asia/Ho_Chi_Minh"))
            .toInstant()
            .toEpochMilli()
        val closer = storeDraft(
            amount = 28_000L,
            date = "2026-10-21",
            time = "12:29",
            memo = "Highlands Coffee",
            postedAtEpochMillis = receiptAfterMidnight,
        )
        val farther = storeDraft(
            amount = 28_000L,
            date = "2026-10-21",
            time = "12:35",
            memo = "Circle K",
            postedAtEpochMillis = receiptAfterMidnight,
        )
        val runtime = runtimeFor(
            WorkflowCallerScope.AgentSession(scope.scopeId, scope.captureIds, setOf(closer, farther)),
        )
        val capture = observeCapture(runtime)
        val source = selectSource(runtime, capture.getString("ref"), capture.getString("source_text"))
        val recorded = record(
            runtime,
            source.getString("source_ref"),
            valueRef(source, "amount"),
            makeAnchor(runtime, valueRef(source, "date"), valueRef(source, "time")),
            "Lunch purchase",
            "none",
        )
        val eventRef = recorded.getJSONObject("data").getJSONObject("event").getString("ref")
        val data = runtime.execute(
            "list_drafts",
            JSONObject()
                .put("event_ref", eventRef)
                .put("review_status", "PENDING")
                .put("duplicates_only", false)
                .put("cursor", JSONObject.NULL),
            "read-only",
        ).envelope.getJSONObject("data")
        val match = data.getJSONObject("match")
        assertEquals("ambiguous", match.getString("outcome"))
        assertEquals(2, match.getInt("candidate_count"))
        assertTrue(match.getBoolean("pending_database_complete_at_recheck"))
        assertFalse(match.getBoolean("receipt_time_used"))
        assertTrue(data.getBoolean("scope_exhausted"))

        val items = data.getJSONArray("items")
        val labels = (0 until items.length())
            .map { items.getJSONObject(it).getString("user_label") }
        assertEquals(listOf("draft #$closer", "draft #$farther"), labels)
        for (index in 0 until items.length()) {
            val evidence = items.getJSONObject(index).getJSONObject("match_evidence")
            assertEquals("match", evidence.getString("amount"))
            assertEquals("match", evidence.getString("transaction_time_compatibility"))
            assertEquals("missing", evidence.getString("merchant"))
            assertTrue(evidence.getBoolean("time_proximity_ordering_only"))
        }
        val repeated = runtime.execute(
            "list_drafts",
            JSONObject()
                .put("event_ref", eventRef)
                .put("review_status", "PENDING")
                .put("duplicates_only", false)
                .put("cursor", JSONObject.NULL),
            "read-only",
        ).envelope.getJSONObject("data").getJSONArray("items")
        assertEquals(
            labels,
            (0 until repeated.length()).map { repeated.getJSONObject(it).getString("user_label") },
        )
    }


    @Test
    fun `event candidate pagination is stable and cursor-bound to the event`() = runBlocking {
        val eventCaptureId = storeCapture("s9.2-page-events", TWO_TEXT)
        val draftIds = (1..12).map { index ->
            storeDraft(
                amount = 15_000L,
                date = "2026-10-21",
                memo = "Page purchase $index",
            )
        }
        val runtime = runtimeFor(
            WorkflowCallerScope.AgentSession(scope.scopeId, setOf(eventCaptureId), draftIds.toSet()),
        )
        val capture = observeCapture(runtime, eventCaptureId)
        val maskedSource = capture.getString("source_text")
        val firstSource = selectSource(
            runtime,
            capture.getString("ref"),
            maskedSource.substringBefore(" | ").trim(),
        )
        val secondSource = selectSource(
            runtime,
            capture.getString("ref"),
            maskedSource.substringAfter(" | ").trim(),
        )
        val firstEvent = record(
            runtime,
            firstSource.getString("source_ref"),
            valueRef(firstSource, "amount"),
            makeAnchor(runtime, valueRef(firstSource, "date"), null),
            "Coffee purchase",
            "none",
        ).getJSONObject("data").getJSONObject("event")
        val secondEvent = record(
            runtime,
            secondSource.getString("source_ref"),
            valueRef(secondSource, "amount"),
            makeAnchor(runtime, valueRef(secondSource, "date"), null),
            "Taxi purchase",
            "none",
        ).getJSONObject("data").getJSONObject("event")

        val firstPage = runtime.execute(
            "list_drafts",
            JSONObject()
                .put("event_ref", firstEvent.getString("ref"))
                .put("review_status", "PENDING")
                .put("duplicates_only", false)
                .put("cursor", JSONObject.NULL),
            "read-only",
        ).envelope.getJSONObject("data")
        assertEquals("ambiguous", firstPage.getJSONObject("match").getString("outcome"))
        assertEquals(12, firstPage.getJSONObject("match").getInt("candidate_count"))
        assertEquals(10, firstPage.getJSONArray("items").length())
        val firstPageMatch = firstPage.getJSONObject("match")
        assertTrue(firstPageMatch.getBoolean("candidate_set_complete_within_granted_scope"))
        assertFalse(firstPageMatch.getBoolean("candidate_page_complete"))
        assertTrue(firstPageMatch.getBoolean("pending_database_complete_at_recheck"))
        val cursor = firstPage.getString("next_cursor")

        val secondPage = runtime.execute(
            "list_drafts",
            JSONObject()
                .put("event_ref", firstEvent.getString("ref"))
                .put("review_status", "PENDING")
                .put("duplicates_only", false)
                .put("cursor", cursor),
            "read-only",
        ).envelope.getJSONObject("data")
        assertEquals(2, secondPage.getJSONArray("items").length())
        assertTrue(secondPage.getJSONObject("match").getBoolean("candidate_page_complete"))
        assertTrue(secondPage.getBoolean("scope_exhausted"))
        val expectedLabels = draftIds.sortedDescending().map { "draft #$it" }
        assertEquals(
            expectedLabels.take(10),
            (0 until firstPage.getJSONArray("items").length())
                .map { firstPage.getJSONArray("items").getJSONObject(it).getString("user_label") },
        )
        assertEquals(
            expectedLabels.drop(10),
            (0 until secondPage.getJSONArray("items").length())
                .map { secondPage.getJSONArray("items").getJSONObject(it).getString("user_label") },
        )

        val stale = runtime.execute(
            "list_drafts",
            JSONObject()
                .put("event_ref", secondEvent.getString("ref"))
                .put("review_status", "PENDING")
                .put("duplicates_only", false)
                .put("cursor", cursor),
            "read-only",
        ).envelope
        assertEquals("CURSOR_STALE", stale.getJSONObject("error").getString("code"))
    }


    @Test
    fun `missing draft evidence stays missing instead of becoming unique or conflicting`() = runBlocking {
        val missing = storeDraft(amount = null, date = null, time = null, memo = null)
        val runtime = runtimeFor(
            WorkflowCallerScope.AgentSession(scope.scopeId, scope.captureIds, setOf(missing)),
        )
        val capture = observeCapture(runtime)
        val source = selectSource(runtime, capture.getString("ref"), capture.getString("source_text"))
        val recorded = record(
            runtime,
            source.getString("source_ref"),
            valueRef(source, "amount"),
            makeAnchor(runtime, valueRef(source, "date"), valueRef(source, "time")),
            "Lunch purchase",
            "none",
        )
        val eventRef = recorded.getJSONObject("data").getJSONObject("event").getString("ref")
        val data = runtime.execute(
            "list_drafts",
            JSONObject()
                .put("event_ref", eventRef)
                .put("review_status", "PENDING")
                .put("duplicates_only", false)
                .put("cursor", JSONObject.NULL),
            "read-only",
        ).envelope.getJSONObject("data")

        assertEquals("missing", data.getJSONObject("match").getString("outcome"))
        assertEquals(1, data.getJSONObject("match").getInt("candidate_count"))
        assertEquals(0, data.getJSONObject("match").getInt("conflicting_count"))
        val evidence = data.getJSONArray("items").getJSONObject(0).getJSONObject("match_evidence")
        assertEquals("missing", evidence.getString("amount"))
        assertEquals("missing", evidence.getString("transaction_time_compatibility"))
        assertTrue(data.getBoolean("scope_exhausted"))
    }

    @Test
    fun `conflicting outcome requires known incompatible evidence in a complete scope`() = runBlocking {
        val wrongAmount = storeDraft(
            amount = 29_000L,
            date = "2026-10-21",
            time = "12:30",
            memo = "Synthetic purchase",
        )
        val runtime = runtimeFor(
            WorkflowCallerScope.AgentSession(scope.scopeId, scope.captureIds, setOf(wrongAmount)),
        )
        val capture = observeCapture(runtime)
        val source = selectSource(runtime, capture.getString("ref"), capture.getString("source_text"))
        val recorded = record(
            runtime,
            source.getString("source_ref"),
            valueRef(source, "amount"),
            makeAnchor(runtime, valueRef(source, "date"), valueRef(source, "time")),
            "Lunch purchase",
            "none",
        )
        val eventRef = recorded.getJSONObject("data").getJSONObject("event").getString("ref")
        val data = runtime.execute(
            "list_drafts",
            JSONObject()
                .put("event_ref", eventRef)
                .put("review_status", "PENDING")
                .put("duplicates_only", false)
                .put("cursor", JSONObject.NULL),
            "read-only",
        ).envelope.getJSONObject("data")

        assertEquals("conflicting", data.getJSONObject("match").getString("outcome"))
        assertEquals(0, data.getJSONObject("match").getInt("candidate_count"))
        assertEquals(1, data.getJSONObject("match").getInt("conflicting_count"))
        assertTrue(data.getBoolean("scope_exhausted"))
    }
    @Test
    fun `cross-midnight event interval uses parsed transaction date and time`() = runBlocking {
        val eventCaptureId = storeCapture("s9.2-midnight-event", MIDNIGHT_INTERVAL_TEXT)
        val before = storeDraft(
            amount = 28_000L,
            date = "2026-10-21",
            time = "23:49",
            memo = "Coffee before",
        )
        val inside = storeDraft(
            amount = 28_000L,
            date = "2026-10-22",
            time = "00:10",
            memo = "Coffee purchase",
            postedAtEpochMillis = LocalDateTime.parse("2026-10-22T00:30")
                .atZone(ZoneId.of("Asia/Ho_Chi_Minh"))
                .toInstant()
                .toEpochMilli(),
        )
        val after = storeDraft(
            amount = 28_000L,
            date = "2026-10-22",
            time = "00:21",
            memo = "Coffee after",
        )
        val runtime = runtimeFor(
            WorkflowCallerScope.AgentSession(
                scope.scopeId,
                setOf(eventCaptureId),
                setOf(before, inside, after),
            ),
        )
        val capture = observeCapture(runtime, eventCaptureId)
        val source = selectSource(runtime, capture.getString("ref"), capture.getString("source_text"))
        val dates = valueRefs(source, "date")
        val times = valueRefs(source, "time")
        val anchor = makeIntervalAnchor(runtime, dates[0], times[0], dates[1], times[1])
        assertEquals("ok", anchor.getString("outcome"))
        val recorded = record(
            runtime,
            source.getString("source_ref"),
            valueRef(source, "amount"),
            anchor.getJSONObject("data").getString("anchor_ref"),
            "Coffee purchase",
            "none",
        )
        assertEquals("ok", recorded.getString("outcome"))
        val eventRef = recorded.getJSONObject("data").getJSONObject("event").getString("ref")
        val data = runtime.execute(
            "list_drafts",
            JSONObject()
                .put("event_ref", eventRef)
                .put("review_status", "PENDING")
                .put("duplicates_only", false)
                .put("cursor", JSONObject.NULL),
            "read-only",
        ).envelope.getJSONObject("data")
        assertEquals("unique", data.getJSONObject("match").getString("outcome"))
        assertEquals(1, data.getJSONObject("match").getInt("candidate_count"))
        assertEquals(2, data.getJSONObject("match").getInt("conflicting_count"))
        assertEquals("draft #$inside", data.getJSONArray("items").getJSONObject(0).getString("user_label"))
        assertTrue(data.getBoolean("scope_exhausted"))
    }

    @Test
    fun `incomplete pending scope preserves useful candidates without claiming unique or missing`() = runBlocking {
        val outsidePage = storeDraft(
            amount = 28_000L,
            date = "2026-10-21",
            time = "12:30",
            memo = "Target purchase outside the first page",
        )
        val visibleMatch = storeDraft(
            amount = 28_000L,
            date = "2026-10-21",
            time = "12:30",
            memo = "Visible matching purchase",
        )
        val visibleConflicts = (1..31).map { index ->
            storeDraft(
                amount = 30_000L + index,
                date = "2026-10-21",
                time = "12:30",
                memo = "Conflicting purchase $index",
            )
        }
        val allIds = listOf(outsidePage, visibleMatch) + visibleConflicts
        // With the deterministic newest-first query, the oldest (outsidePage) is beyond the first 32.
        val firstPageIds = allIds.drop(1).toSet()
        val session = WorkflowCallerScope.AgentSession(
            scopeId = scope.scopeId,
            captureIds = scope.captureIds,
            draftIds = firstPageIds,
            draftScopeComplete = false,
        )
        val runtime = runtimeFor(session)
        val capture = observeCapture(runtime)
        val source = selectSource(runtime, capture.getString("ref"), capture.getString("source_text"))
        val recorded = record(
            runtime,
            source.getString("source_ref"),
            valueRef(source, "amount"),
            makeAnchor(runtime, valueRef(source, "date"), valueRef(source, "time")),
            "Lunch purchase",
            "none",
        )
        val eventRef = recorded.getJSONObject("data").getJSONObject("event").getString("ref")
        val arguments = JSONObject()
            .put("event_ref", eventRef)
            .put("review_status", "PENDING")
            .put("duplicates_only", false)
            .put("cursor", JSONObject.NULL)
        val data = runtime.execute("list_drafts", arguments, "read-only").envelope.getJSONObject("data")
        val match = data.getJSONObject("match")
        assertEquals("incomplete", match.getString("outcome"))
        assertEquals(1, match.getInt("candidate_count"))
        assertTrue(match.getBoolean("candidate_set_complete_within_granted_scope"))
        assertTrue(match.getBoolean("candidate_page_complete"))
        assertFalse(match.getBoolean("pending_database_complete_at_recheck"))
        assertTrue(match.getString("scope_note").contains("initial grant was incomplete"))
        assertFalse(data.getBoolean("scope_exhausted"))
        assertEquals(
            "draft #$visibleMatch",
            data.getJSONArray("items").getJSONObject(0).getString("user_label"),
        )

        val noCandidateSession = runtimeFor(
            WorkflowCallerScope.AgentSession(
                scopeId = scope.scopeId,
                captureIds = scope.captureIds,
                draftIds = visibleConflicts.toSet(),
            ),
        )
        val freshCapture = observeCapture(noCandidateSession)
        val freshEvent = freshCapture.getJSONArray("events").getJSONObject(0).getString("ref")
        val noMatch = noCandidateSession.execute(
            "list_drafts",
            JSONObject()
                .put("event_ref", freshEvent)
                .put("review_status", "PENDING")
                .put("duplicates_only", false)
                .put("cursor", JSONObject.NULL),
            "read-only",
        ).envelope.getJSONObject("data")
        val incompleteResult = noMatch.getJSONObject("match")
        assertEquals("incomplete", incompleteResult.getString("outcome"))
        assertEquals(0, incompleteResult.getInt("candidate_count"))
        assertTrue(incompleteResult.getBoolean("candidate_set_complete_within_granted_scope"))
        assertTrue(incompleteResult.getBoolean("candidate_page_complete"))
        assertFalse(incompleteResult.getBoolean("pending_database_complete_at_recheck"))
        assertFalse(noMatch.getBoolean("scope_exhausted"))
    }

    @Test
    fun `fresh pending rows outside an otherwise complete grant prevent a unique claim`() = runBlocking {
        val visible = storeDraft(
            amount = 28_000L,
            date = "2026-10-21",
            time = "12:30",
            memo = "Visible purchase",
        )
        val runtime = runtimeFor(
            WorkflowCallerScope.AgentSession(
                scopeId = scope.scopeId,
                captureIds = scope.captureIds,
                draftIds = setOf(visible),
                draftScopeComplete = true,
            ),
        )
        val capture = observeCapture(runtime)
        val source = selectSource(runtime, capture.getString("ref"), capture.getString("source_text"))
        val recorded = record(
            runtime,
            source.getString("source_ref"),
            valueRef(source, "amount"),
            makeAnchor(runtime, valueRef(source, "date"), valueRef(source, "time")),
            "Lunch purchase",
            "none",
        )
        assertEquals("ok", recorded.getString("outcome"))
        val eventRef = recorded.getJSONObject("data").getJSONObject("event").getString("ref")
        // This matching draft arrives after the session grant was created and is not authorized.
        storeDraft(
            amount = 28_000L,
            date = "2026-10-21",
            time = "12:30",
            memo = "Fresh pending purchase",
        )

        val data = runtime.execute(
            "list_drafts",
            JSONObject()
                .put("event_ref", eventRef)
                .put("review_status", "PENDING")
                .put("duplicates_only", false)
                .put("cursor", JSONObject.NULL),
            "read-only",
        ).envelope.getJSONObject("data")
        val match = data.getJSONObject("match")
        assertEquals("incomplete", match.getString("outcome"))
        assertEquals(1, match.getInt("candidate_count"))
        assertTrue(match.getBoolean("candidate_set_complete_within_granted_scope"))
        assertTrue(match.getBoolean("candidate_page_complete"))
        assertFalse(match.getBoolean("pending_database_complete_at_recheck"))
        assertTrue(match.getString("scope_note").contains("fresh/out-of-grant pending ids"))
        assertFalse(data.getBoolean("scope_exhausted"))
        assertEquals(
            "draft #$visible",
            data.getJSONArray("items").getJSONObject(0).getString("user_label"),
        )
    }

    @Test
    fun `same day intervals follow chronological order and reject reversed ends`() = runBlocking {
        val saved = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest("s8.13-pre-interval-capture", INTERVAL_TEXT),
        ) as WorkflowCommandOutcome.Applied
        val intervalId = (saved.change as WorkflowChange.CaptureSaved).captureId
        val runtime = runtimeFor(
            WorkflowCallerScope.AgentSession(scope.scopeId, setOf(intervalId), emptySet()),
        )
        val capture = observeCapture(runtime, intervalId)
        val source = selectSource(runtime, capture.getString("ref"), capture.getString("source_text"))
        val dateRef = valueRefs(source, "date").single()
        val times = valueRefs(source, "time")
        assertEquals(2, times.size)

        val increasing = makeIntervalAnchor(runtime, dateRef, times[0], dateRef, times[1])
        assertEquals(increasing.toString(), "ok", increasing.getString("outcome"))

        val reversed = makeIntervalAnchor(runtime, dateRef, times[1], dateRef, times[0])
        assertEquals("TEMPORAL_CONSTRAINT", reversed.getJSONObject("error").getString("code"))
        assertEquals(
            "end_after_start",
            reversed.getJSONObject("error").getJSONArray("issues").getJSONObject(0).getString("constraint"),
        )
        assertEquals("none", reversed.getString("effect"))

        val equalCapture = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest("s8.13-pre-interval-equal", EQUAL_INTERVAL_TEXT),
        ) as WorkflowCommandOutcome.Applied
        val equalId = (equalCapture.change as WorkflowChange.CaptureSaved).captureId
        val equalRuntime = runtimeFor(
            WorkflowCallerScope.AgentSession(scope.scopeId, setOf(equalId), emptySet()),
        )
        val equalObservation = observeCapture(equalRuntime, equalId)
        val equalSource = selectSource(
            equalRuntime,
            equalObservation.getString("ref"),
            equalObservation.getString("source_text"),
        )
        val equalDate = valueRefs(equalSource, "date").single()
        val equalTimes = valueRefs(equalSource, "time")
        // The same stated time is one value and therefore one alias.
        assertEquals(1, equalTimes.size)
        val endsAtStart = makeIntervalAnchor(equalRuntime, equalDate, equalTimes[0], equalDate, equalTimes[0])
        assertEquals("TEMPORAL_CONSTRAINT", endsAtStart.getJSONObject("error").getString("code"))
        assertEquals(
            "end_after_start",
            endsAtStart.getJSONObject("error").getJSONArray("issues").getJSONObject(0).getString("constraint"),
        )

        val spanCapture = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest("s8.13-pre-interval-span", SPAN_TEXT),
        ) as WorkflowCommandOutcome.Applied
        val spanId = (spanCapture.change as WorkflowChange.CaptureSaved).captureId
        val spanRuntime = runtimeFor(
            WorkflowCallerScope.AgentSession(scope.scopeId, setOf(spanId), emptySet()),
        )
        val spanObservation = observeCapture(spanRuntime, spanId)
        val spanSource = selectSource(
            spanRuntime,
            spanObservation.getString("ref"),
            spanObservation.getString("source_text"),
        )
        val dates = valueRefs(spanSource, "date")
        val spanTimes = valueRefs(spanSource, "time")
        assertEquals(2, dates.size)
        assertEquals(2, spanTimes.size)
        val multiDay = makeIntervalAnchor(spanRuntime, dates[0], spanTimes[0], dates[1], spanTimes[1])
        assertEquals(multiDay.toString(), "ok", multiDay.getString("outcome"))

        val reversedDates = makeIntervalAnchor(spanRuntime, dates[1], spanTimes[0], dates[0], spanTimes[1])
        assertEquals("TEMPORAL_CONSTRAINT", reversedDates.getJSONObject("error").getString("code"))
        assertEquals(
            "end_not_before_start",
            reversedDates.getJSONObject("error").getJSONArray("issues").getJSONObject(0).getString("constraint"),
        )
    }

    @Test
    fun `a different boundary over an existing reading never becomes a second reading`() = runBlocking {
        val runtime = runtimeFor(scope)
        val capture = observeCapture(runtime)
        val maskedSource = capture.getString("source_text")
        val exact = selectSource(runtime, capture.getString("ref"), maskedSource)
        val recorded = record(
            runtime, exact.getString("source_ref"), valueRef(exact, "amount"),
            makeAnchor(runtime, valueRef(exact, "date"), null), "Lunch", "none",
        )
        assertEquals(recorded.toString(), "ok", recorded.getString("outcome"))

        val overlapping = maskedSource.substringBefore(" at ")
        val alternate = selectSource(runtime, capture.getString("ref"), overlapping)
        assertTrue("an overlapping selection is not the existing reading", alternate.isNull("event"))
        val refused = record(
            runtime, alternate.getString("source_ref"), valueRef(alternate, "amount"),
            makeAnchor(runtime, valueRef(alternate, "date"), null), "Lunch", "none",
        )
        assertEquals("STALE_OBSERVATION", refused.getJSONObject("error").getString("code"))
        assertEquals("none", refused.getString("effect"))
        assertEquals(
            "selected_span_matches_existing_reading",
            refused.getJSONObject("error").getJSONArray("issues").getJSONObject(0).getString("constraint"),
        )
        assertTrue(refused.getJSONArray("observations").length() >= 1)
        val rows = database.noteEventDao().findForCapture(captureId)
        assertEquals(1, rows.size)
        assertEquals(0, rows.single().spanStart)
        assertEquals(SOURCE_TEXT.length, rows.single().spanEnd)
    }

    @Test
    fun `a correction cannot silently drop fields the stored reading already has`() = runBlocking {
        val runtime = runtimeFor(scope)
        val capture = observeCapture(runtime)
        val maskedSource = capture.getString("source_text")
        val first = selectSource(runtime, capture.getString("ref"), maskedSource)
        val recorded = record(
            runtime, first.getString("source_ref"), valueRef(first, "amount"),
            makeAnchor(runtime, valueRef(first, "date"), null), "Lunch", "none",
        )
        assertEquals(recorded.toString(), "ok", recorded.getString("outcome"))

        val fresh = observeCapture(runtime)
        val exact = selectSource(runtime, fresh.getString("ref"), maskedSource)
        assertFalse("the exact span binds the stored reading", exact.isNull("event"))
        val refused = record(runtime, exact.getString("source_ref"), null, null, "Lunch", "amount")
        assertEquals("MISSING_REQUIRED_MEANING", refused.getJSONObject("error").getString("code"))
        assertEquals("none", refused.getString("effect"))
        val issues = refused.getJSONObject("error").getJSONArray("issues")
        val paths = (0 until issues.length()).map { issues.getJSONObject(it).getString("path") }.toSet()
        assertTrue(paths.containsAll(setOf("$.amount_ref", "$.anchor_ref")))
        val stored = database.noteEventDao().findForCapture(captureId).single()
        assertEquals(28_000L, stored.amountVnd)
        assertEquals("2026-10-21", stored.anchorDate)
    }

    @Test
    fun `a write whose effect can no longer be re-read reports an unknown effect`() = runBlocking {
        val runtime = runtimeFor(scope)
        val capture = observeCapture(runtime)
        val maskedSource = capture.getString("source_text")
        val source = selectSource(runtime, capture.getString("ref"), maskedSource)
        val arguments = JSONObject()
            .put("source_ref", source.getString("source_ref"))
            .put("summary", "Lunch")
            .put("amount_ref", valueRef(source, "amount"))
            .put("anchor_ref", makeAnchor(runtime, valueRef(source, "date"), null))
            .put("merchant_ref", JSONObject.NULL)
            .put("clarification", "none")
        val first = runtime.execute("record_event", arguments, "unverified-effect").envelope
        assertEquals("applied", first.getString("effect"))
        val eventId = database.noteEventDao().findForCapture(captureId).single().id
        // The applied row disappears before its receipt can be re-read: the call may have
        // written, so its effect is unknown, never a no-write guarantee.
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM note_events WHERE id = ?",
            arrayOf<Any?>(eventId),
        )
        val again = runtime.execute("record_event", arguments, "unverified-effect").envelope
        assertEquals("error", again.getString("outcome"))
        assertEquals("EFFECT_NOT_VERIFIED", again.getJSONObject("error").getString("code"))
        assertEquals("unknown", again.getString("effect"))
        assertEquals("stop", again.getJSONObject("control").getString("action"))
        assertFalse(again.getBoolean("verified"))
        assertTrue(database.noteEventDao().findForCapture(captureId).isEmpty())
        assertEquals(1L, database.workflowOperationDao().countOf("UPDATE_EVENT"))
    }

    @Test
    fun `a replay whose journal is still pending is uncertain and creates nothing`() = runBlocking {
        val runtime = runtimeFor(scope)
        val capture = observeCapture(runtime)
        val maskedSource = capture.getString("source_text")
        val source = selectSource(runtime, capture.getString("ref"), maskedSource)
        val recorded = runtime.execute(
            "record_event",
            JSONObject()
                .put("source_ref", source.getString("source_ref"))
                .put("summary", "Lunch")
                .put("amount_ref", valueRef(source, "amount"))
                .put("anchor_ref", makeAnchor(runtime, valueRef(source, "date"), null))
                .put("merchant_ref", JSONObject.NULL)
                .put("clarification", "none"),
            "pending-replay",
        ).envelope
        assertEquals("applied", recorded.getString("effect"))
        markOperationPending("pending-replay")

        val replay = runtime.execute(
            "replay_operation",
            JSONObject().put("operation_ref", recorded.getString("operation_ref")),
            "read-only",
        ).envelope
        assertEquals("error", replay.getString("outcome"))
        assertEquals("OPERATION_PENDING", replay.getJSONObject("error").getString("code"))
        assertEquals("unknown", replay.getString("effect"))
        assertEquals("stop", replay.getJSONObject("control").getString("action"))
        assertEquals(1, database.noteEventDao().findForCapture(captureId).size)
        assertEquals(1L, database.workflowOperationDao().countOf("UPDATE_EVENT"))
    }

    @Test
    fun `list_drafts requires a declared review status and never null`() = runBlocking {
        val draftId = storeDraft(amount = 1_000L, date = "2026-10-21")
        val runtime = runtimeFor(
            WorkflowCallerScope.AgentSession(scope.scopeId, scope.captureIds, setOf(draftId)),
        )
        val nullStatus = runtime.execute(
            "list_drafts",
            JSONObject()
                .put("review_status", JSONObject.NULL)
                .put("duplicates_only", false)
                .put("cursor", JSONObject.NULL),
            "read-only",
        ).envelope
        assertEquals("error", nullStatus.getString("outcome"))
        assertEquals("not_dispatched", nullStatus.getString("dispatch"))
        assertEquals("SCHEMA_INVALID", nullStatus.getJSONObject("error").getString("code"))
        assertEquals(
            "$.review_status",
            nullStatus.getJSONObject("error").getJSONArray("issues").getJSONObject(0).getString("path"),
        )
        assertEquals(0, nullStatus.getJSONArray("observations").length())

        val all = runtime.execute(
            "list_drafts",
            JSONObject()
                .put("review_status", "ALL")
                .put("duplicates_only", false)
                .put("cursor", JSONObject.NULL),
            "read-only",
        ).envelope
        assertEquals("ok", all.getString("outcome"))
        assertEquals(1, all.getJSONObject("data").getJSONArray("items").length())
    }

    @Test
    fun `the reference registry is bounded and evicts the oldest alias`() {
        val registry = AgentReferenceRegistry("s8.13-pre-eviction", maxEntries = 16)
        val oldest = registry.issue(
            AgentReference(
                handle = registry.nextHandle(),
                kind = AgentReferenceKind.CAPTURE,
                scopeId = "s8.13-pre-eviction",
                captureId = 1L,
            ),
        )
        repeat(15) { index ->
            registry.issue(
                AgentReference(
                    handle = registry.nextHandle(),
                    kind = AgentReferenceKind.DRAFT,
                    scopeId = "s8.13-pre-eviction",
                    draftId = index.toLong() + 1,
                ),
            )
        }
        assertEquals(16, registry.size())
        val newest = registry.issue(
            AgentReference(
                handle = registry.nextHandle(),
                kind = AgentReferenceKind.EVENT,
                scopeId = "s8.13-pre-eviction",
                eventId = 2L,
            ),
        )
        assertEquals(16, registry.size())
        val evicted = runCatching { registry.resolve(oldest, AgentReferenceKind.CAPTURE, "$.ref") }
            .exceptionOrNull() as? AgentToolException
        assertEquals(AgentErrorCode.UNKNOWN_REFERENCE, evicted?.code)
        assertEquals(AgentReferenceKind.EVENT, registry.resolve(newest, AgentReferenceKind.EVENT, "$.ref").kind)
    }

    // ---- Helpers -------------------------------------------------------------------------

    private suspend fun storeCapture(key: String, text: String): Long {
        val saved = commands.saveCapture(WorkflowCallerScope.User, SaveCaptureRequest(key, text))
            as WorkflowCommandOutcome.Applied
        return (saved.change as WorkflowChange.CaptureSaved).captureId
    }

    private fun runtimeFor(
        sessionScope: WorkflowCallerScope.AgentSession,
        registry: AgentReferenceRegistry = AgentReferenceRegistry(sessionScope.scopeId),
    ): AgentToolRuntime =
        AgentToolRuntime(
            commands = commands,
            queries = queries,
            session = AgentToolSession(
                scope = sessionScope,
                boundary = boundary,
                registry = registry,
            ),
            declarations = declarations,
        )

    private suspend fun observeCapture(
        runtime: AgentToolRuntime,
        id: Long = captureId,
    ): JSONObject = runtime.captured(id)

    /**
     * Finds the initial capture observation for one stored capture by its masked source text.
     * Capture observations intentionally carry no database id, so the helper never guesses:
     * the requested capture must be inside the trusted scope.
     */
    private suspend fun AgentToolRuntime.captured(id: Long): JSONObject {
        val capture = queries.sourceExcerpt(id) ?: error("capture $id is not stored")
        val expected = capture.bodyText?.let {
            boundary.maskOutbound(it, io.github.triet4p.aegisf.privacy.MaskedPath.TOOL_RESULT)
        }
        return initialCaptureObservations(16)
            .firstOrNull { it.optString("source_text") == expected }
            ?: error("capture $id is not in the trusted scope observations")
    }

    private suspend fun selectSource(
        runtime: AgentToolRuntime,
        captureRef: String,
        quote: String,
        occurrence: Int = 1,
    ): JSONObject {
        val result = runtime.execute(
            "select_source",
            JSONObject()
                .put("capture_ref", captureRef)
                .put("quote", quote)
                .put("occurrence", occurrence),
            "read-only",
        ).envelope
        assertEquals("ok", result.getString("outcome"))
        return result.getJSONObject("data")
    }

    private suspend fun makeAnchor(
        runtime: AgentToolRuntime,
        dateRef: String,
        timeRef: String?,
    ): String {
        val result = runtime.execute(
            "make_time_anchor",
            JSONObject()
                .put("date_ref", dateRef)
                .put("time_ref", timeRef ?: JSONObject.NULL)
                .put("end_date_ref", JSONObject.NULL)
                .put("end_time_ref", JSONObject.NULL),
            "read-only",
        ).envelope
        assertEquals("ok", result.getString("outcome"))
        return result.getJSONObject("data").getString("anchor_ref")
    }

    private suspend fun record(
        runtime: AgentToolRuntime,
        sourceRef: String,
        amountRef: String?,
        anchorRef: String?,
        summary: String,
        clarification: String,
        merchantRef: String? = null,
    ): JSONObject = runtime.execute(
        "record_event",
        JSONObject()
            .put("source_ref", sourceRef)
            .put("summary", summary)
            .put("amount_ref", amountRef ?: JSONObject.NULL)
            .put("anchor_ref", anchorRef ?: JSONObject.NULL)
            .put("merchant_ref", merchantRef ?: JSONObject.NULL)
            .put("clarification", clarification),
        // Each call is a distinct host-issued operation identity; only an explicit
        // replay_operation repeats a recorded one.
        "record-${sequence.incrementAndGet()}",
    ).envelope

    /**
     * The test model's semantic choice: the one neutral value alias whose value parses for the
     * role, standing in for reading the sentence. The host itself parses whichever alias the
     * model passes to the argument.
     */
    private fun valueRef(source: JSONObject, role: String): String =
        valueRefs(source, role).single()

    private fun valueRefs(source: JSONObject, role: String): List<String> {
        val values = source.getJSONArray("values")
        return (0 until values.length())
            .map { values.getJSONObject(it) }
            .filter { item ->
                val token = item.getString("display")
                when (role) {
                    "amount" -> runCatching { boundary.vault.parseAmount(token) }.isSuccess
                    "date" -> runCatching { boundary.vault.parseDate(token, 2026) }.isSuccess
                    "time" -> runCatching { boundary.vault.parseTime(token) }.isSuccess
                    else -> false
                }
            }
            .map { it.getString("ref") }
    }

    private suspend fun makeIntervalAnchor(
        runtime: AgentToolRuntime,
        dateRef: String,
        timeRef: String?,
        endDateRef: String?,
        endTimeRef: String?,
    ): JSONObject = runtime.execute(
        "make_time_anchor",
        JSONObject()
            .put("date_ref", dateRef)
            .put("time_ref", timeRef ?: JSONObject.NULL)
            .put("end_date_ref", endDateRef ?: JSONObject.NULL)
            .put("end_time_ref", endTimeRef ?: JSONObject.NULL),
        "read-only",
    ).envelope

    /**
     * Makes a settled operation look like one a concurrent attempt still owns.
     *
     * The journal is the only host state a live claimant is visible through, so the test
     * writes exactly the pending claim the service would find: same key and digest, an
     * owner token that is not this run and a lease that has not expired.
     */
    private fun markOperationPending(key: String) {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE workflow_operations SET outcome = 'PENDING', ownerToken = ?, " +
                "leaseExpiresAtEpochMillis = ? WHERE idempotencyKey = ?",
            arrayOf<Any?>("s8.13-pre-concurrent-owner", System.currentTimeMillis() + 300_000L, key),
        )
    }

    /**
     * Reads the stored draft page and returns the observation for exactly [draftId].
     *
     * Draft observations deliberately expose no database id, so the requested row is matched
     * through its masked stored description; a page without that row is an error, never the
     * first unrelated item.
     */
    private suspend fun draftPage(runtime: AgentToolRuntime, draftId: Long): JSONObject {
        val stored = database.pendingBankDraftDao().findById(draftId)
            ?: error("draft $draftId is not stored")
        val expected = boundary.maskOutbound(
            stored.workingDescription(),
            io.github.triet4p.aegisf.privacy.MaskedPath.TOOL_RESULT,
        )
        val result = runtime.execute(
            "list_drafts",
            JSONObject()
                .put("review_status", "PENDING")
                .put("duplicates_only", false)
                .put("cursor", JSONObject.NULL),
            "read-only",
        ).envelope
        val items = result.getJSONObject("data").getJSONArray("items")
        for (index in 0 until items.length()) {
            val candidate = runtime.execute(
                "observe",
                JSONObject().put("ref", items.getJSONObject(index).getString("ref")),
                "read-only",
            ).envelope
            val observation = candidate.getJSONObject("data").getJSONObject("observation")
            if (observation.optString("description") == expected) return observation
        }
        error("draft $draftId was not on the observed page")
    }

    private suspend fun storeDraft(
        amount: Long?,
        date: String?,
        memo: String? = "Synthetic bank memo for $amount",
        time: String? = "12:30",
        postedAtEpochMillis: Long = 1_798_000_000_000L,
        direction: IntakeDirection = IntakeDirection.DEBIT,
        amountState: IntakeFieldState = if (amount == null) IntakeFieldState.MISSING else IntakeFieldState.PRESENT,
        momentState: IntakeFieldState =
            if (date == null && time == null) IntakeFieldState.MISSING else IntakeFieldState.PRESENT,
        memoState: IntakeFieldState = if (memo == null) IntakeFieldState.MISSING else IntakeFieldState.PRESENT,
    ): Long =
        database.pendingBankDraftDao().store(
            PendingBankDraftEntity(
                deliveryId = "s8.13-pre-draft-${sequence.incrementAndGet()}-$amount-$date",
                sourcePackage = "synthetic.fixture",
                notificationKey = null,
                postedAtEpochMillis = postedAtEpochMillis,
                bank = "Fixture bank",
                parseStatus = IntakeParseStatus.PARSED,
                amountState = amountState,
                amountVnd = amount,
                amountUnresolvedReason = null,
                direction = direction,
                momentState = momentState,
                statedDate = date,
                statedTime = time,
                momentUnresolvedReason = null,
                memoState = memoState,
                memo = memo,
                memoUnresolvedReason = null,
                createdAtEpochMillis = 1_798_000_000_000L,
            ),
        )

    @Test
    fun `category choice from another draft observation is refused`() = runBlocking {
        val first = storeDraft(amount = 28_000L, date = "2026-10-21")
        val second = storeDraft(amount = 29_000L, date = "2026-10-21")
        commands.addCategory(
            WorkflowCallerScope.User,
            AddCategoryRequest(idempotencyKey = "foreign-choice-category", name = "Food"),
        )
        val session = WorkflowCallerScope.AgentSession(scope.scopeId, scope.captureIds, setOf(first, second))
        val runtime = runtimeFor(session)
        val firstObservation = draftPage(runtime, first)
        val secondObservation = draftPage(runtime, second)
        val foreignChoice = secondObservation.getJSONArray("category_choices").getJSONObject(0).getString("ref")
        val result = runtime.execute(
            "update_draft_review",
            JSONObject().put("draft_ref", firstObservation.getString("ref")).put("category_ref", foreignChoice),
            "foreign-choice",
        ).envelope
        assertEquals("error", result.getString("outcome"))
        assertNull(database.pendingBankDraftDao().findById(first)!!.reviewCategoryId)
    }

    @Test
    fun `the hosted workflow saves one offered category without confirming the draft`() = runBlocking {
        val draftId = storeDraft(amount = 28_000L, date = "2026-10-21")
        val added = commands.addCategory(
            WorkflowCallerScope.User,
            AddCategoryRequest(idempotencyKey = "s8.13-pre-category", name = "Food"),
        )
        assertTrue(added is WorkflowCommandOutcome.Applied)
        val categoryId = ((added as WorkflowCommandOutcome.Applied).change as WorkflowChange.CategoryAdded).categoryId
        val session = WorkflowCallerScope.AgentSession(
            scopeId = scope.scopeId,
            captureIds = scope.captureIds,
            draftIds = setOf(draftId),
        )
        val runtime = runtimeFor(session)
        val draft = draftPage(runtime, draftId)
        val choices = draft.getJSONArray("category_choices")
        assertTrue("the draft observation must carry current category choices", choices.length() >= 1)
        val chosen = (0 until choices.length())
            .map { choices.getJSONObject(it) }
            .single { it.getString("name") == "Food" }

        val applied = runtime.execute(
            "update_draft_review",
            JSONObject()
                .put("draft_ref", draft.getString("ref"))
                .put("category_ref", chosen.getString("ref")),
            "category-choice",
        ).envelope
        assertEquals(applied.toString(), "ok", applied.getString("outcome"))
        assertEquals("applied", applied.getString("effect"))
        assertTrue(applied.getBoolean("verified"))

        val stored = database.pendingBankDraftDao().findById(draftId)!!
        assertEquals(IntakeReviewStatus.PENDING, stored.reviewStatus)
        assertEquals(categoryId, stored.reviewCategoryId)
        assertTrue(stored.reviewCategoryResolved)
        assertNull(stored.confirmedTransactionId)
        assertTrue("the category save never writes the ledger", database.transactionDao().findAll().isEmpty())

        // The observation's revision moved on, so the same stale aliases are refused.
        val stale = runtime.execute(
            "update_draft_review",
            JSONObject()
                .put("draft_ref", draft.getString("ref"))
                .put("category_ref", chosen.getString("ref")),
            "category-choice-stale",
        ).envelope
        assertEquals("error", stale.getString("outcome"))
        assertEquals("STALE_OBSERVATION", stale.getJSONObject("error").getString("code"))
        assertEquals(categoryId, database.pendingBankDraftDao().findById(draftId)!!.reviewCategoryId)
    }

    private companion object {
        const val SOURCE_TEXT = "Lunch 28,000 VND on 2026-10-21 at 12:30"
        const val MERCHANT_TEXT =
            "Lunch 28,000 VND at Highlands on 2026-10-21 at 12:30; taxi 65,000 VND on 2026-10-21 at 13:05."
        const val MIDNIGHT_INTERVAL_TEXT =
            "Coffee 28,000 VND 2026-10-21 23:50 to 2026-10-22 00:20"
        const val TWO_TEXT = "Coffee 15,000 VND on 2026-10-21 | Taxi 40,000 VND on 2026-10-21"
        const val INTERVAL_TEXT = "Meeting 2026-10-21 from 09:00 to 17:00"
        const val EQUAL_INTERVAL_TEXT = "Standup 2026-10-21 09:00 to 09:00"
        const val SPAN_TEXT = "Trip 2026-10-20 08:00 to 2026-10-22 18:00"
    }
}
