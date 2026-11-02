package io.github.triet4p.aegisf.agent

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.triet4p.aegisf.MainActivity
import io.github.triet4p.aegisf.AegisApplication
import io.github.triet4p.aegisf.data.AppContainer
import io.github.triet4p.aegisf.data.CaptureEventQuery
import io.github.triet4p.aegisf.data.CaptureKind
import io.github.triet4p.aegisf.data.CaptureSourceKind
import io.github.triet4p.aegisf.data.LedgerRecordQuery
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.SourceQuery
import io.github.triet4p.aegisf.data.SourceState
import io.github.triet4p.aegisf.data.SaveCaptureRequest
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowChange
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.privacy.MaskedPromptBoundary
import io.github.triet4p.aegisf.privacy.SensitiveVault
import io.github.triet4p.aegisf.provider.HostedHttpRequest
import io.github.triet4p.aegisf.provider.HostedHttpResponse
import io.github.triet4p.aegisf.provider.HttpTransport
import java.io.IOException
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Capture-only smoke with a sanitized runtime inventory of actual Chat Compose semantics. */
@RunWith(AndroidJUnit4::class)
class S814ChatSemanticsDiagnosticInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    private var appContainer: AppContainer? = null
    private var originalTransport: HttpTransport? = null
    @Volatile
    private var hostedRequests = 0

    @After
    fun restoreHostedTransport() {
        val currentContainer = appContainer ?: return
        val transport = originalTransport ?: return
        currentContainer.hostedHttpTransport = transport
    }

    private val safeLabels = setOf(
        "CHAT / QUICK CAPTURE",
        "ASK THE HOSTED AGENT",
        "Write anything",
        "Save capture",
        "NEW QUICK CAPTURE",
        "Ask about your notes or pending drafts",
        "Chat / Quick Capture",
    )

    @Test
    fun dumpActualChatCaptureSemantics() = runBlocking {
        val context = compose.activity.applicationContext
        val runId = requireNotNull(InstrumentationRegistry.getArguments().getString("S814_RUN_ID"))
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val evidenceDirectory = File(context.filesDir, "s814-phone/$runId")
        check(!evidenceDirectory.exists()) { "refusing to overwrite prior diagnostic evidence" }
        check(evidenceDirectory.mkdirs()) { "could not create the isolated diagnostic evidence directory" }
        val evidenceFile = File(evidenceDirectory, "evidence.json")
        val snapshots = JSONArray()
        val evidence = JSONObject()
            .put("schema", "aegis-f-s814-compose-capture-diagnostic/v1")
            .put("task_id", "S8.14")
            .put("attempt_id", "S8.14-integration-a97")
            .put("agent_id", "S814Integration97")
            .put("run_id", runId)
            .put("synthetic_only", true)
            .put("app_package", context.packageName)
            .put("surface", "actual MainActivity Chat / Quick Capture")
            .put("hosted_requests", 0)
            .put("compose_semantics_snapshots", snapshots)

        appContainer = (compose.activity.application as AegisApplication).container
        val currentContainer = requireNotNull(appContainer)
        originalTransport = currentContainer.hostedHttpTransport
        currentContainer.hostedHttpTransport = object : HttpTransport {
            override suspend fun post(request: HostedHttpRequest): HostedHttpResponse {
                hostedRequests += 1
                throw IOException("hosted transport is blocked in the capture-only diagnostic")
            }
        }
        val ledgerBefore = allLedgerRecords(currentContainer)
        val note = "S8.14 capture-only $runId"
        check(currentContainer.workflowQueryService.sourceExcerpts(
            SourceQuery(
                kind = CaptureKind.USER_NOTE,
                sourceKind = CaptureSourceKind.USER_QUICK_CAPTURE,
                state = SourceState.ACTIVE,
                bounds = QueryPageRequest(500, 0),
            ),
        ).items.none { it.bodyText == note }) { "the unique diagnostic note already exists" }
        val seededCaptureIds = (1..5).map { index ->
            val saved = currentContainer.workflowCommandService.saveCapture(
                WorkflowCallerScope.User,
                SaveCaptureRequest(
                    idempotencyKey = "s814-retained-history-$runId-$index",
                    bodyText = "S8.14 retained synthetic history $runId item $index",
                ),
            )
            check(saved is WorkflowCommandOutcome.Applied) {
                "the synthetic retained-history capture was not stored: ${saved::class.simpleName}"
            }
            val change = (saved as WorkflowCommandOutcome.Applied).change as? WorkflowChange.CaptureSaved
                ?: error("the synthetic history command did not return its stored capture identity")
            change.captureId
        }
        evidence.put("seeded_retained_capture_count", seededCaptureIds.size)
            .put("network_transport_blocked", true)
        writeEvidence(evidenceFile, evidence)

        compose.onNodeWithContentDescription("Chat / Quick Capture", substring = true).performClick()
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText("ASK THE HOSTED AGENT").fetchSemanticsNodes().isNotEmpty()
        }
        val initialSnapshot = semanticsSnapshot(0)
        snapshots.put(initialSnapshot.json)
        evidence.put("initial_capture_label_present", initialSnapshot.labels.contains("Write anything"))
            .put("initial_editable_semantics_nodes", initialSnapshot.editableNodeCount)
            .put("initial_set_text_action_nodes", initialSnapshot.setTextNodeCount)
            .put("initial_scroll_by_action_nodes", initialSnapshot.scrollByNodeCount)
            .put("initial_scroll_to_index_action_nodes", initialSnapshot.scrollToIndexNodeCount)
        writeEvidence(evidenceFile, evidence)

        val actionDiagnostics = try {
            S814ActualChatCapture.saveCapture(
                compose = compose,
                note = note,
                beforeSave = {
                    val saveSnapshot = semanticsSnapshot(3)
                    snapshots.put(saveSnapshot.json)
                    evidence.put("save_capture_label_visible", saveSnapshot.labels.contains("Save capture"))
                    saveCapturePanelScreenshot(
                        evidenceDirectory,
                        saveSnapshot,
                        "capture-surface-before-save.png",
                    )
                    writeEvidence(evidenceFile, evidence)
                },
                afterSave = {
                    val savedSnapshot = semanticsSnapshot(4)
                    snapshots.put(savedSnapshot.json)
                    writeEvidence(evidenceFile, evidence)
                    saveCapturePanelScreenshot(
                        evidenceDirectory,
                        savedSnapshot,
                        "capture-surface-after-save.png",
                    )
                },
            )
        } catch (failure: S814CaptureSurfaceFailure) {
            evidence.put("capture_surface_failure_diagnostics", failure.diagnostics)
            writeEvidence(evidenceFile, evidence)
            throw failure
        }
        evidence.put("capture_surface_action_diagnostics", actionDiagnostics)
            .put("synthetic_note_entered_via_observed_field", true)
            .put("sensitive_text_values_written", false)
        writeEvidence(evidenceFile, evidence)

        val savedCapture = currentContainer.workflowQueryService.sourceExcerpts(
            SourceQuery(
                kind = CaptureKind.USER_NOTE,
                sourceKind = CaptureSourceKind.USER_QUICK_CAPTURE,
                state = SourceState.ACTIVE,
                bounds = QueryPageRequest(500, 0),
            ),
        ).items.singleOrNull { it.bodyText == note }
        val eventCount = savedCapture?.let { capture ->
            currentContainer.workflowQueryService.captureEvents(
                CaptureEventQuery(capture.id, QueryPageRequest(50, 0)),
            ).items.size
        } ?: -1
        val scopeCaptureIds = seededCaptureIds + listOfNotNull(savedCapture?.id)
        val scope = WorkflowCallerScope.AgentSession(
            scopeId = "s814-capture-observation-$runId",
            captureIds = scopeCaptureIds.toSet(),
            draftIds = emptySet(),
        )
        val registry = AgentReferenceRegistry(scope.scopeId)
        val runtime = AgentToolRuntime(
            commands = currentContainer.workflowCommandService,
            queries = currentContainer.workflowQueryService,
            session = AgentToolSession(
                scope = scope,
                boundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId)),
                registry = registry,
            ),
            declarations = AgentToolDeclarations.load(context.assets::open),
        )
        val observationBatch = runtime.initialCaptureObservations(4)
        val newestObservation = observationBatch.firstOrNull()
        val newestReference = newestObservation?.let {
            registry.resolve(
                it.getString("ref"),
                AgentReferenceKind.CAPTURE,
                "$.ref",
            )
        }
        val newestObservedCaptureId = newestReference?.captureId
        val newestObservationRevisionMatches = savedCapture != null &&
            newestObservation?.optInt("capture_revision", -1) == savedCapture.revision &&
            newestReference?.captureRevision == savedCapture.revision
        val newestObservationSourceComplete =
            newestObservation?.optBoolean("source_is_complete") == true
        val newestCaptureObservationPresent =
            newestObservedCaptureId == savedCapture?.id &&
                newestObservationRevisionMatches && newestObservationSourceComplete
        val ledgerAfter = allLedgerRecords(currentContainer)
        evidence.put("capture_id", savedCapture?.id ?: JSONObject.NULL)
            .put("capture_persisted_once", savedCapture != null)
            .put("capture_event_count", eventCount)
            .put("ledger_rows_before", ledgerBefore.size)
            .put("ledger_rows_after", ledgerAfter.size)
            .put("ledger_rows_unchanged", ledgerBefore == ledgerAfter)
            .put("hosted_requests", hostedRequests)
            .put("external_network_calls", 0)
            .put("observation_scope_capture_count", scopeCaptureIds.size)
            .put("production_observation_limit", 4)
            .put("newest_observed_capture_revision", newestObservation?.optInt("capture_revision", -1) ?: -1)
            .put("saved_capture_revision", savedCapture?.revision ?: JSONObject.NULL)
            .put("newest_observation_revision_matches", newestObservationRevisionMatches)
            .put("newest_observation_source_complete", newestObservationSourceComplete)
            .put("newest_saved_capture_observation_present", newestCaptureObservationPresent)
            .put("production_observation_batch_size", observationBatch.size)
            .put("newest_observed_capture_id", newestObservedCaptureId ?: JSONObject.NULL)
            .put("newest_saved_capture_is_first_in_batch", newestObservedCaptureId == savedCapture?.id)
            .put("response_boundary_local_proof", runS814ResponseBoundaryProof(evidenceDirectory))
        writeEvidence(evidenceFile, evidence)
        check(savedCapture != null) { "the actual Quick Capture action did not persist its note" }
        check(eventCount == 0) { "saving a note created an event or financial effect" }
        check(scopeCaptureIds.size > 4 && observationBatch.size == 4) {
            "the production observation proof did not exercise a bounded batch over long history"
        }
        check(newestObservedCaptureId == savedCapture.id &&
            newestObservationRevisionMatches && newestObservationSourceComplete
        ) {
            "the newest UI-saved capture identity or revision was missing from the production observation batch"
        }
        check(ledgerBefore == ledgerAfter) { "saving a note changed the financial ledger" }
        check(hostedRequests == 0) { "the capture-only UI action attempted hosted transport" }

    }

    private fun semanticsSnapshot(step: Int): SemanticsSnapshot {
        val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val records = JSONArray()
        val labels = mutableSetOf<String>()
        var editableCount = 0
        var setTextCount = 0
        var scrollByCount = 0
        var scrollToIndexCount = 0

        fun visit(node: SemanticsNode, parentId: Int?, depth: Int) {
            val config = node.config
            val textValues = if (config.contains(SemanticsProperties.Text)) {
                config[SemanticsProperties.Text].map { it.text }
            } else {
                emptyList()
            }
            val recognizedLabels = textValues.filter { it in safeLabels }
            labels += recognizedLabels
            val editable = config.contains(SemanticsProperties.EditableText)
            val setText = config.contains(SemanticsActions.SetText)
            val scrollBy = config.contains(SemanticsActions.ScrollBy)
            val scrollToIndex = config.contains(SemanticsActions.ScrollToIndex)
            if (editable) editableCount += 1
            if (setText) setTextCount += 1
            if (scrollBy) scrollByCount += 1
            if (scrollToIndex) scrollToIndexCount += 1
            val bounds: Rect = node.boundsInRoot
            records.put(
                JSONObject()
                    .put("node_id", node.id)
                    .put("parent_node_id", parentId ?: JSONObject.NULL)
                    .put("depth", depth)
                    .put("bounds_px", JSONArray()
                        .put(bounds.left)
                        .put(bounds.top)
                        .put(bounds.right)
                        .put(bounds.bottom))
                    .put("has_text", textValues.isNotEmpty())
                    .put("recognized_labels", JSONArray(recognizedLabels))
                    .put("has_editable_text", editable)
                    .put("has_set_text_action", setText)
                    .put("has_scroll_by_action", scrollBy)
                    .put("has_scroll_to_index_action", scrollToIndex)
                    .put("has_click_action", config.contains(SemanticsActions.OnClick)),
            )
            node.children.forEach { child -> visit(child, node.id, depth + 1) }
        }

        visit(root, null, 0)
        return SemanticsSnapshot(
            json = JSONObject()
                .put("scroll_step", step)
                .put("node_count", records.length())
                .put("recognized_labels", JSONArray(labels.sorted()))
                .put("editable_semantics_node_count", editableCount)
                .put("set_text_action_node_count", setTextCount)
                .put("scroll_by_action_node_count", scrollByCount)
                .put("scroll_to_index_action_node_count", scrollToIndexCount)
                .put("nodes", records),
            labels = labels,
            nodeRecords = records,
            editableNodeCount = editableCount,
            setTextNodeCount = setTextCount,
            scrollByNodeCount = scrollByCount,
            scrollToIndexNodeCount = scrollToIndexCount,
        )
    }

    private fun saveCapturePanelScreenshot(
        directory: File,
        snapshot: SemanticsSnapshot,
        fileName: String,
    ) {
        val labelBounds = snapshot.boundsForLabel("NEW QUICK CAPTURE") ?: return
        val saveBounds = snapshot.boundsForLabel("Save capture") ?: return
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val left = labelBounds.left.toInt().coerceIn(0, image.width - 1)
        val top = labelBounds.top.toInt().coerceIn(0, image.height - 1)
        val right = image.width
        val bottom = (saveBounds.bottom.toInt() + 240).coerceIn(top + 1, image.height)
        val crop = Bitmap.createBitmap(image, left, top, right - left, bottom - top)
        File(directory, fileName).outputStream().use { output ->
            crop.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    private suspend fun allLedgerRecords(container: AppContainer): List<io.github.triet4p.aegisf.data.TransactionEntity> {
        val records = mutableListOf<io.github.triet4p.aegisf.data.TransactionEntity>()
        var offset = 0
        while (true) {
            val page = container.workflowQueryService.ledgerRecords(
                LedgerRecordQuery(bounds = QueryPageRequest(500, offset)),
            )
            records += page.items
            val nextOffset = page.nextOffset ?: break
            check(nextOffset > offset) { "ledger pagination did not advance" }
            offset = nextOffset
        }
        return records
    }

    private fun writeEvidence(file: File, evidence: JSONObject) {
        file.writeText(evidence.toString(2), Charsets.UTF_8)
    }

    private data class SemanticsSnapshot(
        val json: JSONObject,
        val labels: Set<String>,
        val nodeRecords: JSONArray,
        val editableNodeCount: Int,
        val setTextNodeCount: Int,
        val scrollByNodeCount: Int,
        val scrollToIndexNodeCount: Int,
    ) {
        fun labelNodeCount(label: String): Int = (0 until nodeRecords.length()).count { index ->
            val recognized = nodeRecords.getJSONObject(index).getJSONArray("recognized_labels")
            (0 until recognized.length()).any { recognized.getString(it) == label }
        }

        fun boundsForLabel(label: String): Rect? = (0 until nodeRecords.length())
            .asSequence()
            .map { nodeRecords.getJSONObject(it) }
            .firstOrNull { record ->
                val recognized = record.getJSONArray("recognized_labels")
                (0 until recognized.length()).any { recognized.getString(it) == label }
            }
            ?.getJSONArray("bounds_px")
            ?.let { bounds -> Rect(bounds.getDouble(0).toFloat(), bounds.getDouble(1).toFloat(), bounds.getDouble(2).toFloat(), bounds.getDouble(3).toFloat()) }
    }
}

internal class S814CaptureSurfaceFailure(
    val diagnostics: JSONObject,
    reason: String,
    cause: Throwable? = null,
) : AssertionError(reason) {
    init {
        if (cause != null) initCause(cause)
    }
}

internal object S814ActualChatCapture {
    private const val CAPTURE_LABEL = "Write anything"
    private const val SAVE_LABEL = "Save capture"
    private const val SAVED_MESSAGE = "Saved. No ledger record or automatic match was created."
    private val safeLabels = setOf(
        "CHAT / QUICK CAPTURE",
        "ASK THE HOSTED AGENT",
        CAPTURE_LABEL,
        SAVE_LABEL,
        "NEW QUICK CAPTURE",
        "Ask about your notes or pending drafts",
        "Chat / Quick Capture",
    )
    private val scrollMatcher = SemanticsMatcher("actual Chat lazy scroll container") {
        it.config.contains(SemanticsActions.ScrollBy) &&
            it.config.contains(SemanticsActions.ScrollToIndex)
    }

    suspend fun saveCapture(
        compose: ComposeTestRule,
        note: String,
        beforeSave: (() -> Unit)? = null,
        afterSave: (() -> Unit)? = null,
    ): JSONObject {
        val diagnostics = JSONObject()
            .put("surface", "actual_MainActivity_Chat_Quick_Capture")
            .put("field_label", CAPTURE_LABEL)
            .put("field_selection_relation", "label_descendant_of_SetText_field")
        var stage = "snapshot_before_scroll"
        try {
            diagnostics.put("semantics_tree_before_scroll", semanticsTree(compose))
                .put("root_scroll_before", scrollContainerState(compose))

            stage = "scroll_to_capture_label"
            val scrollsBefore = compose.onAllNodes(scrollMatcher, useUnmergedTree = true)
                .fetchSemanticsNodes()
            if (scrollsBefore.size != 1) {
                throw S814CaptureSurfaceFailure(
                    diagnostics.put("scroll_container_count_before", scrollsBefore.size),
                    "the actual Chat surface did not expose one lazy scroll container",
                )
            }
            compose.onAllNodes(scrollMatcher, useUnmergedTree = true)
                .get(0)
                .performScrollToNode(hasText(CAPTURE_LABEL))
            diagnostics.put("semantics_tree_after_scroll", semanticsTree(compose))
                .put("root_scroll_after", scrollContainerState(compose))

            stage = "identify_capture_field"
            val labelNodes = compose.onAllNodesWithText(CAPTURE_LABEL, useUnmergedTree = true)
                .fetchSemanticsNodes()
            val setTextNodes = compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
                .fetchSemanticsNodes()
            val labelNode = labelNodes.singleOrNull()
            val scopedFields = labelNode?.let { label ->
                setTextNodes.filter { field -> containsNode(field, label.id) }
            }.orEmpty()
            val selectedField = scopedFields.singleOrNull()
            diagnostics.put(
                "targeted_field_identity",
                JSONObject()
                    .put("label_node_count", labelNodes.size)
                    .put("label_node_ids", JSONArray(labelNodes.map { it.id }))
                    .put("SetText_node_ids", JSONArray(setTextNodes.map { it.id }))
                    .put("scoped_field_candidate_ids", JSONArray(scopedFields.map { it.id }))
                    .put("selected_label_node_id", labelNode?.id ?: JSONObject.NULL)
                    .put("selected_field_node_id", selectedField?.id ?: JSONObject.NULL),
            )
            if (selectedField == null) {
                throw S814CaptureSurfaceFailure(
                    diagnostics,
                    "the unique capture label did not identify one SetText node inside its field",
                )
            }

            stage = "enter_synthetic_note"
            val fieldIndex = setTextNodes.indexOfFirst { it.id == selectedField.id }
            val textField = compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
                .get(fieldIndex)
            textField.performScrollTo()
            textField.performClick()
            textField.performTextClearance()
            textField.performTextInput(note)
            diagnostics.put("synthetic_note_entered", true)
                .put("typed_field_node_id", selectedField.id)

            stage = "scroll_to_save_capture"
            compose.onAllNodes(scrollMatcher, useUnmergedTree = true)
                .get(0)
                .performScrollToNode(hasText(SAVE_LABEL))
            val saveNodes = compose.onAllNodesWithText(SAVE_LABEL, useUnmergedTree = true)
                .fetchSemanticsNodes()
            diagnostics.put("save_capture_label_node_ids", JSONArray(saveNodes.map { it.id }))
            if (saveNodes.size != 1) {
                throw S814CaptureSurfaceFailure(
                    diagnostics,
                    "the actual Save capture action did not resolve to one semantics node",
                )
            }
            beforeSave?.invoke()

            stage = "click_save_capture"
            compose.onAllNodesWithText(SAVE_LABEL, useUnmergedTree = true)
                .get(0)
                .performClick()

            stage = "wait_for_saved_confirmation"
            compose.waitUntil(timeoutMillis = 30_000) {
                compose.onAllNodesWithText(
                    SAVED_MESSAGE,
                    substring = true,
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(
                SAVED_MESSAGE,
                substring = true,
                useUnmergedTree = true,
            ).performScrollTo().assertIsDisplayed()
            diagnostics.put("saved_confirmation_visible", true)
                .put("semantics_tree_after_save", semanticsTree(compose))
                .put("root_scroll_after_save", scrollContainerState(compose))
            afterSave?.invoke()
            return diagnostics
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: S814CaptureSurfaceFailure) {
            throw failure
        } catch (failure: Throwable) {
            diagnostics.put("failure_stage", stage)
                .put("failure_type", failure.javaClass.simpleName)
                .put("semantics_tree_at_failure", semanticsTree(compose))
                .put("root_scroll_at_failure", scrollContainerState(compose))
            throw S814CaptureSurfaceFailure(
                diagnostics,
                "the shared actual-surface capture action failed at $stage",
                failure,
            )
        }
    }

    private fun semanticsTree(compose: ComposeTestRule): JSONObject {
        val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val records = JSONArray()
        val labels = sortedSetOf<String>()

        fun visit(node: SemanticsNode, parentId: Int?, depth: Int) {
            val config = node.config
            val textValues = if (config.contains(SemanticsProperties.Text)) {
                config[SemanticsProperties.Text].map { it.text }
            } else {
                emptyList()
            }
            val recognized = textValues.filter { it in safeLabels }
            labels += recognized
            val bounds = node.boundsInRoot
            val verticalScroll = if (config.contains(SemanticsProperties.VerticalScrollAxisRange)) {
                val range = config[SemanticsProperties.VerticalScrollAxisRange]
                JSONObject()
                    .put("value_px", range.value().toDouble())
                    .put("max_value_px", range.maxValue().toDouble())
            } else {
                JSONObject.NULL
            }
            records.put(
                JSONObject()
                    .put("node_id", node.id)
                    .put("parent_node_id", parentId ?: JSONObject.NULL)
                    .put("depth", depth)
                    .put("bounds_px", rectJson(bounds))
                    .put("recognized_labels", JSONArray(recognized))
                    .put("has_editable_text", config.contains(SemanticsProperties.EditableText))
                    .put("has_set_text_action", config.contains(SemanticsActions.SetText))
                    .put("has_scroll_by_action", config.contains(SemanticsActions.ScrollBy))
                    .put("has_scroll_to_index_action", config.contains(SemanticsActions.ScrollToIndex))
                    .put("vertical_scroll", verticalScroll),
            )
            node.children.forEach { child -> visit(child, node.id, depth + 1) }
        }

        visit(root, null, 0)
        return JSONObject()
            .put("root_node_id", root.id)
            .put("root_bounds_px", rectJson(root.boundsInRoot))
            .put("node_count", records.length())
            .put("recognized_labels", JSONArray(labels))
            .put("nodes", records)
    }

    private fun scrollContainerState(compose: ComposeTestRule): JSONObject {
        val nodes = compose.onAllNodes(scrollMatcher, useUnmergedTree = true)
            .fetchSemanticsNodes()
        return JSONObject()
            .put("container_count", nodes.size)
            .put(
                "containers",
                JSONArray(nodes.map { node ->
                    val config = node.config
                    val vertical = if (config.contains(SemanticsProperties.VerticalScrollAxisRange)) {
                        val range = config[SemanticsProperties.VerticalScrollAxisRange]
                        JSONObject()
                            .put("value_px", range.value().toDouble())
                            .put("max_value_px", range.maxValue().toDouble())
                    } else {
                        JSONObject.NULL
                    }
                    JSONObject()
                        .put("node_id", node.id)
                        .put("bounds_px", rectJson(node.boundsInRoot))
                        .put("vertical_scroll", vertical)
                }),
            )
    }

    private fun containsNode(root: SemanticsNode, targetId: Int): Boolean =
        root.id == targetId || root.children.any { containsNode(it, targetId) }

    private fun rectJson(rect: Rect): JSONArray = JSONArray()
        .put(rect.left)
        .put(rect.top)
        .put(rect.right)
        .put(rect.bottom)
}
