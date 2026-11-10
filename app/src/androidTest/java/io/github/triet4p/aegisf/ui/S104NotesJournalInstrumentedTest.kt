package io.github.triet4p.aegisf.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.triet4p.aegisf.AegisApplication
import io.github.triet4p.aegisf.MainActivity
import io.github.triet4p.aegisf.agent.AgentRunHistory
import io.github.triet4p.aegisf.data.AegisLanguage
import io.github.triet4p.aegisf.data.AegisThemeMode
import io.github.triet4p.aegisf.data.CaptureKind
import io.github.triet4p.aegisf.data.CaptureSourceKind
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.NoteEventInterpretation
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.ProposeLinkRequest
import io.github.triet4p.aegisf.data.SaveCaptureRequest
import io.github.triet4p.aegisf.data.UpdateEventRequest
import io.github.triet4p.aegisf.data.UserCaptureKind
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowChange
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.ui.navigation.AegisAppShell
import io.github.triet4p.aegisf.ui.theme.AegisTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * S10.4 at the largest supported app-scoped text scale on the phone.
 *
 * The Notes screens are composed against an in-memory Room database this test owns, so no note,
 * event or link of the owner's is touched. The 1.5× font scale is this process's only; the
 * device's font-size setting is not changed. A file-backed test database (deleted afterwards)
 * proves a saved and edited note survives closing and reopening the store, and the failure test
 * drops only the capture tables of its own throwaway database.
 */
