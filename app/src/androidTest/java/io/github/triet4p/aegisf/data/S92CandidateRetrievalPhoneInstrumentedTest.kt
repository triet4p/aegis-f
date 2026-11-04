package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises event candidate retrieval through production Room on isolated file-backed app data. */
@RunWith(AndroidJUnit4::class)
class S92CandidateRetrievalPhoneInstrumentedTest {
    @Test
    fun parsedTransactionIntervalWinsOverDelayedReceiptAndPersistsAcrossReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("io.github.triet4p.aegisf.s812evidence", context.packageName)
        val runId = UUID.randomUUID().toString()
        val zoneId = "Asia/Ho_Chi_Minh"
        val amountVnd = 500_000L + runId.take(8).toLong(16) % 5_000_000L
        var database = LedgerDatabase.build(context)
        val originalTransactionIds = database.transactionDao().findAll().map { it.id }.toSet()
        try {
            val commands = WorkflowCommandService(
                database = database,
                clock = SystemLedgerClock,
                timezoneId = { zoneId },
            )
            val queries = WorkflowQueryService(database)
            val noteText =
                "Coffee $amountVnd VND at Highlands 2026-10-21 23:50 to 2026-10-22 00:20"
            val savedCapture = commands.saveCapture(
                WorkflowCallerScope.User,
                SaveCaptureRequest("s9.2-phone-$runId", noteText),
            )
            assertTrue(savedCapture is WorkflowCommandOutcome.Applied)
            val captureId =
                ((savedCapture as WorkflowCommandOutcome.Applied).change as WorkflowChange.CaptureSaved).captureId
            val capture = database.captureMessageDao().findById(captureId)!!
            val storedEvent = EventContextRepository(database).recordEvent(
                captureId = captureId,
                expectedCaptureRevision = capture.revision,
                interpretation = NoteEventInterpretation(
                    spanStart = 0,
                    spanEnd = noteText.length,
                    normalizedText = "Coffee purchase",
                    amountVnd = amountVnd,
                    merchant = "Highlands",
                    timeAnchor = NoteEventTimeAnchor(
                        date = "2026-10-21",
                        time = "23:50",
                        timezoneId = zoneId,
                        intervalEndDate = "2026-10-22",
                        intervalEndTime = "00:20",
                    ),
                ),
                extractionVersion = "s9.2-phone-fixture",
            )
            assertTrue(storedEvent is NoteEventWriteResult.Stored)
            val eventId = (storedEvent as NoteEventWriteResult.Stored).eventId
            val receiptAt = LocalDateTime.parse("2026-10-22T00:45")
                .atZone(ZoneId.of(zoneId))
                .toInstant()
                .toEpochMilli()
            val withinInterval = database.pendingBankDraftDao().store(
                pendingDraft(runId, "within", amountVnd, "2026-10-22", "00:10", "Highlands Coffee", receiptAt),
            )
            val wrongAmount = database.pendingBankDraftDao().store(
                pendingDraft(runId, "wrong-amount", amountVnd + 1, "2026-10-22", "00:10", "Highlands Coffee", receiptAt),
            )
            val wrongMerchant = database.pendingBankDraftDao().store(
                pendingDraft(runId, "wrong-merchant", amountVnd, "2026-10-22", "00:10", "Circle K", receiptAt),
            )
            val pendingPage = queries.draftCandidates(
                DraftCandidateQuery(
                    reviewStatus = IntakeReviewStatus.PENDING,
                    bounds = QueryPageRequest(limit = 32),
                ),
            )
            val authorizedIds = pendingPage.items.map { it.id }.toSet()

            val result = queries.matchDraftCandidates(
                eventId,
                authorizedIds,
                pendingDatabaseComplete = !pendingPage.hasMore,
            )!!
            assertEquals(
                if (result.pendingDatabaseComplete) {
                    DraftCandidateMatchOutcome.UNIQUE
                } else {
                    DraftCandidateMatchOutcome.INCOMPLETE
                },
                result.outcome,
            )
            assertEquals(!pendingPage.hasMore, result.pendingDatabaseComplete)
            assertEquals(listOf(withinInterval), result.candidates.map { it.draft.id })
            assertTrue(result.conflictingDraftCount >= 2)
            assertEquals("2026-10-22", result.candidates.single().draft.statedDate)
            assertEquals("00:10", result.candidates.single().draft.statedTime)
            assertEquals(receiptAt, result.candidates.single().draft.postedAtEpochMillis)
            assertEquals(
                originalTransactionIds,
                database.transactionDao().findAll().map { it.id }.toSet(),
            )

            database.close()
            database = LedgerDatabase.build(context)
            val reopenedQueries = WorkflowQueryService(database)
            val reopenedPendingPage = reopenedQueries.draftCandidates(
                DraftCandidateQuery(
                    reviewStatus = IntakeReviewStatus.PENDING,
                    bounds = QueryPageRequest(limit = 32),
                ),
            )
            val reopened = reopenedQueries.matchDraftCandidates(
                eventId,
                reopenedPendingPage.items.map { it.id }.toSet(),
                pendingDatabaseComplete = !reopenedPendingPage.hasMore,
            )!!
            assertEquals(result.outcome, reopened.outcome)
            assertEquals(listOf(withinInterval), reopened.candidates.map { it.draft.id })
            assertEquals("2026-10-21", database.noteEventDao().findById(eventId)!!.anchorDate)
            assertEquals("2026-10-22", database.noteEventDao().findById(eventId)!!.intervalEndDate)
            assertEquals("2026-10-22", database.pendingBankDraftDao().findById(withinInterval)!!.statedDate)
            assertEquals(
                originalTransactionIds,
                database.transactionDao().findAll().map { it.id }.toSet(),
            )
        } finally {
            database.close()
        }
    }


    private fun pendingDraft(
        runId: String,
        suffix: String,
        amount: Long,
        date: String,
        time: String,
        memo: String,
        receiptAt: Long,
    ) = PendingBankDraftEntity(
        deliveryId = "s9.2-phone-$runId-$suffix",
        sourcePackage = "synthetic.fixture",
        notificationKey = null,
        postedAtEpochMillis = receiptAt,
        bank = "Fixture bank",
        parseStatus = IntakeParseStatus.PARSED,
        amountState = IntakeFieldState.PRESENT,
        amountVnd = amount,
        amountUnresolvedReason = null,
        direction = IntakeDirection.DEBIT,
        momentState = IntakeFieldState.PRESENT,
        statedDate = date,
        statedTime = time,
        momentUnresolvedReason = null,
        memoState = IntakeFieldState.PRESENT,
        memo = memo,
        memoUnresolvedReason = null,
        createdAtEpochMillis = SystemLedgerClock.nowEpochMillis(),
    )
}
