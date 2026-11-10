package io.github.triet4p.aegisf.ui

import android.content.Context
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.triet4p.aegisf.MainActivity
import io.github.triet4p.aegisf.data.AegisLanguage
import io.github.triet4p.aegisf.data.AegisThemeMode
import io.github.triet4p.aegisf.data.AttachLinkRequest
import io.github.triet4p.aegisf.data.BankDraftReviewEdits
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.ContextLinkState
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.LedgerDateRange
import io.github.triet4p.aegisf.data.NoteEventInterpretation
import io.github.triet4p.aegisf.data.NoteEventTimeAnchor
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.ProposeLinkRequest
import io.github.triet4p.aegisf.data.SaveCaptureRequest
import io.github.triet4p.aegisf.data.SaveDraftReviewRequest
import io.github.triet4p.aegisf.data.SelectDraftCategoryRequest
import io.github.triet4p.aegisf.data.TransactionType
import io.github.triet4p.aegisf.data.UpdateEventRequest
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowChange
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.data.categoryCatalogRevision
import io.github.triet4p.aegisf.ui.review.DraftDetailScreen
import io.github.triet4p.aegisf.ui.review.ReviewInboxScreen
import io.github.triet4p.aegisf.ui.theme.AegisTheme
import java.io.File
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
 * S10.6 actual Compose review evidence on isolated synthetic fixtures.
 *
 * Every test owns an in-memory Room database and renders the production `ReviewInboxScreen` and
 * `DraftDetailScreen` through the real theme. The tests assert the stored rows and the exact
 * confirmed aggregate as well as the visible words, so a card is never mistaken for the ledger.
 * The opt-in package guard keeps this off the owner's installation.
 */
