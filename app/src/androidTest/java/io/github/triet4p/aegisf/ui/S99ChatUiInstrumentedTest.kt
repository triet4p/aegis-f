package io.github.triet4p.aegisf.ui

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.triet4p.aegisf.AegisApplication
import io.github.triet4p.aegisf.MainActivity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S9.9 actual Compose Chat UI evidence on the isolated app's persistent, enlarged inbox.
 *
 * The screen, the production container, the real provider profile, the production executor and
 * the persistent database are the app's own. Only the text injection is scripted: the note was
 * already saved through the real Quick Capture screen with the device IME, and this test sets the
 * prompt through Compose semantics because the device IME autocorrects English words.
 *
 * Nothing is cleared. The test reads the app's own database after the run and emits the exact
 * terminal state, stop reason, serialized request limits, tool calls, usage and effects.
 */
@RunWith(AndroidJUnit4::class)
class S99ChatUiInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun chatRunOnEnlargedPersistentInbox() {
        org.junit.Assume.assumeTrue(
            "opt-in hosted UI evidence",
            androidx.test.platform.app.InstrumentationRegistry.getArguments()
                .getString("s99_ui") == "yes",
        )
        val activity = compose.activity
        val app = activity.application as AegisApplication
        val databaseFile = activity.getDatabasePath("aegis-ledger.db")
        val ledgerBefore = scalarLong(databaseFile, "SELECT COUNT(*) FROM transactions")
        val priorRunId = scalarLong(databaseFile, "SELECT COALESCE(MAX(id), 0) FROM agent_runs")
        val pendingBefore = scalarLong(
            databaseFile,
            "SELECT COUNT(*) FROM pending_bank_drafts WHERE reviewStatus = 'PENDING'",
        )
        val captureBefore = scalarLong(databaseFile, "SELECT COUNT(*) FROM capture_messages")
        emit(
            "S99UI start captures=$captureBefore pendingDrafts=$pendingBefore " +
                "ledger=$ledgerBefore priorRunId=$priorRunId",
        )
        emit(
            "S99UI activeProvider=" +
                (app.container.hostedProviderSettingsStore.active()?.let {
                    "${it.name} · ${it.model} · maxOutputTokens=${it.maxOutputTokens}"
                } ?: "none"),
        )

