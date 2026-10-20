package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.notification.PermittedNotificationContent
import kotlinx.coroutines.test.runTest
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
 * The stored behavior of event interpretations and the links between them and bank drafts.
 *
 * These run against the real Room file, so a reading, a link, and a refusal are observed the
 * way the app observes them after a restart, not as a mocked return value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteEventContextTest {
    private class FixedClock(var now: Long = CAPTURE_TIME) : LedgerClock {
        override fun nowEpochMillis(): Long = now
    }

    private lateinit var context: Context
    private lateinit var clock: FixedClock

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(LedgerDatabase.NAME)
        clock = FixedClock()
    }

    private fun open(): LedgerDatabase = LedgerDatabase.build(context)

    private fun captures(database: LedgerDatabase) = CaptureMessageRepository(
        database = database,
        clock = clock,
        timezoneId = { CAPTURE_ZONE },
    )

    private fun events(database: LedgerDatabase) = EventContextRepository(database, clock)

    @Test
    fun `two events from one note attach independently to different drafts`() = runTest {
        val database = open()
        val query = WorkflowQueryService(database)
        try {
            val captureId = captures(database).saveQuickCapture(NOTE_TEXT)
            val fuelEvent = storeFuelEvent(database, captureId)
            val snackEvent = storeSnackEvent(database, captureId)

            assertEquals(2, events(database).eventsForCapture(captureId).size)
            assertEquals(NoteEventStatus.INTERPRETED, fuelEvent.status)
            assertEquals(NoteEventStatus.INTERPRETED, snackEvent.status)
            assertEquals(50_000L, fuelEvent.amountVnd)
            assertEquals(20_000L, snackEvent.amountVnd)
            assertEquals("Circle K", snackEvent.merchant)
            assertEquals("2026-10-16", snackEvent.anchorDate)
            assertEquals("15:15", snackEvent.anchorTime)
            assertEquals(CAPTURE_ZONE, snackEvent.anchorTimezone)
            assertEquals(EXTRACTION_VERSION, fuelEvent.extractionVersion)
            assertEquals(0, fuelEvent.revision)
            assertEquals(0, fuelEvent.sourceRevision)

            val fuelDraft = storeDraft(database, "fuel-key", 50_000L)
            val snackDraft = storeDraft(database, "snack-key", 20_000L)

            assertTrue(events(database).proposeLink(fuelEvent.id, fuelDraft, "same day fuel") is ContextLinkResult.Linked)
            assertTrue(events(database).acceptLink(fuelEvent.id, fuelDraft, 0, "USER_ACCEPTED") is ContextLinkResult.Linked)
            assertTrue(events(database).proposeLink(snackEvent.id, snackDraft, "snack minutes later") is ContextLinkResult.Linked)
            assertTrue(events(database).acceptLink(snackEvent.id, snackDraft, 0, "USER_ACCEPTED") is ContextLinkResult.Linked)

            val fuelLinks = events(database).linksForEvent(fuelEvent.id)
            val snackLinks = events(database).linksForEvent(snackEvent.id)
            assertEquals(1, fuelLinks.size)
            assertEquals(1, snackLinks.size)
            assertEquals(ContextLinkState.ACCEPTED, fuelLinks.single().state)
            assertEquals(ContextLinkState.ACCEPTED, snackLinks.single().state)
            assertEquals(fuelDraft, fuelLinks.single().draftId)
            assertEquals(snackDraft, snackLinks.single().draftId)
            assertEquals("USER_ACCEPTED", fuelLinks.single().userOverride)
            assertEquals(fuelEvent.id, fuelLinks.single().activeEventId)

            // Linking a draft never touches the draft's own review lifecycle.
            val drafts = WorkflowQueryService(database).draftCandidates(ALL_DRAFTS).items.associateBy { it.id }
            assertEquals(IntakeReviewStatus.PENDING, drafts.getValue(fuelDraft).reviewStatus)
            assertEquals(IntakeReviewStatus.PENDING, drafts.getValue(snackDraft).reviewStatus)
            assertNull(drafts.getValue(fuelDraft).confirmedTransactionId)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a refused link stays refused across retries, restarts, and re-processing`() = runTest {
        val database = open()
        val query = WorkflowQueryService(database)
        val captureId = captures(database).saveQuickCapture(NOTE_TEXT)
        val fuelEvent = storeFuelEvent(database, captureId)
        val draftId = storeDraft(database, "fuel-key", 50_000L)
        assertTrue(events(database).proposeLink(fuelEvent.id, draftId, "candidate") is ContextLinkResult.Linked)
        assertTrue(events(database).rejectLink(fuelEvent.id, draftId, 0, "USER_REJECTED") is ContextLinkResult.Linked)

        val refused = events(database).linksForEvent(fuelEvent.id).single()
        assertEquals(ContextLinkState.REJECTED, refused.state)
        assertNull(refused.activeEventId)
        assertEquals("USER_REJECTED", refused.userOverride)
        assertEquals(NoteEventStatus.NO_MATCH, events(database).findEvent(fuelEvent.id)!!.status)

        // A repeated proposal of the same pair changes nothing and does not revive the link.
        assertEquals(ContextLinkResult.Rejected, events(database).proposeLink(fuelEvent.id, draftId))
        assertEquals(ContextLinkResult.Rejected, events(database).acceptLink(fuelEvent.id, draftId, refused.revision))
        assertEquals(ContextLinkResult.Rejected, events(database).unlink(fuelEvent.id, draftId, refused.revision))
        val afterRetries = events(database).linksForEvent(fuelEvent.id)
        assertEquals(1, afterRetries.size)
        assertEquals(ContextLinkState.REJECTED, afterRetries.single().state)
        assertEquals(refused.revision, afterRetries.single().revision)

        // Re-processing the same reading must not append a second event or revive the link.
        assertEquals(
            NoteEventWriteResult.NoChange,
            events(database).recordEvent(
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = reading(fuelSpanStart(), fuelSpanEnd()),
                extractionVersion = EXTRACTION_VERSION,
            ),
        )
        assertEquals(1, events(database).eventsForCapture(captureId).size)

        database.close()

        val reopened = open()
        try {
            val restarted = events(reopened).linksForEvent(fuelEvent.id).single()
            assertEquals(ContextLinkState.REJECTED, restarted.state)
            assertEquals("USER_REJECTED", restarted.userOverride)
            assertEquals(NoteEventStatus.NO_MATCH, events(reopened).findEvent(fuelEvent.id)!!.status)
            assertEquals(ContextLinkResult.Rejected, events(reopened).proposeLink(fuelEvent.id, draftId))
            assertEquals(1, events(reopened).linksForEvent(fuelEvent.id).size)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `invalid spans, stale capture revisions, and unknown references fail without mutation`() = runTest {
        val database = open()
        val query = WorkflowQueryService(database)
        try {
            val captureId = captures(database).saveQuickCapture(NOTE_TEXT)
            val repository = events(database)

            val beyondText = repository.recordEvent(
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = reading(0, NOTE_TEXT.length + 10),
                extractionVersion = EXTRACTION_VERSION,
            )
            assertTrue(beyondText is NoteEventWriteResult.Invalid)
            assertEquals(NoteEventField.SPAN, (beyondText as NoteEventWriteResult.Invalid).field)

            val backwards = repository.recordEvent(
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = reading(10, 4),
                extractionVersion = EXTRACTION_VERSION,
            )
            assertTrue(backwards is NoteEventWriteResult.Invalid)

            val negative = repository.recordEvent(
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = reading(-1, 4),
                extractionVersion = EXTRACTION_VERSION,
            )
            assertTrue(negative is NoteEventWriteResult.Invalid)

            val staleRevision = repository.recordEvent(
                captureId = captureId,
                expectedCaptureRevision = 7,
                interpretation = reading(0, 20),
                extractionVersion = EXTRACTION_VERSION,
            )
            assertEquals(NoteEventWriteResult.Changed, staleRevision)

            val unknownCapture = repository.recordEvent(
                captureId = 9_999L,
                expectedCaptureRevision = 0,
                interpretation = reading(0, 20),
                extractionVersion = EXTRACTION_VERSION,
            )
            assertEquals(NoteEventWriteResult.NotFound, unknownCapture)

            val noAmountReason = repository.recordEvent(
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = NoteEventInterpretation(
                    spanStart = 0,
                    spanEnd = 20,
                    normalizedText = "Fuel",
                    amountVnd = null,
                ),
                extractionVersion = EXTRACTION_VERSION,
            )
            assertTrue(noAmountReason is NoteEventWriteResult.Invalid)
            assertEquals(NoteEventField.AMOUNT, (noAmountReason as NoteEventWriteResult.Invalid).field)

            val badTime = repository.recordEvent(
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = reading(0, 20).copy(
                    timeAnchor = NoteEventTimeAnchor(date = "2026-10-16", time = "25:00"),
                ),
                extractionVersion = EXTRACTION_VERSION,
            )
            assertTrue(badTime is NoteEventWriteResult.Invalid)
            assertEquals(NoteEventField.TIME, (badTime as NoteEventWriteResult.Invalid).field)

            val backwardsInterval = repository.recordEvent(
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = reading(0, 20).copy(
                    timeAnchor = NoteEventTimeAnchor(
                        date = "2026-10-16",
                        time = "15:00",
                        timezoneId = CAPTURE_ZONE,
                        intervalEndDate = "2026-10-15",
                    ),
                ),
                extractionVersion = EXTRACTION_VERSION,
            )
            assertTrue(backwardsInterval is NoteEventWriteResult.Invalid)

            // Not one of those refusals wrote anything.
            assertTrue(repository.eventsForCapture(captureId).isEmpty())
            assertEquals(
                0L,
                scalar(database.openHelper.writableDatabase, "SELECT COUNT(*) FROM note_events"),
            )
            assertEquals(
                0L,
                scalar(database.openHelper.writableDatabase, "SELECT COUNT(*) FROM context_links"),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `an event holds one active draft while several events may describe one draft`() = runTest {
        val database = open()
        val query = WorkflowQueryService(database)
        try {
            val captureId = captures(database).saveQuickCapture(NOTE_TEXT)
            val fuelEvent = storeFuelEvent(database, captureId)
            val snackEvent = storeSnackEvent(database, captureId)
            val firstDraft = storeDraft(database, "fuel-key", 50_000L)
            val secondDraft = storeDraft(database, "snack-key", 20_000L)
            val repository = events(database)

            assertTrue(repository.proposeLink(fuelEvent.id, firstDraft) is ContextLinkResult.Linked)
            val second = repository.proposeLink(fuelEvent.id, secondDraft)
            assertEquals(ContextLinkResult.MultipleDrafts, second)
            assertEquals(1, repository.linksForEvent(fuelEvent.id).size)
            assertEquals(firstDraft, repository.linksForEvent(fuelEvent.id).single().draftId)

            assertTrue(repository.acceptLink(fuelEvent.id, firstDraft, 0) is ContextLinkResult.Linked)
            assertEquals(ContextLinkResult.MultipleDrafts, repository.proposeLink(fuelEvent.id, secondDraft))
            assertEquals(1, repository.linksForEvent(fuelEvent.id).size)

            // A second, independent event may describe the same draft.
            assertTrue(repository.proposeLink(snackEvent.id, firstDraft) is ContextLinkResult.Linked)
            assertTrue(repository.acceptLink(snackEvent.id, firstDraft, 0) is ContextLinkResult.Linked)
            assertEquals(2, repository.linksForDraft(firstDraft).size)
            assertEquals(1, repository.linksForEvent(snackEvent.id).size)

            val unknownDraft = repository.proposeLink(fuelEvent.id, 9_999L)
            assertEquals(ContextLinkResult.DraftUnavailable, unknownDraft)
            val unknownEvent = repository.proposeLink(9_999L, firstDraft)
            assertEquals(ContextLinkResult.NotFound, unknownEvent)
        } finally {
            database.close()
        }
    }

    @Test
    fun `editing the source clears the derived reading and its active links but keeps the refusal`() =
        runTest {
            val database = open()
            val query = WorkflowQueryService(database)
            try {
                val captureId = captures(database).saveQuickCapture(NOTE_TEXT)
                val fuelEvent = storeFuelEvent(database, captureId)
                val snackEvent = storeSnackEvent(database, captureId)
                val fuelDraft = storeDraft(database, "fuel-key", 50_000L)
                val snackDraft = storeDraft(database, "snack-key", 20_000L)
                val repository = events(database)

                assertTrue(repository.proposeLink(fuelEvent.id, fuelDraft) is ContextLinkResult.Linked)
                assertTrue(repository.acceptLink(fuelEvent.id, fuelDraft, 0) is ContextLinkResult.Linked)
                assertTrue(repository.proposeLink(snackEvent.id, snackDraft) is ContextLinkResult.Linked)
                assertTrue(repository.rejectLink(snackEvent.id, snackDraft, 0, "USER_REJECTED") is ContextLinkResult.Linked)

                val saved = captures(database).revise(captureId, 0, REVISED_NOTE)
                assertEquals(CaptureMessageWriteResult.Saved(1), saved)

                val afterEdit = repository.eventsForCapture(captureId)
                assertEquals(2, afterEdit.size)
                afterEdit.forEach { event ->
                    assertNull(event.normalizedText)
                    assertNull(event.amountVnd)
                    assertNull(event.merchant)
                    assertNull(event.anchorDate)
                    assertNull(event.anchorTime)
                    assertEquals(SOURCE_EDITED, event.unresolvedReason)
                }
                assertEquals(NoteEventStatus.UNRESOLVED, afterEdit.first { it.id == fuelEvent.id }.status)
                assertEquals(NoteEventStatus.NO_MATCH, afterEdit.first { it.id == snackEvent.id }.status)

                // The accepted link described a reading that no longer exists; the refusal stands.
                assertTrue(repository.linksForEvent(fuelEvent.id).isEmpty())
                val refused = repository.linksForEvent(snackEvent.id).single()
                assertEquals(ContextLinkState.REJECTED, refused.state)

                // The reading cannot be written against a capture revision the caller never saw.
                assertEquals(
                    NoteEventWriteResult.Changed,
                    repository.recordEvent(
                        captureId = captureId,
                        expectedCaptureRevision = 0,
                        interpretation = reading(0, 20),
                        extractionVersion = EXTRACTION_VERSION,
                    ),
                )
            } finally {
                database.close()
            }
        }

    @Test
    fun `deleting the source leaves no derived text and no invalid link`() = runTest {
        val database = open()
        val query = WorkflowQueryService(database)
        try {
            val captureId = captures(database).saveQuickCapture(NOTE_TEXT)
            val fuelEvent = storeFuelEvent(database, captureId)
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val repository = events(database)
            assertTrue(repository.proposeLink(fuelEvent.id, draftId) is ContextLinkResult.Linked)
            assertTrue(repository.acceptLink(fuelEvent.id, draftId, 0) is ContextLinkResult.Linked)

            val deleted = captures(database).delete(captureId, 0)
            assertEquals(CaptureMessageWriteResult.Deleted(1), deleted)

            val event = repository.findEvent(fuelEvent.id)!!
            assertNull(event.normalizedText)
            assertNull(event.amountVnd)
            assertEquals(SOURCE_DELETED, event.unresolvedReason)
            assertTrue(repository.linksForEvent(fuelEvent.id).isEmpty())
            assertNull(database.captureMessageDao().findById(captureId)!!.bodyText)
            assertEquals(
                0L,
                scalar(database.openHelper.writableDatabase, "SELECT COUNT(*) FROM context_links"),
            )
            database.openHelper.writableDatabase.query(SimpleSQLiteQuery("PRAGMA foreign_key_check"))
                .use { cursor ->
                    assertFalse("the deleted source left an invalid link", cursor.moveToFirst())
                }
        } finally {
            database.close()
        }
    }

    @Test
    fun `the same reading is stored once and the confirmed ledger is untouched`() = runTest {
        val database = open()
        val query = WorkflowQueryService(database)
        try {
            val ledger = LedgerRepository(
                categoryDao = database.categoryDao(),
                transactionDao = database.transactionDao(),
                rememberedCategoryRuleDao = database.rememberedCategoryRuleDao(),
                captureMessageDao = database.captureMessageDao(),
                clock = clock,
            )
            val transactionId = ledger.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = 45_000L,
                    occurredAtEpochMillis = CAPTURE_TIME,
                    description = "Manual entry",
                ),
            )
            val captureId = captures(database).saveQuickCapture(NOTE_TEXT)
            val first = events(database).recordEvent(
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = reading(fuelSpanStart(), fuelSpanEnd()),
                extractionVersion = EXTRACTION_VERSION,
            )
            assertTrue(first is NoteEventWriteResult.Stored)
            val second = events(database).recordEvent(
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = reading(fuelSpanStart(), fuelSpanEnd()),
                extractionVersion = EXTRACTION_VERSION,
            )
            assertEquals(NoteEventWriteResult.NoChange, second)
            assertEquals(1, events(database).eventsForCapture(captureId).size)

            val changed = events(database).recordEvent(
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = reading(fuelSpanStart(), fuelSpanEnd()).copy(amountVnd = 55_000L),
                extractionVersion = EXTRACTION_VERSION,
            )
            assertTrue(changed is NoteEventWriteResult.Revised)
            assertEquals(1, (changed as NoteEventWriteResult.Revised).revision)
            assertEquals(1, events(database).eventsForCapture(captureId).single().revision)

            // The confirmed record and its total are untouched by anything stored here.
            val stored = query.findLedgerRecord(transactionId)!!
            assertEquals(45_000L, stored.amountVnd)
            assertEquals(TransactionType.EXPENSE, stored.type)
            assertEquals(
                45_000L,
                database.transactionDao().findAll()
                    .filter { it.confirmation == ConfirmationStatus.CONFIRMED }
                    .sumOf { it.amountVnd },
            )
        } finally {
            database.close()
        }
    }

    private suspend fun storeFuelEvent(database: LedgerDatabase, captureId: Long): NoteEventEntity {
        val result = events(database).recordEvent(
            captureId = captureId,
            expectedCaptureRevision = 0,
            interpretation = reading(fuelSpanStart(), fuelSpanEnd()),
            extractionVersion = EXTRACTION_VERSION,
        )
        assertTrue("the fuel reading was refused: $result", result is NoteEventWriteResult.Stored)
        return events(database).findEvent((result as NoteEventWriteResult.Stored).eventId)!!
    }

    private suspend fun storeSnackEvent(database: LedgerDatabase, captureId: Long): NoteEventEntity {
        val result = events(database).recordEvent(
            captureId = captureId,
            expectedCaptureRevision = 0,
            interpretation = NoteEventInterpretation(
                spanStart = snackSpanStart(),
                spanEnd = snackSpanEnd(),
                normalizedText = "Circle K snack",
                amountVnd = 20_000L,
                merchant = "Circle K",
                timeAnchor = NoteEventTimeAnchor(
                    date = "2026-10-16",
                    time = "15:15",
                    timezoneId = CAPTURE_ZONE,
                ),
            ),
            extractionVersion = EXTRACTION_VERSION,
        )
        assertTrue("the snack reading was refused: $result", result is NoteEventWriteResult.Stored)
        return events(database).findEvent((result as NoteEventWriteResult.Stored).eventId)!!
    }

    private fun reading(spanStart: Int, spanEnd: Int) = NoteEventInterpretation(
        spanStart = spanStart,
        spanEnd = spanEnd,
        normalizedText = "Fuel",
        amountVnd = 50_000L,
        merchant = "Petrol",
        timeAnchor = NoteEventTimeAnchor(
            date = "2026-10-16",
            time = "15:00",
            timezoneId = CAPTURE_ZONE,
        ),
    )

    private suspend fun storeDraft(
        database: LedgerDatabase,
        key: String,
        amountVnd: Long,
    ): Long = IntakeRepository(database, clock).storePermitted(
        sourcePackage = BANK_PACKAGE,
        notificationKey = key,
        postedAtEpochMillis = POSTED_AT + key.length,
        content = PermittedNotificationContent(
            title = "Transaction notice",
            text = listOf(
                "VietinBank: 16/10/2026 07:38",
                "TK: account-redacted",
                "GD: -$amountVnd VND",
                "ND: $BANK_MEMO",
            ).joinToString("\n"),
            bigText = null,
        ),
    )

    private fun scalar(database: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Long =
        database.query(SimpleSQLiteQuery(sql)).use { cursor ->
            check(cursor.moveToFirst()) { "$sql returned no row" }
            cursor.getLong(0)
        }

    private companion object {
        const val BANK_PACKAGE = "com.example.bank"
        const val BANK_MEMO = "Circle K store"
        const val CAPTURE_ZONE = "Asia/Bangkok"
        const val CAPTURE_TIME = 1_797_000_000_000L
        const val POSTED_AT = 1_797_000_000_000L
        const val EXTRACTION_VERSION = "extract-v1"
        const val NOTE_TEXT = "Fuel 50,000 at 15:00; Circle K snack 20,000 at 15:15"
        const val REVISED_NOTE = "Fuel 50,000 at 15:00; Circle K snack 20,000 at 15:20"

        fun fuelSpanStart(): Int = NOTE_TEXT.indexOf("Fuel 50,000 at 15:00")

        fun fuelSpanEnd(): Int = fuelSpanStart() + "Fuel 50,000 at 15:00".length

        fun snackSpanStart(): Int = NOTE_TEXT.indexOf("Circle K snack 20,000 at 15:15")

        fun snackSpanEnd(): Int = snackSpanStart() + "Circle K snack 20,000 at 15:15".length
    }
}
