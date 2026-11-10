package io.github.triet4p.aegisf.ui

import android.content.Context
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.triet4p.aegisf.MainActivity
import io.github.triet4p.aegisf.data.AegisLanguage
import io.github.triet4p.aegisf.data.AegisThemeMode
import io.github.triet4p.aegisf.data.ArchiveCategoryRequest
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.CorrectTransactionRequest
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.LedgerDateRange
import io.github.triet4p.aegisf.data.LedgerRecordQuery
import io.github.triet4p.aegisf.data.ManualTransactionSource
import io.github.triet4p.aegisf.data.NoteEventInterpretation
import io.github.triet4p.aegisf.data.NoteEventTimeAnchor
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.SaveCaptureRequest
import io.github.triet4p.aegisf.data.SaveManualTransactionRequest
import io.github.triet4p.aegisf.data.TransactionEntity
import io.github.triet4p.aegisf.data.TransactionType
import io.github.triet4p.aegisf.data.UpdateEventRequest
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowChange
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.ui.theme.AegisTheme
import java.io.File
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S10.7 actual Compose manual-record evidence on isolated synthetic fixtures.
 *
 * Every test owns an in-memory Room database and renders the production `TransactionEditorScreen`
 * through the real theme. The tests assert the stored rows and the exact confirmed aggregate as
 * well as the visible words, so the form is never the only witness. The opt-in package guard
 * keeps this off the owner's installation.
 */