@RunWith(AndroidJUnit4::class)
class S104NotesJournalInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var database: LedgerDatabase
    private lateinit var queryService: WorkflowQueryService
    private lateinit var commandService: WorkflowCommandService

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        queryService = WorkflowQueryService(database)
        commandService = WorkflowCommandService(database = database)
    }

    @After
    fun tearDown() {
        compose.activity.setContent { }
        compose.waitForIdle()
        database.close()
    }

    @Test
    fun noteSaveBrowseEditDetailsAndChatHandoffHoldAtLargeText() {
        val noteText = "S104 lunch 65k, taxi 120k, coffee 45k"
        val editedText = "S104 lunch 65k, taxi 120k, coffee 45k, water 10k"
        showShell()

        compose.onNodeWithContentDescription("Notes").performClick()
        compose.waitForIdle()
        assertTrue(exists("Saved notes"))

        compose.onNode(hasSetTextAction()).performTextInput(noteText)
        compose.onNodeWithText("Save note").performClick()
        compose.waitForIdle()
        screenshot("309-after-save-click")
        val savedNow = runBlocking {
            queryService.sourceExcerpts(
                io.github.triet4p.aegisf.data.SourceQuery(
                    kind = CaptureKind.USER_NOTE,
                    sourceKind = CaptureSourceKind.USER_QUICK_CAPTURE,
                    state = io.github.triet4p.aegisf.data.SourceState.ACTIVE,
                    bounds = io.github.triet4p.aegisf.data.QueryPageRequest(limit = 10, offset = 0),
                ),
            ).items.size
        }
        assertTrue("saved rows after click: $savedNow", waitForText("Saved on this device."))
        screenshot("310-notes-root-saved")
        compose.onNodeWithText("Saved notes").performClick()
        assertTrue(waitForText("Saved notes"))
        assertTrue(waitForText(noteText, substring = true))
        screenshot("310b-saved-notes-browse")
        compose.onNodeWithContentDescription(noteText, substring = true).performClick()
        assertTrue(waitForText("Saved note"))
        assertTrue(exists(noteText, substring = true))
        screenshot("311-note-detail")

        compose.onNodeWithText("Discuss with AI").performClick()
        assertTrue(waitForText("Attached note"))
        assertTrue(exists(noteText, substring = true))
        assertEquals(0, rowCount("agent_runs"))
        assertFalse(exists("Preparing a scoped, privacy-masked run…"))
        screenshot("312-chat-attached-note")

        compose.onNodeWithText("Remove attached note").performClick()
        compose.waitForIdle()
        assertFalse(exists("Attached note"))
        assertEquals(0, rowCount("agent_runs"))
        screenshot("313-chat-context-removed")

        compose.onNodeWithContentDescription("Notes").performClick()
        assertTrue(waitForText("Saved note"))
        compose.onNodeWithText("Edit note").performClick()
        assertTrue(waitForText("Edit saved note"))
        compose.onNode(hasSetTextAction()).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(editedText)
        hideKeyboard()
        compose.onNodeWithText("Save changes").performClick()
        assertTrue(waitForText("Changes saved on this device."))
        assertTrue(exists(editedText, substring = true))
        screenshot("314-note-edited")

        addDetail(excerpt = "taxi 120k", reading = "Taxi ride", amount = "120000")
        assertTrue(waitForText("Detail saved."))
        addDetail(excerpt = "coffee 45k", reading = "Coffee", amount = "45000")
        assertTrue(waitForText("Detail saved."))
        scrollToText("120,000 ₫")
        assertTrue(exists("120,000 ₫"))
        scrollToText("45,000 ₫")
        assertTrue(exists("45,000 ₫"))
        scrollToText("Taxi ride")
        assertTrue(exists("Taxi ride"))
        assertTrue(exists("Coffee"))
        screenshot("315-note-details")

        compose.onNodeWithContentDescription("Taxi ride", substring = true).performClick()
        assertTrue(waitForText("Edit detail"))
        assertEquals("Taxi ride", fieldText(0))
        screenshot("316-event-detail")

        scrollToText("Record a transaction")
        compose.onNodeWithText("Record a transaction").performClick()
        assertTrue(waitForText("New transaction"))
        assertTrue(exists("Taxi ride", substring = true))
        screenshot("317-manual-handoff")
        leaveScreen()
        assertTrue(waitForText("Edit detail"))
        pressBack()
        assertTrue(waitForText("Saved note"))

        // Return to the saved-note list and delete the note through its guarded dialog.
        pressBack()
        assertTrue(waitForText("Saved notes"))
        compose.onNodeWithContentDescription(editedText, substring = true).performClick()
        assertTrue(waitForText("Saved note"))
        compose.onNodeWithText("Delete").performClick()
        assertTrue(waitForText("Delete this note?"))
        compose.onNodeWithText("Delete note").performClick()
        assertTrue(waitForText("Note deleted from this device."))
        assertTrue(waitForText("No saved notes yet"))
        screenshot("318-note-deleted")

        // The deleted note is no longer an active list row; the source policy clears the
        // derived readings and removes draft links while keeping the event rows' identity.
        assertEquals(0, activeUserNotes())
        assertEquals(2, clearedEventCount())
        assertEquals(0, rowCount("note_events WHERE normalizedText IS NOT NULL"))
        assertEquals(0, rowCount("context_links"))
    }

    @Test
    fun emptyHistoryAndSaveFailureAreTruthful() {
        showShell()
        compose.onNodeWithContentDescription("Notes").performClick()
        assertTrue(waitForText("Saved notes"))
        compose.onNodeWithText("Saved notes").performClick()
        assertTrue(waitForText("No saved notes yet"))
        screenshot("320-notes-empty")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val failing = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            failing.openHelper.writableDatabase.execSQL("DROP TABLE IF EXISTS context_links")
            failing.openHelper.writableDatabase.execSQL("DROP TABLE IF EXISTS note_events")
            failing.openHelper.writableDatabase.execSQL("DROP TABLE IF EXISTS capture_messages")
            val failingQueries = WorkflowQueryService(failing)
            val failingCommands = WorkflowCommandService(database = failing)
            compose.activity.setContent {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density = base.density, fontScale = 1.5f),
                ) {
                    AegisTheme(
                        themeMode = AegisThemeMode.LIGHT,
                        language = AegisLanguage.ENGLISH,
                    ) {
                        SavedNotesScreen(
                            queryService = failingQueries,
                            notice = null,
                            onDismissNotice = {},
                            onOpenNote = {},
                            onNavigateUp = {},
                        )
                    }
                }
            }
            assertTrue(waitForText("Saved notes couldn't be read. Nothing was changed."))
            screenshot("321-notes-read-error")

            compose.activity.setContent {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density = base.density, fontScale = 1.5f),
                ) {
                    AegisTheme(
                        themeMode = AegisThemeMode.LIGHT,
                        language = AegisLanguage.ENGLISH,
                    ) {
                        QuickCaptureScreen(
                            queryService = failingQueries,
                            commandService = failingCommands,
                            notice = null,
                            onDismissNotice = {},
                            onOpenSavedNotes = {},
                        )
                    }
                }
            }
            assertTrue(waitForText("Save note"))
            compose.onNode(hasSetTextAction()).performTextInput("S104 cannot save this")
            hideKeyboard()
            compose.onNodeWithText("Save note").performClick()
            assertTrue(
                waitForText("Couldn't save this note. Your text is still here; try again."),
            )
            assertEquals("S104 cannot save this", fieldText(0))
            screenshot("322-notes-save-error")
        } finally {
            compose.activity.setContent { }
            compose.waitForIdle()
            failing.close()
        }
    }

    @Test
    fun editorSavePersistsTheStoredText() {
        val captureId = saveNote("S104 editor save probe")
        showNoteEditor(captureId)
        assertTrue(waitForField(0, "S104 editor save probe"))
        compose.onNode(hasSetTextAction()).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("S104 editor save probe v2")
        compose.onNodeWithText("Save changes").performClick()
        compose.waitForIdle()
        screenshot("330-editor-save-probe")
        val stored = runBlocking { queryService.sourceExcerpt(captureId) }
        assertEquals("S104 editor save probe v2", stored?.bodyText)
    }

    @Test
    fun staleNoteEditKeepsTypedTextAndReloadsOnlyOnRequest() {
        var exited = false
        val captureId = saveNote("S104 stale note original")
        showNoteEditor(captureId, onNavigateUp = { exited = true })
        assertTrue(waitForText("Edit saved note"))
        compose.onNode(hasSetTextAction()).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("S104 my local edit")
        assertEquals("after edit: " + fieldText(0), "S104 my local edit", fieldText(0))

        val revised = runBlocking {
            commandService.reviseCapture(
                WorkflowCallerScope.User,
                io.github.triet4p.aegisf.data.ReviseCaptureRequest(
                    idempotencyKey = UUID.randomUUID().toString(),
                    captureId = captureId,
                    expectedRevision = 0,
                    bodyText = "S104 changed elsewhere",
                ),
            )
        }
        assertTrue(revised is WorkflowCommandOutcome.Applied)

        hideKeyboard()
        assertEquals("after hide: " + fieldText(0), "S104 my local edit", fieldText(0))
        compose.onNodeWithText("Save changes").performClick()
        assertTrue(waitForText("This note changed after you opened it. Your edits are still here."))
        compose.waitForIdle()
        screenshot("323-stale-note-edit")
        assertEquals("field was: " + fieldText(0), "S104 my local edit", fieldText(0))

        hideKeyboard()
        scrollToText("Reload saved note")
        compose.onNodeWithText("Reload saved note").performClick()
        compose.waitForIdle()
        screenshot("323b-after-reload-click")
        assertTrue(waitForText("Reload the saved note?"))
        compose.onNodeWithText("Reload").performClick()
        assertTrue(waitForField(0, "S104 changed elsewhere"))
        assertEquals("S104 changed elsewhere", fieldText(0))
        screenshot("324-stale-note-reloaded")

        compose.onNode(hasSetTextAction()).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("S104 discard me")
        hideKeyboard()
        pressBack()
        assertTrue(waitForText("Discard changes?"))
        compose.onNodeWithText("Keep editing").performClick()
        assertEquals("S104 discard me", fieldText(0))
        hideKeyboard()
        pressBack()
        assertTrue(waitForText("Discard changes?"))
        compose.onNodeWithText("Discard").performClick()
        compose.waitForIdle()
        assertTrue(exited)
        assertFalse(exists("Discard changes?"))
    }

    @Test
    fun staleDetailConflictKeepsTypedTextAndStoredStaleDetailIsNotEdited() {
        val noteText = "S104 taxi 120k and coffee 45k"
        val captureId = saveNote(noteText)
        val eventId = saveDetail(captureId, noteText, "taxi 120k", "Taxi ride", 120_000L)
        showDetail(captureId, eventId)
        assertTrue(waitForText("Edit detail"))
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performClick()
        fields[0].performTextReplacement("Taxi ride edited here")

        val event = runBlocking { queryService.noteEvent(eventId) }!!
        val revised = runBlocking {
            commandService.updateEventInterpretation(
                WorkflowCallerScope.User,
                UpdateEventRequest(
                    idempotencyKey = UUID.randomUUID().toString(),
                    captureId = captureId,
                    expectedCaptureRevision = event.sourceRevision,
                    expectedEventRevision = event.revision,
                    interpretation = NoteEventInterpretation(
                        spanStart = event.spanStart,
                        spanEnd = event.spanEnd,
                        normalizedText = "Taxi ride changed elsewhere",
                        amountVnd = event.amountVnd,
                        merchant = event.merchant,
                        timeAnchor = null,
                        unresolvedReason = null,
                    ),
                    extractionVersion = event.extractionVersion,
                ),
            )
        }
        assertTrue(
            revised is WorkflowCommandOutcome.Applied ||
                revised is WorkflowCommandOutcome.NoChange,
        )

        hideKeyboard()
        assertEquals("after hide: " + fieldText(0), "Taxi ride edited here", fieldText(0))
        compose.onNodeWithText("Save detail").performClick()
        compose.waitForIdle()
        screenshot("325a-after-detail-save-click")
        assertTrue(
            waitForText("This note or detail changed after you opened it. Your edits are still here."),
        )
        compose.waitForIdle()
        screenshot("325-stale-detail-conflict")
        assertEquals("field was: " + fieldText(0), "Taxi ride edited here", fieldText(0))

        hideKeyboard()
        scrollToText("Reload saved detail")
        compose.onNodeWithText("Reload saved detail").performClick()
        assertTrue(waitForText("Reload the saved detail?"))
        compose.onNodeWithText("Reload").performClick()
        assertTrue(waitForField(0, "Taxi ride changed elsewhere"))
        assertEquals("Taxi ride changed elsewhere", fieldText(0))

        // A note revised after the detail stops matching it: the stored detail is not editable.
        val latest = runBlocking { queryService.noteEvent(eventId) }!!
        runBlocking {
            commandService.reviseCapture(
                WorkflowCallerScope.User,
                io.github.triet4p.aegisf.data.ReviseCaptureRequest(
                    idempotencyKey = UUID.randomUUID().toString(),
                    captureId = captureId,
                    expectedRevision = latest.sourceRevision,
                    bodyText = "S104 a different note",
                ),
            )
        }
        compose.activity.setContent { }
        compose.waitForIdle()
        showDetail(captureId, eventId)
        assertTrue(waitForText("Out of date"))
        assertTrue(
            exists(
                "This detail was read from an older version of the note. Add a new detail from " +
                    "the current text if it still applies.",
            ),
        )
        assertTrue(exists("Add detail from current note"))
        assertFalse(exists("Save detail"))
        screenshot("326-stale-detail-stored")
    }

    @Test
    fun proposedLinkIsResolvableFromTheFocusedChooser() {
        val noteText = "S104 taxi 120k proposal"
        val captureId = saveNote(noteText)
        val eventId = saveDetail(captureId, noteText, "taxi 120k", "Taxi", 120_000L)
        val draftId = runBlocking {
            database.pendingBankDraftDao().store(
                PendingBankDraftEntity(
                    deliveryId = "s104-proposal-draft",
                    sourcePackage = "synthetic.fixture",
                    notificationKey = null,
                    postedAtEpochMillis = System.currentTimeMillis(),
                    bank = "Synthetic Bank",
                    parseStatus = IntakeParseStatus.PARSED,
                    amountState = IntakeFieldState.PRESENT,
                    amountVnd = 120_000L,
                    amountUnresolvedReason = null,
                    direction = IntakeDirection.DEBIT,
                    momentState = IntakeFieldState.PRESENT,
                    statedDate = "2026-11-09",
                    statedTime = "17:00",
                    momentUnresolvedReason = null,
                    memoState = IntakeFieldState.PRESENT,
                    memo = "S104 synthetic bank notification",
                    memoUnresolvedReason = null,
                    createdAtEpochMillis = System.currentTimeMillis(),
                ),
            )
        }
        val proposed = runBlocking {
            commandService.proposeLink(
                WorkflowCallerScope.User,
                ProposeLinkRequest(
                    idempotencyKey = UUID.randomUUID().toString(),
                    eventId = eventId,
                    draftId = draftId,
                ),
            )
        }
        assertTrue(
            proposed is WorkflowCommandOutcome.Applied ||
                proposed is WorkflowCommandOutcome.NoChange,
        )

        showShell()
        compose.onNodeWithContentDescription("Notes").performClick()
        compose.onNodeWithText("Saved notes").performClick()
        assertTrue(waitForText(noteText, substring = true))
        compose.onNodeWithContentDescription(noteText, substring = true).performClick()
        assertTrue(waitForText("Saved note"))
        compose.onNodeWithContentDescription("Taxi", substring = true).performClick()
        assertTrue(waitForText("Edit detail"))
        scrollToText("Choose a pending bank notification")
        compose.onNodeWithText("Choose a pending bank notification")
            .assertIsEnabled()
            .performClick()
        assertTrue(waitForText("Pending bank notifications"))
        assertTrue(waitForText("Suggested for this detail; choose Attach or Reject."))
        screenshot("337-proposal-chooser-1.5x")

        scrollToText("Attach")
        compose.onNodeWithText("Attach").performClick()
        scrollToText("Attached. The bank notification and the ledger are unchanged.")
        assertTrue(exists("Attached. The bank notification and the ledger are unchanged."))
        scrollToText("Already attached to this detail.")
        assertTrue(exists("Already attached to this detail."))
        screenshot("337b-proposal-accepted-chooser-1.5x")

        pressBack()
        assertTrue(waitForText("Edit detail"))
        scrollToText("Linked to a bank notification")
        assertTrue(exists("Linked to a bank notification"))
        screenshot("339-event-attached-1.5x")
        scrollToText("Unlink")
        compose.onNodeWithText("Unlink").performClick()
        scrollToText("Bank notification unlinked. The ledger is unchanged.")
        assertTrue(exists("Bank notification unlinked. The ledger is unchanged."))

        scrollToText("Choose a pending bank notification")
        compose.onNodeWithText("Choose a pending bank notification")
            .assertIsEnabled()
            .performClick()
        assertTrue(waitForText("Pending bank notifications"))
        scrollToText("Reject")
        compose.onNodeWithText("Reject").performClick()
        scrollToText("Refused. That decision is kept.")
        assertTrue(exists("Refused. That decision is kept."))
        scrollToText("Refused before. This pair cannot be suggested again.")
        assertTrue(exists("Refused before. This pair cannot be suggested again."))
        screenshot("338-proposal-refused-1.5x")

        pressBack()
        assertTrue(waitForText("Edit detail"))
        scrollToText("Match refused (decision kept)")
        assertTrue(exists("Match refused (decision kept)"))
    }

    @Test
    fun notesAndSavedNoteHoldInVietnameseAtLargeText() {
        val noteText = "S104 phở 65k taxi 120k"
        val captureId = saveNote(noteText)
        saveDetail(captureId, noteText, "taxi 120k", "Taxi", 120_000L)
        showShell(language = AegisLanguage.VIETNAMESE)
        compose.onNodeWithContentDescription("Ghi chép").performClick()
        assertTrue(waitForText("Ghi chú đã lưu"))
        screenshot("331-notes-root-vi-1.5x")
        compose.onNodeWithText("Ghi chú đã lưu").performClick()
        assertTrue(waitForText(noteText, substring = true))
        screenshot("332-saved-notes-vi-1.5x")
        compose.onNodeWithContentDescription(noteText, substring = true).performClick()
        assertTrue(waitForText("Ghi chú đã lưu"))
        scrollToText("Chi tiết từ ghi chú")
        assertTrue(exists("Chi tiết từ ghi chú"))
        screenshot("333-note-detail-vi-1.5x")
    }

    @Test
    fun notesAndSavedNoteHoldInDarkThemeAtLargeText() {
        val noteText = "S104 dark note 65k"
        val captureId = saveNote(noteText)
        saveDetail(captureId, noteText, "65k", "Lunch", 65_000L)
        showShell(themeMode = AegisThemeMode.DARK)
        compose.onNodeWithContentDescription("Notes").performClick()
        assertTrue(waitForText("Saved notes"))
        screenshot("334-notes-root-dark-1.5x")
        compose.onNodeWithText("Saved notes").performClick()
        assertTrue(waitForText(noteText, substring = true))
        screenshot("335-saved-notes-dark-1.5x")
        compose.onNodeWithContentDescription(noteText, substring = true).performClick()
        assertTrue(waitForText("Saved note"))
        screenshot("336-note-detail-dark-1.5x")
    }

    @Test
    fun savedAndEditedNoteSurvivesClosingAndReopeningTheStore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "s104-notes-evidence.db"
        context.deleteDatabase(name)

        var store = openFileDatabase(name)
        var queries = WorkflowQueryService(store)
        var commands = WorkflowCommandService(database = store)
        showShell(store, queries, commands)
        compose.onNodeWithContentDescription("Notes").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("S104 restart probe")
        compose.onNodeWithText("Save note").performClick()
        assertTrue(waitForText("Saved on this device."))
        compose.activity.setContent { }
        compose.waitForIdle()
        store.close()

        store = openFileDatabase(name)
        queries = WorkflowQueryService(store)
        commands = WorkflowCommandService(database = store)
        showShell(store, queries, commands)
        compose.onNodeWithContentDescription("Notes").performClick()
        compose.onNodeWithText("Saved notes").performClick()
        assertTrue(waitForText("S104 restart probe", substring = true))
        compose.onNodeWithText("S104 restart probe", substring = true).performClick()
        assertTrue(waitForText("Saved note"))
        compose.onNodeWithText("Edit note").performClick()
        assertTrue(waitForText("Edit saved note"))
        compose.onNode(hasSetTextAction()).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("S104 restart probe edited")
        assertEquals("S104 restart probe edited", fieldText(0))
        hideKeyboard()
        compose.onNodeWithText("Save changes").performClick()
        screenshot("327-reopen-edit-saved")
        assertTrue(waitForText("Changes saved on this device."))
        compose.activity.setContent { }
        compose.waitForIdle()
        store.close()

        store = openFileDatabase(name)
        queries = WorkflowQueryService(store)
        commands = WorkflowCommandService(database = store)
        showShell(store, queries, commands)
        compose.onNodeWithContentDescription("Notes").performClick()
        compose.onNodeWithText("Saved notes").performClick()
        assertTrue(waitForText("S104 restart probe edited", substring = true))
        compose.onNodeWithText("S104 restart probe edited", substring = true).performClick()
        assertTrue(waitForText("Saved note"))
        assertTrue(exists("S104 restart probe edited", substring = true))
        screenshot("328-reopened-after-store-close")

        compose.activity.setContent { }
        compose.waitForIdle()
        store.close()
        context.deleteDatabase(name)
    }

    private fun showShell(
        store: LedgerDatabase = database,
        queries: WorkflowQueryService = queryService,
        commands: WorkflowCommandService = commandService,
        language: AegisLanguage = AegisLanguage.ENGLISH,
        themeMode: AegisThemeMode = AegisThemeMode.LIGHT,
    ) {
        val app = compose.activity.application as AegisApplication
        compose.activity.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density, fontScale = 1.5f),
            ) {
                AegisTheme(
                    themeMode = themeMode,
                    language = language,
                ) {
                    AegisAppShell(
                        queryService = queries,
                        commandService = commands,
                        notificationAccessStore = app.container.notificationAccessStore,
                        hostedProviderSettingsStore = app.container.hostedProviderSettingsStore,
                        createHostedWorkflowHost = { _, _ ->
                            error("Hosted processing is not part of the Notes evidence.")
                        },
                        agentRunHistory = AgentRunHistory(store),
                        language = language,
                        onLanguageChange = {},
                        themeMode = themeMode,
                        onThemeModeChange = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun showNoteEditor(captureId: Long, onNavigateUp: () -> Unit = {}) {
        compose.activity.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density, fontScale = 1.5f),
            ) {
                AegisTheme(
                    themeMode = AegisThemeMode.LIGHT,
                    language = AegisLanguage.ENGLISH,
                ) {
                    NoteEditorScreen(
                        queryService = queryService,
                        commandService = commandService,
                        captureId = captureId,
                        onSaved = {},
                        onNavigateUp = onNavigateUp,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun showDetail(captureId: Long, eventId: Long) {
        compose.activity.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density, fontScale = 1.5f),
            ) {
                AegisTheme(
                    themeMode = AegisThemeMode.LIGHT,
                    language = AegisLanguage.ENGLISH,
                ) {
                    NoteEventScreen(
                        queryService = queryService,
                        commandService = commandService,
                        captureId = captureId,
                        eventId = eventId,
                        onSaved = {},
                        onChooseDraft = {},
                        onRecordManually = {},
                        onAddFromCurrent = {},
                        onNavigateUp = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun addDetail(excerpt: String, reading: String, amount: String) {
        compose.onNodeWithText("Add detail").performClick()
        assertTrue(waitForText("Exact text from the note"))
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput(excerpt)
        fields[1].performClick()
        fields[1].performTextReplacement(reading)
        fields[2].performTextInput(amount)
        hideKeyboard()
        compose.onNodeWithText("Save detail").performClick()
    }

    private fun saveNote(text: String): Long = runBlocking {
        val outcome = commandService.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest(
                idempotencyKey = UUID.randomUUID().toString(),
                bodyText = text,
                captureKind = UserCaptureKind.QUICK_CAPTURE,
            ),
        )
        (outcome as WorkflowCommandOutcome.Applied).let {
            (it.change as WorkflowChange.CaptureSaved).captureId
        }
    }

    private fun saveDetail(
        captureId: Long,
        noteText: String,
        excerpt: String,
        reading: String,
        amount: Long,
    ): Long = runBlocking {
        val start = noteText.indexOf(excerpt)
        require(start >= 0)
        val outcome = commandService.updateEventInterpretation(
            WorkflowCallerScope.User,
            UpdateEventRequest(
                idempotencyKey = UUID.randomUUID().toString(),
                captureId = captureId,
                expectedCaptureRevision = 0,
                expectedEventRevision = null,
                interpretation = NoteEventInterpretation(
                    spanStart = start,
                    spanEnd = start + excerpt.length,
                    normalizedText = reading,
                    amountVnd = amount,
                    merchant = null,
                    timeAnchor = null,
                    unresolvedReason = null,
                ),
                extractionVersion = MANUAL_EXTRACTION_VERSION,
            ),
        )
        (outcome as WorkflowCommandOutcome.Applied).let {
            (it.change as WorkflowChange.EventStored).eventId
        }
    }

    private fun openFileDatabase(name: String): LedgerDatabase =
        Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            LedgerDatabase::class.java,
            name,
        ).allowMainThreadQueries().build()

    private fun activeUserNotes(): Int {
        val captureDao = database.captureMessageDao()
        return runBlocking {
            captureDao.findPage(
                captureKind = CaptureKind.USER_NOTE.name,
                sourceKind = CaptureSourceKind.USER_QUICK_CAPTURE.name,
                tombstone = false,
                fromEpochMillis = null,
                throughExclusiveEpochMillis = null,
                limit = 50,
                offset = 0,
            ).size
        }
    }

    private fun rowCount(table: String): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun clearedEventCount(): Int =
        database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM note_events WHERE normalizedText IS NULL")
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }

    private fun exists(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodesWithText(text, substring = substring)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun fieldText(index: Int): String =
        compose.onAllNodes(hasSetTextAction())[index]
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.EditableText)
            ?.text
            .orEmpty()

    private fun waitForText(text: String, substring: Boolean = false): Boolean {
        compose.waitUntil(5_000) { exists(text, substring) }
        return exists(text, substring)
    }

    private fun waitForField(index: Int, text: String): Boolean {
        compose.waitUntil(5_000) {
            runCatching { fieldText(index) == text }.getOrDefault(false)
        }
        return runCatching { fieldText(index) == text }.getOrDefault(false)
    }

    /** Scrolls the screen's scrollable content until [text] is composed and visible. */
    private fun scrollToText(text: String) {
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text))
        compose.waitForIdle()
    }

    /** Closes the software keyboard before a control that may sit under it. */
    private fun hideKeyboard() {
        compose.activityRule.scenario.onActivity { activity ->
            val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
    }

    private fun pressBack() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** Leaves a form, explicitly discarding when its unsaved-changes guard appears. */
    private fun leaveScreen() {
        pressBack()
        if (exists("Discard changes?")) {
            compose.onNodeWithText("Discard").performClick()
            compose.waitForIdle()
        }
    }

    private fun screenshot(name: String) {
        writeScreenshot(name, compose.onRoot().captureToImage().asAndroidBitmap())
    }

    private fun writeScreenshot(name: String, bitmap: Bitmap) {
        val directory = InstrumentationRegistry.getInstrumentation()
            .targetContext.getExternalFilesDir("s104")
            ?.apply { mkdirs() }
            ?: return
        FileOutputStream(File(directory, "$name.png")).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
