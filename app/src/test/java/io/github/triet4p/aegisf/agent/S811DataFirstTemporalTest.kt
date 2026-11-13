package io.github.triet4p.aegisf.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.data.CaptureKind
import io.github.triet4p.aegisf.data.CaptureMessageEntity
import io.github.triet4p.aegisf.data.CaptureSourceKind
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.SystemLedgerClock
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.privacy.MaskedPath
import io.github.triet4p.aegisf.privacy.MaskedPromptBoundary
import io.github.triet4p.aegisf.privacy.SensitiveVault
import io.github.triet4p.aegisf.provider.HostedToolDeclaration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S11.C3 boundary tests: the trusted current-time read, the spelled and natural temporal answers
 * the host resolves under a named chronology, and the bounded data-first candidate search that
 * runs before any date or time question.
 *
 * Every test drives the real runtime over a real in-memory Room database, so the privacy boundary
 * and the scope/revision rules production uses are the ones exercised here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class S811DataFirstTemporalTest {
    private lateinit var context: Context
    private lateinit var database: LedgerDatabase
    private lateinit var commands: WorkflowCommandService
    private lateinit var queries: WorkflowQueryService
    private lateinit var boundary: MaskedPromptBoundary
    private lateinit var declarations: List<HostedToolDeclaration>
    private var registry: AgentReferenceRegistry = AgentReferenceRegistry("s811-c3-session")
    private var scope: WorkflowCallerScope.AgentSession = WorkflowCallerScope.AgentSession(
        scopeId = "s811-c3-session",
        captureIds = emptySet(),
        draftIds = emptySet(),
    )
    private var clockMillis: Long = VIETNAM_2026_11_13_1405
    private val sequence = AtomicInteger()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        commands = WorkflowCommandService(
            database = database,
            clock = SystemLedgerClock,
            timezoneId = { ZONE },
        )
        queries = WorkflowQueryService(database)
        boundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId))
        declarations = AgentToolDeclarations.load(context.assets::open)
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    // ---- get_current_time ----------------------------------------------------------------

    @Test
    fun `current time reports trusted device components and a masked current-day alias`() = runBlocking {
        val runtime = runtimeFor(scope)
        val result = runtime.execute("get_current_time", JSONObject(), "read-only").envelope

        assertEquals("ok", result.getString("outcome"))
        assertEquals("none", result.getString("effect"))
        val data = result.getJSONObject("data")
        val date = data.getJSONObject("local_date")
        assertEquals(2026, date.getInt("year"))
        assertEquals(11, date.getInt("month"))
        assertEquals(13, date.getInt("day"))
        val time = data.getJSONObject("local_time")
        assertEquals(14, time.getInt("hour"))
        assertEquals(5, time.getInt("minute"))
        assertEquals(ZONE, data.getString("timezone"))
        assertEquals(420, data.getInt("utc_offset_minutes"))
        assertEquals("FRIDAY", data.getString("weekday"))

        val todayRef = data.getString("today_ref")
        val reference = registry.resolve(todayRef, AgentReferenceKind.VALUE, "$.today_ref")
        assertEquals("2026-11-13", boundary.vault.parseDate(requireNotNull(reference.valueToken)))
        assertEquals(TemporalChronology.CURRENT_TIME.wire, reference.temporalChronology)

        // The observation carries calendar components, never a rendered date or time string.
        assertFalse(data.toString().contains("2026-11-13"))
        assertFalse(data.toString().contains("14:05"))
    }

    // ---- spelled dates -------------------------------------------------------------------

    @Test
    fun `a spelled day month in the selection resolves from the capture's own recorded year`() = runBlocking {
        val captureId = storeCapture(
            text = "Cà phê sáng ngày 11 tháng 10 lúc 22h giá 25.000",
            capturedAt = instantAt(2024, 10, 20, 9, 0),
            zone = ZONE,
        )
        scope = WorkflowCallerScope.AgentSession(scope.scopeId, setOf(captureId), emptySet())
        val runtime = runtimeFor(scope)
        val capture = runtime.observeCapture(captureId)
        val data = runtime.selectSource(capture.getString("ref"), capture.getString("source_text"))

        val spelled = data.getJSONArray("spelled_dates").getJSONObject(0)
        assertEquals(11, spelled.getInt("day"))
        assertEquals(10, spelled.getInt("month"))
        assertTrue(spelled.getBoolean("year_resolved"))
        assertEquals(TemporalChronology.CAPTURE_CHRONOLOGY.wire, spelled.getString("chronology"))
        val dateRef = registry.resolve(spelled.getString("ref"), AgentReferenceKind.VALUE, "$")
        assertEquals("2024-10-11", boundary.vault.parseDate(requireNotNull(dateRef.valueToken)))

        // The alias is usable as an anchor date; numeric ISO/slash behavior is unchanged.
        val anchor = runtime.makeAnchor(spelled.getString("ref"), null)
        val anchorRef = registry.resolve(anchor, AgentReferenceKind.ANCHOR, "$")
        assertEquals(
            "2024-10-11",
            boundary.vault.parseDate(requireNotNull(anchorRef.dateToken)),
        )
    }

    @Test
    fun `a spelled day month with no capture chronology stays unresolved instead of guessing a year`() = runBlocking {
        val captureId = storeCapture(
            text = "Mua sách ngày 11 tháng 10",
            capturedAt = null,
            zone = null,
        )
        scope = WorkflowCallerScope.AgentSession(scope.scopeId, setOf(captureId), emptySet())
        val runtime = runtimeFor(scope)
        val capture = runtime.observeCapture(captureId)
        val data = runtime.selectSource(capture.getString("ref"), "ngày 11 tháng 10")

        val spelled = data.getJSONArray("spelled_dates").getJSONObject(0)
        assertFalse(spelled.getBoolean("year_resolved"))
        assertTrue(spelled.isNull("chronology"))
        val dateRef = registry.resolve(spelled.getString("ref"), AgentReferenceKind.VALUE, "$")
        val parts = boundary.vault.dateParts(requireNotNull(dateRef.valueToken))
        assertNotNull(parts)
        assertNull(parts!!.year)
        assertEquals(10, parts.month)
        assertEquals(11, parts.day)

        val refused = runtime.execute(
            "make_time_anchor",
            JSONObject()
                .put("date_ref", spelled.getString("ref"))
                .put("time_ref", JSONObject.NULL)
                .put("end_date_ref", JSONObject.NULL)
                .put("end_time_ref", JSONObject.NULL),
            "read-only",
        ).envelope
        assertEquals("error", refused.getString("outcome"))
        assertEquals("TEMPORAL_CONSTRAINT", refused.getJSONObject("error").getString("code"))
    }

    // ---- natural answers -----------------------------------------------------------------

    @Test
    fun `a natural year answer resolves under the answer chronology and survives a retry`() = runBlocking {
        val captureId = storeCapture(
            text = "Mua sách ngày 11 tháng 10, chưa ghi giá",
            capturedAt = null,
            zone = null,
        )
        scope = WorkflowCallerScope.AgentSession(scope.scopeId, setOf(captureId), emptySet())
        val runtime = runtimeFor(scope)
        val capture = runtime.observeCapture(captureId)
        val source = runtime.selectSource(capture.getString("ref"), capture.getString("source_text"))

        // The application raises the date question; that fixes the exchange chronology at 2026.
        val asked = runtime.execute(
            "ask_user",
            JSONObject()
                .put("subject_ref", source.getString("source_ref"))
                .put("field", "date")
                .put("question", "Giao dịch này diễn ra ngày nào?"),
            "read-only",
        ).envelope
        assertEquals("clarifying", asked.getString("outcome"))

        val first = runtime.registerClarification(source.getString("source_ref"), "date", "năm nay")
        val firstRef = registry.resolve(first.getString("value_ref"), AgentReferenceKind.VALUE, "$")
        assertEquals("2026-10-11", boundary.vault.parseDate(requireNotNull(firstRef.valueToken)))
        assertEquals(TemporalChronology.CLARIFICATION_ANSWER_TIME.wire, first.getString("chronology"))
        assertEquals(11, first.getJSONObject("date_parts").getInt("day"))
        assertEquals(10, first.getJSONObject("date_parts").getInt("month"))
        assertFalse(first.getString("answer").contains("2026"))
        assertFalse(first.getJSONObject("date_parts").toString().contains("2026"))

        // A retry of the same continuation much later reuses the year the question was asked
        // under: the same answer does not silently become next year's date.
        clockMillis = instantAt(2027, 1, 2, 8, 0)
        val retried = runtime.registerClarification(source.getString("source_ref"), "date", "năm nay")
        val retriedRef = registry.resolve(retried.getString("value_ref"), AgentReferenceKind.VALUE, "$")
        assertEquals("2026-10-11", boundary.vault.parseDate(requireNotNull(retriedRef.valueToken)))
        assertEquals(firstRef.valueToken, retriedRef.valueToken)
        assertEquals(TemporalChronology.CLARIFICATION_ANSWER_TIME.wire, retried.getString("chronology"))
    }

    @Test
    fun `an explicit year combines with the grounded day and a bare day month uses the note year`() = runBlocking {
        val captureId = storeCapture(
            text = "Mua sách ngày 11 tháng 10",
            capturedAt = instantAt(2023, 5, 1, 9, 0),
            zone = ZONE,
        )
        scope = WorkflowCallerScope.AgentSession(scope.scopeId, setOf(captureId), emptySet())
        val runtime = runtimeFor(scope)
        val capture = runtime.observeCapture(captureId)
        val source = runtime.selectSource(capture.getString("ref"), "ngày 11 tháng 10")
        val subject = source.getString("source_ref")

        val dayMonthOnly = runtime.registerClarification(subject, "date", "11 tháng 10")
        val dayMonthRef = registry.resolve(dayMonthOnly.getString("value_ref"), AgentReferenceKind.VALUE, "$")
        assertEquals("2023-10-11", boundary.vault.parseDate(requireNotNull(dayMonthRef.valueToken)))
        assertEquals(TemporalChronology.CAPTURE_CHRONOLOGY.wire, dayMonthOnly.getString("chronology"))

        clockMillis = instantAt(2026, 11, 13, 14, 5)
        val explicitYear = runtime.registerClarification(subject, "date", "2025")
        val explicitYearRef = registry.resolve(explicitYear.getString("value_ref"), AgentReferenceKind.VALUE, "$")
        assertEquals("2025-10-11", boundary.vault.parseDate(requireNotNull(explicitYearRef.valueToken)))
        assertEquals(TemporalChronology.STATED_YEAR.wire, explicitYear.getString("chronology"))
    }

    @Test
    fun `a year only answer without a grounded day is refused with an answerable note`() = runBlocking {
        val captureId = storeCapture(
            text = "Mua sách cuối tuần, chưa ghi ngày",
            capturedAt = instantAt(2023, 5, 1, 9, 0),
            zone = ZONE,
        )
        scope = WorkflowCallerScope.AgentSession(scope.scopeId, setOf(captureId), emptySet())
        val runtime = runtimeFor(scope)
        val capture = runtime.observeCapture(captureId)
        val source = runtime.selectSource(capture.getString("ref"), "Mua sách cuối tuần")

        val answer = runtime.registerClarification(source.getString("source_ref"), "date", "năm nay")
        assertTrue(answer.isNull("value_ref"))
        assertTrue(answer.getString("note").contains("11 tháng 10"))
    }

    // ---- bounded candidate search --------------------------------------------------------

    @Test
    fun `one compatible candidate is returned with its application label and no raw values`() = runBlocking {
        val captureId = storeCapture(
            text = "Cà phê sáng ngày 11 tháng 10 lúc 22h giá 25.000",
            capturedAt = instantAt(2024, 10, 20, 9, 0),
            zone = ZONE,
        )
        val draftId = storeDraft(
            amount = 25_000L,
            date = "2024-10-11",
            time = "22:30",
            memo = "Cà phê sáng",
        )
        scope = WorkflowCallerScope.AgentSession(scope.scopeId, setOf(captureId), setOf(draftId))
        val runtime = runtimeFor(scope)
        val capture = runtime.observeCapture(captureId)
        val source = runtime.selectSource(capture.getString("ref"), capture.getString("source_text"))
        val spelled = source.getJSONArray("spelled_dates").getJSONObject(0)
        val amountRef = source.valueRef { boundary.vault.parseAmountSafely(it) != null }
        val timeRef = source.valueRef { boundary.vault.parseTimeSafely(it) != null }

        val data = runtime.findCandidates(
            dateRef = spelled.getString("ref"),
            amountRef = amountRef,
            timeRef = timeRef,
            dayWindow = 1,
            kinds = "BOTH",
        )

        assertEquals("ONE", data.getString("outcome"))
        assertTrue(data.getBoolean("complete_within_scope"))
        val items = data.getJSONArray("items")
        assertEquals(1, items.length())
        val item = items.getJSONObject(0)
        assertEquals("draft", item.getString("kind"))
        assertEquals("draft #$draftId", item.getString("user_label"))
        assertEquals(0, item.getInt("day_offset"))
        assertEquals(TemporalChronology.CAPTURE_CHRONOLOGY.wire, item.getString("reference_year"))
        assertEquals("match", item.getString("amount"))
        assertEquals(30, item.getInt("time_distance_minutes"))
        val search = data.getJSONObject("search")
        assertEquals("stated-date", search.getString("anchor_kind"))
        assertEquals(3, search.getInt("days_searched"))
        assertEquals(
            TemporalChronology.CAPTURE_CHRONOLOGY.wire,
            search.getJSONArray("chronology").getString(0),
        )
        assertFalse(search.getBoolean("receipt_time_used"))
        assertTrue(search.getString("honest_range").isNotBlank())

        // No raw stored value, date or amount crosses the tool result.
        val serialized = data.toString()
        assertFalse(serialized.contains("2024-10-11"))
        assertFalse(serialized.contains("25000"))
        assertFalse(serialized.contains("22:30"))
        assertFalse(serialized.contains("Cà phê"))

        // Retrieval is not authorization: nothing was linked, confirmed or recorded.
        assertEquals(0, database.transactionDao().findAll().size)
        assertTrue(database.noteEventDao().findForCapture(captureId).isEmpty())
        assertEquals(
            IntakeReviewStatus.PENDING,
            database.pendingBankDraftDao().findById(draftId)!!.reviewStatus,
        )
    }

    @Test
    fun `several candidates are reported by label with distinguishing amount evidence`() = runBlocking {
        val captureId = storeCapture(
            text = "Cà phê sáng ngày 11 tháng 10 giá 25.000",
            capturedAt = instantAt(2024, 10, 20, 9, 0),
            zone = ZONE,
        )
        val matching = storeDraft(amount = 25_000L, date = "2024-10-11", time = "22:30", memo = "Cà phê")
        val conflicting = storeDraft(amount = 31_000L, date = "2024-10-12", time = "08:00", memo = "Cà phê")
        scope = WorkflowCallerScope.AgentSession(
            scope.scopeId,
            setOf(captureId),
            setOf(matching, conflicting),
        )
        val runtime = runtimeFor(scope)
        val capture = runtime.observeCapture(captureId)
        val source = runtime.selectSource(capture.getString("ref"), capture.getString("source_text"))
        val spelled = source.getJSONArray("spelled_dates").getJSONObject(0)
        val amountRef = source.valueRef { boundary.vault.parseAmountSafely(it) != null }

        val data = runtime.findCandidates(
            dateRef = spelled.getString("ref"),
            amountRef = amountRef,
            timeRef = null,
            dayWindow = 1,
            kinds = "DRAFTS",
        )

        assertEquals("MANY", data.getString("outcome"))
        val items = data.getJSONArray("items")
        assertEquals(2, items.length())
        assertEquals("draft #$matching", items.getJSONObject(0).getString("user_label"))
        assertEquals("match", items.getJSONObject(0).getString("amount"))
        assertEquals("draft #$conflicting", items.getJSONObject(1).getString("user_label"))
        assertEquals("conflict", items.getJSONObject(1).getString("amount"))
        assertEquals(1, items.getJSONObject(1).getInt("day_offset"))
        assertNotEquals(
            items.getJSONObject(0).getString("ref"),
            items.getJSONObject(1).getString("ref"),
        )
    }

    @Test
    fun `an empty bounded search reports NONE with the chronologies it read`() = runBlocking {
        val captureId = storeCapture(
            text = "Cà phê sáng ngày 11 tháng 10",
            capturedAt = instantAt(2024, 10, 20, 9, 0),
            zone = ZONE,
        )
        val draftId = storeDraft(amount = 9_000L, date = "2025-03-04", time = "09:00", memo = "Khác")
        scope = WorkflowCallerScope.AgentSession(scope.scopeId, setOf(captureId), setOf(draftId))
        val runtime = runtimeFor(scope)
        val capture = runtime.observeCapture(captureId)
        val source = runtime.selectSource(capture.getString("ref"), capture.getString("source_text"))
        val spelled = source.getJSONArray("spelled_dates").getJSONObject(0)

        val data = runtime.findCandidates(
            dateRef = spelled.getString("ref"),
            amountRef = null,
            timeRef = null,
            dayWindow = 0,
            kinds = "BOTH",
        )

        assertEquals("NONE", data.getString("outcome"))
        assertEquals(0, data.getJSONArray("items").length())
        assertTrue(data.getBoolean("complete_within_scope"))
        val search = data.getJSONObject("search")
        assertEquals(1, search.getInt("days_searched"))
        val chronology = search.getJSONArray("chronology")
        assertEquals(1, chronology.length())
        assertEquals(TemporalChronology.CAPTURE_CHRONOLOGY.wire, chronology.getString(0))
    }

    @Test
    fun `a yearless capture day searches the note year and the current year, both disclosed`() =
        runBlocking {
            val captureId = storeCapture(
                text = "Cà phê sáng 11/10",
                capturedAt = instantAt(2024, 12, 1, 9, 0),
                zone = ZONE,
            )
            val currentYearDraft = storeDraft(
                amount = 12_000L,
                date = "2026-10-11",
                time = "09:00",
                memo = "Cà phê",
            )
            scope = WorkflowCallerScope.AgentSession(scope.scopeId, setOf(captureId), setOf(currentYearDraft))
            val runtime = runtimeFor(scope)
            // The capture-page read mints the one neutral alias for the year-less 11/10 value.
            val capture = runtime.observeCapture(captureId)
            val dateRef = capture.getJSONArray("values").getJSONObject(0).getString("ref")
            val parts = boundary.vault.dateParts(
                requireNotNull(registry.resolve(dateRef, AgentReferenceKind.VALUE, "$").valueToken),
            )
            assertNotNull(parts)
            assertNull(parts!!.year)
            assertEquals(10, parts.month)
            assertEquals(11, parts.day)

            val data = runtime.findCandidates(
                dateRef = dateRef,
                amountRef = null,
                timeRef = null,
                dayWindow = 0,
                kinds = "DRAFTS",
            )

            assertEquals("ONE", data.getString("outcome"))
            val item = data.getJSONArray("items").getJSONObject(0)
            assertEquals("draft #$currentYearDraft", item.getString("user_label"))
            assertEquals(TemporalChronology.CURRENT_TIME.wire, item.getString("reference_year"))
            val chronology = data.getJSONObject("search").getJSONArray("chronology")
            val labels = (0 until chronology.length()).map { chronology.getString(it) }.toSet()
            assertEquals(
                setOf(
                    TemporalChronology.CAPTURE_CHRONOLOGY.wire,
                    TemporalChronology.CURRENT_TIME.wire,
                ),
                labels,
            )
            assertEquals(2, data.getJSONObject("search").getInt("days_searched"))
        }

    @Test
    fun `a search window wider than the declared bound is refused`() = runBlocking {
        val captureId = storeCapture(
            text = "Kỳ nghỉ 2025-01-01 đến 2025-02-20",
            capturedAt = instantAt(2024, 12, 1, 9, 0),
            zone = ZONE,
        )
        scope = WorkflowCallerScope.AgentSession(scope.scopeId, setOf(captureId), emptySet())
        val runtime = runtimeFor(scope)
        val capture = runtime.observeCapture(captureId)
        val values = capture.getJSONArray("values")
        val aliases = (0 until values.length()).map { values.getJSONObject(it).getString("ref") }
        assertEquals(2, aliases.size)

        val result = runtime.execute(
            "find_candidates",
            JSONObject()
                .put("date_ref", aliases[0])
                .put("end_date_ref", aliases[1])
                .put("day_window", 0)
                .put("kinds", "DRAFTS")
                .put("amount_ref", JSONObject.NULL)
                .put("time_ref", JSONObject.NULL)
                .put("cursor", JSONObject.NULL),
            "read-only",
        ).envelope

        assertEquals("error", result.getString("outcome"))
        assertEquals("TEMPORAL_CONSTRAINT", result.getJSONObject("error").getString("code"))
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun SensitiveVault.parseAmountSafely(token: String): Long? =
        runCatching { parseAmount(token) }.getOrNull()

    private fun SensitiveVault.parseTimeSafely(token: String): String? =
        runCatching { parseTime(token) }.getOrNull()

    private fun JSONObject.valueRef(matches: (String) -> Boolean): String {
        val values = getJSONArray("values")
        return (0 until values.length())
            .map { values.getJSONObject(it).getString("ref") }
            .single { reference ->
                val token = requireNotNull(
                    registry.resolve(reference, AgentReferenceKind.VALUE, "$").valueToken,
                )
                matches(token)
            }
    }

    private fun runtimeFor(sessionScope: WorkflowCallerScope.AgentSession): AgentToolRuntime {
        registry = AgentReferenceRegistry(sessionScope.scopeId)
        return AgentToolRuntime(
            commands = commands,
            queries = queries,
            session = AgentToolSession(
                scope = sessionScope,
                boundary = boundary,
                registry = registry,
                clock = WorkflowRunClock { clockMillis },
                runChronology = RunChronology.of(clockMillis, ZONE),
            ),
            declarations = declarations,
        )
    }

    private suspend fun storeCapture(text: String, capturedAt: Long?, zone: String?): Long =
        database.captureMessageDao().insert(
            CaptureMessageEntity(
                captureKind = CaptureKind.USER_NOTE,
                sourceKind = CaptureSourceKind.USER_QUICK_CAPTURE,
                sourceIdentity = "s811-c3-capture-${sequence.incrementAndGet()}",
                bodyText = text,
                capturedAtEpochMillis = capturedAt,
                captureTimezone = zone,
                revision = 0,
                originRef = null,
            ),
        )

    private suspend fun storeDraft(
        amount: Long?,
        date: String?,
        time: String?,
        memo: String?,
    ): Long = database.pendingBankDraftDao().store(
        PendingBankDraftEntity(
            deliveryId = "s811-c3-draft-${sequence.incrementAndGet()}",
            sourcePackage = "synthetic.fixture",
            notificationKey = null,
            postedAtEpochMillis = instantAt(2024, 10, 20, 9, 0),
            bank = "Fixture bank",
            parseStatus = IntakeParseStatus.PARSED,
            amountState = if (amount == null) IntakeFieldState.MISSING else IntakeFieldState.PRESENT,
            amountVnd = amount,
            amountUnresolvedReason = null,
            direction = IntakeDirection.DEBIT,
            momentState = if (date == null) IntakeFieldState.MISSING else IntakeFieldState.PRESENT,
            statedDate = date,
            statedTime = time,
            momentUnresolvedReason = null,
            memoState = if (memo == null) IntakeFieldState.MISSING else IntakeFieldState.PRESENT,
            memo = memo,
            memoUnresolvedReason = null,
            createdAtEpochMillis = 1_798_000_000_000L + sequence.get(),
        ),
    )

    private suspend fun AgentToolRuntime.observeCapture(captureId: Long): JSONObject {
        val capture = queries.sourceExcerpt(captureId) ?: error("capture $captureId is not stored")
        val expected = capture.bodyText?.let { boundary.maskOutbound(it, MaskedPath.TOOL_RESULT) }
        return initialCaptureObservations(16)
            .firstOrNull { it.optString("source_text") == expected }
            ?: error("capture $captureId is not in the trusted scope observations")
    }

    private suspend fun AgentToolRuntime.selectSource(captureRef: String, quote: String): JSONObject {
        val result = execute(
            "select_source",
            JSONObject()
                .put("capture_ref", captureRef)
                .put("quote", quote)
                .put("occurrence", 1),
            "read-only",
        ).envelope
        assertEquals(result.toString(), "ok", result.getString("outcome"))
        return result.getJSONObject("data")
    }

    private suspend fun AgentToolRuntime.makeAnchor(dateRef: String, timeRef: String?): String {
        val result = execute(
            "make_time_anchor",
            JSONObject()
                .put("date_ref", dateRef)
                .put("time_ref", timeRef ?: JSONObject.NULL)
                .put("end_date_ref", JSONObject.NULL)
                .put("end_time_ref", JSONObject.NULL),
            "read-only",
        ).envelope
        assertEquals(result.toString(), "ok", result.getString("outcome"))
        return result.getJSONObject("data").getString("anchor_ref")
    }

    private suspend fun AgentToolRuntime.findCandidates(
        dateRef: String,
        amountRef: String?,
        timeRef: String?,
        dayWindow: Int,
        kinds: String,
    ): JSONObject {
        val result = execute(
            "find_candidates",
            JSONObject()
                .put("date_ref", dateRef)
                .put("end_date_ref", JSONObject.NULL)
                .put("day_window", dayWindow)
                .put("kinds", kinds)
                .put("amount_ref", amountRef ?: JSONObject.NULL)
                .put("time_ref", timeRef ?: JSONObject.NULL)
                .put("cursor", JSONObject.NULL),
            "read-only",
        ).envelope
        assertEquals(result.toString(), "ok", result.getString("outcome"))
        return result.getJSONObject("data")
    }

    private companion object {
        const val ZONE = "Asia/Ho_Chi_Minh"
        val VIETNAM_2026_11_13_1405: Long =
            ZonedDateTime.of(2026, 11, 13, 14, 5, 0, 0, ZoneId.of(ZONE)).toInstant().toEpochMilli()

        fun instantAt(
            year: Int,
            month: Int,
            day: Int,
            hour: Int,
            minute: Int,
        ): Long = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneId.of(ZONE))
            .toInstant()
            .toEpochMilli()
    }
}
