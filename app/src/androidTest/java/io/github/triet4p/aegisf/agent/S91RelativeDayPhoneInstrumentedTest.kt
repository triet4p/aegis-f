package io.github.triet4p.aegisf.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.triet4p.aegisf.data.CaptureEventQuery
import io.github.triet4p.aegisf.data.CaptureMessageEntity
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.NoteEventStatus
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.ReviseCaptureRequest
import io.github.triet4p.aegisf.data.SaveCaptureRequest
import io.github.triet4p.aegisf.data.SystemLedgerClock
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowChange
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.privacy.MaskedPath
import io.github.triet4p.aegisf.privacy.MaskedPromptBoundary
import io.github.triet4p.aegisf.privacy.SensitiveVault
import io.github.triet4p.aegisf.provider.HostedToolDeclaration
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof of the S9.1 source-backed extraction boundary.
 *
 * Everything here runs on the real Android runtime against a real file-backed Room database in
 * this isolated evidence package's own data directory: the real command service, the real
 * masking boundary, the real reference registry and the real tool runtime. No provider request is
 * made, so no credential and no network are involved; the contribution over the host-side tests is
 * the actual device SQLite/Room behaviour, the device timezone the capture records, persisted
 * durability across a process-level reopen, and the real editable capture surface.
 */
@RunWith(AndroidJUnit4::class)
class S91RelativeDayPhoneInstrumentedTest {

    private lateinit var context: Context
    private lateinit var database: LedgerDatabase
    private lateinit var commands: WorkflowCommandService
    private lateinit var queries: WorkflowQueryService
    private lateinit var boundary: MaskedPromptBoundary
    private lateinit var declarations: List<HostedToolDeclaration>
    private val sequence = AtomicInteger()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
        database = openDatabase()
        commands = WorkflowCommandService(
            database = database,
            clock = SystemLedgerClock,
            timezoneId = { TimeZone.getDefault().id },
        )
        queries = WorkflowQueryService(database)
        boundary = MaskedPromptBoundary(SensitiveVault(SCOPE_ID))
        declarations = AgentToolDeclarations.load(context.assets::open)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun twoPurchasesPersistWithTheirOwnDaysAndTheCaptureStaysEditable() = runBlocking {
        val coffee = "Coffee 15,000 VND yesterday at 08:10"
        val taxi = "Taxi 40,000 VND on 2026-10-21 at 13:05"
        val captureId = storeCapture("$coffee | $taxi")
        val capture = queries.sourceExcerpt(captureId)!!
        val runtime = runtimeFor(setOf(captureId))
        val observation = observe(runtime, capture)
        val masked = observation.getString("source_text")
        val coffeeSource = select(runtime, observation.getString("ref"), masked.substringBefore(" | ").trim())
        val taxiSource = select(runtime, observation.getString("ref"), masked.substringAfter(" | ").trim())

        // Only the occurrence that states the day word carries a relative-day alias.
        assertEquals(1, coffeeSource.getJSONArray("relative_dates").length())
        assertEquals(0, taxiSource.getJSONArray("relative_dates").length())

        val coffeeEvent = record(
            runtime,
            coffeeSource,
            anchor = makeAnchor(
                runtime,
                dateRef = coffeeSource.getJSONArray("relative_dates").getJSONObject(0).getString("ref"),
                timeRef = valueRef(boundary, coffeeSource, "time"),
            ),
            summary = "Coffee",
            clarification = "none",
        )
        val taxiEvent = record(
            runtime,
            taxiSource,
            anchor = makeAnchor(
                runtime,
                dateRef = valueRef(boundary, taxiSource, "date"),
                timeRef = valueRef(boundary, taxiSource, "time"),
            ),
            summary = "Taxi",
            clarification = "none",
        )
        assertEquals("ok", coffeeEvent.getString("outcome"))
        assertEquals("ok", taxiEvent.getString("outcome"))

        val rows = database.noteEventDao().findForCapture(captureId).sortedBy { it.spanStart }
        assertEquals(2, rows.size)
        assertEquals(coffee, capture.bodyText!!.substring(rows[0].spanStart, rows[0].spanEnd))
        assertEquals(taxi, capture.bodyText.substring(rows[1].spanStart, rows[1].spanEnd))
        assertEquals(listOf("Coffee", "Taxi"), rows.mapNotNull { it.normalizedText })
        assertEquals(setOf(15_000L, 40_000L), rows.mapNotNull { it.amountVnd }.toSet())
        assertEquals(previousLocalDay(capture), rows[0].anchorDate)
        assertEquals("08:10", rows[0].anchorTime)
        assertEquals(capture.captureTimezone, rows[0].anchorTimezone)
        assertEquals("2026-10-21", rows[1].anchorDate)
        assertEquals("13:05", rows[1].anchorTime)
        assertEquals(NoteEventStatus.INTERPRETED, rows[0].status)
        assertEquals(NoteEventStatus.INTERPRETED, rows[1].status)
        assertTrue("extraction never writes money", database.transactionDao().findAll().isEmpty())

        // The user's own edit remains available and ends what was read from the old text.
        val revised = commands.reviseCapture(
            WorkflowCallerScope.User,
            ReviseCaptureRequest(
                idempotencyKey = "s91-device-revise-${sequence.incrementAndGet()}",
                captureId = captureId,
                expectedRevision = capture.revision,
                bodyText = "Coffee 15,000 VND on 2026-10-20 at 08:10 | $taxi",
            ),
        )
        assertTrue(revised is WorkflowCommandOutcome.Applied)
        assertEquals(
            "Coffee 15,000 VND on 2026-10-20 at 08:10 | $taxi",
            queries.sourceExcerpt(captureId)!!.bodyText,
        )

        // Both readings and the user's edit survive a real database reopen.
        database.close()
        database = openDatabase()
        val reopened = WorkflowQueryService(database)
        assertEquals(
            2,
            reopened.captureEvents(
                CaptureEventQuery(captureId, QueryPageRequest(10, 0)),
            ).items.size,
        )
        assertEquals(
            "Coffee 15,000 VND on 2026-10-20 at 08:10 | $taxi",
            reopened.sourceExcerpt(captureId)!!.bodyText,
        )
    }

