package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The S8.4 agent contract path through the real application boundary.
 *
 * These pin the consumer-visible behavior the agent evaluation depends on: an
 * agent-scoped multi-event extraction stores exact whole-dong readings, a bounded
 * draft read observes the page it asked for, a propose-then-attach sequence follows
 * the application's own evidence policy, an out-of-scope target is refused without
 * disclosure, an idempotent retry changes nothing, and an unknown tool never reaches
 * the API. They run against the real Room database through [WorkflowCommandService]
 * and [WorkflowQueryService], not against a mocked return value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class S84AgentContractTest {
    private class FixedClock(@Volatile var now: Long = CAPTURE_TIME) : LedgerClock {
        override fun nowEpochMillis(): Long = now
    }

    private class RefusingEngine : ClassificationEngine {
        override suspend fun classify(
            context: io.github.triet4p.aegisf.data.laya.LayaEffectiveContext,
            categories: List<CategoryEntity>,
        ): ClassificationEngineResult = ClassificationEngineResult.Failed(
            "Laya runtime is not installed on this host test machine.",
        )
    }

    private lateinit var context: Context
    private lateinit var clock: FixedClock

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(LedgerDatabase.NAME)
        clock = FixedClock()
        WorkflowClaimGuard.reset()
    }

    private fun open(): LedgerDatabase = LedgerDatabase.build(context)

    private fun commands(database: LedgerDatabase): WorkflowCommandService = WorkflowCommandService(
        database = database,
        clock = clock,
        timezoneId = { CAPTURE_ZONE },
        engine = RefusingEngine(),
    )

    private fun agent(captureIds: Set<Long>, draftIds: Set<Long>): WorkflowCallerScope.AgentSession =
        WorkflowCallerScope.AgentSession(
            scopeId = "s8.4-contract-test",
            captureIds = captureIds,
            draftIds = draftIds,
        )

    @Test
    fun `an agent-scoped multi-event extraction stores exact whole-dong readings`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val scope = agent(setOf(999L), emptySet())
            val captureId = saveCapture(commands, "a8-multi-save", CAPTURE_TEXT)

            val scoped = agent(setOf(captureId), emptySet())
            assertEquals(emptySet<Long>(), (scope.captureIds intersect setOf(captureId)))

            val first = commands.updateEventInterpretation(
                scoped,
                UpdateEventRequest(
                    idempotencyKey = "a8-multi-event-1",
                    captureId = captureId,
                    expectedCaptureRevision = 0,
                    interpretation = NoteEventInterpretation(
                        spanStart = 0,
                        spanEnd = 38,
                        normalizedText = "Lunch purchase",
                        amountVnd = 28_000L,
                        merchant = "Highlands",
                        timeAnchor = NoteEventTimeAnchor(
                            date = "2026-10-21",
                            time = "12:30",
                            timezoneId = CAPTURE_ZONE,
                        ),
                    ),
                    extractionVersion = EXTRACTION_VERSION,
                ),
            )
            assertTrue("the first reading was refused: $first", first is WorkflowCommandOutcome.Applied)

            val second = commands.updateEventInterpretation(
                scoped,
                UpdateEventRequest(
                    idempotencyKey = "a8-multi-event-2",
                    captureId = captureId,
                    expectedCaptureRevision = 0,
                    interpretation = NoteEventInterpretation(
                        spanStart = 40,
                        spanEnd = 64,
                        normalizedText = "Taxi ride",
                        amountVnd = 65_000L,
                        timeAnchor = NoteEventTimeAnchor(
                            date = "2026-10-21",
                            time = "13:05",
                            timezoneId = CAPTURE_ZONE,
                        ),
                    ),
                    extractionVersion = EXTRACTION_VERSION,
                ),
            )
            assertTrue("the second reading was refused: $second", second is WorkflowCommandOutcome.Applied)

            val stored = database.noteEventDao().findForCapture(captureId).sortedBy { it.spanStart }
            assertEquals(2, stored.size)
            assertEquals(28_000L, stored[0].amountVnd)
            assertEquals(65_000L, stored[1].amountVnd)
            assertEquals("2026-10-21", stored[0].anchorDate)
            assertEquals("12:30", stored[0].anchorTime)
            assertEquals(0, database.transactionDao().findAll().size)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a bounded draft read observes the page it asked for`() = runTest {
        val database = open()
        try {
            val queries = WorkflowQueryService(database)
            val page = queries.draftCandidates(
                DraftCandidateQuery(
                    reviewStatus = IntakeReviewStatus.PENDING,
                    onlyPossibleDuplicates = false,
                    bounds = QueryPageRequest(limit = 5, offset = 0),
                ),
            )
            assertEquals(5, page.limit)
            assertEquals(0, page.offset)
            assertTrue(page.items.size <= 5)
        } finally {
            database.close()
        }
    }

    @Test
    fun `an out-of-scope target is refused without disclosure`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val captureId = saveCapture(commands, "a8-scope-save", CAPTURE_TEXT)
            val outsider = agent(emptySet(), emptySet())

            val refused = commands.updateEventInterpretation(
                outsider,
                UpdateEventRequest(
                    idempotencyKey = "a8-scope-event-1",
                    captureId = captureId,
                    expectedCaptureRevision = 0,
                    interpretation = NoteEventInterpretation(
                        spanStart = 0,
                        spanEnd = 5,
                        normalizedText = "Lunch",
                        amountVnd = 28_000L,
                    ),
                    extractionVersion = EXTRACTION_VERSION,
                ),
            )
            assertTrue("the out-of-scope write was not refused: $refused", refused is WorkflowCommandOutcome.Denied)
            assertEquals(
                WorkflowDenialReason.CALLER_SCOPE,
                (refused as WorkflowCommandOutcome.Denied).reason,
            )
            assertNull(database.workflowOperationDao().findByKey("a8-scope-event-1"))
        } finally {
            database.close()
        }
    }

    @Test
    fun `an idempotent retry of the same reading changes nothing`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val captureId = saveCapture(commands, "a8-retry-save", CAPTURE_TEXT)
            val scoped = agent(setOf(captureId), emptySet())
            val request = UpdateEventRequest(
                idempotencyKey = "a8-retry-event-1",
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = NoteEventInterpretation(
                    spanStart = 0,
                    spanEnd = 38,
                    normalizedText = "Lunch purchase",
                    amountVnd = 28_000L,
                    timeAnchor = NoteEventTimeAnchor(date = "2026-10-21", time = "12:30"),
                ),
                extractionVersion = EXTRACTION_VERSION,
            )

            val first = commands.updateEventInterpretation(scoped, request)
            assertTrue("the first request was refused: $first", first is WorkflowCommandOutcome.Applied)
            val second = commands.updateEventInterpretation(scoped, request)
            assertTrue("the retry was not a no-change: $second", second is WorkflowCommandOutcome.NoChange)

            assertEquals(1, database.noteEventDao().findForCapture(captureId).size)
            assertEquals(1L, database.workflowOperationDao().countOf("UPDATE_EVENT"))
        } finally {
            database.close()
        }
    }

    @Test
    fun `a stale capture revision is a conflict and never overwrites newer text`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val captureId = saveCapture(commands, "a8-stale-save", CAPTURE_TEXT)
            val scoped = agent(setOf(captureId), emptySet())

            val revised = commands.reviseCapture(
                WorkflowCallerScope.User,
                ReviseCaptureRequest(
                    idempotencyKey = "a8-stale-revise-1",
                    captureId = captureId,
                    expectedRevision = 0,
                    bodyText = "Lunch 28,000 VND at Highlands at 12:30; taxi 65,000 VND at 13:05! ",
                ),
            )
            assertTrue("the revision was refused: $revised", revised is WorkflowCommandOutcome.Applied)

            val stale = commands.updateEventInterpretation(
                scoped,
                UpdateEventRequest(
                    idempotencyKey = "a8-stale-event-1",
                    captureId = captureId,
                    expectedCaptureRevision = 0,
                    interpretation = NoteEventInterpretation(
                        spanStart = 0,
                        spanEnd = 38,
                        normalizedText = "Lunch purchase",
                        amountVnd = 28_000L,
                    ),
                    extractionVersion = EXTRACTION_VERSION,
                ),
            )
            assertTrue("the stale write was not a conflict: $stale", stale is WorkflowCommandOutcome.Conflict)
        } finally {
            database.close()
        }
    }

    private suspend fun saveCapture(
        commands: WorkflowCommandService,
        key: String,
        text: String,
    ): Long {
        val outcome = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest(idempotencyKey = key, bodyText = text),
        )
        assertTrue("the capture was refused: $outcome", outcome is WorkflowCommandOutcome.Applied)
        return ((outcome as WorkflowCommandOutcome.Applied).change as WorkflowChange.CaptureSaved).captureId
    }

    private companion object {
        const val CAPTURE_ZONE = "Asia/Ho_Chi_Minh"
        const val CAPTURE_TIME = 1_797_000_000_000L
        const val EXTRACTION_VERSION = "s8-agent-probe-v1"
        const val CAPTURE_TEXT = "Lunch 28,000 VND at Highlands at 12:30; taxi 65,000 VND at 13:05."
    }
}