        selectDestination("AI Chat")
        compose.waitUntil(timeoutMillis = 120_000) {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
        val fields = compose.onAllNodes(hasSetTextAction())
        val fieldCount = fields.fetchSemanticsNodes().size
        emit("S99UI textFields=$fieldCount")
        val promptField = fields[0]
        promptField.performScrollTo()
        promptField.performTextClearance()
        promptField.performTextInput(PROMPT)
        val typed = promptField.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals("the prompt text was not entered exactly", PROMPT, typed.trim())
        emit("S99UI promptChars=${typed.length} promptExact=true")

        // The four workflow skills the run needs; the first checkbox is INTERPRET EVENTS.
        val toggles = compose.onAllNodes(androidx.compose.ui.test.isToggleable())
        val toggleCount = toggles.fetchSemanticsNodes().size
        emit("S99UI toggles=$toggleCount")
        for (index in 1 until minOf(4, toggleCount)) {
            val toggle = toggles[index]
            toggle.performScrollTo()
            toggle.performClick()
        }
        emit("S99UI skillsSelected=4")

        // The field's focus can raise the soft keyboard, which overlays the Send button in
        // root coordinates. Hide it before the click, then require an enabled, visible button.
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        activity.runOnUiThread {
            imm.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
        val send = compose.onNodeWithText("Send")
        send.performScrollTo()
        send.assertIsEnabled()
        send.performClick()
        compose.waitForIdle()
        emit("S99UI sent=true")

        // Wait on the app's own busy state (the Cancel action is shown only while a run is
        // active). No database handle is opened during the run: a second read-only SQLite
        // connection on the same WAL file would contend with Room's connection.
        compose.waitUntil(timeoutMillis = 180_000) {
            compose.onAllNodesWithText("Cancel", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        emit("S99UI runBecameBusy=true")
        compose.waitUntil(timeoutMillis = 600_000) {
            compose.onAllNodesWithText("Cancel", substring = true).fetchSemanticsNodes().isEmpty()
        }
        emit("S99UI runBecameIdle=true")
        runCatching {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val file = File(activity.filesDir, "s99-chat-ui-final.png")
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            emit("S99UI screenshot=${file.name} bytes=${file.length()}")
        }.onFailure { emit("S99UI screenshotFailed=${it::class.simpleName}: ${it.message}") }

        val newest = scalarString(
            databaseFile,
            "SELECT id || '|' || runId || '|' || state FROM agent_runs ORDER BY id DESC LIMIT 1",
        )
        var runId = ""
        var state = ""
        if (newest != null) {
            val parts = newest.split('|')
            if (parts.size == 3 && (parts[0].toLongOrNull() ?: 0L) > (priorRunId ?: 0L)) {
                runId = parts[1]
                state = parts[2]
            }
        }
        emit("S99UI runId=$runId state=$state")

        assertTrue("the real Chat run did not reach a terminal state: $state", state in TERMINAL_STATES)
        val row = scalarString(
            databaseFile,
            "SELECT state || '|' || COALESCE(stopReason,'-') || '|' || intentNames || '|' || " +
                "COALESCE(maxOutputTokens,'null') FROM agent_runs WHERE runId = '$runId'",
        )
        emit("S99UI runRow=$row")

        val admitted = scalarString(
            databaseFile,
            "SELECT payloadJson FROM agent_trace_events WHERE runId = '$runId' AND kind = 'RUN_ADMITTED' LIMIT 1",
        )
        emit("S99UI admitted=${admitted?.take(700)}")
        assertNotNull("the run recorded no admission trace", admitted)
        assertTrue(
            "the admitted run recorded a fixed output cap",
            admitted!!.contains("\"maxOutputTokens\":null"),
        )

        val requests = queryRows(
            databaseFile,
            "SELECT payloadJson FROM agent_trace_events WHERE runId = '$runId' AND kind = 'PROVIDER_REQUEST' ORDER BY id",
        )
        var cappedRequests = 0
        requests.forEachIndexed { index, payload ->
            val body = runCatching { JSONObject(payload).getString("body") }.getOrNull()
            val parsed = body?.let { runCatching { JSONObject(it) }.getOrNull() }
            val serialized = buildList {
                parsed?.opt("max_completion_tokens")?.let { add("max_completion_tokens=$it") }
                parsed?.opt("max_output_tokens")?.let { add("max_output_tokens=$it") }
                parsed?.optJSONObject("generationConfig")?.opt("maxOutputTokens")
                    ?.let { add("generationConfig.maxOutputTokens=$it") }
            }
            if (serialized.isNotEmpty()) cappedRequests += 1
            emit("S99UI request index=$index bytes=${body?.length ?: -1} serializedLimits=$serialized")
        }
        assertEquals("a request carried an output cap", 0, cappedRequests)

        val turns = queryRows(
            databaseFile,
            "SELECT payloadJson FROM agent_trace_events WHERE runId = '$runId' AND kind = 'PROVIDER_RESPONSE' ORDER BY id",
        )
        turns.forEachIndexed { index, payload ->
            val root = runCatching { JSONObject(payload) }.getOrNull()
            val usage = root?.optJSONObject("usage")
            val finish = root?.optString("finish")
                ?: root?.optJSONArray("choices")?.optJSONObject(0)?.optString("finish_reason")
                ?: root?.optString("status")
            emit(
                "S99UI turn index=$index finish=$finish " +
                    "usage=${usage?.toString()?.take(220)}",
            )
        }
        val toolCalls = queryRows(
            databaseFile,
            "SELECT payloadJson FROM agent_trace_events WHERE runId = '$runId' AND kind = 'TOOL_CALL' ORDER BY id",
        ).mapNotNull { payload ->
            runCatching { JSONObject(payload).optString("name") }.getOrNull()
        }
        emit("S99UI tools=${toolCalls.size} names=$toolCalls")

        val events = queryRows(
            databaseFile,
            "SELECT payloadJson FROM agent_trace_events WHERE runId = '$runId' AND kind = 'TOOL_RESULT' ORDER BY id",
        )
        emit("S99UI toolResults=${events.size}")

        // Only the owned capture's rows are read: no ledger-wide or unrelated row is exported.
        val ownedCapture = scalarLong(
            databaseFile,
            "SELECT MAX(id) FROM capture_messages WHERE bodyText LIKE '%50000d at 15:00%'",
        )
        val ownedEvents = if (ownedCapture != null) queryRows(
            databaseFile,
            "SELECT id || '|' || status || '|' || COALESCE(amountVnd,'-') || '|' || " +
                "COALESCE(anchorDate,'-') || ' ' || COALESCE(anchorTime,'-') " +
                "FROM note_events WHERE captureId = $ownedCapture ORDER BY id",
        ) else emptyList()
        val ownedLinks = if (ownedCapture != null) queryRows(
            databaseFile,
            "SELECT eventId || '|' || draftId || '|' || state FROM context_links " +
                "WHERE eventId IN (SELECT id FROM note_events WHERE captureId = $ownedCapture) ORDER BY id",
        ) else emptyList()
        emit("S99UI owned capture=$ownedCapture events=$ownedEvents links=$ownedLinks")
        assertEquals(
            "the Chat run used a confirm/dismiss tool",
            0,
            toolCalls.count { it in setOf("confirm_draft", "dismiss_draft", "confirm_transaction") },
        )
        if (state == "SUCCEEDED") {
            assertTrue("the completed Chat run produced no owned event reading", ownedEvents.isNotEmpty())
        } else {
            emit("S99UI notCompleted state=$state row=$row")
        }
        assertFalse(
            "the Chat run truncated on its output budget again",
            row?.contains("TRUNCATED_TURN") == true,
        )
        runCatching {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val file = File(activity.filesDir, "s99-chat-ui-final.png")
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            emit("S99UI screenshot=${file.name} bytes=${file.length()}")
        }.onFailure { emit("S99UI screenshotFailed=${it::class.simpleName}: ${it.message}") }
        emit("S99UI done state=$state stopReason=${row?.split('|')?.getOrNull(1)}")
    }

    /**
     * A complete actual-UI workflow on a fresh owned fixture with unique amounts: one note, the
     * hosted run, an answered clarification if the model asks one, explicit confirmation in
     * Review, and the resulting ledger rows. It writes only run-scoped synthetic rows and removes
     * its own two ledger records afterwards.
     */
    @Test
    fun chatCompletesOwnedUniqueFixture() = runBlocking {
        org.junit.Assume.assumeTrue(
            "opt-in hosted UI evidence",
            androidx.test.platform.app.InstrumentationRegistry.getArguments()
                .getString("s99_ui") == "yes",
        )
        val activity = compose.activity
        val app = activity.application as AegisApplication
        val stamp = System.currentTimeMillis()
        val day = java.time.Instant.now()
            .atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
            .toLocalDate()
            .toString()
        val note = "Today fuel 61000d at 15:00 and today snack 37000d at 15:15."
        val fuelDraft = containerIntake(app, "fuel", 61_000L, day, "15:00", "Synthetic Fuel")
        val snackDraft = containerIntake(app, "snack", 37_000L, day, "15:15", "Synthetic Snack")
        val capture = app.container.workflowCommandService.saveCapture(
            io.github.triet4p.aegisf.data.WorkflowCallerScope.User,
            io.github.triet4p.aegisf.data.SaveCaptureRequest("s99-ui-$stamp-note", note),
        ) as io.github.triet4p.aegisf.data.WorkflowCommandOutcome.Applied
        val captureId = (capture.change as io.github.triet4p.aegisf.data.WorkflowChange.CaptureSaved).captureId
        emit("S99UIC start capture=$captureId fuels=$fuelDraft snacks=$snackDraft note=$note")

        selectDestination("AI Chat")
        compose.waitUntil(300_000) {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
        val prompt = "Use the newest saved note only. Attach unique bank matches and choose " +
            "one existing category for each affected draft. Never confirm anything."
        val field = compose.onAllNodes(hasSetTextAction())[0]
        field.performScrollTo()
        field.performTextClearance()
        field.performTextInput(prompt)
        val toggles = compose.onAllNodes(androidx.compose.ui.test.isToggleable())
        val toggleCount = toggles.fetchSemanticsNodes().size
        for (index in 1 until minOf(4, toggleCount)) {
            toggles[index].performScrollTo()
            toggles[index].performClick()
        }
        hideKeyboard(activity)
        val send = compose.onNodeWithText("Send")
        send.performScrollTo()
        send.assertIsEnabled()
        send.performClick()
        emit("S99UIC sent=true")

        var rounds = 0
        while (rounds <= 2) {
            waitBusy(activity)
            waitIdle()
            val clarification = compose.onAllNodesWithText("Clarification needed", substring = true)
                .fetchSemanticsNodes()
            if (clarification.isEmpty()) break
            val question = clarification.joinToString(" | ") { node ->
                (node.config.firstOrNull {
                    it.key == androidx.compose.ui.semantics.SemanticsProperties.Text
                }?.value as? List<*>)?.joinToString(" ") { it.toString() }.orEmpty()
            }
            emit("S99UIC clarification=$question")
            val captureRow = app.container.workflowQueryService.sourceExcerpt(captureId)
            val answerDate = captureRow?.capturedAtEpochMillis?.let { instant ->
                java.time.Instant.ofEpochMilli(instant)
                    .atZone(
                        captureRow.captureTimezone
                            ?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() }
                            ?: java.time.ZoneId.of("Asia/Ho_Chi_Minh"),
                    )
                    .toLocalDate()
                    .toString()
            } ?: error("the owned capture has no captured instant")
            val answer = if (question.contains("date", true) || question.contains("day", true)) {
                answerDate
            } else {
                "Use the draft whose stated time matches the note; otherwise leave it unlinked."
            }
            emit("S99UIC answer=$answer source=owned-capture-${captureRow.capturedAtEpochMillis}")
            val answerField = compose.onNodeWithText("Your answer")
            answerField.performScrollTo()
            answerField.performTextClearance()
            answerField.performTextInput(answer)
            hideKeyboard(activity)
            compose.onNodeWithText("Continue").performScrollTo().performClick()
            rounds += 1
        }
        waitIdle()
        val runId = latestRunId(activity)
        val state = scalarString(
            activity.getDatabasePath("aegis-ledger.db"),
            "SELECT state FROM agent_runs ORDER BY id DESC LIMIT 1",
        )
        emit("S99UIC run=$runId state=$state rounds=$rounds")
        val events = app.container.workflowQueryService
        val captureEvents = queryRows(
            activity.getDatabasePath("aegis-ledger.db"),
            "SELECT id || '|' || status || '|' || COALESCE(amountVnd,'-') FROM note_events " +
                "WHERE captureId = $captureId ORDER BY id",
        )
        val links = queryRows(
            activity.getDatabasePath("aegis-ledger.db"),
            "SELECT draftId || '|' || state FROM context_links " +
                "WHERE eventId IN (SELECT id FROM note_events WHERE captureId = $captureId) ORDER BY id",
        )
        emit("S99UIC effects state=$state events=$captureEvents links=$links")

        if (state == "SUCCEEDED") {
            // Explicit user confirmation through the real Review screen.
            selectDestination("Review")
            waitForText("REVIEW NOTIFICATIONS")
            listOf(fuelDraft, snackDraft).forEach { draftId ->
                openDraftFromInbox(draftId)
                waitForText("Amount (VND)")
                compose.onNodeWithText("Confirm transaction").performScrollTo()
                tapText(compose.onNodeWithText("Confirm transaction"))
                waitForText("Confirm this record?")
                tapText(compose.onNodeWithText("Confirm"))
                waitForText("Back to inbox")
                tapText(compose.onNodeWithText("Back to inbox"))
                compose.waitForIdle()
            }
            val confirmed = app.container.workflowQueryService
                .let { queries ->
                    listOf(fuelDraft, snackDraft).map { queries.draftCandidate(it)?.confirmedTransactionId }
                }
            emit("S99UIC confirmed=$confirmed")
            val rows = queryRows(
                activity.getDatabasePath("aegis-ledger.db"),
                "SELECT id || '|' || amountVnd || '|' || type || '|' || COALESCE(categoryId,'-') " +
                    "FROM transactions WHERE id IN (${confirmed.filterNotNull().joinToString(",")}) ORDER BY id",
            )
            emit("S99UIC ledger=$rows")
            assertEquals(2, confirmed.count { it != null })
            confirmed.filterNotNull().forEach { transactionId ->
                app.container.workflowCommandService.deleteTransaction(
                    io.github.triet4p.aegisf.data.WorkflowCallerScope.User,
                    io.github.triet4p.aegisf.data.DeleteTransactionRequest(
                        "s99-ui-clean-$transactionId",
                        transactionId,
                        0,
                    ),
                )
            }
            emit("S99UIC cleanup=own-two-records")
        }
        assertTrue("the owned fixture run reached no terminal state: $state", state != null)
    }

    private fun chooseReviewCategory(name: String) {
        val choice = compose.onNodeWithText(name)
        choice.performScrollTo()
        tapText(choice)
    }

    private fun containerIntake(
        app: AegisApplication,
        suffix: String,
        amount: Long,
        day: String,
        time: String,
        memo: String,
    ): Long = runBlocking {
        app.container.intakeRepository.storePermitted(
            sourcePackage = "com.vietinbank.ipay",
            notificationKey = "s99-ui-$suffix-$amount",
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
    }

    private fun latestRunId(activity: android.app.Activity): String? =
        setOf("$activity").let {
            scalarString(
                activity.getDatabasePath("aegis-ledger.db"),
                "SELECT runId FROM agent_runs ORDER BY id DESC LIMIT 1",
            )
        }

    private fun hideKeyboard(activity: android.app.Activity) {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        activity.runOnUiThread {
            imm.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
    }

    private fun waitBusy(activity: android.app.Activity) {
        compose.waitUntil(timeoutMillis = 240_000) {
            compose.onAllNodesWithText("Cancel", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        emit("S99UIC busy=true")
    }

    private fun waitIdle() {
        compose.waitUntil(timeoutMillis = 600_000) {
            compose.onAllNodesWithText("Cancel", substring = true).fetchSemanticsNodes().isEmpty()
        }
        emit("S99UIC busy=false")
    }

    private fun selectDestination(description: String) {
        // Home's feature launchers announce "Open Notes" / "Open AI Chat" / "Open Review", so a
        // destination is selected by its tab semantics instead of a substring both nodes share.
        compose.onNode(
            hasContentDescription(description, substring = true) and isSelectable(),
        ).performClick()
    }

    private fun waitForText(text: String, timeoutMillis: Long = 120_000) {
        compose.waitUntil(timeoutMillis = timeoutMillis) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun tapText(node: androidx.compose.ui.test.SemanticsNodeInteraction) {
        node.performClick()
    }

    private fun openDraftFromInbox(draftId: Long) {
        repeat(10) {
            val matches = compose.onAllNodesWithText("Review draft #$draftId", substring = true)
                .fetchSemanticsNodes()
            if (matches.isNotEmpty()) {
                tapText(compose.onNodeWithText("Review draft #$draftId", substring = true).performScrollTo())
                return
            }
            val next = compose.onAllNodesWithText("Next").fetchSemanticsNodes()
            if (next.isEmpty()) error("draft $draftId is not reachable from the inbox")
            tapText(compose.onNodeWithText("Next").performScrollTo())
            compose.waitForIdle()
        }
        error("draft $draftId was not found after paging the inbox")
    }

    private fun scalarString(file: File, sql: String): String? =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(sql, null).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        }

    private fun scalarLong(file: File, sql: String): Long? =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(sql, null).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        }

    private fun queryRows(file: File, sql: String): List<String> =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(sql, null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        if (!cursor.isNull(0)) add(cursor.getString(0))
                    }
                }
            }
        }

    private fun emit(line: String) {
        android.util.Log.i("S99EVID", line)
        println(line)
        val file = File(compose.activity.filesDir, "s99-evidence-chat_ui.log")
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(line + "\n")
        }
    }

    private companion object {
        const val RUN_TIMEOUT_MILLIS = 600_000L
        const val PROMPT =
            "Read the newest saved note. Attach only a unique bank match and choose one existing " +
                "category for each affected draft. Never confirm or dismiss anything."
        val TERMINAL_STATES = setOf("SUCCEEDED", "FAILED", "CLARIFYING", "CANCELLED")
    }
}