    @Test
    fun aPeriodWordStaysAmbiguousAndTheSavedCaptureStaysEditable() = runBlocking {
        val text = "Taxi 65,000 VND last night"
        val captureId = storeCapture(text)
        val capture = queries.sourceExcerpt(captureId)!!
        val runtime = runtimeFor(setOf(captureId))
        val observation = observe(runtime, capture)
        val source = select(runtime, observation.getString("ref"), observation.getString("source_text"))

        assertEquals("a period is not one calendar day", 0, source.getJSONArray("relative_dates").length())
        val recorded = record(
            runtime,
            source,
            anchor = null,
            summary = "Taxi",
            clarification = "date",
        )
        assertEquals("ok", recorded.getString("outcome"))

        val stored = database.noteEventDao().findForCapture(captureId).single()
        assertNull(stored.anchorDate)
        assertNull(stored.anchorTime)
        assertEquals(65_000L, stored.amountVnd)
        assertEquals(NoteEventStatus.UNRESOLVED, stored.status)
        assertEquals("Clarification required: date", stored.unresolvedReason)
        assertTrue("still no expense", database.transactionDao().findAll().isEmpty())

        // Low-quality output leaves the saved capture exactly as the user wrote it, and editable.
        assertEquals(text, WorkflowQueryService(database).sourceExcerpt(captureId)!!.bodyText)
        val revised = commands.reviseCapture(
            WorkflowCallerScope.User,
            ReviseCaptureRequest(
                idempotencyKey = "s91-device-period-revise-${sequence.incrementAndGet()}",
                captureId = captureId,
                expectedRevision = capture.revision,
                bodyText = "Taxi 65,000 VND on 2026-10-20 at 21:30",
            ),
        )
        assertTrue(revised is WorkflowCommandOutcome.Applied)
        assertNull(database.noteEventDao().findForCapture(captureId).single().normalizedText)
    }

