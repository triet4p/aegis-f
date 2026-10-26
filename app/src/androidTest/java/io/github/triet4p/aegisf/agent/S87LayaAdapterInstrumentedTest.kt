package io.github.triet4p.aegisf.agent

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.ContextLinkState
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.LinkOperationQuery
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.PredictionSource
import io.github.triet4p.aegisf.data.SaveCaptureRequest
import io.github.triet4p.aegisf.data.SystemLedgerClock
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowChange
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.data.laya.LayaClassificationEngine
import io.github.triet4p.aegisf.data.laya.LayaInferenceOutcome
import io.github.triet4p.aegisf.data.laya.LayaModelProvisioner
import io.github.triet4p.aegisf.data.laya.LayaInferenceLifecycle
import io.github.triet4p.aegisf.privacy.MaskedPath
import io.github.triet4p.aegisf.privacy.MaskedPromptBoundary
import io.github.triet4p.aegisf.privacy.SensitiveVault
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class S87LayaAdapterInstrumentedTest {
    @Test
    fun actualRoomToolOperationsReachLocalLayaAndPersistOnlyTheAllowedEffects() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val lifecycle = LayaInferenceLifecycle(File(context.filesDir, "laya/model"))
        try {
            val commands = WorkflowCommandService(
                database = database,
                clock = SystemLedgerClock,
                timezoneId = { "Asia/Ho_Chi_Minh" },
                engine = LayaClassificationEngine(lifecycle),
            )
            val queries = WorkflowQueryService(database)
            val captureText = "Coffee 28,000 VND on 2026-10-21 at 12:30"
            val saved = commands.saveCapture(
                WorkflowCallerScope.User,
                SaveCaptureRequest("s8.7-device-capture", captureText),
            ) as WorkflowCommandOutcome.Applied
            val captureId = (saved.change as WorkflowChange.CaptureSaved).captureId
            var scope = WorkflowCallerScope.AgentSession("s8.7-device-session", setOf(captureId), emptySet())
            val boundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId))
            val declarations = S8AgentToolDeclarations.load(context.assets::open)
            fun adapter(key: String) = BoundedWorkflowToolAdapter(
                commands,
                queries,
                WorkflowToolSession(scope, boundary, key),
                declarations,
            )

            val read = adapter("s8.7-device-read").observeCaptureContext(captureId)
            val maskedSource = read.getString("sourceText")
            assertFalse(maskedSource.contains("28,000"))
            assertFalse(maskedSource.contains("2026-10-21"))
            assertFalse(maskedSource.contains("12:30"))
            assertTrue(maskedSource.length != captureText.length)
            val eventCall = JSONObject()
                .put("captureId", captureId)
                .put("expectedCaptureRevision", 0)
                .put("expectedEventRevision", JSONObject.NULL)
                .put("extractionVersion", "s8.7-device-smoke")
                .put(
                    "interpretation",
                    JSONObject()
                        .put("amountVnd", token(read, "AMT"))
                        .put("merchant", JSONObject.NULL)
                        .put("maskedSpanText", maskedSource)
                        .put("normalizedText", maskedSource)
                        .put("spanStart", 0)
                        .put("spanEnd", maskedSource.length)
                        .put(
                            "timeAnchor",
                            JSONObject()
                                .put("date", token(read, "DATE"))
                                .put("intervalEndDate", JSONObject.NULL)
                                .put("intervalEndTime", JSONObject.NULL)
                                .put("time", token(read, "TIME"))
                                .put("timezoneId", JSONObject.NULL),
                        )
                        .put("unresolvedReason", JSONObject.NULL),
                )
            val interpreted = adapter("s8.7-device-event").execute(
                BoundedWorkflowToolAdapter.UPDATE_EVENT_INTERPRETATION,
                eventCall,
            )
            assertEquals("applied", interpreted.getString("status"))
            val storedEvent = database.noteEventDao().findForCapture(captureId).single()
            assertEquals(28_000L, storedEvent.amountVnd)
            assertEquals("2026-10-21", storedEvent.anchorDate)
            assertEquals("12:30", storedEvent.anchorTime)
            assertEquals(captureText, storedEvent.normalizedText)
            assertEquals(0, storedEvent.spanStart)
            assertEquals(captureText.length, storedEvent.spanEnd)
            val reread = adapter("s8.7-device-reread").observeCaptureContext(captureId)
                .getJSONArray("events").getJSONObject(0)
            assertEquals(0, reread.getInt("spanStart"))
            assertEquals(maskedSource.length, reread.getInt("spanEnd"))

            val now = System.currentTimeMillis()
            val draftId = database.pendingBankDraftDao().store(
                PendingBankDraftEntity(
                    deliveryId = "s8.7-device-draft",
                    sourcePackage = "synthetic.fixture",
                    notificationKey = null,
                    postedAtEpochMillis = now,
                    bank = "Synthetic bank",
                    parseStatus = IntakeParseStatus.PARSED,
                    amountState = IntakeFieldState.PRESENT,
                    amountVnd = 28_000L,
                    amountUnresolvedReason = null,
                    direction = IntakeDirection.DEBIT,
                    momentState = IntakeFieldState.PRESENT,
                    statedDate = "2026-10-21",
                    statedTime = "12:30",
                    momentUnresolvedReason = null,
                    memoState = IntakeFieldState.PRESENT,
                    memo = "Coffee",
                    memoUnresolvedReason = null,
                    createdAtEpochMillis = now,
                ),
            )
            scope = WorkflowCallerScope.AgentSession(scope.scopeId, scope.captureIds, setOf(draftId))
            val proposed = adapter("s8.7-device-propose").execute(
                BoundedWorkflowToolAdapter.PROPOSE_LINK,
                JSONObject()
                    .put("eventId", storedEvent.id)
                    .put("draftId", draftId),
            )
            assertEquals("applied", proposed.getString("status"))
            val proposedLink = queries.linkOperations(
                LinkOperationQuery(
                    eventId = storedEvent.id,
                    draftId = draftId,
                    bounds = QueryPageRequest(1, 0),
                ),
            ).items.single()
            assertEquals(ContextLinkState.PROPOSED, proposedLink.state)

            val attached = adapter("s8.7-device-attach").execute(
                BoundedWorkflowToolAdapter.ATTACH_LINK,
                JSONObject()
                    .put("eventId", storedEvent.id)
                    .put("draftId", draftId)
                    .put("expectedRevision", proposedLink.revision)
                    .put("userOverride", JSONObject.NULL),
            )
            assertEquals("applied", attached.getString("status"))
            assertEquals(
                ContextLinkState.ACCEPTED,
                queries.linkOperations(
                    LinkOperationQuery(
                        eventId = storedEvent.id,
                        draftId = draftId,
                        bounds = QueryPageRequest(1, 0),
                    ),
                ).items.single().state,
            )

            val categoryId = database.categoryDao().insert(
                CategoryEntity(name = "Food", createdAtEpochMillis = now, updatedAtEpochMillis = now),
            )
            assertTrue(categoryId > 0)
            ensurePinnedLayaModel(context)
            val prepared = lifecycle.prepare()
            assertTrue("local Laya model must prepare successfully: $prepared", prepared is LayaInferenceOutcome.Completed)
            val maskedDescription = boundary.maskOutbound("Coffee", MaskedPath.TOOL_RESULT)
            val classificationCall = JSONObject()
                .put("draftId", draftId)
                .put("description", maskedDescription)
                .put("expectedReviewRevision", 0)
            val classified = adapter("s8.7-device-laya").execute(
                BoundedWorkflowToolAdapter.REQUEST_LAYA_CLASSIFICATION,
                classificationCall,
            )
            assertEquals(classified.toString(), "applied", classified.getString("status"))
            val snapshot = database.classificationSnapshotDao().findForDraft(draftId)
            assertNotNull("the real local classifier must persist its model result", snapshot)
            assertNotNull("an ONNX evaluation produces a calibrated no-fit share", snapshot?.unknownShare)
            assertTrue(snapshot?.decisionSource in setOf(PredictionSource.MODEL.name, PredictionSource.NO_FIT.name))
            assertTrue(snapshot?.candidateIds?.isNotBlank() == true)
            assertTrue(snapshot?.probabilities?.isNotBlank() == true)
            assertNotNull(snapshot?.confidence)
            Log.i(
                "S87LayaSmoke",
                "source=${snapshot?.decisionSource} candidates=${snapshot?.candidateIds} " +
                    "probabilities=${snapshot?.probabilities} unknownShare=${snapshot?.unknownShare} " +
                    "confidence=${snapshot?.confidence}",
            )
            val retriedClassification = adapter("s8.7-device-laya").execute(
                BoundedWorkflowToolAdapter.REQUEST_LAYA_CLASSIFICATION,
                classificationCall,
            )
            assertEquals("no_change", retriedClassification.getString("status"))
            assertEquals(snapshot, database.classificationSnapshotDao().findForDraft(draftId))
            assertEquals(1L, database.workflowOperationDao().countOf("REQUEST_CLASSIFICATION"))
            assertTrue(database.transactionDao().findAll().isEmpty())
            assertEquals(1, database.noteEventDao().findForCapture(captureId).size)
            assertEquals(ContextLinkState.ACCEPTED, queries.linkOperations(
                LinkOperationQuery(eventId = storedEvent.id, draftId = draftId, bounds = QueryPageRequest(1, 0)),
            ).items.single().state)
        } finally {
            lifecycle.close()
            database.close()
        }
    }

    private suspend fun ensurePinnedLayaModel(context: Context) {
        val provisioner = LayaModelProvisioner(context.filesDir) {
            context.assets.open("laya/laya_tokenizer.bin")
        }
        val sourceDirectory = File(context.filesDir, "s8-public-laya-source")
        try {
            if (provisioner.inspect() == LayaModelProvisioner.Status.Ready) return
            val sourceFiles = listOf("laya.onnx", "laya.onnx.data", "laya_config.json")
            val missing = sourceFiles.filterNot { File(sourceDirectory, it).isFile }
            assertTrue("pinned public Laya source files were not staged: $missing", missing.isEmpty())
            val sources = sourceFiles.associateWith { path ->
                { FileInputStream(File(sourceDirectory, path)) }
            }
            assertEquals(
                "the pinned source files must pass the production LayaModelProvisioner",
                LayaModelProvisioner.Status.Ready,
                provisioner.provision(sources),
            )
        } finally {
            sourceDirectory.deleteRecursively()
        }
    }

    private fun token(observation: JSONObject, prefix: String): String =
        Regex("⟦$prefix-[A-Za-z0-9]{4,12}-\\d{4}⟧").find(observation.toString())?.value
            ?: error("expected a scoped $prefix token")
}
