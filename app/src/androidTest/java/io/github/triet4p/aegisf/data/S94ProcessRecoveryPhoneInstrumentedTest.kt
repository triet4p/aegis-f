package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.triet4p.aegisf.AegisApplication
import io.github.triet4p.aegisf.notification.PermittedNotificationContent
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The `seed` argument lets the host force-stop the isolated app between seed and recovery. */
@RunWith(AndroidJUnit4::class)
class S94ProcessRecoveryPhoneInstrumentedTest {
    @Test
    fun pendingArrivalResumesAtApplicationStartup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("io.github.triet4p.aegisf.s812evidence", context.packageName)
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val stage = InstrumentationRegistry.getArguments().getString(STAGE_ARGUMENT)
        if (stage == SEED_STAGE) {
            preferences.edit().clear().commit()
            seedPendingOperation(context, preferences)
            return@runBlocking
        }

        val storedOperationId = preferences.getString(OPERATION_KEY, null)
        val operationId = if (storedOperationId == null) {
            val seededOperationId = seedPendingOperation(context, preferences)
            (context.applicationContext as AegisApplication)
                .container.workflowCommandService.resumePendingArrivalReconciliation()
            seededOperationId
        } else {
            storedOperationId
        }
        val eventId = preferences.getLong(EVENT_ID_KEY, -1L)
        val draftId = preferences.getLong(DRAFT_ID_KEY, -1L)
        assertTrue(eventId > 0L)
        assertTrue(draftId > 0L)

        val database = openProductionDatabase(context)
        try {
            var operation = database.arrivalReconciliationOperationDao().findById(operationId)
            for (attempt in 0 until MAX_RECOVERY_POLL_ATTEMPTS) {
                if (operation?.state == ARRIVAL_OPERATION_COMPLETE) break
                delay(RECOVERY_POLL_DELAY_MILLIS)
                operation = database.arrivalReconciliationOperationDao().findById(operationId)
            }
            assertNotNull(operation)
            assertEquals(ARRIVAL_OPERATION_COMPLETE, operation!!.state)
            assertEquals(ARRIVAL_OUTCOME_LINKED, operation!!.outcome)
            assertEquals(
                ContextLinkState.ACCEPTED,
                database.contextLinkDao().findByPair(eventId, draftId)!!.state,
            )
            assertEquals(IntakeReviewStatus.PENDING, database.pendingBankDraftDao().findById(draftId)!!.reviewStatus)
            assertTrue(database.transactionDao().findAll().isEmpty())
        } finally {
            database.close()
            preferences.edit().clear().commit()
        }
    }

    private suspend fun seedPendingOperation(
        context: Context,
        preferences: android.content.SharedPreferences,
    ): String {
        val runId = UUID.randomUUID().toString()
        val database = openProductionDatabase(context)
        try {
            val commands = WorkflowCommandService(database)
            val draftId = IntakeRepository(database).storePermitted(
                sourcePackage = "com.vietinbank.ipay",
                notificationKey = "s9.4-startup-$runId",
                postedAtEpochMillis = FIXTURE_POSTED_AT,
                content = PermittedNotificationContent(
                    title = "Tin biến động số dư",
                    text = listOf(
                        "VietinBank: 09/11/2026 10:00",
                        "TK: Redacted",
                        "GD: -45,000 VND",
                        "SDC: Redacted",
                        "ND: Known Cafe",
                    ).joinToString("\n"),
                    bigText = null,
                ),
            )
            val body = "Synthetic startup recovery purchase"
            val savedCapture = commands.saveCapture(
                WorkflowCallerScope.User,
                SaveCaptureRequest("s9.4-startup-$runId-capture", body),
            ) as WorkflowCommandOutcome.Applied
            val captureId = (savedCapture.change as WorkflowChange.CaptureSaved).captureId
            val event = database.withTransaction {
                val stored = EventContextRepository(database).recordEvent(
                    captureId = captureId,
                    expectedCaptureRevision = 0,
                    interpretation = NoteEventInterpretation(
                        spanStart = 0,
                        spanEnd = body.length,
                        normalizedText = body,
                        amountVnd = 45_000L,
                        merchant = "Known Cafe",
                        timeAnchor = NoteEventTimeAnchor(
                            date = "2026-11-09",
                            time = "10:00",
                            timezoneId = "Asia/Ho_Chi_Minh",
                        ),
                    ),
                    extractionVersion = "s9.4-startup-fixture",
                    userEdited = false,
                ) as NoteEventWriteResult.Stored
                ArrivalReconciliationCoordinator(database).enqueueEvent(
                    stored.eventId,
                    stored.revision,
                    expectedCaptureRevision = 0,
                )
                stored
            }
            val operationId = "event:${event.eventId}:0"
            preferences.edit()
                .putString(OPERATION_KEY, operationId)
                .putLong(EVENT_ID_KEY, event.eventId)
                .putLong(DRAFT_ID_KEY, draftId)
                .commit()
            assertEquals(
                ARRIVAL_OPERATION_PENDING,
                database.arrivalReconciliationOperationDao().findById(operationId)!!.state,
            )
            assertTrue(database.contextLinkDao().findForEvent(event.eventId).isEmpty())
            return operationId
        } finally {
            database.close()
        }
    }

    private fun openProductionDatabase(context: Context): LedgerDatabase =
        Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, LedgerDatabase.NAME).build()

    private companion object {
        const val PREFERENCES = "s94_process_recovery_evidence"
        const val STAGE_ARGUMENT = "s94_recovery_stage"
        const val SEED_STAGE = "seed"
        const val OPERATION_KEY = "operation_id"
        const val EVENT_ID_KEY = "event_id"
        const val DRAFT_ID_KEY = "draft_id"
        const val FIXTURE_POSTED_AT = 1_800_000_000_000L
        const val MAX_RECOVERY_POLL_ATTEMPTS = 50
        const val RECOVERY_POLL_DELAY_MILLIS = 100L
    }
}
