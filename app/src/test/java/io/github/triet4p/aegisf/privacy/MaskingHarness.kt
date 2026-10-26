package io.github.triet4p.aegisf.privacy

import io.github.triet4p.aegisf.data.ClassificationEngine
import io.github.triet4p.aegisf.data.ClassificationEngineResult
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.SaveCaptureRequest
import io.github.triet4p.aegisf.data.SystemLedgerClock
import io.github.triet4p.aegisf.data.UpdateEventRequest
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowChange
import io.github.triet4p.aegisf.data.WorkflowClaimGuard
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import kotlinx.coroutines.runBlocking

/**
 * The S8.6 evidence harness behind the boundary tests: real services, isolated storage.
 *
 * [runIsolated] opens a fresh in-memory Room database wired to the real
 * [WorkflowCommandService] and [WorkflowQueryService], runs the block, then closes it.
 * Captures are saved through the command boundary itself (never inserted around it), the masked
 * gateway drives the provider path, and the asserted effects are read back from stored rows.
 */
internal object MaskingHarnessIsolation {

    class Harness(
        val database: LedgerDatabase,
        val commands: WorkflowCommandService,
        val queries: WorkflowQueryService,
    ) {
        suspend fun saveCapture(key: String, text: String): Long {
            val outcome = commands.saveCapture(WorkflowCallerScope.User, SaveCaptureRequest(key, text))
            check(outcome is WorkflowCommandOutcome.Applied) { "capture was refused: $outcome" }
            return (outcome.change as WorkflowChange.CaptureSaved).captureId
        }

        suspend fun storeMaskedReading(
            scope: WorkflowCallerScope.AgentSession,
            boundary: MaskedPromptBoundary,
            key: String,
            captureId: Long,
            captureText: String,
            spanStart: Int,
            spanEnd: Int,
            maskedSpanText: String,
            masked: MaskedInterpretation,
            extractionVersion: String = "s8.6-boundary-test",
        ): Long {
            val interpretation = MaskedApplicationEffect.rehydrateInterpretation(
                scope = scope,
                boundary = boundary,
                captureId = captureId,
                captureText = captureText,
                spanStart = spanStart,
                spanEnd = spanEnd,
                maskedSpanText = maskedSpanText,
                masked = masked,
            )
            val outcome = commands.updateEventInterpretation(
                scope,
                UpdateEventRequest(
                    idempotencyKey = key,
                    captureId = captureId,
                    expectedCaptureRevision = 0,
                    interpretation = interpretation,
                    extractionVersion = extractionVersion,
                ),
            )
            check(outcome is WorkflowCommandOutcome.Applied) { "reading was refused: $outcome" }
            val stored = database.noteEventDao().findForCapture(captureId).single()
            check(stored.amountVnd == interpretation.amountVnd)
            check(stored.anchorDate == interpretation.timeAnchor?.date)
            return stored.id
        }
    }

    fun <T> runIsolated(block: suspend (Harness) -> T): T = runBlocking {
        val context =
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(LedgerDatabase.NAME)
        val database = LedgerDatabase.build(context)
        try {
            val commands = WorkflowCommandService(
                database = database,
                clock = SystemLedgerClock,
                timezoneId = { "Asia/Ho_Chi_Minh" },
                engine = RefusingEngine,
            )
            block(Harness(database, commands, WorkflowQueryService(database)))
        } finally {
            WorkflowClaimGuard.reset()
            database.close()
        }
    }

    private object RefusingEngine : ClassificationEngine {
        override suspend fun classify(
            context: io.github.triet4p.aegisf.data.laya.LayaEffectiveContext,
            categories: List<io.github.triet4p.aegisf.data.CategoryEntity>,
        ): ClassificationEngineResult =
            ClassificationEngineResult.Failed("classifier is out of scope for masking")
    }
}