@RunWith(AndroidJUnit4::class)
class S107ManualRecordInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var database: LedgerDatabase
    private lateinit var commands: WorkflowCommandService
    private lateinit var queries: WorkflowQueryService
    private var databaseOpen = false
    private var navigatedUp = false
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        if (databaseOpen) {
            database.close()
            databaseOpen = false
        }
    }

    @Test
    fun createWritesTheExactAmountOnceAndTheMomentSheetSetsTheStoredInstant() {
        prepare()
        assertEquals("io.github.triet4p.aegisf.s812evidence", context.packageName)
        val food = insertCategory("Food")
        showEditor(transactionId = null)
        awaitEditor()
        screenshot("s107-01-create-en")

        typeAmount("150000")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("One hundred fifty thousand dong", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // The moment is a focused sheet; applying a moment changes the form, not the ledger.
        compose.onNodeWithTag("manual-moment").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("manual-date").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("manual-date").performTextClearance()
        compose.onNodeWithTag("manual-date").performTextInput("2026-11-05")
        compose.onNodeWithTag("manual-time").performTextClearance()
        compose.onNodeWithTag("manual-time").performTextInput("15:00")
        screenshot("s107-02-moment-sheet-en")
        compose.onNodeWithTag("manual-moment-apply").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("05/11/2026 · 15:00", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Food").performScrollTo().performClick()
        typeMemo("Synthetic lunch")
        assertTrue(editableText("manual-memo").contains("Synthetic lunch"))
        screenshot("s107-03-create-filled-en")
        compose.onNodeWithTag("manual-save").performClick()
        awaitText("Transaction saved")
        screenshot("s107-04-saved-en")

        assertEquals(150_000L to 0L, totals())
        val rows = records()
        assertEquals(1, rows.size)
        assertEquals(150_000L, rows.single().amountVnd)
        assertEquals(TransactionType.EXPENSE, rows.single().type)
        assertEquals(food, rows.single().categoryId)
        assertEquals("Synthetic lunch", rows.single().description)
        assertEquals(
            ManualDateTime.toEpochMillis("2026-11-05", "15:00", TimeZone.getDefault()),
            rows.single().occurredAtEpochMillis,
        )
    }

    @Test
    fun correctionChangesTheExactTotalOnceAndDiscardWritesNothing() {
        prepare()
        val food = insertCategory("Food")
        val recordId = runBlocking { seedRecord(100_000, food, "Synthetic seed") }
        assertEquals(100_000L to 0L, totals())

        navigatedUp = false
        showEditor(transactionId = recordId)
        awaitEditor()
        awaitText("Edit transaction")
        assertEquals("100000", editableText("manual-amount"))
        screenshot("s107-05-correction-en")

        typeAmount("125000")
        compose.onNodeWithTag("manual-save").performClick()
        awaitTrue { navigatedUp }
        assertEquals(125_000L to 0L, totals())
        assertEquals(1, records().size)
        assertEquals(125_000L, records().single().amountVnd)

        // A second visit starts from the stored value; leaving with edits discarded changes none.
        navigatedUp = false
        showEditor(transactionId = recordId)
        awaitEditor()
        awaitTrue { editableText("manual-amount") == "125000" }
        typeAmount("130000")
        compose.onNodeWithContentDescription("Close").performClick()
        awaitText("Discard changes?")
        compose.onNodeWithText("Discard").performClick()
        awaitTrue { navigatedUp }
        assertEquals(125_000L to 0L, totals())
        assertEquals(125_000L, records().single().amountVnd)
    }

    @Test
    fun invalidAmountAndAnArchivedCategoryAreRefusedWithoutWriting() {
        prepare()
        val food = insertCategory("Food")
        showEditor(transactionId = null)
        awaitEditor()

        compose.onNodeWithTag("manual-save").performClick()
        awaitText("Enter an amount before saving.")
        typeAmount("12.5")
        compose.onNodeWithTag("manual-save").performClick()
        awaitText("Use whole digits only", substring = true)
        screenshot("s107-06-validation-en")

        typeAmount("150000")
        compose.onNodeWithText("Food").performScrollTo().performClick()
        runBlocking {
            val outcome = commands.archiveCategory(
                WorkflowCallerScope.User,
                ArchiveCategoryRequest(
                    idempotencyKey = "s107-archive-${UUID.randomUUID()}",
                    categoryId = food,
                ),
            )
            check(outcome is WorkflowCommandOutcome.Applied) { "category archive refused: $outcome" }
        }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("was archived while this form was open", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        hideKeyboard()
        compose.onNodeWithText("was archived while this form was open", substring = true)
            .performScrollTo()
        screenshot("s107-07-archive-warning-en")
        compose.onNodeWithTag("manual-save").performClick()
        awaitText("That category is no longer available.", substring = true)
        compose.onNodeWithText("That category is no longer available.", substring = true)
            .performScrollTo()
        screenshot("s107-07b-archive-refusal-en")

        assertEquals(0L to 0L, totals())
        assertEquals(0, records().size)
    }

    @Test
    fun aRecordChangedElsewhereIsRefusedWithTheTypedValuesIntact() {
        prepare()
        val food = insertCategory("Food")
        val recordId = runBlocking { seedRecord(100_000, food, "Synthetic stale seed") }
        showEditor(transactionId = recordId)
        awaitEditor()
        awaitText("Edit transaction")

        typeAmount("130000")
        val stored = runBlocking { queries.findLedgerRecord(recordId)!! }
        runBlocking {
            val outcome = commands.correctTransaction(
                WorkflowCallerScope.User,
                CorrectTransactionRequest(
                    idempotencyKey = "s107-concurrent-${UUID.randomUUID()}",
                    transactionId = recordId,
                    expectedRevision = stored.revision,
                    type = TransactionType.EXPENSE,
                    amountVnd = 110_000,
                    occurredAtEpochMillis = stored.occurredAtEpochMillis,
                    description = "Synthetic concurrent correction",
                    categoryId = food,
                ),
            )
            check(outcome is WorkflowCommandOutcome.Applied) { "concurrent correction refused: $outcome" }
        }
        compose.onNodeWithTag("manual-save").performClick()
        awaitText("This record changed elsewhere.", substring = true)
        assertEquals("130000", editableText("manual-amount"))
        hideKeyboard()
        compose.onNodeWithText("This record changed elsewhere.", substring = true)
            .performScrollTo()
        screenshot("s107-08-stale-record-en")
        compose.onNodeWithTag("manual-amount").performScrollTo()
        screenshot("s107-08b-stale-amount-en")
        assertEquals(110_000L to 0L, totals())
        assertEquals(1, records().size)
        assertEquals(110_000L, records().single().amountVnd)

        navigatedUp = false
        showEditor(transactionId = 987_654L)
        awaitText("This transaction is not here")
        screenshot("s107-09-missing-record-en")
        assertFalse(navigatedUp)
    }

    @Test
    fun anAlreadyRecordedSavedNoteCannotBeWrittenTwiceAndKeepsTheTypedValues() {
        prepare()
        val eventId = runBlocking { seedNoteEvent(amount = 45_000, reading = "Taxi ride") }
        showEditor(transactionId = null, sourceEventId = eventId)
        awaitEditor()
        awaitTrue { editableText("manual-amount") == "45000" }
        assertTrue(editableText("manual-memo").contains("Taxi ride"))
        compose.onNodeWithText("Record without the note").performScrollTo()
        screenshot("s107-10-source-note-en")

        typeAmount("47000")
        val note = runBlocking { queries.unrecordedNoteEvent(eventId)!! }
        runBlocking {
            val outcome = commands.saveManualTransaction(
                WorkflowCallerScope.User,
                SaveManualTransactionRequest(
                    idempotencyKey = "s107-concurrent-note-${UUID.randomUUID()}",
                    type = TransactionType.EXPENSE,
                    amountVnd = 45_000,
                    occurredAtEpochMillis = ManualDateTime.toEpochMillis(
                        note.anchorDate ?: "2026-11-05",
                        note.anchorTime ?: "15:00",
                        TimeZone.getDefault(),
                    ),
                    description = note.normalizedText.orEmpty(),
                    source = ManualTransactionSource.NoteEvent(eventId, note.revision),
                ),
            )
            check(outcome is WorkflowCommandOutcome.Applied) { "concurrent note record refused: $outcome" }
        }
        compose.onNodeWithTag("manual-save").performClick()
        awaitText("The saved note changed or was already recorded", substring = true)
        assertEquals("47000", editableText("manual-amount"))
        hideKeyboard()
        compose.onNodeWithText("The saved note changed or was already recorded", substring = true)
            .performScrollTo()
        screenshot("s107-11-source-stale-en")
        compose.onNodeWithTag("manual-amount").performScrollTo()
        screenshot("s107-11b-source-amount-en")
        assertEquals(45_000L to 0L, totals())
        assertEquals(1, records().size)
        assertEquals(45_000L, records().single().amountVnd)
    }

    @Test
    fun vietnameseLargeTextAndTheKeyboardKeepTheSingleSaveReachable() {
        prepare()
        insertCategory("Food")
        showEditor(transactionId = null, language = AegisLanguage.VIETNAMESE, fontScale = 1.5f)
        awaitEditor()
        awaitText("Thêm giao dịch mới")
        awaitText("Lưu vào sổ giao dịch")

        typeAmount("150000")
        compose.waitForIdle()
        Thread.sleep(1600)
        compose.waitForIdle()
        screenshot("s107-12-vi-large-ime")
        compose.onNodeWithTag("manual-amount").assertIsDisplayed()
        compose.onNodeWithTag("manual-save").assertIsDisplayed()
        compose.onNodeWithTag("manual-save").assertIsEnabled()

        hideKeyboard()
        compose.onNodeWithText("Chuyển khoản nội bộ").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(
                "Chuyển khoản nội bộ giữa các tài khoản của bạn",
                substring = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("s107-13-vi-transfer")
        compose.onNodeWithTag("manual-save").assertIsDisplayed()
    }

    @Test
    fun vietnameseRetainedArchivedCategorySheetUsesLocalizedWords() {
        prepare()
        val food = insertCategory("Food")
        val recordId = runBlocking { seedRecord(100_000, food, "Synthetic archived seed") }
        runBlocking {
            val outcome = commands.archiveCategory(
                WorkflowCallerScope.User,
                ArchiveCategoryRequest(
                    idempotencyKey = "s107-retained-archive-${UUID.randomUUID()}",
                    categoryId = food,
                ),
            )
            check(outcome is WorkflowCommandOutcome.Applied) { "category archive refused: $outcome" }
        }

        showEditor(
            transactionId = recordId,
            language = AegisLanguage.VIETNAMESE,
            fontScale = 1.5f,
        )
        awaitEditor()
        awaitText("Sửa giao dịch")
        compose.onNodeWithTag("manual-category-all").performScrollTo().performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("manual-category-sheet")
                .fetchSemanticsNodes().isNotEmpty()
        }
        awaitText("Đã lưu trữ", substring = true)
        assertTrue(
            "the retained archived row did not use the Vietnamese badge",
            compose.onAllNodesWithText("Đã lưu trữ", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "the default English ARCHIVED badge leaked into the Vietnamese sheet",
            compose.onAllNodesWithText("ARCHIVED").fetchSemanticsNodes().isEmpty(),
        )
        screenshot("s107-14-vi-archived-sheet")
    }

    // --- harness ---------------------------------------------------------

    private fun prepare() {
        database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        databaseOpen = true
        commands = WorkflowCommandService(database)
        queries = WorkflowQueryService(database)
    }

    private fun showEditor(
        transactionId: Long?,
        sourceEventId: Long? = null,
        language: AegisLanguage = AegisLanguage.ENGLISH,
        fontScale: Float = 1f,
    ) {
        setContent(language, fontScale) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            ) {
                TransactionEditorScreen(
                    commandService = commands,
                    queryService = queries,
                    transactionId = transactionId,
                    sourceEventId = sourceEventId,
                    onNavigateUp = { navigatedUp = true },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun setContent(
        language: AegisLanguage,
        fontScale: Float,
        content: @Composable () -> Unit,
    ) {
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                AegisTheme(themeMode = AegisThemeMode.LIGHT, language = language) {
                    val base = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(base.density, fontScale),
                    ) {
                        content()
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun awaitEditor() {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("manual-save").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("This transaction is not here")
                    .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitText(text: String, substring: Boolean = false) {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(text, substring = substring)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitTrue(condition: () -> Boolean) {
        compose.waitUntil(20_000) { condition() }
    }

    private fun editableText(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.EditableText)
            ?.text
            .orEmpty()

    /** Focus, clear, and type into one field; a real tap is needed before the IME accepts text. */
    private fun typeField(tag: String, value: String) {
        val field = compose.onNodeWithTag(tag)
        field.performScrollTo()
        field.performClick()
        field.performTextClearance()
        field.performTextInput(value)
        compose.waitForIdle()
    }

    private fun typeAmount(value: String) = typeField("manual-amount", value)

    private fun typeMemo(value: String) = typeField("manual-memo", value)

    private fun insertCategory(name: String): Long = runBlocking {
        database.categoryDao().insert(
            CategoryEntity(
                name = name,
                createdAtEpochMillis = System.currentTimeMillis(),
                updatedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun seedRecord(
        amountVnd: Long,
        categoryId: Long?,
        description: String,
    ): Long {
        val outcome = commands.saveManualTransaction(
            WorkflowCallerScope.User,
            SaveManualTransactionRequest(
                idempotencyKey = "s107-seed-${UUID.randomUUID()}",
                type = TransactionType.EXPENSE,
                amountVnd = amountVnd,
                occurredAtEpochMillis = System.currentTimeMillis(),
                description = description,
                categoryId = categoryId,
            ),
        )
        check(outcome is WorkflowCommandOutcome.Applied) { "seed refused: $outcome" }
        return ((outcome.change) as WorkflowChange.TransactionSaved).transactionId
    }

    private suspend fun seedNoteEvent(amount: Long, reading: String): Long {
        val body = "Synthetic note source for $reading"
        val saved = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest("s107-capture-${UUID.randomUUID()}", body),
        ) as WorkflowCommandOutcome.Applied
        val captureId = (saved.change as WorkflowChange.CaptureSaved).captureId
        val stored = commands.updateEventInterpretation(
            WorkflowCallerScope.User,
            UpdateEventRequest(
                idempotencyKey = "s107-event-${UUID.randomUUID()}",
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = NoteEventInterpretation(
                    spanStart = 0,
                    spanEnd = body.length,
                    normalizedText = reading,
                    amountVnd = amount,
                    merchant = "Synthetic merchant",
                    timeAnchor = NoteEventTimeAnchor(
                        date = "2026-11-05",
                        time = "15:00",
                        timezoneId = TimeZone.getDefault().id,
                    ),
                ),
                extractionVersion = "s107-manual-phone-fixture",
            ),
        ) as WorkflowCommandOutcome.Applied
        return (stored.change as WorkflowChange.EventStored).eventId
    }

    private fun totals(): Pair<Long, Long> = runBlocking {
        val aggregate = queries.confirmedLedgerAggregate(
            LedgerDateRange.closed(
                fromEpochMillis = 0L,
                throughExclusiveEpochMillis = 4_000_000_000_000L,
                zoneId = "Asia/Ho_Chi_Minh",
            ),
        )
        aggregate.spending.totalVnd to aggregate.income.totalVnd
    }

    private fun records(): List<TransactionEntity> = runBlocking {
        queries.ledgerRecords(
            LedgerRecordQuery(bounds = QueryPageRequest(limit = 200, offset = 0)),
        ).items
    }

    private fun screenshot(name: String) {
        // A third-party heads-up can otherwise cover the app in the captured frame. This closes
        // the system shade/heads-up without touching app windows, notifications or device data.
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
                .close()
        }
        Thread.sleep(400)
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: error("the device returned no screenshot")
        val file = File(context.filesDir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        android.util.Log.i("S107UI", "S107 screenshot=$name file=${file.absolutePath} bytes=${file.length()}")
    }

    private fun hideKeyboard() {
        val manager = context.getSystemService(InputMethodManager::class.java) ?: return
        compose.activity.runOnUiThread {
            manager.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
        Thread.sleep(700)
        compose.waitForIdle()
    }
}
