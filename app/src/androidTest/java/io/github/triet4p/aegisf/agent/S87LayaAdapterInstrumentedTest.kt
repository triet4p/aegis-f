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
            val declarations = AgentToolDeclarations.load(context.assets::open)
            val registry = AgentReferenceRegistry(scope.scopeId)
            fun runtimeFor(current: WorkflowCallerScope.AgentSession) = AgentToolRuntime(
                commands,
                queries,
                AgentToolSession(current, boundary, registry),
                declarations,
            )
            val runtime = runtimeFor(scope)

            val captureObservation = runtime.initialCaptureObservations(4).single()
            val maskedSource = captureObservation.getString("source_text")
            assertFalse(maskedSource.contains("28,000"))
            assertFalse(maskedSource.contains("2026-10-21"))
            assertFalse(maskedSource.contains("12:30"))
            assertTrue(maskedSource.length != captureText.length)
            val selected = runtime.execute(
                "select_source",
                JSONObject()
                    .put("capture_ref", captureObservation.getString("ref"))
                    .put("quote", maskedSource)
                    .put("occurrence", 1),
                "s8.7-device-select",
            ).envelope
            assertEquals("ok", selected.getString("outcome"))
            val source = selected.getJSONObject("data")
            fun valueRef(kind: String): String {
                val values = source.getJSONArray("values")
                for (index in 0 until values.length()) {
                    if (values.getJSONObject(index).getString("kind") == kind) {
                        return values.getJSONObject(index).getString("ref")
                    }
                }
                error("missing $kind alias")
            }
            val anchor = runtime.execute(
                "make_time_anchor",
                JSONObject()
                    .put("date_ref", valueRef("date"))
                    .put("time_ref", valueRef("time"))
                    .put("end_date_ref", JSONObject.NULL)
                    .put("end_time_ref", JSONObject.NULL),
                "s8.7-device-anchor",
            ).envelope
            assertEquals("ok", anchor.getString("outcome"))
            val interpreted = runtime.execute(
                "record_event",
                JSONObject()
                    .put("source_ref", source.getString("source_ref"))
                    .put("summary", "Coffee")
                    .put("amount_ref", valueRef("amount"))
                    .put("anchor_ref", anchor.getJSONObject("data").getString("anchor_ref"))
                    .put("merchant_ref", JSONObject.NULL)
                    .put("clarification", "none"),
                "s8.7-device-record",
            ).envelope
            assertEquals("ok", interpreted.getString("outcome"))
            assertEquals("applied", interpreted.getString("effect"))
            val storedEvent = database.noteEventDao().findForCapture(captureId).single()
            assertEquals(28_000L, storedEvent.amountVnd)
            assertEquals("2026-10-21", storedEvent.anchorDate)
            assertEquals("12:30", storedEvent.anchorTime)
            // The stored normalized text is the agent's grounded summary, not the raw span.
            assertEquals("Coffee", storedEvent.normalizedText)
            assertEquals(0, storedEvent.spanStart)
            assertEquals(captureText.length, storedEvent.spanEnd)
            val eventRef = interpreted.getJSONObject("data").getJSONObject("event").getString("ref")
            val reread = runtime.execute(
                "observe",
                JSONObject().put("ref", eventRef),
                "s8.7-device-reread",
            ).envelope.getJSONObject("data").getJSONObject("observation")
            assertTrue(reread.has("source_span"))
            assertEquals(0, reread.getJSONObject("source_span").getInt("masked_start"))

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
            val widened = WorkflowCallerScope.AgentSession(scope.scopeId, scope.captureIds, setOf(draftId))
            val linkRuntime = runtimeFor(widened)
            val draftRef = requireNotNull(linkRuntime.initialDraftObservation())
                .getJSONArray("items").getJSONObject(0).getString("ref")
            val inspected = linkRuntime.execute(
                "inspect_link",
                JSONObject().put("event_ref", eventRef).put("draft_ref", draftRef),
                "s8.7-device-inspect",
            ).envelope
            assertEquals("ok", inspected.getString("outcome"))
            assertEquals("eligible", inspected.getJSONObject("data").getString("eligibility"))
            val pairRef = inspected.getJSONObject("data").getString("pair_ref")
            val proposed = linkRuntime.execute(
                "propose_link",
                JSONObject().put("pair_ref", pairRef),
                "s8.7-device-propose",
            ).envelope
            assertEquals("ok", proposed.getString("outcome"))
            assertEquals("PROPOSED", proposed.getJSONObject("data").getJSONObject("link").getString("state"))
            val proposedLink = queries.linkOperations(
                LinkOperationQuery(
                    eventId = storedEvent.id,
                    draftId = draftId,
                    bounds = QueryPageRequest(1, 0),
                ),
            ).items.single()
            assertEquals(ContextLinkState.PROPOSED, proposedLink.state)

            val attached = linkRuntime.execute(
                "attach_link",
                JSONObject().put("proposal_ref", proposed.getJSONObject("data").getString("proposal_ref")),
                "s8.7-device-attach",
            ).envelope
            assertEquals("ok", attached.getString("outcome"))
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
            val classified = linkRuntime.execute(
                "classify_draft",
                JSONObject().put("draft_ref", draftRef),
                "s8.7-device-laya",
            ).envelope
            assertEquals(classified.toString(), "ok", classified.getString("outcome"))
            assertEquals("applied", classified.getString("effect"))
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
            val retriedClassification = linkRuntime.execute(
                "classify_draft",
                JSONObject().put("draft_ref", draftRef),
                "s8.7-device-laya",
            ).envelope
            assertEquals("unchanged", retriedClassification.getString("effect"))
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
