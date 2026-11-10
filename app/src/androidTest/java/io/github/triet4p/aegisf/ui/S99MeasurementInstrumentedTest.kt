package io.github.triet4p.aegisf.ui

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.triet4p.aegisf.AegisApplication
import io.github.triet4p.aegisf.MainActivity
import io.github.triet4p.aegisf.data.ContextLinkState
import io.github.triet4p.aegisf.data.CaptureKind
import io.github.triet4p.aegisf.data.DeleteCaptureRequest
import io.github.triet4p.aegisf.data.DeleteTransactionRequest
import io.github.triet4p.aegisf.data.DismissDraftRequest
import io.github.triet4p.aegisf.data.DraftCandidateQuery
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.LedgerAggregate
import io.github.triet4p.aegisf.data.LedgerDateRange
import io.github.triet4p.aegisf.data.LedgerRecordQuery
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.ReviewCategoryOrigin
import io.github.triet4p.aegisf.data.SourceQuery
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.provider.HostedAdapters
import io.github.triet4p.aegisf.provider.HostedHttpRequest
import io.github.triet4p.aegisf.provider.HostedHttpResponse
import io.github.triet4p.aegisf.provider.HostedProtocol
import io.github.triet4p.aegisf.provider.HostedSseEvent
import io.github.triet4p.aegisf.provider.HostedStreamFrame
import io.github.triet4p.aegisf.provider.HttpStreamTransport
import io.github.triet4p.aegisf.provider.HttpTransport
import io.github.triet4p.aegisf.provider.UrlConnectionHttpTransport
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S9.9 measurement harness: one financial fixture measured twice through the real app UI — once
 * with two full Manual forms, once with one Quick Capture note plus the assisted Chat run — with
 * the same amounts, times and fixture categories, from capture to two explicitly confirmed ledger
 * records.
 *
 * Fixture ownership is tracked by the ids this run creates: the two seeded draft ids, the note
 * capture id read back from the production query, and the ledger ids its own confirmations wrote.
 * Cleanup addresses exactly those ids through the production user commands; nothing is selected by
 * an amount string and no foreign row is removed.
 *
 * Preflight gates immediately before Send: the intended note must be the one capture holding this
 * note's exact text, the pending drafts carrying this fixture's amounts must be exactly the two
 * seeded ids, the fixture categories must exist, and the ledger must still be at its baseline.
 * A mismatch aborts before generation.
 *
 * Counts: user actions (clicks/typing calls, not physical finger taps), characters typed, and
 * monotonic elapsed milliseconds at named milestones. It never polls the app database while a run
 * is active: reads happen only when the UI is idle, through the production query service.
 *
 * Opt-in with `-e s99_measure=pair`.
 */