@RunWith(AndroidJUnit4::class)
class S106ReviewInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var database: LedgerDatabase
    private lateinit var commands: WorkflowCommandService
    private lateinit var queries: WorkflowQueryService
    private var databaseOpen = false
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        if (databaseOpen) {
            database.close()
            databaseOpen = false
        }
    }

    @Test
    fun inboxGroupsEveryReasonAndNavigationKeepsTheChosenOrder() {
        prepare()
        assertEquals("io.github.triet4p.aegisf.s812evidence", context.packageName)
        val food = insertCategory("Food")
        val ready = runBlocking {
            storeDraft("ready", amount = 500_000, memo = "Synthetic ready draft")
                .also { id -> prepareReview(id, TransactionType.EXPENSE, food, "500000", "Synthetic ready draft") }
        }
        runBlocking { storeDraft("need", amount = 50_000, memo = "Synthetic category draft") }
        runBlocking {
            storeDraft(
                "conflict",
                amount = null,
                amountState = IntakeFieldState.UNRESOLVED,
                memo = "Synthetic unreadable draft",
                parseStatus = IntakeParseStatus.UNSUPPORTED_FORMAT,
            )
        }
        runBlocking {
            storeDraft("dup-a", amount = 77_000, memo = "Synthetic duplicate evidence")
            storeDraft("dup-b", amount = 77_000, memo = "Synthetic duplicate evidence")
        }
        val linked = runBlocking { storeProposedLinkDraft() }
        assertTrue(linked.first > 0L)

        showReview()

        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Needs your decision", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("s106-01-inbox-en")
        assertTrue(
            shownText().contains("6 drafts waiting"),
        )
        compose.onNodeWithTag("review-inbox")
            .performScrollToNode(hasText("Needs a category", substring = true))
        compose.onNodeWithTag("review-inbox")
            .performScrollToNode(hasText("Ready to confirm", substring = true))
        compose.onNodeWithTag("review-inbox")
            .performScrollToNode(hasTestTag("review-draft-$ready"))

        // The chosen order survives opening a draft and coming Back.
        compose.onNodeWithTag("review-inbox")
            .performScrollToNode(hasText("Needs decision first"))
        compose.onNodeWithText("Needs decision first").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Oldest first").fetchSemanticsNodes().isNotEmpty()
        }
        openDraft(ready)
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Draft review").fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("s106-02-detail-en")
        assertTrue(
            "the opened draft's amount is not on the detail",
            shownText().contains("500,000"),
        )

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("review-inbox").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "the inbox lost the row it was showing after Back",
            compose.onAllNodesWithTag("review-draft-$ready").fetchSemanticsNodes().isNotEmpty(),
        )
        compose.onNodeWithTag("review-inbox")
            .performScrollToNode(hasText("Oldest first"))
        assertTrue(
            "the inbox order was not preserved after Back",
            compose.onAllNodesWithText("Oldest first").fetchSemanticsNodes().isNotEmpty(),
        )
        screenshot("s106-03-inbox-back")
    }

    @Test
    fun confirmWritesTheExactTotalOnceAndDismissWritesNothing() {
        prepare()
        assertEquals("io.github.triet4p.aegisf.s812evidence", context.packageName)
        val food = insertCategory("Food")
        val expense = runBlocking {
            storeDraft("expense-ready", amount = 500_000, memo = "Synthetic lunch")
                .also { id -> prepareReview(id, TransactionType.EXPENSE, food, "500000", "Synthetic lunch") }
        }
        val income = runBlocking {
            storeDraft(
                "income-ready",
                amount = 250_000,
                direction = IntakeDirection.CREDIT,
                memo = "Synthetic refund",
            ).also {
                id -> prepareReview(id, TransactionType.INCOME, food, "250000", "Synthetic refund")
            }
        }
        assertEquals(0L to 0L, totals())
        assertEquals(0, runBlocking { database.transactionDao().findAll().size })

        showReview()
        openDraft(expense)
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Confirm and add to ledger").fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("s106-04-detail-ready-en")
        compose.onNodeWithTag("review-confirm").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Confirm this record?").fetchSemanticsNodes().isNotEmpty()
        }
        settleCapture()
        screenshot("s106-05-preview-en")
        compose.onNodeWithTag("review-preview-confirm").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("review-inbox").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("the confirmed expense total is wrong", 500_000L to 0L, totals())
        assertEquals(1, runBlocking { database.transactionDao().findAll().size })
        assertEquals(
            IntakeReviewStatus.CONFIRMED,
            runBlocking { database.pendingBankDraftDao().findById(expense)!!.reviewStatus },
        )
        screenshot("s106-06-inbox-after-confirm-en")

        openDraft(income)
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Dismiss").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Dismiss").onFirst().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Dismiss draft").fetchSemanticsNodes().isNotEmpty()
        }
        settleCapture()
        screenshot("s106-07-dismiss-confirm-en")
        compose.onAllNodesWithText("Dismiss draft").onFirst().performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("review-inbox").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("dismissing changed income", 500_000L to 0L, totals())
        assertEquals("dismissing added a record", 1, runBlocking {
            database.transactionDao().findAll().size
        })
        assertEquals(
            IntakeReviewStatus.DISMISSED,
            runBlocking { database.pendingBankDraftDao().findById(income)!!.reviewStatus },
        )

        showDetailOnly(expense)
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("This draft is terminal.", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("s106-15-confirmed-terminal-en")

        showDetailOnly(income)
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("This draft was dismissed.", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("s106-16-dismissed-terminal-en")
    }

    @Test
    fun categoryAndLinkEditsDoNotMoveTotalsAndAStalePreviewRefuses() {
        prepare()
        assertEquals("io.github.triet4p.aegisf.s812evidence", context.packageName)
        val fuel = insertCategory("Fuel")
        val staleFood = insertCategory("Food")
        val needCategory = runBlocking {
            storeDraft("category-edit", amount = 40_000, memo = "Synthetic fuel")
        }
        val linked = runBlocking { storeProposedLinkDraft() }
        val staleReady = runBlocking {
            storeDraft("stale-ready", amount = 60_000, memo = "Synthetic stale original")
                .also {
                    id -> prepareReview(
                        id,
                        TransactionType.EXPENSE,
                        staleFood,
                        "60000",
                        "Synthetic stale original",
                    )
                }
        }
        assertEquals(0L to 0L, totals())

        showReview()
        openDraft(needCategory)
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("review-category").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("review-category").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("review-category-sheet").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("review-category-choice-$fuel").performClick()
        compose.onNodeWithTag("review-save").performScrollTo().performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Corrections saved.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Corrections saved.").onFirst().performScrollTo()
        compose.onNodeWithTag("review-save").assertIsEnabled()
        settleCapture()
        assertEquals("a category edit changed totals", 0L to 0L, totals())
        assertEquals(0, runBlocking { database.transactionDao().findAll().size })
        val stored = runBlocking { database.pendingBankDraftDao().findById(needCategory)!! }
        assertEquals(fuel, stored.reviewCategoryId)
        assertEquals(IntakeReviewStatus.PENDING, stored.reviewStatus)
        screenshot("s106-08-category-saved-en")

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("review-inbox").fetchSemanticsNodes().isNotEmpty()
        }
        openDraft(linked.first)
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("review-attach-${linked.second}")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("review-attach-${linked.second}").performScrollTo().performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Note excerpt attached. The bank amount and ledger are unchanged.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(
            ContextLinkState.ACCEPTED,
            runBlocking {
                database.contextLinkDao().findForDraft(linked.first).single().state
            },
        )
        assertEquals("a link decision changed totals", 0L to 0L, totals())
        compose.onNodeWithTag("review-unlink-${linked.second}").performScrollTo()
        settleCapture()
        screenshot("s106-09-link-attached-en")

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("review-inbox").fetchSemanticsNodes().isNotEmpty()
        }
        openDraft(staleReady)
        compose.onNodeWithTag("review-confirm").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Confirm this record?").fetchSemanticsNodes().isNotEmpty()
        }

        // A concurrent correction moves the draft after the preview, without touching the form.
        val current = runBlocking { database.pendingBankDraftDao().findById(staleReady)!! }
        val changed = runBlocking {
            commands.saveDraftReview(
                WorkflowCallerScope.User,
                SaveDraftReviewRequest(
                    idempotencyKey = "s106-concurrent-${UUID.randomUUID()}",
                    draftId = staleReady,
                    expectedRevision = current.reviewRevision,
                    edits = BankDraftReviewEdits(
                        amountText = "60000",
                        dateText = "2026-11-05",
                        timeText = "15:00",
                        memoText = "Synthetic stale changed elsewhere",
                        type = TransactionType.EXPENSE,
                        categoryId = staleFood,
                        categoryResolved = true,
                    ),
                ),
            )
        }
        assertTrue("the concurrent correction was refused: $changed", changed.isApplied())
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Preview is stale").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodesWithTag("review-preview-confirm")
            .fetchSemanticsNodes().isEmpty())
        assertEquals("a stale preview wrote a record", 0L to 0L, totals())
        settleCapture()
        screenshot("s106-10-stale-preview-en")

        compose.onNodeWithText("Check current values").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("These exact values will be stored:")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("review-preview-confirm").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("review-inbox").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("the fresh preview did not write the exact total", 60_000L to 0L, totals())
        assertEquals(1, runBlocking { database.transactionDao().findAll().size })
    }

    @Test
    fun vietnameseLargeTextAndKeyboardKeepTheReviewActionReachable() {
        prepare()
        assertEquals("io.github.triet4p.aegisf.s812evidence", context.packageName)
        val food = insertCategory("Food")
        val ready = runBlocking {
            storeDraft("vi-ready", amount = 150_000, memo = "Synthetic Vietnamese draft")
                .also {
                    id -> prepareReview(id, TransactionType.EXPENSE, food, "150000", "Synthetic Vietnamese draft")
                }
        }
        runBlocking { storeDraft("vi-need", amount = 45_000, memo = "Synthetic Vietnamese need") }

        showReview(language = AegisLanguage.VIETNAMESE, fontScale = 1.5f)
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Duyệt tin").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("review-inbox")
            .performScrollToNode(hasText("Cần chọn danh mục", substring = true))
        screenshot("s106-11-inbox-vi-large")

        openDraft(ready, language = AegisLanguage.VIETNAMESE, fontScale = 1.5f)
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Biến động").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("review-amount").performScrollTo().performClick()
        compose.waitForIdle()
        Thread.sleep(1600)
        compose.waitForIdle()
        screenshot("s106-12-detail-vi-ime")
        compose.onNodeWithTag("review-amount").assertIsDisplayed()
        hideKeyboard()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("review-confirm").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("review-confirm").assertIsDisplayed()
    }

    @Test
    fun missingAndUnreadableDraftsSayWhatHappened() {
        prepare()
        assertEquals("io.github.triet4p.aegisf.s812evidence", context.packageName)
        showDetailOnly(987_654L)
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("This draft is not here").fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("s106-13-missing-draft-en")

        database.close()
        databaseOpen = false
        showDetailOnly(1L)
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("This draft couldn't be read").fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("s106-14-read-error-en")
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

    private fun showReview(
        language: AegisLanguage = AegisLanguage.ENGLISH,
        fontScale: Float = 1f,
    ) {
        setContent(language, fontScale) {
            val holder = rememberSaveableStateHolder()
            var openDraftId by rememberSaveable { mutableStateOf<Long?>(null) }
            holder.SaveableStateProvider(openDraftId?.let { "draft-$it" } ?: "inbox") {
                val current = openDraftId
                if (current == null) {
                    // The production shell already places this root above its bottom bar; the
                    // test host has no shell, so it provides the same system-navigation inset.
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier
                            .fillMaxSize()
                            .navigationBarsPadding(),
                    ) {
                        ReviewInboxScreen(
                            queryService = queries,
                            onOpenDraft = { openDraftId = it },
                        )
                    }
                } else {
                    DraftDetailScreen(
                        queryService = queries,
                        commandService = commands,
                        draftId = current,
                        onOpenDraft = { openDraftId = it },
                        onDiscussDraft = {},
                        onNavigateUp = { openDraftId = null },
                    )
                }
            }
        }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("review-inbox").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun showDetailOnly(draftId: Long) {
        setContent(AegisLanguage.ENGLISH, 1f) {
            DraftDetailScreen(
                queryService = queries,
                commandService = commands,
                draftId = draftId,
                onOpenDraft = {},
                onDiscussDraft = {},
                onNavigateUp = {},
            )
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

    private fun openDraft(
        draftId: Long,
        language: AegisLanguage = AegisLanguage.ENGLISH,
        fontScale: Float = 1f,
    ) {
        compose.onNodeWithTag("review-inbox")
            .performScrollToNode(hasTestTag("review-draft-$draftId"))
        compose.onNodeWithTag("review-draft-$draftId").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("review-confirm").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Draft review").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Biến động").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun insertCategory(name: String): Long = runBlocking {
        database.categoryDao().insert(
            CategoryEntity(
                name = name,
                createdAtEpochMillis = System.currentTimeMillis(),
                updatedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun storeDraft(
        key: String,
        amount: Long?,
        memo: String,
        amountState: IntakeFieldState = IntakeFieldState.PRESENT,
        direction: IntakeDirection = IntakeDirection.DEBIT,
        parseStatus: IntakeParseStatus = IntakeParseStatus.PARSED,
        createdAt: Long = System.currentTimeMillis(),
    ): Long = database.pendingBankDraftDao().store(
        PendingBankDraftEntity(
            deliveryId = "s106-$key-${UUID.randomUUID()}",
            sourcePackage = "synthetic.fixture",
            notificationKey = null,
            postedAtEpochMillis = createdAt,
            bank = "VIETINBANK",
            parseStatus = parseStatus,
            amountState = amountState,
            amountVnd = amount,
            amountUnresolvedReason = if (amountState == IntakeFieldState.UNRESOLVED) {
                "UNREADABLE"
            } else {
                null
            },
            direction = direction,
            momentState = IntakeFieldState.PRESENT,
            statedDate = "2026-11-05",
            statedTime = "15:00",
            momentUnresolvedReason = null,
            memoState = IntakeFieldState.PRESENT,
            memo = memo,
            memoUnresolvedReason = null,
            createdAtEpochMillis = createdAt,
        ),
    )

    private suspend fun prepareReview(
        draftId: Long,
        type: TransactionType,
        categoryId: Long,
        amountText: String,
        memo: String,
    ) {
        val draft = database.pendingBankDraftDao().findById(draftId)
            ?: error("draft $draftId not stored")
        val outcome = commands.saveDraftReview(
            WorkflowCallerScope.User,
            SaveDraftReviewRequest(
                idempotencyKey = "s106-prepare-$draftId-${UUID.randomUUID()}",
                draftId = draftId,
                expectedRevision = draft.reviewRevision,
                edits = BankDraftReviewEdits(
                    amountText = amountText,
                    dateText = "2026-11-05",
                    timeText = "15:00",
                    memoText = memo,
                    type = type,
                    categoryId = categoryId,
                    categoryResolved = true,
                ),
            ),
        )
        check(outcome.isApplied()) { "review preparation refused: $outcome" }
    }

    private suspend fun storeProposedLinkDraft(): Pair<Long, Long> {
        val body = "Synthetic note for the review link fixture"
        val saved = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest("s106-capture-${UUID.randomUUID()}", body),
        ) as WorkflowCommandOutcome.Applied
        val captureId = (saved.change as WorkflowChange.CaptureSaved).captureId
        val stored = commands.updateEventInterpretation(
            WorkflowCallerScope.User,
            UpdateEventRequest(
                idempotencyKey = "s106-event-${UUID.randomUUID()}",
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = NoteEventInterpretation(
                    spanStart = 0,
                    spanEnd = body.length,
                    normalizedText = "Synthetic review link reading",
                    amountVnd = 30_000L,
                    merchant = "Synthetic merchant",
                    timeAnchor = NoteEventTimeAnchor(
                        date = "2026-11-05",
                        time = "15:00",
                        timezoneId = "Asia/Ho_Chi_Minh",
                    ),
                ),
                extractionVersion = "s106-review-phone-fixture",
            ),
        ) as WorkflowCommandOutcome.Applied
        val eventId = (stored.change as WorkflowChange.EventStored).eventId
        val draftId = storeDraft("linked", amount = 30_000, memo = "Synthetic linked draft")
        val proposed = commands.proposeLink(
            WorkflowCallerScope.User,
            ProposeLinkRequest("s106-proposal-${UUID.randomUUID()}", eventId, draftId),
        ) as WorkflowCommandOutcome.Applied
        val linkId = (proposed.change as WorkflowChange.LinkDecided).linkId
        return draftId to linkId
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

    private fun WorkflowCommandOutcome.isApplied(): Boolean = this is WorkflowCommandOutcome.Applied

    private fun shownText(): String = compose.onAllNodes(androidx.compose.ui.test.isRoot())
        .fetchSemanticsNodes()
        .joinToString(" ") { root -> collectText(root) }

    private fun collectText(node: androidx.compose.ui.semantics.SemanticsNode): String = buildString {
        val text = runCatching { node.config[SemanticsProperties.Text] }.getOrNull()
        append(text?.joinToString("") { it.text }.orEmpty())
        append(' ')
        node.children.forEach { child -> append(collectText(child)) }
    }

    /** Lets a dialog's own enter animation finish before the device frame is captured. */
    private fun settleCapture() {
        Thread.sleep(600)
        compose.waitForIdle()
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
        android.util.Log.i("S106UI", "S106 screenshot=$name file=${file.absolutePath} bytes=${file.length()}")
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
