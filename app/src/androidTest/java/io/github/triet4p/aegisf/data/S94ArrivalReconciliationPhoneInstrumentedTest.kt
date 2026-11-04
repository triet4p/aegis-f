package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.triet4p.aegisf.notification.PermittedNotificationContent
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises both persisted arrival orders through the production command and intake paths. */
@RunWith(AndroidJUnit4::class)
class S94ArrivalReconciliationPhoneInstrumentedTest {
    @Test
    fun noteFirstArrivalLinksItsUniqueNotification() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("io.github.triet4p.aegisf.s812evidence", context.packageName)
        val runId = UUID.randomUUID().toString()
        val databaseName = "s9_4_note_first_${runId.replace("-", "")}.db"
        val database = openEvidenceDatabase(context, databaseName)
        try {
            val commands = commands(database)
            val event = storeEvent(commands, runId, "note-first", 28_000L, "2026-11-03", "15:00")
            assertTrue(database.contextLinkDao().findForEvent(event.eventId).isEmpty())

            val captureBefore = database.captureMessageDao().findById(event.captureId)!!
            val eventBefore = database.noteEventDao().findById(event.eventId)!!
            val draftId = storeNotification(database, runId, "note-first", 28_000L, "03/11/2026", "15:00")
            val link = database.contextLinkDao().findByPair(event.eventId, draftId)
            assertEquals("note-first arrival was not reconciled", ContextLinkState.ACCEPTED, link?.state)
            assertEquals(1, link?.revision)
            assertEquals(draftId, storeNotification(database, runId, "note-first", 28_000L, "03/11/2026", "15:00"))
            assertTrue(
                replayEvent(commands, runId, event, "note-first", 28_000L, "2026-11-03", "15:00") is
                    WorkflowCommandOutcome.NoChange,
            )
            assertEquals(1, database.noteEventDao().findForCapture(event.captureId).size)
            assertEquals(1, database.pendingBankDraftDao().findAll().size)
            assertEquals(captureBefore, database.captureMessageDao().findById(event.captureId))
            assertEquals(eventBefore, database.noteEventDao().findById(event.eventId))
            assertEquals(1, database.contextLinkDao().findForEvent(event.eventId).size)
            assertEquals(
                1,
                database.arrivalReconciliationOperationDao()
                    .countByOperationId("event:${event.eventId}:0"),
            )
            assertEquals(
                1,
                database.arrivalReconciliationOperationDao().countByOperationId("draft:$draftId"),
            )
            assertEquals(0, database.transactionDao().findAll().size)
        } finally {
            database.close()
        }
    }

    @Test
    fun notificationFirstArrivalLinksItsUniqueEvent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("io.github.triet4p.aegisf.s812evidence", context.packageName)
        val runId = UUID.randomUUID().toString()
        val databaseName = "s9_4_notification_first_${runId.replace("-", "")}.db"
        val database = openEvidenceDatabase(context, databaseName)
        try {
            val commands = commands(database)
            val draftId = storeNotification(database, runId, "notification-first", 64_000L, "04/11/2026", "15:15")
            val event = storeEvent(commands, runId, "notification-first", 64_000L, "2026-11-04", "15:15")
            val link = database.contextLinkDao().findByPair(event.eventId, draftId)
            assertEquals("notification-first arrival was not reconciled", ContextLinkState.ACCEPTED, link?.state)
            assertEquals(1, link?.revision)
            assertEquals(0, database.transactionDao().findAll().size)
        } finally {
            database.close()
        }
    }

    private fun openEvidenceDatabase(context: Context, name: String): LedgerDatabase =
        Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, name).build()

    private fun commands(database: LedgerDatabase) = WorkflowCommandService(
        database = database,
        timezoneId = { "Asia/Ho_Chi_Minh" },
    )

    private suspend fun storeEvent(
        commands: WorkflowCommandService,
        runId: String,
        suffix: String,
        amount: Long,
        date: String,
        time: String,
    ): StoredEvent {
        val body = "Synthetic purchase $suffix"
        val savedCapture = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest("s9.4-$runId-$suffix-capture", body),
        ) as WorkflowCommandOutcome.Applied
        val captureId = (savedCapture.change as WorkflowChange.CaptureSaved).captureId
        val storedEvent = commands.updateEventInterpretation(
            WorkflowCallerScope.User,
            UpdateEventRequest(
                idempotencyKey = "s9.4-$runId-$suffix-event",
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = NoteEventInterpretation(
                    spanStart = 0,
                    spanEnd = body.length,
                    normalizedText = body,
                    amountVnd = amount,
                    merchant = "Known Cafe",
                    timeAnchor = NoteEventTimeAnchor(date = date, time = time, timezoneId = "Asia/Ho_Chi_Minh"),
                ),
                extractionVersion = "s9.4-phone-fixture",
            ),
        ) as WorkflowCommandOutcome.Applied
        val eventId = (storedEvent.change as WorkflowChange.EventStored).eventId
        return StoredEvent(captureId, eventId)
    }

    private suspend fun replayEvent(
        commands: WorkflowCommandService,
        runId: String,
        event: StoredEvent,
        suffix: String,
        amount: Long,
        date: String,
        time: String,
    ): WorkflowCommandOutcome {
        val body = "Synthetic purchase $suffix"
        return commands.updateEventInterpretation(
            WorkflowCallerScope.User,
            UpdateEventRequest(
                idempotencyKey = "s9.4-$runId-$suffix-event",
                captureId = event.captureId,
                expectedCaptureRevision = 0,
                interpretation = NoteEventInterpretation(
                    spanStart = 0,
                    spanEnd = body.length,
                    normalizedText = body,
                    amountVnd = amount,
                    merchant = "Known Cafe",
                    timeAnchor = NoteEventTimeAnchor(date = date, time = time, timezoneId = "Asia/Ho_Chi_Minh"),
                ),
                extractionVersion = "s9.4-phone-fixture",
            ),
        )
    }

    private suspend fun storeNotification(
        database: LedgerDatabase,
        runId: String,
        suffix: String,
        amount: Long,
        date: String,
        time: String,
    ): Long {
        val amountText = "${amount / 1_000},${(amount % 1_000).toString().padStart(3, '0')}"
        return IntakeRepository(database).storePermitted(
            sourcePackage = "com.vietinbank.ipay",
            notificationKey = "s9.4-$runId-$suffix",
            postedAtEpochMillis = FIXTURE_POSTED_AT,
            content = PermittedNotificationContent(
                title = "Tin biến động số dư",
                text = listOf(
                    "VietinBank: $date $time",
                    "TK: Redacted",
                    "GD: -$amountText VND",
                    "SDC: Redacted",
                    "ND: Known Cafe",
                ).joinToString("\n"),
                bigText = null,
            ),
        )
    }

    private companion object {
        const val FIXTURE_POSTED_AT = 1_800_000_000_000L
    }

    private data class StoredEvent(val captureId: Long, val eventId: Long)
}