    // ---- Helpers -------------------------------------------------------------------------

    private fun openDatabase(): LedgerDatabase =
        Room.databaseBuilder(context, LedgerDatabase::class.java, DB_NAME).build()

    private suspend fun storeCapture(text: String): Long {
        val saved = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest("s91-device-capture-${sequence.incrementAndGet()}", text),
        )
        assertTrue(saved is WorkflowCommandOutcome.Applied)
        return ((saved as WorkflowCommandOutcome.Applied).change as WorkflowChange.CaptureSaved).captureId
    }

    private fun runtimeFor(captureIds: Set<Long>): AgentToolRuntime {
        val scope = WorkflowCallerScope.AgentSession(SCOPE_ID, captureIds, emptySet())
        return AgentToolRuntime(
            commands = commands,
            queries = queries,
            session = AgentToolSession(
                scope = scope,
                boundary = boundary,
                registry = AgentReferenceRegistry(scope.scopeId),
            ),
            declarations = declarations,
        )
    }

    private suspend fun observe(
        runtime: AgentToolRuntime,
        capture: CaptureMessageEntity,
    ): JSONObject {
        val expected = capture.bodyText?.let { boundary.maskOutbound(it, MaskedPath.TOOL_RESULT) }
        return runtime.initialCaptureObservations(16)
            .firstOrNull { it.optString("source_text") == expected }
            ?: error("the capture is not in the trusted scope observations")
    }

    private suspend fun select(
        runtime: AgentToolRuntime,
        captureRef: String,
        quote: String,
    ): JSONObject {
        val result = runtime.execute(
            "select_source",
            JSONObject().put("capture_ref", captureRef).put("quote", quote).put("occurrence", 1),
            "read-only",
        ).envelope
        assertEquals(result.toString(), "ok", result.getString("outcome"))
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
        assertEquals(result.toString(), "ok", result.getString("outcome"))
        return result.getJSONObject("data").getString("anchor_ref")
    }

    private suspend fun record(
        runtime: AgentToolRuntime,
        source: JSONObject,
        anchor: String?,
        summary: String,
        clarification: String,
    ): JSONObject = runtime.execute(
        "record_event",
        JSONObject()
            .put("source_ref", source.getString("source_ref"))
            .put("summary", summary)
            .put("amount_ref", valueRef(boundary, source, "amount"))
            .put("anchor_ref", anchor ?: JSONObject.NULL)
            .put("merchant_ref", JSONObject.NULL)
            .put("clarification", clarification),
        "record-${sequence.incrementAndGet()}",
    ).envelope

    private fun valueRef(boundary: MaskedPromptBoundary, source: JSONObject, role: String): String {
        val values = source.getJSONArray("values")
        for (index in 0 until values.length()) {
            val item = values.getJSONObject(index)
            val token = item.getString("display")
            val parses = when (role) {
                "amount" -> runCatching { boundary.vault.parseAmount(token) }.isSuccess
                "date" -> runCatching { boundary.vault.parseDate(token) }.isSuccess
                "time" -> runCatching { boundary.vault.parseTime(token) }.isSuccess
                else -> false
            }
            if (parses) return item.getString("ref")
        }
        error("no value alias usable as $role in the selected occurrence")
    }

    /** The capture's own local day before it, computed with Calendar rather than the resolver. */
    private fun previousLocalDay(capture: CaptureMessageEntity): String {
        val instant = capture.capturedAtEpochMillis ?: error("the capture records no instant")
        val zone = TimeZone.getTimeZone(capture.captureTimezone ?: error("the capture records no zone"))
        val calendar = Calendar.getInstance(zone).apply {
            timeInMillis = instant
            add(Calendar.DAY_OF_MONTH, -1)
        }
        return "%04d-%02d-%02d".format(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
    }

    private companion object {
        const val DB_NAME = "s91-relative-day.db"
        const val SCOPE_ID = "s91-relative-day-device"
    }
}