@RunWith(AndroidJUnit4::class)
class S99MeasurementInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private var actions = 0
    private var characters = 0
    private val pin: S99TransportPin by lazy { S99TransportPin() }
    private val guardChecks: Int get() = pin.checks
    private val marks = mutableMapOf<String, Long>()
    private var logFile: File? = null

    private val app
        get() = compose.activity.application as AegisApplication

    /**
     * Read-only model preflight: proves the active profile and the exact serialized request carry
     * the operator-supplied authorized target, then installs the fail-closed transport guard. It
     * opens no connection and touches no fixture, so a wrong model or endpoint stops the attempt
     * before the one authorized run.
     */
    @Test
    fun modelProof() = runBlocking {
        val mode = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("s99_measure")
        org.junit.Assume.assumeTrue("opt-in measurement", mode == "model")
        startLog("model-proof")
        installPinnedGuard()
        emit("S99M modelProof OK guardChecks=$guardChecks")
    }

    /**
     * Local proof of both guard paths with fake delegates and no network: the authorized payload
     * is forwarded verbatim on `post` and `postStream`, and any other model or endpoint is refused
     * before the delegate is called.
     */
    @Test
    fun guardPathsProof() = runBlocking {
        val mode = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("s99_measure")
        org.junit.Assume.assumeTrue("opt-in measurement", mode == "guard")
        startLog("guard-paths")
        var postCalls = 0
        var streamCalls = 0
        var checks = 0
        val fake = PinnedModelTransport(
            postDelegate = { request ->
                postCalls += 1
                HostedHttpResponse(
                    status = 200,
                    headers = emptyMap(),
                    body = "{\"forwardedBytes\":${request.body.length}}",
                )
            },
            streamDelegate = { request ->
                streamCalls += 1
                kotlinx.coroutines.flow.flowOf(HostedStreamFrame.Start(200, emptyMap(), null))
            },
            check = { request ->
                checks += 1
                val model = org.json.JSONObject(request.body).optString("model")
                check(model == pin.model) {
                    "non-authorized model $model reached the guard"
                }
                check(request.url == pin.endpoint) {
                    "non-authorized endpoint ${request.url} reached the guard"
                }
            },
        )
        fun request(model: String) = HostedHttpRequest(
            url = pin.endpoint,
            headers = mapOf("Content-Type" to "application/json"),
            body = "{\"model\":\"$model\",\"messages\":[]}",
        )
        val allowed = request(pin.model)
        val allowedPost = fake.post(allowed)
        val allowedStream = fake.postStream(allowed).first() as HostedStreamFrame.Start
        val otherModel = request("synthetic-not-authorized")
        val otherEndpoint = allowed.copy(url = pin.endpoint + "-not-authorized")
        val modelPostBlocked = runCatching { fake.post(otherModel) }.exceptionOrNull() != null
        val modelStreamBlocked = runCatching { fake.postStream(otherModel) }.exceptionOrNull() != null
        val endpointPostBlocked = runCatching { fake.post(otherEndpoint) }.exceptionOrNull() != null
        val endpointStreamBlocked =
            runCatching { fake.postStream(otherEndpoint) }.exceptionOrNull() != null
        emit(
            "S99M guardProof allowedPostForwarded=${postCalls == 1} " +
                "allowedStreamForwarded=${streamCalls == 1} " +
                "modelPostBlocked=$modelPostBlocked modelStreamBlocked=$modelStreamBlocked " +
                "endpointPostBlocked=$endpointPostBlocked endpointStreamBlocked=$endpointStreamBlocked " +
                "postCalls=$postCalls streamCalls=$streamCalls checks=$checks " +
                "allowedPostStatus=${allowedPost.status} allowedStreamStatus=${allowedStream.status}",
        )
        assertEquals("the allowed payload was not forwarded on post", 1, postCalls)
        assertEquals("the allowed payload was not forwarded on postStream", 1, streamCalls)
        assertTrue("an unauthorized model reached the post delegate", modelPostBlocked)
        assertTrue("an unauthorized model reached the stream delegate", modelStreamBlocked)
        assertTrue("an unauthorized endpoint reached the post delegate", endpointPostBlocked)
        assertTrue("an unauthorized endpoint reached the stream delegate", endpointStreamBlocked)
        assertEquals("the blocked attempts must not reach any delegate", 1, postCalls)
        assertEquals("the blocked attempts must not reach any delegate", 1, streamCalls)
    }

    @Test
    fun measurementPair() = runBlocking {
        val mode = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("s99_measure")
        org.junit.Assume.assumeTrue("opt-in measurement", mode == "pair")
        startLog("measure-pair")
        val day = java.time.Instant.now().atZone(ZoneId.of(ZONE)).toLocalDate().toString()
        emit("S99M pair start day=$day zone=$ZONE")
        emit(
            "S99M pair provider=" + (app.container.hostedProviderSettingsStore.active()?.let {
                "${it.name} · ${it.model} · maxOutputTokens=${it.maxOutputTokens}"
            } ?: "none"),
        )
        val baseline = aggregate(day)
        emit("S99M pair baseline spending=${baseline.spending.totalVnd} records=${baseline.spending.recordCount}")

        measureManual(day, baseline)
        measureAssisted(day, baseline)
        emit("S99M pair done")
    }

    /**
     * Proves the confirmation navigation the measured path depends on, with no model call: a
     * confirmed draft returns to the review list by itself. The real screen's `handleOutcome`
     * calls `onNavigateUp()` on an applied confirmation, so "Back to inbox" exists only on a
     * draft that was already terminal before the visit.
     */
    @Test
    fun confirmReturnsToReviewList() = runBlocking {
        val mode = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("s99_measure")
        org.junit.Assume.assumeTrue("opt-in measurement", mode == "confirm")
        startLog("confirm-flow")
        val day = java.time.Instant.now().atZone(ZoneId.of(ZONE)).toLocalDate().toString()
        val draft = intake("confirm-flow", 99_000L, day, "11:05", "Synthetic Confirm Flow")
        emit("S99M confirm start draft=$draft")
        var transactionId: Long? = null
        try {
            selectDestination("Review notifications")
            waitForText("REVIEW NOTIFICATIONS")
            openDraftFromInbox(draft)
            waitForText("Amount (VND)")
            tap(compose.onNodeWithText("Expense").performScrollTo())
            val before = app.container.workflowQueryService.draftCandidate(draft)!!
            if (before.reviewCategoryId == null || !before.reviewCategoryResolved) {
                chooseReviewCategory("Transport")
            }
            tap(compose.onNodeWithText("Confirm transaction").performScrollTo())
            awaitText("Confirm this record?", 30_000, "confirm-flow")
            tap(compose.onNodeWithText("Confirm"))
            compose.waitUntil(timeoutMillis = 30_000) {
                compose.onAllNodesWithText("REVIEW NOTIFICATIONS", substring = true)
                    .fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithText("Back to inbox", substring = true)
                        .fetchSemanticsNodes().isNotEmpty()
            }
            val returnedToList = compose.onAllNodesWithText("REVIEW NOTIFICATIONS", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
            emit("S99M confirm returnedToReviewList=$returnedToList")
            assertTrue("a successful confirmation left the detail screen", returnedToList)
            transactionId = app.container.workflowQueryService.draftCandidate(draft)?.confirmedTransactionId
            emit("S99M confirm draft=$draft tx=$transactionId")
            assertTrue("the confirmation wrote no ledger record", transactionId != null)
            screenshot("S99M-confirm-returned")
        } finally {
            transactionId?.let { id ->
                val record = app.container.workflowQueryService.findLedgerRecord(id)
                if (record != null) {
                    val outcome = app.container.workflowCommandService.deleteTransaction(
                        WorkflowCallerScope.User,
                        DeleteTransactionRequest(
                            idempotencyKey = "s99-m-confirm-clean-$id",
                            transactionId = id,
                            expectedRevision = record.revision,
                        ),
                    )
                    emit("S99M confirm cleanup tx=$id outcome=${outcome::class.simpleName}")
                }
            }
            val after = aggregate(day)
            emit("S99M confirm afterCleanup spending=${after.spending.totalVnd} records=${after.spending.recordCount}")
        }
    }

    // --- Manual path ---------------------------------------------------------

    private suspend fun measureManual(day: String, baseline: LedgerAggregate) {
        val trial = System.currentTimeMillis().toString()
        val fuelDescription = "S99M $trial pharmacy"
        val snackDescription = "S99M $trial books"
        resetCounters()
        mark("t0")
        emit("S99M path=manual start trial=$trial")
        // The S10.2 Home launcher opens the record form directly; the intermediate Manual
        // menu no longer exists.
        selectDestination("Manual entry")
        awaitText("New transaction", 30_000, "editor")
        fillForm(day, "09:10", "89000", fuelDescription, "Transport")
        tap(compose.onNodeWithTag("manual-save").performScrollTo())
        awaitText("Transaction saved", 30_000, "saved-1")
        mark("firstSaved")
        tap(compose.onNodeWithText("Record another"))
        awaitText("New transaction", 30_000, "editor-2")
        fillForm(day, "09:35", "54000", snackDescription, "Food")
        tap(compose.onNodeWithTag("manual-save").performScrollTo())
        awaitText("Transaction saved", 30_000, "saved-2")
        mark("secondSaved")
        tap(compose.onNodeWithText("Done"))
        waitForText("Manual record")
        mark("done")
        emit(
            "S99M path=manual metrics actions=$actions chars=$characters " +
                "captureToSavedMillis=${marks.getValue("secondSaved") - marks.getValue("t0")} " +
                "firstSavedMillis=${marks.getValue("firstSaved") - marks.getValue("t0")} " +
                "finalizeMillis=${marks.getValue("done") - marks.getValue("secondSaved")} " +
                "forms=2 fieldsPerForm=4 categoryChoices=2 descriptions=${fuelDescription.length + snackDescription.length}",
        )
        // This path's own two records, addressed by their exact ids, prove the endpoint.
        val page = app.container.workflowQueryService.ledgerRecords(
            LedgerRecordQuery(bounds = QueryPageRequest(limit = 200, offset = 0)),
        ).items
        val owned = page.filter { it.description == fuelDescription || it.description == snackDescription }
        emit("S99M path=manual endpoint records=${owned.map { "${it.id}:${it.amountVnd}:${it.categoryId}" }}")
        assertEquals("manual path did not write exactly its two records", 2, owned.size)
        assertEquals(setOf(89_000L, 54_000L), owned.mapNotNull { it.amountVnd }.toSet())

        dashboardEvidence(day, "manual", baseline)

        // Remove exactly this path's two records so the assisted path starts from the baseline.
        owned.forEach { record ->
            val outcome = app.container.workflowCommandService.deleteTransaction(
                WorkflowCallerScope.User,
                DeleteTransactionRequest(
                    idempotencyKey = "s99-m-pair-clean-${record.id}",
                    transactionId = record.id,
                    expectedRevision = record.revision,
                ),
            )
            emit("S99M path=manual cleanup tx=${record.id} outcome=${outcome::class.simpleName}")
        }
        val after = aggregate(day)
        emit("S99M path=manual afterCleanup spending=${after.spending.totalVnd} records=${after.spending.recordCount}")
        assertEquals(
            "the manual fixture's records were not removed",
            baseline.spending.totalVnd to baseline.spending.recordCount,
            after.spending.totalVnd to after.spending.recordCount,
        )
        openAnalytics()
        waitForText("CONFIRMED SPENDING")
        screenshot("S99M-pair-after-manual-cleanup")
    }

    // --- Assisted path -------------------------------------------------------

    private suspend fun measureAssisted(day: String, baseline: LedgerAggregate) {
        val note = NOTE
        val prompt = PROMPT
        // Fail closed before seeding anything: the authorized model and serialized bytes are
        // proven, and any later request with another model is refused before the network call.
        installPinnedGuard()
        val fuelDraft = intake("pharmacy", 89_000L, day, "09:10", "Synthetic Pharmacy")
        val snackDraft = intake("books", 54_000L, day, "09:35", "Synthetic Bookshop")
        val seededDrafts = setOf(fuelDraft, snackDraft)
        emit("S99M path=assisted seededDrafts=$fuelDraft/$snackDraft")
        resetCounters()
        val priorRunId = latestRunRowId()
        mark("t0")
        var captureId: Long? = null
        var confirmedIds: List<Long?> = listOf(null, null)
        try {
            // S10.2 splits the local note journal and the AI conversation into two
            // destinations: the note is saved on Notes, then the run starts on AI Chat.
            selectDestination("Notes")
            compose.waitUntil(120_000) {
                compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
            }
            val captureField = compose.onAllNodes(hasSetTextAction())[0]
            captureField.performScrollTo()
            characters += note.length
            captureField.performTextInput(note)
            actions += 1
            tap(compose.onNodeWithText("Save capture").performScrollTo())
            awaitText("Saved. No ledger record or automatic match was created.", 30_000, "capture-saved")
            mark("captureSaved")
            screenshot("S99M-pair-assisted-capture")
            captureId = newestCaptureId(note)
            emit("S99M path=assisted captureSaved id=$captureId actions=$actions chars=$characters")

            // The conversation now starts on its own destination.
            selectDestination("Chat / Quick Capture")
            compose.waitUntil(120_000) {
                compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
            }

            // Preflight immediately before Send: prove the intended note and the intended drafts.
            preflight(day, note, captureId, seededDrafts, baseline)

            compose.onAllNodes(hasScrollAction())[0]
                .performScrollToNode(hasText("ASK THE HOSTED AGENT", substring = true))
            compose.waitForIdle()
            val promptField = compose.onAllNodes(hasSetTextAction())[0]
            promptField.performScrollTo()
            compose.waitForIdle()
            characters += prompt.length
            promptField.performTextInput(prompt)
            actions += 1
            val toggles = compose.onAllNodes(androidx.compose.ui.test.isToggleable())
            for (index in 1 until minOf(4, toggles.fetchSemanticsNodes().size)) {
                toggles[index].performScrollTo()
                toggles[index].performClick()
                actions += 1
            }
            hideKeyboard()
            tap(compose.onNodeWithText("Send").performScrollTo())
            mark("sent")
            awaitBusy()
            awaitIdle()
            var rounds = 0
            while (rounds < 2) {
                val clarification = compose.onAllNodesWithText("Clarification needed", substring = true)
                    .fetchSemanticsNodes()
                if (clarification.isEmpty()) break
                val question = latestClarificationQuestion(priorRunId)
                emit("S99M path=assisted clarificationQuestion=${question?.take(600) ?: "unreadable"}")
                val answer = question?.let { fixtureAnswer(it, day, note, seededDrafts) }
                if (answer == null) {
                    emit("S99M path=assisted clarificationAbort=unknownQuestion")
                    detectEndOfRun(priorRunId)
                    return finishAssisted(
                        day = day, note = note, captureId = captureId, priorRunId = priorRunId,
                        seededDrafts = seededDrafts, baseline = baseline,
                        aborted = "UNKNOWN_CLARIFICATION_QUESTION",
                    )
                }
                val answerField = compose.onNodeWithText("Your answer")
                answerField.performScrollTo()
                answerField.performTextClearance()
                characters += answer.length
                answerField.performTextInput(answer)
                actions += 1
                hideKeyboard()
                tap(compose.onNodeWithText("Continue"))
                awaitBusy()
                awaitIdle()
                rounds += 1
            }
            mark("idle")
            emit(
                "S99M path=assisted runIdle actions=$actions chars=$characters " +
                    "hostedWaitMillis=${marks.getValue("idle") - marks.getValue("sent")} " +
                    "totalToIdleMillis=${marks.getValue("idle") - marks.getValue("t0")} " +
                    "clarificationRounds=$rounds",
            )
            val runFacts = newestRunFacts(priorRunId)
            emit("S99M path=assisted runFacts=$runFacts")
            emit("S99M path=assisted traceTools=" + traceToolNames(runFacts?.first))
            val appliedCategoryWrites = traceAppliedCategoryWrites(runFacts?.first)
            emit(
                "S99M path=assisted hostedCategoryCalls=" +
                    traceToolNames(runFacts?.first).count { it == "update_draft_review" } +
                    " appliedVerified=" + appliedCategoryWrites,
            )
            emit("S99M path=assisted guardChecks=$guardChecks")
            exportMeasurementTrace(runFacts?.first)
            screenshot("S99M-pair-assisted-idle")
            // The endpoint requires two applied, locally verified hosted category writes before
            // any person touches the row. A missing write is a failed assisted path, not a manual
            // fallback, so it stops here without entering a category.
            if (appliedCategoryWrites < 2) {
                emit(
                    "S99M path=assisted OUTCOME=FAILURE_CATEGORY_WRITES " +
                        "appliedVerifiedWrites=$appliedCategoryWrites required=2",
                )
                return finishAssisted(
                    day = day, note = note, captureId = captureId, priorRunId = priorRunId,
                    seededDrafts = seededDrafts, baseline = baseline,
                    aborted = "HOSTED_CATEGORY_WRITES_MISSING",
                    confirmed = listOf(fuelDraft, snackDraft).map { id ->
                        app.container.workflowQueryService.draftCandidate(id)?.confirmedTransactionId
                    },
                )
            }

            selectDestination("Review notifications")
            waitForText("REVIEW NOTIFICATIONS")
            val catalogIds = app.container.workflowQueryService.categoryCatalog()
                .map { it.id }.toSet()
            listOf(fuelDraft, snackDraft).forEachIndexed { index, draftId ->
                val before = app.container.workflowQueryService.draftCandidate(draftId)!!
                val links = app.container.workflowQueryService
                    .draftContextLinks(draftId, QueryPageRequest(10, 0)).items
                val accepted = links.any { it.state == ContextLinkState.ACCEPTED }
                val categoryExists = before.reviewCategoryId != null &&
                    before.reviewCategoryId in catalogIds
                emit(
                    "S99M path=assisted reviewOpen draft=$draftId category=${before.reviewCategoryId} " +
                        "origin=${before.reviewCategoryOrigin} resolved=${before.reviewCategoryResolved} " +
                        "categoryExists=$categoryExists userCategoryActions=0 " +
                        "acceptedLink=$accepted links=${links.map { it.state }} " +
                        "pairs=${links.filter { it.state == ContextLinkState.ACCEPTED }.map {
                            "${it.eventId}:${it.draftId}"
                        }}",
                )
                if (!accepted) return@forEachIndexed
                // The endpoint under test stores the category from the hosted workflow before the
                // person touches the row. A category this harness entered itself would prove the
                // manual path, not the assisted one, so it is never substituted here.
                if (before.reviewCategoryOrigin != ReviewCategoryOrigin.HOSTED ||
                    !before.reviewCategoryResolved || !categoryExists
                ) {
                    emit(
                        "S99M path=assisted hostedCategoryMissing draft=$draftId " +
                            "origin=${before.reviewCategoryOrigin} resolved=${before.reviewCategoryResolved} " +
                            "categoryExists=$categoryExists " +
                            "appliedVerifiedWrites=$appliedCategoryWrites",
                    )
                    return finishAssisted(
                        day = day, note = note, captureId = captureId, priorRunId = priorRunId,
                        seededDrafts = seededDrafts, baseline = baseline,
                        aborted = "HOSTED_CATEGORY_MISSING",
                        confirmed = listOf(fuelDraft, snackDraft).map { id ->
                            app.container.workflowQueryService.draftCandidate(id)?.confirmedTransactionId
                        },
                    )
                }
                emit("S99M path=assisted hostedCategoryPreserved draft=$draftId origin=HOSTED")
                openDraftFromInbox(draftId)
                waitForText("Amount (VND)")
                tap(compose.onNodeWithText("Expense").performScrollTo())
                tap(compose.onNodeWithText("Confirm transaction").performScrollTo())
                awaitText("Confirm this record?", 30_000, "confirm-$draftId")
                tap(compose.onNodeWithText("Confirm"))
                emit(
                    "S99M path=assisted explicitConfirm draft=$draftId ordinal=${index + 1}/2 " +
                        "actions=$actions",
                )
                // A successful confirmation navigates back to the inbox itself; "Back to inbox"
                // only exists on a draft that was already terminal before this visit.
                compose.waitUntil(timeoutMillis = 30_000) {
                    compose.onAllNodesWithText("REVIEW NOTIFICATIONS", substring = true)
                        .fetchSemanticsNodes().isNotEmpty() ||
                        compose.onAllNodesWithText("Back to inbox", substring = true)
                            .fetchSemanticsNodes().isNotEmpty()
                }
                if (compose.onAllNodesWithText("Back to inbox", substring = true)
                        .fetchSemanticsNodes().isNotEmpty()
                ) {
                    tap(compose.onNodeWithText("Back to inbox"))
                }
                compose.waitForIdle()
            }
            mark("confirmed")
            confirmedIds = listOf(fuelDraft, snackDraft).map {
                app.container.workflowQueryService.draftCandidate(it)?.confirmedTransactionId
            }
            val linkedCount = listOf(fuelDraft, snackDraft).sumOf { draftId ->
                if (app.container.workflowQueryService
                        .draftContextLinks(draftId, QueryPageRequest(10, 0))
                        .items.any { it.state == ContextLinkState.ACCEPTED }
                ) 1 else 0
            }
            val acceptedPairs = listOf(fuelDraft, snackDraft).flatMap { draftId ->
                app.container.workflowQueryService
                    .draftContextLinks(draftId, QueryPageRequest(10, 0)).items
                    .filter { it.state == ContextLinkState.ACCEPTED }
                    .map { "${it.eventId}:${it.draftId}" to it }
            }.distinctBy { it.first }
            emit(
                "S99M path=assisted acceptedPairs=${acceptedPairs.map { it.first }} " +
                    "distinct=${acceptedPairs.size}",
            )
            val hostedCategories = listOf(fuelDraft, snackDraft).count { draftId ->
                val row = app.container.workflowQueryService.draftCandidate(draftId)
                row != null && row.reviewCategoryOrigin == ReviewCategoryOrigin.HOSTED &&
                    row.reviewCategoryResolved
            }
            val attempts = traceAttemptCount(runFacts?.first)
            emit(
                "S99M path=assisted endpoint actions=$actions chars=$characters linked=$linkedCount/2 " +
                    "distinctAcceptedPairs=${acceptedPairs.size} " +
                    "confirmed=${confirmedIds.count { it != null }}/2 " +
                    "hostedCategories=$hostedCategories/2 userCategoryActions=0 attempts=$attempts " +
                    "guardChecks=$guardChecks " +
                    "captureToEndpointMillis=${marks.getValue("confirmed") - marks.getValue("t0")} " +
                    "runToEndpointMillis=${marks.getValue("confirmed") - marks.getValue("idle")}",
            )
            if (linkedCount < 2 || acceptedPairs.size != 2 ||
                confirmedIds.count { it != null } < 2
            ) {
                val categories = listOf(fuelDraft, snackDraft).map { draftId ->
                    draftId to app.container.workflowQueryService.draftCandidate(draftId)?.reviewCategoryId
                }
                emit(
                    "S99M path=assisted OUTCOME=FAILURE_UNLINKED linked=$linkedCount/2 " +
                        "confirmed=${confirmedIds.count { it != null }}/2 requiredCorrections=" +
                        categories.joinToString(",") { (draftId, categoryId) ->
                            "draft$draftId:link+category" +
                                if (categoryId == null) "" else " (category $categoryId)"
                        },
                )
                return finishAssisted(
                    day = day, note = note, captureId = captureId, priorRunId = priorRunId,
                    seededDrafts = seededDrafts, baseline = baseline,
                    aborted = "ASSISTED_UNLINKED", confirmed = confirmedIds,
                )
            }
            val rows = confirmedIds.filterNotNull().mapNotNull {
                app.container.workflowQueryService.findLedgerRecord(it)
            }
            emit(
                "S99M path=assisted ledger=" + rows.joinToString("|") {
                    "${it.id}:${it.amountVnd}:${it.type}:${it.categoryId}"
                },
            )
            assertEquals(2, rows.size)
            dashboardEvidence(day, "assisted", baseline)
            finishAssisted(
                day = day, note = note, captureId = captureId, priorRunId = priorRunId,
                seededDrafts = seededDrafts, baseline = baseline,
                aborted = null, confirmed = confirmedIds,
            )
        } catch (failure: Throwable) {
            emit("S99M path=assisted ABORTED=${failure::class.simpleName}: ${failure.message?.take(600)}")
            runCatching { exportMeasurementTrace(newestRunFacts(priorRunId)?.first) }
                .onFailure { emit("S99M path=assisted traceExportOnAbortFailed=${it::class.simpleName}") }
            runCatching {
                finishAssisted(
                    day = day, note = note, captureId = captureId, priorRunId = priorRunId,
                    seededDrafts = seededDrafts, baseline = baseline,
                    aborted = "EXCEPTION_${failure::class.simpleName}", confirmed = confirmedIds,
                )
            }
            throw failure
        }
    }

    /**
     * Removes exactly what this attempt created — its two ledger records, its two seeded drafts and
     * its note capture — through the production user commands, then proves the ledger is back at
     * the baseline. Every id is one this attempt read back; no amount or text filter is used.
     */
    private suspend fun finishAssisted(
        day: String,
        note: String,
        captureId: Long?,
        priorRunId: Long,
        seededDrafts: Set<Long>,
        baseline: LedgerAggregate,
        aborted: String?,
        confirmed: List<Long?> = listOf(null, null),
    ) {
        val commands = app.container.workflowCommandService
        val queries = app.container.workflowQueryService
        // The durable trace is exported before any cleanup touches the fixture, so an abort still
        // leaves the complete request/tool/timestamp record on the device for host retrieval.
        runCatching { exportMeasurementTrace(newestRunFacts(priorRunId)?.first) }
            .onFailure { emit("S99M path=assisted traceExportBeforeCleanupFailed=${it::class.simpleName}") }
        confirmed.filterNotNull().forEach { transactionId ->
            val record = queries.findLedgerRecord(transactionId)
            if (record != null) {
                val outcome = commands.deleteTransaction(
                    WorkflowCallerScope.User,
                    DeleteTransactionRequest(
                        idempotencyKey = "s99-m-pair-a-clean-$transactionId",
                        transactionId = transactionId,
                        expectedRevision = record.revision,
                    ),
                )
                emit("S99M path=assisted cleanup tx=$transactionId outcome=${outcome::class.simpleName}")
            }
        }
        seededDrafts.forEach { draftId ->
            val draft = queries.draftCandidate(draftId)
            if (draft != null && draft.reviewStatus == IntakeReviewStatus.PENDING) {
                val outcome = commands.dismissDraft(
                    WorkflowCallerScope.User,
                    DismissDraftRequest(
                        idempotencyKey = "s99-m-pair-a-draft-$draftId",
                        draftId = draftId,
                        expectedRevision = draft.reviewRevision,
                    ),
                )
                emit("S99M path=assisted cleanup draft=$draftId outcome=${outcome::class.simpleName}")
            } else {
                emit("S99M path=assisted cleanup draft=$draftId state=${draft?.reviewStatus}")
            }
        }
        if (captureId != null) {
            val capture = queries.sourceExcerpt(captureId)
            if (capture != null && capture.bodyText == note) {
                val outcome = commands.deleteCapture(
                    WorkflowCallerScope.User,
                    DeleteCaptureRequest(
                        idempotencyKey = "s99-m-pair-a-capture-$captureId",
                        captureId = captureId,
                        expectedRevision = capture.revision,
                    ),
                )
                emit("S99M path=assisted cleanup capture=$captureId outcome=${outcome::class.simpleName}")
            } else {
                emit("S99M path=assisted cleanup capture=$captureId notThisNote=true")
            }
        }
        val after = aggregate(day)
        emit(
            "S99M path=assisted afterCleanup spending=${after.spending.totalVnd} " +
                "records=${after.spending.recordCount} aborted=${aborted ?: "none"}",
        )
        assertEquals(
            "the assisted fixture's ledger records were not removed",
            baseline.spending.totalVnd to baseline.spending.recordCount,
            after.spending.totalVnd to after.spending.recordCount,
        )
        openAnalytics()
        waitForText("CONFIRMED SPENDING")
        screenshot("S99M-pair-after-assisted-cleanup")
    }

    // --- Fixture gates -------------------------------------------------------

    /**
     * Fails before Send unless the fixture is exactly what this attempt created. Prints the whole
     * reading first so a deliberate abort still has its diagnosis.
     */
    private suspend fun preflight(
        day: String,
        note: String,
        captureId: Long?,
        seededDrafts: Set<Long>,
        baseline: LedgerAggregate,
    ) {
        val queries = app.container.workflowQueryService
        val noteRow = captureId?.let { queries.sourceExcerpt(it) }
        val activeCaptures = queries.sourceExcerpts(SourceQuery(bounds = QueryPageRequest(500, 0))).items
        val sameText = activeCaptures.filter { it.bodyText == note }
        val pending = queries.draftCandidates(
            DraftCandidateQuery(
                reviewStatus = IntakeReviewStatus.PENDING,
                bounds = QueryPageRequest(500, 0),
            ),
        ).items
        val plausible = pending.filter { it.amountVnd == 89_000L || it.amountVnd == 54_000L }
        val categories = queries.categoryCatalog().map { it.name }
        val ledgerNow = aggregate(day)
        emit(
            "S99M preflight capture=$captureId noteMatches=${noteRow?.bodyText == note} " +
                "noteKind=${noteRow?.captureKind} capturedAt=${noteRow?.capturedAtEpochMillis} " +
                "sameTextCaptures=${sameText.map { it.id }} activeCaptures=${activeCaptures.size}",
        )
        emit(
            "S99M preflight pending=${pending.size} plausible=" +
                plausible.joinToString("|") { "${it.id}:${it.amountVnd}:${it.memo}:${it.statedDate} ${it.statedTime}" } +
                " seeded=${seededDrafts.sorted()}",
        )
        emit(
            "S99M preflight categories=${categories.size} transport=${"Transport" in categories} " +
                "food=${"Food" in categories} ledger=${ledgerNow.spending.totalVnd}/" +
                "${ledgerNow.spending.recordCount} baseline=${baseline.spending.totalVnd}/" +
                "${baseline.spending.recordCount}",
        )
        val failures = buildList {
            if (noteRow?.bodyText != note) add("the intended note id does not hold this note")
            if (noteRow?.captureKind != CaptureKind.USER_NOTE) {
                add("the intended capture is ${noteRow?.captureKind}, not a user note")
            }
            if (sameText.size != 1) add("${sameText.size} active captures hold this note text")
            if (plausible.map { it.id }.toSet() != seededDrafts) {
                add("plausible pending drafts ${plausible.map { it.id }} != seeded ${seededDrafts.sorted()}")
            }
            if (plausible.mapNotNull { it.amountVnd }.toSet() != setOf(89_000L, 54_000L)) {
                add("plausible drafts do not carry exactly the two fixture amounts")
            }
            plausible.forEach { draft ->
                if (draft.statedDate != day) add("draft ${draft.id} stated date ${draft.statedDate} != $day")
            }
            if ("Transport" !in categories) add("the fixture category Transport does not exist")
            if ("Food" !in categories) add("the fixture category Food does not exist")
            if (ledgerNow.spending.totalVnd != baseline.spending.totalVnd ||
                ledgerNow.spending.recordCount != baseline.spending.recordCount
            ) {
                add("the ledger moved before Send")
            }
        }
        if (failures.isNotEmpty()) {
            emit("S99M preflight FAILED=" + failures.joinToString("; "))
            error("fixture preflight failed: " + failures.joinToString("; "))
        }
        emit("S99M preflight OK")
    }

    /**
     * One answer derived from the fixture itself, or `null` when the question cannot be answered
     * from it (never a date or draft guess). A question naming any draft outside this fixture is
     * treated as a contaminated fixture.
     */
    private fun fixtureAnswer(
        question: String,
        day: String,
        note: String,
        seededDrafts: Set<Long>,
    ): String? {
        val mentioned = Regex("#(\\d+)").findAll(question).map { it.groupValues[1].toLong() }.toSet()
        if (mentioned.isNotEmpty() && !seededDrafts.containsAll(mentioned)) return null
        val lower = question.lowercase()
        return when {
            "which draft" in lower || "draft #" in lower ->
                seededDrafts.sorted().joinToString("; ") { "draft #$it belongs to this note" } +
                    " and no other draft does"
            "time" in lower && "date" in lower -> "$day; the note states 09:10 and 09:35"
            "time" in lower -> "the note states 09:10 and 09:35"
            "date" in lower || "day" in lower -> day
            "which note" in lower || "which capture" in lower -> "the newest saved note: $note"
            else -> null
        }
    }

    // --- Measurement helpers ------------------------------------------------

    /**
     * Fail-closed provider pin for the one authorized live run: see [s99InstallPinnedGuard]. It
     * proves the active profile and the exact serialized request carry the operator-supplied
     * authorized model and endpoint, then wraps the production transport so any later request with
     * a different model or endpoint is refused before the network call. Test-only: the app's
     * stored configuration is read, never rewritten.
     */
    private suspend fun installPinnedGuard() = s99InstallPinnedGuard(app, pin, ::emit)

    private suspend fun aggregate(day: String): LedgerAggregate {
        val zone = ZoneId.of(ZONE)
        val localDay = java.time.LocalDate.parse(day)
        return app.container.workflowQueryService.confirmedLedgerAggregate(
            LedgerDateRange(
                localDay.atStartOfDay(zone).toInstant().toEpochMilli(),
                localDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                zone.id,
            ),
        )
    }

    private suspend fun dashboardEvidence(day: String, path: String, baseline: LedgerAggregate) {
        openAnalytics()
        waitForText("CONFIRMED SPENDING")
        screenshot("S99M-pair-$path-dashboard")
        val zone = ZoneId.of(ZONE)
        val localDay = java.time.LocalDate.parse(day)
        val range = LedgerDateRange(
            localDay.atStartOfDay(zone).toInstant().toEpochMilli(),
            localDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            zone.id,
        )
        val aggregate = app.container.workflowQueryService.confirmedLedgerAggregate(range)
        val drill = app.container.workflowQueryService.spendingDrillDown(
            range,
            bounds = QueryPageRequest(50, 0),
        )
        emit(
            "S99M path=$path dashboard spending=${aggregate.spending.totalVnd} " +
                "records=${aggregate.spending.recordCount} " +
                "categories=${aggregate.spending.categoryTotals.joinToString("|") {
                    "${it.categoryId}:${it.categoryName}:${it.totalVnd}"
                }} baseline=${baseline.spending.totalVnd}/${baseline.spending.recordCount}",
        )
        emit(
            "S99M path=$path drilldown=" + drill.items.joinToString("|") {
                "${it.id}:${it.amountVnd}:${it.categoryId}"
            },
        )
        val stored = app.container.workflowQueryService
            .ledgerRecords(LedgerRecordQuery(bounds = QueryPageRequest(500, 0))).items
        emit(
            "S99M path=$path storedLedger records=${stored.size} " +
                "total=${stored.sumOf { it.amountVnd ?: 0L }} ids=" +
                stored.joinToString(",") { "${it.id}:${it.amountVnd}" },
        )
        assertEquals(
            "the dashboard total is not the baseline plus this path's two records",
            baseline.spending.totalVnd + 143_000L,
            aggregate.spending.totalVnd,
        )
        assertEquals(
            "the dashboard record count is not the baseline plus two",
            baseline.spending.recordCount + 2,
            aggregate.spending.recordCount,
        )
    }

    private fun resetCounters() {
        actions = 0
        characters = 0
        marks.clear()
    }

    /**
     * Resolves the capture this attempt just saved by exact text from the production query. The
     * newest row is the one the real Quick Capture screen wrote; its id is then used for every
     * later read and for cleanup.
     */
    private suspend fun newestCaptureId(note: String): Long {
        val page = app.container.workflowQueryService
            .sourceExcerpts(SourceQuery(bounds = QueryPageRequest(20, 0))).items
        return page.firstOrNull { it.bodyText == note }?.id
            ?: error("the saved note was not found in the production capture query")
    }

    // --- Steps ---------------------------------------------------------------

    private fun fillForm(
        day: String,
        time: String,
        amount: String,
        description: String,
        category: String,
    ) {
        typeIntoTag("manual-amount", amount)
        tap(compose.onNodeWithTag("manual-moment").performScrollTo())
        waitForText("Date & time")
        typeIntoTag("manual-date", day)
        typeIntoTag("manual-time", time)
        tap(compose.onNodeWithTag("manual-moment-apply"))
        typeIntoTag("manual-memo", description)
        val chooser = compose.onNodeWithTag("manual-category-all")
        chooser.performScrollTo()
        tap(chooser)
        waitForText("Choose a category")
        tap(compose.onNodeWithText(category))
    }

    private fun typeIntoTag(tag: String, value: String) {
        val field = compose.onNodeWithTag(tag)
        field.performScrollTo()
        field.performClick()
        field.performTextClearance()
        characters += value.length
        actions += 1
        field.performTextInput(value)
        compose.waitForIdle()
    }

    private fun chooseReviewCategory(name: String) {
        val choice = compose.onNodeWithText(name)
        choice.performScrollTo()
        tap(choice)
    }

    private fun openDraftFromInbox(draftId: Long) {
        repeat(12) {
            val matches = compose.onAllNodesWithText("Review draft #$draftId", substring = true)
                .fetchSemanticsNodes()
            if (matches.isNotEmpty()) {
                tap(compose.onNodeWithText("Review draft #$draftId", substring = true).performScrollTo())
                return
            }
            val next = compose.onAllNodesWithText("Next").fetchSemanticsNodes()
            if (next.isEmpty()) error("draft $draftId is not reachable from the inbox")
            tap(compose.onNodeWithText("Next").performScrollTo())
            compose.waitForIdle()
        }
        error("draft $draftId was not found after paging the inbox")
    }

    private fun selectDestination(description: String) {
        // S10.2 five-tab shell: Manual entry and Analytics are Home launchers, while the
        // conversation has its own destination.
        when (description) {
            "Review notifications" -> selectTab("Review")

            "Chat / Quick Capture" -> selectTab("AI Chat")

            "Notes" -> selectTab("Notes")

            "Manual entry" -> {
                selectTab("Home")
                tap(compose.onNodeWithText("Manual record", substring = true))
            }

            else -> error("unknown destination: $description")
        }
    }

    /**
     * Root destinations are selected by tab semantics: Home's launchers announce
     * "Open Review" / "Open AI Chat" / "Open Notes", which a substring match would also find.
     */
    private fun selectTab(description: String) {
        tap(
            compose.onNode(
                hasContentDescription(description, substring = true) and isSelectable(),
            ),
        )
    }

    /**
     * The reports live behind Home's Analytics launcher. Already being there is a valid state:
     * the launcher is not on screen while a focused route is open.
     */
    private fun openAnalytics() {
        if (compose.onAllNodesWithText("CONFIRMED SPENDING", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        ) {
            return
        }
        selectTab("Home")
        tap(compose.onNodeWithText("Analytics", substring = true))
    }

    private fun tap(node: androidx.compose.ui.test.SemanticsNodeInteraction) {
        actions += 1
        node.performClick()
    }

    private fun waitForText(text: String, timeoutMillis: Long = 120_000) {
        compose.waitUntil(timeoutMillis = timeoutMillis) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitText(text: String, timeoutMillis: Long, tag: String) {
        try {
            waitForText(text, timeoutMillis)
        } catch (failure: Throwable) {
            emit("S99M path=diag tag=$tag screen=" + compose.onRoot().printToString(maxDepth = 10).take(1600))
            throw failure
        }
    }

    private fun awaitBusy() {
        compose.waitUntil(timeoutMillis = 180_000) {
            compose.onAllNodesWithText("Cancel", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        emit("S99M busy=true")
    }

    private fun awaitIdle() {
        compose.waitUntil(timeoutMillis = 600_000) {
            compose.onAllNodesWithText("Cancel", substring = true).fetchSemanticsNodes().isEmpty()
        }
        emit("S99M busy=false")
    }

    /**
     * After an unusable clarification the screen stays in the run; the durable run row already
     * holds its terminal state. This records that state without answering or waiting again.
     */
    private fun detectEndOfRun(priorRunId: Long) {
        emit("S99M path=assisted endOfRun=" + (newestRunFacts(priorRunId)?.toString() ?: "none"))
    }

    private fun hideKeyboard() {
        val imm = compose.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        compose.activity.runOnUiThread {
            imm.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
    }

    private suspend fun intake(
        suffix: String,
        amount: Long,
        day: String,
        time: String,
        memo: String,
    ): Long = app.container.intakeRepository.storePermitted(
        sourcePackage = "com.vietinbank.ipay",
        notificationKey = "s99-measure-$suffix-$amount",
        postedAtEpochMillis = System.currentTimeMillis(),
        content = io.github.triet4p.aegisf.notification.PermittedNotificationContent(
            title = "Tin biến động số dư",
            text = listOf(
                "VietinBank: ${day.substring(8)}/${day.substring(5, 7)}/${day.substring(0, 4)} $time",
                "TK: Redacted",
                "GD: -${String.format(java.util.Locale.US, "%,d", amount)} VND",
                "SDC: Redacted",
                "ND: $memo",
            ).joinToString("\n"),
            bigText = null,
        ),
    )

    private fun latestRunRowId(): Long =
        queryLong("SELECT COALESCE(MAX(id),0) FROM agent_runs")

    private fun newestRunFacts(priorRowId: Long): Triple<String, String, String?>? =
        readRows(
            "SELECT runId, state, COALESCE(stopReason,'-'), answerText FROM agent_runs " +
                "WHERE id > $priorRowId ORDER BY id DESC LIMIT 1",
        ).firstOrNull()?.let { row ->
            val parts = row.split('|')
            Triple(parts[0], parts[1], "${parts[2]}|${parts.getOrNull(3)?.take(400)}")
        }

    private fun latestClarificationQuestion(priorRowId: Long): String? =
        readRows(
            "SELECT payloadJson FROM agent_trace_events WHERE kind='CLARIFICATION_REQUEST' " +
                "AND runId=(SELECT runId FROM agent_runs WHERE id>? ORDER BY id DESC LIMIT 1) " +
                "ORDER BY id DESC LIMIT 1",
            arrayOf(priorRowId.toString()),
        ).firstOrNull()?.let { payload ->
            org.json.JSONObject(payload).optString("question").takeIf { it.isNotBlank() }
        }

    private fun exportMeasurementTrace(runId: String?) {
        if (runId == null) return
        val rows = readRows(
            "SELECT id || '␟' || runId || '␟' || scopeId || '␟' || attempt || '␟' || kind || '␟' || " +
                "direction || '␟' || completion || '␟' || COALESCE(turn,'') || '␟' || " +
                "COALESCE(callId,'') || '␟' || createdAtEpochMillis || '␟' || payloadJson " +
                "FROM agent_trace_events WHERE runId=? ORDER BY id ASC",
            arrayOf(runId),
        )
        val array = org.json.JSONArray()
        rows.forEach { line ->
            val parts = line.split('␟')
            array.put(
                org.json.JSONObject()
                    .put("id", parts[0].toLongOrNull() ?: 0L)
                    .put("runId", parts[1])
                    .put("scopeId", parts.getOrNull(2))
                    .put("attempt", parts.getOrNull(3)?.toIntOrNull() ?: 0)
                    .put("kind", parts.getOrNull(4))
                    .put("direction", parts.getOrNull(5))
                    .put("completion", parts.getOrNull(6))
                    .put("turn", parts.getOrNull(7)?.toIntOrNull() ?: org.json.JSONObject.NULL)
                    .put("callId", parts.getOrNull(8)?.ifBlank { null } ?: org.json.JSONObject.NULL)
                    .put("createdAt", parts.getOrNull(9)?.toLongOrNull() ?: org.json.JSONObject.NULL)
                    .put(
                        "payload",
                        runCatching { org.json.JSONObject(parts.last()) }.getOrNull() ?: parts.last(),
                    ),
            )
        }
        val file = File(compose.activity.filesDir, "s99-measure-$runId.trace.json")
        file.writeText(array.toString())
        emit("S99M path=assisted traceExport file=${file.name} rows=${array.length()} bytes=${file.length()}")
    }

    private fun traceToolNames(runId: String?): List<String> {
        if (runId == null) return emptyList()
        return readRows(
            "SELECT payloadJson FROM agent_trace_events WHERE runId=? AND kind='TOOL_CALL' ORDER BY id",
            arrayOf(runId),
        ).mapNotNull { org.json.JSONObject(it).optString("name").ifBlank { null } }
    }

    private fun traceAppliedCategoryWrites(runId: String?): Int {
        if (runId == null) return 0
        return readRows(
            "SELECT payloadJson FROM agent_trace_events WHERE runId=? AND kind='TOOL_RESULT' ORDER BY id",
            arrayOf(runId),
        ).count { payload ->
            val p = org.json.JSONObject(payload)
            p.optString("name") == "update_draft_review" &&
                p.optString("resultJson").contains("\"effect\":\"applied\"") &&
                p.optString("resultJson").contains("\"verified\":true")
        }
    }

    /** Bounded transport retries are attempts, not model turns; count the run's own rows. */
    private fun traceAttemptCount(runId: String?): Int {
        if (runId == null) return 0
        return readRows(
            "SELECT id FROM agent_trace_events WHERE runId=? AND kind='ATTEMPT_STARTED'",
            arrayOf(runId),
        ).size
    }

    private fun mark(name: String) {
        marks[name] = System.nanoTime() / 1_000_000
    }

    private fun screenshot(name: String) {
        runCatching {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val file = File(compose.activity.filesDir, "$name.png")
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            emit("S99M screenshot=${file.name} bytes=${file.length()}")
        }.onFailure { emit("S99M screenshotFailed=$name ${it::class.simpleName}") }
    }

    private fun startLog(name: String) {
        resetCounters()
        logFile = File(compose.activity.filesDir, "s99-evidence-$name.log")
        runCatching { logFile?.writeText("") }
    }

    private fun emit(line: String) {
        android.util.Log.i("S99M", line)
        println(line)
        logFile?.let { runCatching { it.appendText(line + "\n") } }
    }

    // Only run when the UI is idle, so the read never contends with the app's own connection.
    private fun readRows(sql: String, args: Array<String> = emptyArray()): List<String> =
        android.database.sqlite.SQLiteDatabase.openDatabase(
            compose.activity.getDatabasePath("aegis-ledger.db").path,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.rawQuery(sql, args).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add((0 until cursor.columnCount).joinToString("|") { index ->
                            if (cursor.isNull(index)) "" else cursor.getString(index)
                        })
                    }
                }
            }
        }

    private fun queryLong(sql: String): Long =
        android.database.sqlite.SQLiteDatabase.openDatabase(
            compose.activity.getDatabasePath("aegis-ledger.db").path,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.rawQuery(sql, null).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
        }

    private companion object {
        const val ZONE = "Asia/Ho_Chi_Minh"
        const val NOTE = "Today pharmacy 89000d at 09:10 and today books 54000d at 09:35."
        const val PROMPT = "Use the newest saved note only. Attach unique bank matches and choose " +
            "one existing category for each affected draft. Never confirm anything."
    }
}

/**
 * A deterministic local server-sent-event stream for the interactive hold's clock diagnostic.
 *
 * It opens no connection and carries no business meaning: [chunkCount] fixed deltas at
 * [spacingMillis] of real time, then a terminating `[DONE]`. The exported trace therefore measures
 * only how fast the app consumed consecutive events. There is no tool call, no category and no
 * event text a workflow could act on, so the replay cannot produce an application effect.
 */
private class S99ReplayStreamTransport(
    private val chunkCount: Int,
    private val spacingMillis: Long,
) : HttpTransport, HttpStreamTransport {

    override suspend fun post(request: HostedHttpRequest): HostedHttpResponse =
        HostedHttpResponse(status = 200, headers = emptyMap(), body = "{}")

    override fun postStream(request: HostedHttpRequest): Flow<HostedStreamFrame> = flow {
        val model = runCatching { org.json.JSONObject(request.body).optString("model") }
            .getOrDefault("")
        emit(HostedStreamFrame.Start(200, mapOf("content-type" to "text/event-stream"), null))
        repeat(chunkCount) { index ->
            delay(spacingMillis)
            val last = index == chunkCount - 1
            val chunk = org.json.JSONObject()
                .put("id", "s99-local-$index")
                .put("object", "chat.completion.chunk")
                .put("created", 0)
                .put("model", model)
                .put(
                    "choices",
                    org.json.JSONArray().put(
                        org.json.JSONObject()
                            .put("index", 0)
                            .put("delta", org.json.JSONObject().put("content", "local$index "))
                            .put(
                                "finish_reason",
                                if (last) "stop" else org.json.JSONObject.NULL,
                            ),
                    ),
                )
            emit(HostedStreamFrame.Event(HostedSseEvent(null, chunk.toString())))
        }
        emit(HostedStreamFrame.Event(HostedSseEvent(null, "[DONE]")))
    }
}

// --- Interactive session support on the app's own frame clock ----------------------------------

private const val S99_HOLD_STOP_MARKER = "s99_interactive_hold.stop"
private const val S99_HOLD_POLL_MILLIS = 1_000L
private const val S99_HOLD_STATUS_INTERVAL_MILLIS = 60_000L
private const val S99_HOLD_HARD_LIMIT_MILLIS = 6 * 60 * 60_000L
private const val S99_REPLAY_CHUNKS = 60
private const val S99_REPLAY_SPACING_MILLIS = 150L

/**
 * The one authorized hosted target for an attempt, supplied by the operator
 * (`-e s99_pin_model`, `-e s99_pin_endpoint`) so the guard pins exactly the provider the attempt
 * staged rather than a model name written into the harness. A missing target fails closed: there
 * is no default and no fallback.
 */
private data class S99PinnedTarget(val model: String, val endpoint: String)

private fun s99PinnedTarget(): S99PinnedTarget {
    val arguments = androidx.test.platform.app.InstrumentationRegistry.getArguments()
    val model = arguments.getString("s99_pin_model")?.trim().orEmpty()
    val endpoint = arguments.getString("s99_pin_endpoint")?.trim().orEmpty()
    check(model.isNotBlank() && endpoint.isNotBlank()) {
        "the authorized provider pin is missing; pass -e s99_pin_model and -e s99_pin_endpoint"
    }
    check(!model.contains("pro", ignoreCase = true)) {
        "refusing to pin a prohibited model: $model"
    }
    return S99PinnedTarget(model, endpoint)
}

/**
 * The one pin both guard paths share: parse the serialized body and refuse anything that is not
 * the authorized model or does not address the authorized endpoint, before the delegate is
 * reached. [checks] counts every request the pin inspected.
 */
private class S99TransportPin {
    private val target: S99PinnedTarget by lazy { s99PinnedTarget() }

    var checks = 0
        private set

    val model: String get() = target.model
    val endpoint: String get() = target.endpoint

    fun require(request: HostedHttpRequest) {
        val requested = runCatching {
            org.json.JSONObject(request.body).optString("model")
        }.getOrElse { "unparseable" }
        checks += 1
        check(requested == model) {
            "refusing a provider request with model $requested; only $model is authorized"
        }
        check(request.url == endpoint) {
            "refusing a provider request to ${request.url}; only $endpoint is authorized"
        }
    }
}

/**
 * A transport decorator that pins the serialized model on both the complete-body and the
 * streaming path, then forwards the exact request to its delegate. The production transport is
 * streaming-capable, so preserving [HttpStreamTransport] is required for a real run.
 */
private class PinnedModelTransport(
    private val postDelegate: suspend (HostedHttpRequest) -> HostedHttpResponse,
    private val streamDelegate: (HostedHttpRequest) -> Flow<HostedStreamFrame>,
    private val check: (HostedHttpRequest) -> Unit,
) : HttpTransport, HttpStreamTransport {
    override suspend fun post(request: HostedHttpRequest): HostedHttpResponse {
        check(request)
        return postDelegate(request)
    }

    override fun postStream(request: HostedHttpRequest): Flow<HostedStreamFrame> {
        check(request)
        return streamDelegate(request)
    }
}

/**
 * Install [pin] over the app's transport: prove the active profile and the exact serialized
 * request carry the authorized model and endpoint, then wrap the production transport so every
 * later request on either path is refused before the network call. It opens no connection and
 * rewrites no stored configuration.
 */
private suspend fun s99InstallPinnedGuard(
    app: AegisApplication,
    pin: S99TransportPin,
    emit: (String) -> Unit,
) {
    val profile = requireNotNull(app.container.hostedProviderSettingsStore.active()) {
        "no active hosted provider profile on the isolated app"
    }
    emit(
        "S99M guard profile id=${profile.id} model=${profile.model} " +
            "protocol=${profile.protocol} endpoint=${profile.endpoint} " +
            "maxOutputTokens=${profile.maxOutputTokens} reasoning=${profile.reasoningEffort}",
    )
    check(profile.model == pin.model) {
        "the active profile model is ${profile.model}, not ${pin.model}"
    }
    check(profile.protocol == HostedProtocol.OPENAI_CHAT_COMPLETIONS) {
        "the active profile protocol is ${profile.protocol}"
    }
    check(profile.endpoint == pin.endpoint) {
        "the active profile endpoint is ${profile.endpoint}, not ${pin.endpoint}"
    }

    // Serialize one turn through the real adapter and capture the exact wire body without opening
    // a connection: the same code path a live run uses.
    var preview: String? = null
    val capture = object : HttpTransport {
        override suspend fun post(request: HostedHttpRequest): HostedHttpResponse {
            preview = request.body
            return HostedHttpResponse(
                status = 200,
                headers = mapOf("content-type" to "application/json"),
                body = "{\"id\":\"guard\",\"object\":\"chat.completion\",\"created\":0," +
                    "\"model\":\"${pin.model}\",\"choices\":[{\"index\":0," +
                    "\"message\":{\"role\":\"assistant\",\"content\":\"guard\"}," +
                    "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1," +
                    "\"completion_tokens\":1,\"total_tokens\":2}}",
            )
        }
    }
    val adapter = HostedAdapters.create(profile.validatedConfig(), capture)
    adapter.generate(adapter.begin("guard", "guard"), emptyList())
    val body = requireNotNull(preview) { "the adapter produced no serialized request" }
    val wire = org.json.JSONObject(body)
    val wireModel = wire.optString("model")
    val limitFields = listOf("max_completion_tokens", "max_output_tokens", "max_tokens")
        .filter { wire.has(it) }
    emit(
        "S99M guard serializedModel=$wireModel wireMatches=${wireModel == pin.model} " +
            "limitFields=$limitFields maxOutputTokens=${profile.maxOutputTokens}",
    )
    check(wireModel == pin.model) {
        "the serialized request model is $wireModel, not ${pin.model}"
    }
    check(wire.has("messages")) { "the serialized request has no messages" }

    val inner = UrlConnectionHttpTransport()
    app.container.hostedHttpTransport = PinnedModelTransport(
        postDelegate = { inner.post(it) },
        streamDelegate = { inner.postStream(it) },
        check = pin::require,
    )
    emit(
        "S99M guard installed=true expectedModel=${pin.model} " +
            "expectedEndpoint=${pin.endpoint} streamingCapable=true",
    )
}

/**
 * Interactive UI session support for the screen-driven workflow, on the app's own frame clock.
 *
 * It deliberately holds no Compose test rule. The Compose test environment owns the app's frame
 * clock, and a held test clock delivers a frame only when the instrumentation advances it, so the
 * app consumed streamed provider events once per artificial advance (measured at a 1,028 ms median
 * for a local stream produced every 150 ms) instead of at the stream's own pace. This class only
 * installs the fail-closed pin and keeps the instrumentation process alive while the operator
 * launches and drives the real app through adb, exactly like production. It seeds no fixture, taps
 * no screen and answers no question.
 *
 * `localStreamProof` additionally swaps the pinned production transport for a deterministic local
 * SSE replay (no network), so stream consumption, UI refresh and Cancel can be verified without a
 * provider request. The hold ends when the operator creates `files/s99_interactive_hold.stop`; the
 * six-hour bound is a safety stop, not a run deadline.
 */
@RunWith(AndroidJUnit4::class)
class S99InteractiveHoldInstrumentedTest {

    private val app: AegisApplication
        get() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as AegisApplication

    private var logFile: File? = null

    @Test
    fun interactiveHold() = runBlocking {
        val mode = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("s99_measure")
        org.junit.Assume.assumeTrue("opt-in interactive hold", mode == "hold")
        val stop = begin("interactive-hold")
        val pin = install(stop, replay = false)
        hold(stop, pin)
    }

    @Test
    fun localStreamProof() = runBlocking {
        val mode = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("s99_measure")
        org.junit.Assume.assumeTrue("opt-in local stream proof", mode == "replay")
        val stop = begin("local-stream")
        val pin = install(stop, replay = true)
        hold(stop, pin)
    }

    /** Opens this session's log and refuses to start over a stale stop marker. */
    private fun begin(name: String): File {
        logFile = File(app.filesDir, "s99-evidence-$name.log")
        runCatching { logFile?.writeText("") }
        val stop = File(app.filesDir, S99_HOLD_STOP_MARKER)
        check(!stop.exists()) { "the interactive hold stop marker already exists: ${stop.name}" }
        return stop
    }

    /** Installs the pin (and optionally the local replay), then reports what the hold enforces. */
    private suspend fun install(stop: File, replay: Boolean): S99TransportPin {
        check(!stop.exists()) { "the interactive hold stop marker already exists: ${stop.name}" }
        val pin = S99TransportPin()
        s99InstallPinnedGuard(app, pin, ::emit)
        if (replay) {
            val stream = S99ReplayStreamTransport(S99_REPLAY_CHUNKS, S99_REPLAY_SPACING_MILLIS)
            app.container.hostedHttpTransport = PinnedModelTransport(
                postDelegate = { stream.post(it) },
                streamDelegate = { stream.postStream(it) },
                check = pin::require,
            )
            emit(
                "S99M replay installed=true chunks=$S99_REPLAY_CHUNKS " +
                    "spacingMillis=$S99_REPLAY_SPACING_MILLIS network=none",
            )
        }
        emit("S99M hold installed=true guardChecks=${pin.checks} frameClock=application")
        return pin
    }

    private suspend fun hold(stop: File, pin: S99TransportPin) {
        val startedAt = System.currentTimeMillis()
        while (!stop.exists()) {
            delay(S99_HOLD_POLL_MILLIS)
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed % S99_HOLD_STATUS_INTERVAL_MILLIS < S99_HOLD_POLL_MILLIS) {
                emit("S99M hold waiting elapsedMs=$elapsed guardChecks=${pin.checks}")
            }
            if (elapsed >= S99_HOLD_HARD_LIMIT_MILLIS) break
        }
        emit(
            "S99M hold released=true stop=${stop.exists()} guardChecks=${pin.checks} " +
                "elapsedMs=${System.currentTimeMillis() - startedAt}",
        )
    }

    private fun emit(line: String) {
        android.util.Log.i("S99M", line)
        println(line)
        logFile?.let { runCatching { it.appendText(line + "\n") } }
    }
}
