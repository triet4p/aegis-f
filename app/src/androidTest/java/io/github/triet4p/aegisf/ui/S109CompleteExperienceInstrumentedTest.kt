package io.github.triet4p.aegisf.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.triet4p.aegisf.AegisApplication
import io.github.triet4p.aegisf.MainActivity
import io.github.triet4p.aegisf.agent.AgentRunHistory
import io.github.triet4p.aegisf.data.BankDraftReviewEdits
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.ContextLinkState
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.LedgerDateRange
import io.github.triet4p.aegisf.data.NotificationAccessStore
import io.github.triet4p.aegisf.data.NoteEventInterpretation
import io.github.triet4p.aegisf.data.NoteEventTimeAnchor
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.ProposeLinkRequest
import io.github.triet4p.aegisf.data.SaveCaptureRequest
import io.github.triet4p.aegisf.data.SaveDraftReviewRequest
import io.github.triet4p.aegisf.data.SaveManualTransactionRequest
import io.github.triet4p.aegisf.data.TransactionType
import io.github.triet4p.aegisf.data.UpdateEventRequest
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowChange
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.provider.HostedProviderSettingsStore
import io.github.triet4p.aegisf.ui.navigation.AegisAppShell
import io.github.triet4p.aegisf.ui.navigation.HomeScreen
import io.github.triet4p.aegisf.ui.review.DraftDetailScreen
import io.github.triet4p.aegisf.ui.review.ReviewInboxScreen
import io.github.triet4p.aegisf.ui.theme.AegisTheme
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
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
import io.github.triet4p.aegisf.data.AegisLanguage
import io.github.triet4p.aegisf.data.AegisThemeMode

/**
 * S10.9 cross-destination proof on the actual phone.
 *
 * The shell under test is the production `AegisAppShell`; the database is a fresh in-memory
 * Room database seeded through the production command service. Every destination, its focused
 * children, its back contract and the applicable state families are exercised here, and each
 * frame is captured from the device screen for personal comparison with the approved renders.
 * The test refuses to run outside the isolated evidence package.
 */
@RunWith(AndroidJUnit4::class)
class S109CompleteExperienceInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var database: LedgerDatabase
    private var databaseOpen = false
    private lateinit var commands: WorkflowCommandService
    private lateinit var queries: WorkflowQueryService
    private lateinit var history: AgentRunHistory
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var noteText = "S109 synthetic note"

    @After
    fun tearDown() {
        if (databaseOpen) {
            database.close()
            databaseOpen = false
        }
    }

    @Test
    fun everyDestinationAndChildOpensAndReturnsToItsOwnContext() {
        prepare()
        assertEquals(ISOLATED_PACKAGE, context.packageName)
        val food = insertCategory("Food")
        val before = seedLedger(food)
        val drafts = seedReviewDrafts(food)
        showShell()
        awaitText("Good", substring = true)
        screenshot("s109-01-home-en")

        // The eight launchers and five tabs are present, and the four Home children each own a
        // focused route with the bottom bar hidden.
        listOf("Open Ledger", "Open Analytics", "Open Notes", "Open AI Chat", "Open Review",
            "Open Manual record", "Open Categories & rules", "Open Settings").forEach {
            assertEquals("launcher '$it' missing", 1,
                compose.onAllNodesWithContentDescription(it).fetchSemanticsNodes().size)
        }
        listOf("Home", "Ledger", "Notes", "AI Chat").forEach {
            assertEquals("tab '$it' missing", 1,
                compose.onAllNodesWithContentDescription(it).fetchSemanticsNodes().size)
        }

        openLauncher("Open Analytics")
        awaitText("Net cash flow")
        assertTrue(shownText().contains("Spending"))
        screenshot("s109-02-analytics-en")
        back()
        awaitText("Good", substring = true)

        // Unsaved manual entry: Back asks, Keep editing keeps the value, Discard changes nothing.
        openLauncher("Open Manual record")
        awaitText("New transaction")
        typeField("manual-amount", "777777")
        screenshot("s109-03-manual-en")
        back()
        awaitText("Discard changes?")
        compose.onNodeWithText("Keep editing").performClick()
        compose.waitForIdle()
        assertEquals("777777", editableText("manual-amount"))
        back()
        awaitText("Discard changes?")
        compose.onNodeWithText("Discard").performClick()
        compose.waitForIdle()
        awaitText("Good", substring = true)
        assertEquals("cancelled manual entry wrote a record", before, transactionCount())

        // Categories and Rules are separate task views; adding one category is a focused sheet.
        openLauncher("Open Categories & rules")
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("category-add").fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("s109-04-categories-en")
        compose.onNodeWithTag("category-add").performClick()
        compose.waitForIdle()
        typeField("category-name", "S109 travel")
        compose.onNodeWithTag("category-save").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("S109 travel", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("category-tab-rules").performClick()
        compose.waitForIdle()
        assertTrue("Rules tab did not open", shownText().contains("Rules"))
        screenshot("s109-05-rules-en")
        back()
        awaitText("Good", substring = true)

        // Settings is a hub: each row opens one focused child and Back returns to the hub.
        openLauncher("Open Settings")
        awaitText("Provider & model")
        screenshot("s109-06-settings-en")
        compose.onNodeWithText("Provider & model").performClick()
        awaitText("HTTPS endpoint")
        screenshot("s109-07-provider-en")
        back()
        awaitText("Skills & history")
        compose.onNodeWithText("Skills & history").performClick()
        awaitText("These bundled workflows", substring = true)
        screenshot("s109-08-skills-history-en")
        back()
        compose.onNodeWithText("Privacy & retention").performClick()
        awaitText("ON THIS DEVICE")
        screenshot("s109-09-privacy-en")
        back()
        compose.onNodeWithText("Bank notifications").performClick()
        awaitText("ALLOWED BANK APPS")
        screenshot("s109-10-bank-notifications-en")
        back()
        back()
        awaitText("Good", substring = true)

        // Ledger: filters survive opening a record, editing it and returning; a tab round-trip
        // keeps the same filter.
        openTab("Ledger")
        awaitText("Search description or note")
        screenshot("s109-11-ledger-en")
        typeField("ledger-search", "Rent share")
        hideKeyboard()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Rent share for the month", substring = true)
                .fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("Phở Thìn lunch").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Rent share for the month", substring = true).performClick()
        awaitText("Record ID")
        screenshot("s109-12-record-detail-en")
        compose.onNodeWithText("Edit transaction").performClick()
        awaitText("Save changes")
        typeField("manual-memo", "edited in s109")
        back()
        awaitText("Discard changes?")
        compose.onNodeWithText("Keep editing").performClick()
        back()
        compose.onNodeWithText("Discard").performClick()
        compose.waitForIdle()
        awaitText("Record ID")
        screenshot("s109-13-record-detail-after-discard-en")
        back()
        awaitLedger()
        assertEquals("edited in s109 could not leak into a discarded edit",
            "Rent share", editableText("ledger-search"))
        openTab("Notes")
        awaitNoteComposer()
        openTab("Ledger")
        awaitLedger()
        assertEquals("the ledger filter was not preserved across tabs", "Rent share",
            editableText("ledger-search"))

        // Notes: a long multi-line note is saved, opened and handed to Chat with visible context.
        openTab("Notes")
        awaitNoteComposer()
        noteText = "Lunch at Pho Thin 65k\nCoffee 45k at Highlands\nBook 120k with a long " +
            "descriptive line that must wrap instead of clipping in the attached note."
        typeField("note-composer", noteText)
        screenshot("s109-14-notes-composing-en")
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Save note").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Save note").onFirst().performScrollTo().performClick()
        compose.waitForIdle()
        awaitText("Saved on this device", substring = true)
        hideKeyboard()
        compose.onAllNodesWithText("Saved notes").onFirst().performScrollTo().performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithContentDescription("Open saved note", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithContentDescription("Open saved note", substring = true)
            .onFirst().performClick()
        awaitText("Discuss with AI")
        screenshot("s109-15-note-detail-en")
        compose.onNodeWithText("Discuss with AI").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Attached note").fetchSemanticsNodes().isNotEmpty()
        }
        // Regression: the context card must be on screen before the first send, not scrolled
        // under the app bar when the transcript is empty.
        compose.onNodeWithText("Attached note").assertIsDisplayed()
        assertTrue("the first note line is not visible", shownText().contains("Lunch at Pho Thin"))
        screenshot("s109-16-chat-note-context-en")
        compose.onNodeWithText("Remove").performClick()
        compose.waitForIdle()

        // Review: the inbox groups drafts; opening one and Back returns to the same queue.
        openTab("Review")
        awaitText("Bank notifications are evidence", substring = true)
        screenshot("s109-17-review-en")
        compose.onNodeWithTag("review-inbox")
            .performScrollToNode(hasTestTag("review-draft-${drafts.ready}"))
        compose.onNodeWithTag("review-draft-${drafts.ready}").performClick()
        awaitText("Draft review")
        screenshot("s109-18-draft-detail-en")
        back()
        awaitText("Bank notifications are evidence", substring = true)

        // Chat root, then Home root; the Home launcher is still intact after the whole walk.
        openTab("AI Chat")
        awaitText("What can I help with?")
        screenshot("s109-19-chat-en")
        openTab("Home")
        awaitText("Open Settings")
        assertEquals("Home lost its launchers after the walk", 8,
            compose.onAllNodesWithContentDescription("Open", substring = true)
                .fetchSemanticsNodes().size)
        screenshot("s109-20-home-after-walk-en")
    }

    @Test
    fun emptyStatesStayTruthfulOnEveryDestination() {
        prepare()
        assertEquals(ISOLATED_PACKAGE, context.packageName)
        showShell()
        awaitText("0 ₫")
        assertEquals("the empty Home disappeared", 8,
            compose.onAllNodesWithContentDescription("Open", substring = true)
                .fetchSemanticsNodes().size)
        screenshot("s109-21-home-empty-en")

        openLauncher("Open Analytics")
        awaitText("No confirmed records in this period")
        screenshot("s109-22-analytics-empty-en")
        back()
        openTab("Ledger")
        awaitText("No transactions yet")
        screenshot("s109-23-ledger-empty-en")
        openTab("Notes")
        awaitNoteComposer()
        screenshot("s109-24-notes-empty-en")
        compose.onAllNodesWithText("Saved notes").onFirst().performScrollTo().performClick()
        awaitText("No saved notes yet")
        screenshot("s109-25-notes-empty-list-en")
        back()
        awaitNoteComposer()
        openTab("Review")
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Nothing to review").fetchSemanticsNodes().isNotEmpty()
        }
        // One empty statement, no page controls for an inbox with no page to turn.
        assertEquals("the empty inbox repeated its heading", 1,
            compose.onAllNodesWithText("Nothing to review").fetchSemanticsNodes().size)
        assertFalse("a pager was drawn over an empty inbox",
            shownText().contains("Page 1"))
        screenshot("s109-25-review-empty-en")

        // With no provider stored the Chat destination explains the boundary and cannot send.
        openTab("AI Chat")
        awaitText("No AI provider is set up")
        assertTrue("the empty Chat hid its offline boundary",
            shownText().contains("Notes and manual entry keep working offline"))
        screenshot("s109-26-chat-no-provider-en")

        openTab("Home")
        openLauncher("Open Categories & rules")
        awaitText("No categories stored")
        screenshot("s109-27-categories-empty-en")

        back()
        openLauncher("Open Manual record")
        awaitText("New transaction")
        screenshot("s109-28-manual-initial-en")
        assertEquals("an untouched manual form wrote a record", 0, transactionCount())
        back()
        awaitText("0 ₫")
    }

    @Test
    fun ambiguousStaleAndCancelledReviewStatesStayTruthful() {
        prepare()
        assertEquals(ISOLATED_PACKAGE, context.packageName)
        val food = insertCategory("Food")
        val before = seedLedger(food)
        val drafts = seedReviewDrafts(food)
        seedProposedLink(drafts.linked)
        showShell()
        openTab("Review")
        awaitText("Bank notifications are evidence", substring = true)
        // Ambiguity is surfaced, not hidden: a duplicate pair and an undecided note link.
        awaitText("possible duplicate")
        compose.onNodeWithTag("review-inbox")
            .performScrollToNode(hasTestTag("review-draft-${drafts.linked}"))
        awaitText("note association not decided")
        screenshot("s109-29-review-ambiguous-en")

        // A draft whose amount the bank format cannot be read must refuse confirmation visibly.
        compose.onNodeWithTag("review-inbox")
            .performScrollToNode(hasTestTag("review-draft-${drafts.unreadable}"))
        compose.onNodeWithTag("review-draft-${drafts.unreadable}").performClick()
        awaitText("Draft review")
        screenshot("s109-30-draft-unreadable-en")
        compose.onNodeWithTag("review-confirm").performClick()
        compose.waitUntil(20_000) {
            shownText().contains("Resolve before confirmation")
        }
        // The sticky action is far from the inline message; the snackbar makes the refusal
        // visible wherever the reader is (the observed S10.9 defect that was fixed).
        assertTrue("the refusal copy did not name the missing field",
            shownText().contains("amount") || shownText().contains("type"))
        screenshot("s109-31-draft-correction-snackbar-en")
        assertEquals("a refused draft confirmation wrote a record", before, transactionCount())

        back()
        awaitText("Bank notifications are evidence", substring = true)
        // The proposed note link is a decision the reader owns, with explicit actions.
        compose.onNodeWithTag("review-inbox")
            .performScrollToNode(hasTestTag("review-draft-${drafts.linked}"))
        compose.onNodeWithTag("review-draft-${drafts.linked}").performClick()
        awaitText("Draft review")
        assertTrue("the ambiguous link card offered no decision",
            shownText().contains("Attach") || shownText().contains("Reject"))
        screenshot("s109-32-draft-ambiguous-link-en")
        back()
        awaitText("Bank notifications are evidence", substring = true)

        // Preview one ready draft, then change the stored draft. The preview must go stale and
        // refuse to confirm values that no longer describe the stored review.
        compose.onNodeWithTag("review-inbox")
            .performScrollToNode(hasTestTag("review-draft-${drafts.ready}"))
        compose.onNodeWithTag("review-draft-${drafts.ready}").performClick()
        awaitText("Draft review")
        compose.onNodeWithTag("review-confirm").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("review-preview-confirm").fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("s109-33-draft-preview-en")
        val stored = requireNotNull(
            runBlocking { database.pendingBankDraftDao().findById(drafts.ready) },
        )
        runBlocking {
            val bumped = commands.saveDraftReview(
                WorkflowCallerScope.User,
                SaveDraftReviewRequest(
                    idempotencyKey = "s109-bump-${UUID.randomUUID()}",
                    draftId = drafts.ready,
                    expectedRevision = stored.reviewRevision,
                    edits = BankDraftReviewEdits(
                        amountText = "500002",
                        dateText = stored.statedDate.orEmpty(),
                        timeText = stored.statedTime.orEmpty(),
                        memoText = stored.memo.orEmpty(),
                        type = TransactionType.EXPENSE,
                        categoryId = food,
                        categoryResolved = true,
                    ),
                ),
            )
            check(bumped is WorkflowCommandOutcome.Applied) { "the stale fixture was refused" }
        }
        compose.waitUntil(20_000) {
            shownText().contains("Preview is stale")
        }
        assertFalse("a stale preview still offered the financial confirmation",
            compose.onAllNodesWithTag("review-preview-confirm").fetchSemanticsNodes().isNotEmpty())
        screenshot("s109-34-draft-preview-stale-en")
        // The stale dialog offers no financial confirmation; the reader can only re-read the
        // current stored values, then decide again.
        compose.onNodeWithText("Check current values").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("review-preview-confirm").fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("s109-34b-draft-preview-refreshed-en")
        compose.onNodeWithText("Back to review").performClick()
        compose.waitForIdle()
        assertEquals("a stale preview wrote a record", before, transactionCount())

        // Cancelling the dismiss dialog changes nothing, then the explicit dismiss records no
        // transaction and removes the draft from the waiting queue.
        compose.onNodeWithText("Dismiss").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Keep reviewing").performClick()
        compose.waitForIdle()
        assertEquals("cancelling the dismiss dialog moved the draft", IntakeReviewStatus.PENDING,
            runBlocking { database.pendingBankDraftDao().findById(drafts.ready) }
                ?.reviewStatus)
        compose.onNodeWithText("Dismiss").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Dismiss draft").performClick()
        compose.waitUntil(20_000) {
            runBlocking { database.pendingBankDraftDao().findById(drafts.ready) }
                ?.reviewStatus == IntakeReviewStatus.DISMISSED
        }
        assertEquals("dismissing a draft changed the ledger", before, transactionCount())
        awaitText("Bank notifications are evidence", substring = true)
        assertTrue("the dismissed draft stayed in the waiting queue",
            compose.onAllNodesWithTag("review-draft-${drafts.ready}").fetchSemanticsNodes()
                .isEmpty())
        screenshot("s109-35-review-after-dismiss-en")
    }

    @Test
    fun readErrorsStayTruthfulOnEveryDataDestination() {
        prepare()
        assertEquals(ISOLATED_PACKAGE, context.packageName)
        // A closed store must produce a read error, never a fabricated figure. Each destination
        // is mounted over the closed database and must show its own copy.
        database.close()
        databaseOpen = false
        val closedQueries = WorkflowQueryService(database)
        val closedCommands = WorkflowCommandService(database, timezoneId = { ZONE })
        val month = DashboardScopeState.currentMonth(
            System.currentTimeMillis(),
            TimeZone.getDefault(),
        )

        showScreen {
            HomeScreen(
                queryService = closedQueries,
                reviewCount = 0,
                onOpenLedger = {},
                onOpenAnalytics = {},
                onOpenNotes = {},
                onOpenChat = {},
                onOpenReview = {},
                onOpenManualRecord = {},
                onOpenCategories = {},
                onOpenSettings = {},
            )
        }
        awaitText("could not be read", substring = true)
        screenshot("s109-36-home-read-error-en")

        showScreen {
            AnalyticsScreen(
                queryService = closedQueries,
                scope = month,
                onScopeChange = {},
                onOpenRecords = { _, _ -> },
                onAddRecord = {},
                onNavigateUp = {},
            )
        }
        awaitText("could not be read", substring = true)
        screenshot("s109-37-analytics-read-error-en")

        showScreen {
            LedgerScreen(
                queryService = closedQueries,
                filters = TransactionFilterState(),
                onFiltersChange = {},
                filtersExpanded = false,
                onFiltersExpandedChange = {},
                onSelect = {},
                onCreate = {},
                onNavigateUp = {},
            )
        }
        awaitText("could not be read", substring = true)
        screenshot("s109-38-ledger-read-error-en")

        showScreen {
            ReviewInboxScreen(queryService = closedQueries, onOpenDraft = {})
        }
        awaitText("couldn't be read", substring = true)
        screenshot("s109-39-review-read-error-en")

        showScreen {
            DraftDetailScreen(
                queryService = closedQueries,
                commandService = closedCommands,
                draftId = 1L,
                onOpenDraft = {},
                onDiscussDraft = {},
                onNavigateUp = {},
            )
        }
        awaitText("This draft couldn't be read", substring = true)
        screenshot("s109-40-draft-read-error-en")

        showScreen {
            TransactionDetailScreen(
                commandService = closedCommands,
                queryService = closedQueries,
                transactionId = 1L,
                onEdit = {},
                onDeleted = {},
                onNavigateUp = {},
            )
        }
        awaitText("Couldn't read this transaction", substring = true)
        screenshot("s109-41-transaction-read-error-en")
    }

    @Test
    fun vietnameseDarkAndOneAndAHalfTextKeepEveryDestinationUsable() {
        prepare()
        assertEquals(ISOLATED_PACKAGE, context.packageName)
        val food = insertCategory("Food")
        seedLedger(food)
        val drafts = seedReviewDrafts(food)
        showShell(
            language = AegisLanguage.VIETNAMESE,
            themeMode = AegisThemeMode.DARK,
            fontScale = 1.5f,
        )
        awaitText("Chào buổi", substring = true)
        assertNoUnlabelledClickables()
        assertMinimumTouchTargets()
        screenshot("s109-50-home-vi-15-dark")

        openLauncher("Mở Báo cáo")
        awaitText("Dòng tiền ròng")
        assertNoUnlabelledClickables()
        assertMinimumTouchTargets()
        screenshot("s109-51-analytics-vi-15-dark")
        back()

        openLauncher("Mở Sổ quỹ")
        awaitLedger()
        assertNoUnlabelledClickables()
        assertMinimumTouchTargets()
        screenshot("s109-52-ledger-vi-15-dark")
        compose.onNodeWithText("Rent share for the month", substring = true).performClick()
        awaitText("Mã giao dịch")
        assertNoUnlabelledClickables()
        assertMinimumTouchTargets()
        screenshot("s109-53-transaction-detail-vi-15-dark")
        back()
        awaitLedger()
        back()
        awaitText("Chào buổi", substring = true)

        openTab("Ghi chép")
        awaitNoteComposer()
        assertNoUnlabelledClickables()
        assertMinimumTouchTargets()
        screenshot("s109-54-notes-vi-15-dark")

        openTab("Trang chủ")
        openLauncher("Mở Nhập tay")
        awaitText("Thêm giao dịch mới")
        typeField("manual-amount", "123456")
        assertNoUnlabelledClickables()
        assertMinimumTouchTargets()
        screenshot("s109-55-manual-vi-15-dark")
        back()
        compose.onNodeWithText("Bỏ thay đổi").performClick()
        compose.waitForIdle()

        openLauncher("Mở Duyệt tin")
        awaitText("Thông báo ngân hàng là bằng chứng", substring = true)
        assertNoUnlabelledClickables()
        assertMinimumTouchTargets()
        screenshot("s109-56-review-vi-15-dark")
        compose.onNodeWithTag("review-inbox")
            .performScrollToNode(hasTestTag("review-draft-${drafts.ready}"))
        compose.onNodeWithTag("review-draft-${drafts.ready}").performClick()
        awaitText("Biến động")
        assertNoUnlabelledClickables()
        assertMinimumTouchTargets()
        screenshot("s109-57-draft-detail-vi-15-dark")
        back()
        awaitText("Thông báo ngân hàng là bằng chứng", substring = true)
        openTab("Trang chủ")

        openLauncher("Mở Danh mục & quy tắc")
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("category-tab-categories").fetchSemanticsNodes()
                .isNotEmpty()
        }
        awaitText("Food", substring = true)
        assertNoUnlabelledClickables()
        assertMinimumTouchTargets()
        screenshot("s109-58-categories-vi-15-dark")
        back()
        awaitText("Chào buổi", substring = true)

        openLauncher("Mở Cài đặt")
        awaitText("Nhà cung cấp", substring = true)
        assertNoUnlabelledClickables()
        assertMinimumTouchTargets()
        screenshot("s109-59-settings-vi-15-dark")
        back()
        awaitText("Chào buổi", substring = true)

        openTab("Trợ lý AI")
        awaitText("Tôi có thể giúp gì?")
        assertNoUnlabelledClickables()
        assertMinimumTouchTargets()
        screenshot("s109-60-chat-vi-15-dark")
        openTab("Trang chủ")
        awaitText("Chào buổi", substring = true)
    }

    @Test
    fun englishDarkStaysLegible() {
        prepare()
        assertEquals(ISOLATED_PACKAGE, context.packageName)
        val food = insertCategory("Food")
        seedLedger(food)
        seedReviewDrafts(food)

        showShell(language = AegisLanguage.ENGLISH, themeMode = AegisThemeMode.DARK)
        awaitText("Good", substring = true)
        assertNoUnlabelledClickables()
        assertMinimumTouchTargets()
        screenshot("s109-61-home-en-dark")
        openTab("Review")
        awaitText("Bank notifications are evidence", substring = true)
        screenshot("s109-62-review-en-dark")
        openTab("AI Chat")
        awaitText("What can I help with?")
        screenshot("s109-63-chat-en-dark")
    }

    @Test
    fun vietnameseLightStaysLegible() {
        prepare()
        assertEquals(ISOLATED_PACKAGE, context.packageName)
        val food = insertCategory("Food")
        seedLedger(food)
        seedReviewDrafts(food)

        showShell(language = AegisLanguage.VIETNAMESE, themeMode = AegisThemeMode.LIGHT)
        awaitText("Chào buổi", substring = true)
        screenshot("s109-64-home-vi-light")
        openTab("Duyệt tin")
        awaitText("Thông báo ngân hàng là bằng chứng", substring = true)
        screenshot("s109-65-review-vi-light")
        openTab("Trợ lý AI")
        awaitText("Tôi có thể giúp gì?")
        screenshot("s109-66-chat-vi-light")
    }

    @Test
    fun longListsLongTextAndExactMoneyStayExact() {
        prepare()
        assertEquals(ISOLATED_PACKAGE, context.packageName)
        val food = insertCategory("Food")
        seedLedger(food)
        val extra = runBlocking {
            var count = 0
            repeat(35) { index ->
                val outcome = commands.saveManualTransaction(
                    WorkflowCallerScope.User,
                    SaveManualTransactionRequest(
                        idempotencyKey = "s109-long-${UUID.randomUUID()}",
                        type = TransactionType.EXPENSE,
                        amountVnd = (index + 1).toLong() * 100_003L,
                        occurredAtEpochMillis = System.currentTimeMillis() - (index + 2) * 3_600_000L,
                        description = "Synthetic long row $index with a long descriptive tail " +
                            "that has to wrap without hiding the amount",
                        categoryId = food,
                    ),
                )
                check(outcome is WorkflowCommandOutcome.Applied)
                count += 1
            }
            count
        }
        // Two boundary amounts: the largest exact whole-dong value and the smallest positive one.
        runBlocking {
            commands.saveManualTransaction(
                WorkflowCallerScope.User,
                SaveManualTransactionRequest(
                    idempotencyKey = "s109-big-${UUID.randomUUID()}",
                    type = TransactionType.INCOME,
                    amountVnd = 9_999_999_999L,
                    occurredAtEpochMillis = System.currentTimeMillis() - 1_000L,
                    description = "Exact upper-bound income",
                    categoryId = null,
                ),
            )
            commands.saveManualTransaction(
                WorkflowCallerScope.User,
                SaveManualTransactionRequest(
                    idempotencyKey = "s109-one-${UUID.randomUUID()}",
                    type = TransactionType.EXPENSE,
                    amountVnd = 1L,
                    occurredAtEpochMillis = System.currentTimeMillis() - 2_000L,
                    description = "One dong",
                    categoryId = food,
                ),
            )
        }
        val aggregate = runBlocking {
            queries.confirmedLedgerAggregate(
                LedgerDateRange.closed(0L, 4_000_000_000_000L, ZONE),
            )
        }
        val expectedSpending = 1_279_567L + (1..30).sumOf { it.toLong() * 1_001L } +
            (1..35).sumOf { it.toLong() * 100_003L } + 1L
        assertEquals(expectedSpending, aggregate.spending.totalVnd)
        assertEquals(9_999_999_999L, aggregate.income.totalVnd)

        showShell()
        awaitText("Good", substring = true)
        assertTrue("the exact Home figure is missing",
            shownText().contains(formatVnd(aggregate.spending.totalVnd)))
        screenshot("s109-40-home-long-list-en")
        openLauncher("Open Analytics")
        awaitText("Net cash flow")
        assertTrue("the exact income figure is missing",
            shownText().contains(formatVnd(9_999_999_999L)))
        assertTrue("the exact spending figure is missing",
            shownText().contains(formatVnd(expectedSpending)))
        screenshot("s109-41-analytics-long-list-en")
        back()
        openTab("Ledger")
        awaitText("Search description or note")
        screenshot("s109-42-ledger-long-list-en")
        compose.onNodeWithTag("ledger-search").performTextInput("Exact upper-bound")
        hideKeyboard()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(formatVnd(9_999_999_999L), substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("s109-43-ledger-boundary-en")
        compose.onNodeWithTag("ledger-search").performTextClearance()
        compose.waitForIdle()
        // The pager is a bounded, manual page: 50 records per page here means one page, and
        // the list scrolls rather than silently dropping rows.
        assertTrue("the long list lost the seed", extra == 35)
    }

    @Test
    fun activityRecreationKeepsRouteFiltersAndUnsavedText() {
        assertEquals(ISOLATED_PACKAGE, context.packageName)
        compose.waitUntil(20_000) {
            compose.onAllNodesWithContentDescription("Open Settings").fetchSemanticsNodes()
                .isNotEmpty()
        }
        openLauncher("Open Manual record")
        awaitText("New transaction")
        typeField("manual-amount", "424242")
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        awaitText("New transaction")
        assertEquals("the unsaved amount did not survive recreation", "424242",
            editableText("manual-amount"))
        back()
        awaitText("Discard changes?")
        compose.onNodeWithText("Discard").performClick()
        compose.waitForIdle()
        awaitText("Good", substring = true)

        // The selected tab survives a real activity recreation.
        openTab("Notes")
        awaitNoteComposer()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        awaitNoteComposer()
        openTab("Home")
        awaitText("Good", substring = true)
    }

    // --- harness ---------------------------------------------------------

    private fun prepare() {
        database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        databaseOpen = true
        commands = WorkflowCommandService(database, timezoneId = { ZONE })
        queries = WorkflowQueryService(database)
        history = AgentRunHistory(database)
    }

    /** Mounts one screen or the shell with an explicit language, theme and text size. */
    private fun showScreen(
        language: AegisLanguage = AegisLanguage.ENGLISH,
        themeMode: AegisThemeMode = AegisThemeMode.LIGHT,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                AegisTheme(themeMode = themeMode, language = language) {
                    val base = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(base.density, fontScale),
                        content = content,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun showShell(
        language: AegisLanguage = AegisLanguage.ENGLISH,
        themeMode: AegisThemeMode = AegisThemeMode.LIGHT,
        fontScale: Float = 1f,
    ) {
        val providerStore = HostedProviderSettingsStore(context)
        val accessStore = NotificationAccessStore(context)
        showScreen(language = language, themeMode = themeMode, fontScale = fontScale) {
            var currentLanguage by remember { mutableStateOf(language) }
            var currentTheme by remember { mutableStateOf(themeMode) }
            AegisTheme(themeMode = currentTheme, language = currentLanguage) {
                AegisAppShell(
                    queryService = queries,
                    commandService = commands,
                    notificationAccessStore = accessStore,
                    hostedProviderSettingsStore = providerStore,
                    createHostedWorkflowHost = { _, _ ->
                        error("no hosted run in the navigation suite")
                    },
                    agentRunHistory = history,
                    language = currentLanguage,
                    onLanguageChange = { currentLanguage = it },
                    themeMode = currentTheme,
                    onThemeModeChange = { currentTheme = it },
                )
            }
        }
    }

    private fun awaitLedger() {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("ledger-search").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitNoteComposer() {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("note-composer").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openLauncher(description: String) {
        compose.onNodeWithContentDescription(description).performClick()
        compose.waitForIdle()
    }

    private fun openTab(description: String) {
        val exact = compose.onAllNodesWithContentDescription(description)
            .fetchSemanticsNodes()
        if (exact.isNotEmpty()) {
            compose.onNodeWithContentDescription(description).performClick()
        } else {
            compose.onAllNodesWithContentDescription(description, substring = true)
                .onFirst().performClick()
        }
        compose.waitForIdle()
    }

    private fun back() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun awaitText(text: String, substring: Boolean = false) {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(text, substring = substring)
                .fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithContentDescription(text, substring = substring)
                    .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Dismisses the IME so the list behind it is measurable; no device setting changes. */
    private fun hideKeyboard() {
        val manager = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        compose.activity.runOnUiThread {
            manager?.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
        Thread.sleep(400)
    }

    private fun typeField(tag: String, value: String) {
        val field = compose.onNodeWithTag(tag)
        runCatching { field.performScrollTo() }
        field.performClick()
        field.performTextClearance()
        field.performTextInput(value)
        compose.waitForIdle()
    }

    private fun editableText(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.EditableText)
            ?.text
            .orEmpty()

    private fun shownText(): String = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        .joinToString(" ") { root -> collectText(root) }

    private fun collectText(node: androidx.compose.ui.semantics.SemanticsNode): String = buildString {
        val text = runCatching { node.config[SemanticsProperties.Text] }.getOrNull()
        append(text?.joinToString("") { it.text }.orEmpty())
        append(' ')
        node.children.forEach { child -> append(collectText(child)) }
    }

    private fun assertNoUnlabelledClickables() {
        val unnamed = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .mapNotNull { node ->
                val text = node.config.getOrNull(SemanticsProperties.Text)
                    ?.joinToString("") { it.text }
                    .orEmpty()
                val description = node.config.getOrNull(SemanticsProperties.ContentDescription)
                    ?.joinToString(" ")
                    .orEmpty()
                // A text field's audible content is its editable value even when no static
                // label is attached; the hero amount field additionally carries a label.
                val editable = node.config.getOrNull(SemanticsProperties.EditableText)
                    ?.text
                    .orEmpty()
                if (text.isNotBlank() || description.isNotBlank() || editable.isNotBlank()) {
                    null
                } else {
                    "an unnamed clickable control at ${node.boundsInRoot}"
                }
            }
        assertTrue(unnamed.joinToString("; "), unnamed.isEmpty())
    }

    /**
     * Every clickable exposes its real hit area, not just its painted box.
     *
     * Material controls paint a 40 dp icon but expand the touch target to 48 dp; the exposed
     * Android accessibility node and this measurement both use that expanded area, so a
     * painted-size reading would report a false failure. Violations are collected so one run
     * names every offending control instead of stopping at the first.
     */
    private fun assertMinimumTouchTargets() {
        val minimum = 48 * compose.activity.resources.displayMetrics.density
        val violations = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .mapNotNull { node ->
                val bounds = node.touchBoundsInRoot
                if (bounds.width <= 0 || bounds.height <= 0 ||
                    (bounds.width >= minimum && bounds.height >= minimum)
                ) {
                    null
                } else {
                    val text = node.config.getOrNull(SemanticsProperties.Text)
                        ?.joinToString("") { it.text }
                        .orEmpty()
                    val description = node.config.getOrNull(SemanticsProperties.ContentDescription)
                        ?.joinToString(" ")
                        .orEmpty()
                    val line = "${bounds.width}x${bounds.height}px text='$text' " +
                        "desc='$description'"
                    android.util.Log.i("S109A11Y", "small target $line")
                    line
                }
            }
        assertTrue(
            "${violations.size} touch targets are below 48dp: ${violations.joinToString("; ")}",
            violations.isEmpty(),
        )
    }

    private fun transactionCount(): Int = runBlocking { database.transactionDao().findAll().size }

    private fun insertCategory(name: String): Long = runBlocking {
        database.categoryDao().insert(
            CategoryEntity(
                name = name,
                createdAtEpochMillis = System.currentTimeMillis(),
                updatedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    private fun seedLedger(food: Long): Int = runBlocking {
        commands.saveManualTransaction(
            WorkflowCallerScope.User,
            SaveManualTransactionRequest(
                idempotencyKey = "s109-rent-${UUID.randomUUID()}",
                type = TransactionType.EXPENSE,
                amountVnd = 1_234_567,
                occurredAtEpochMillis = System.currentTimeMillis() - 60_000,
                description = "Rent share for the month",
                categoryId = food,
            ),
        )
        commands.saveManualTransaction(
            WorkflowCallerScope.User,
            SaveManualTransactionRequest(
                idempotencyKey = "s109-pho-${UUID.randomUUID()}",
                type = TransactionType.EXPENSE,
                amountVnd = 45_000,
                occurredAtEpochMillis = System.currentTimeMillis() - 120_000,
                description = "Phở Thìn lunch",
                categoryId = food,
            ),
        )
        repeat(30) { index ->
            commands.saveManualTransaction(
                WorkflowCallerScope.User,
                SaveManualTransactionRequest(
                    idempotencyKey = "s109-seed-${UUID.randomUUID()}",
                    type = TransactionType.EXPENSE,
                    amountVnd = (index + 1).toLong() * 1_001L,
                    occurredAtEpochMillis = System.currentTimeMillis() - (index + 3) * 3_600_000L,
                    description = "Seed list row ${index + 1}",
                    categoryId = food,
                ),
            )
        }
        database.transactionDao().findAll().size
    }

    private fun seedReviewDrafts(food: Long): Drafts = runBlocking {
        val ready = storeDraft("ready", 500_000, "Synthetic ready draft")
        prepareReview(ready, food, "500000")
        val needsCategory = storeDraft("needs", 65_000, "Synthetic category draft")
        val unreadable = storeDraft(
            "unreadable",
            null,
            "Synthetic unreadable draft",
            amountState = IntakeFieldState.UNRESOLVED,
            parseStatus = IntakeParseStatus.UNSUPPORTED_FORMAT,
        )
        val duplicateA = storeDraft("dup-a", 77_000, "Synthetic duplicate evidence")
        val duplicateB = storeDraft("dup-b", 77_000, "Synthetic duplicate evidence")
        val linked = storeDraft("linked", 45_000, "Synthetic linked draft at Highlands")
        Drafts(ready, needsCategory, unreadable, duplicateA, duplicateB, linked)
    }

    /**
     * Adds one ambiguous note association: the reading and the draft genuinely match, so the
     * pair is left for the reader to attach or reject instead of being decided for them.
     */
    private fun seedProposedLink(draftId: Long): Long = runBlocking {
        // A same-amount, same-day candidate with the same merchant phrase makes the automatic
        // policy see two possible associations, so the app leaves the pair for the reader and
        // this test can exercise the undecided-link state instead of an already-attached link.
        storeDraft("decoy", 45_000, "Highlands coffee")
        val body = "Coffee 45k at Highlands"
        val today = LocalDate.now(ZoneId.of(ZONE)).toString()
        val saved = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest("s109-link-${UUID.randomUUID()}", body),
        ) as WorkflowCommandOutcome.Applied
        val captureId = (saved.change as WorkflowChange.CaptureSaved).captureId
        commands.updateEventInterpretation(
            WorkflowCallerScope.User,
            UpdateEventRequest(
                idempotencyKey = "s109-link-event-${UUID.randomUUID()}",
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = NoteEventInterpretation(
                    spanStart = 0,
                    spanEnd = body.length,
                    normalizedText = body,
                    amountVnd = 45_000L,
                    merchant = "Highlands",
                    timeAnchor = NoteEventTimeAnchor(date = today, time = "12:30", timezoneId = ZONE),
                ),
                extractionVersion = "s109-phone-fixture",
            ),
        )
        val event = database.noteEventDao().findForCapture(captureId).single()
        val proposed = commands.proposeLink(
            WorkflowCallerScope.User,
            ProposeLinkRequest(
                idempotencyKey = "s109-link-proposal-${UUID.randomUUID()}",
                eventId = event.id,
                draftId = draftId,
            ),
        )
        check(proposed.isAppliedOrNoChange()) { "the proposed link was refused: $proposed" }
        val link = database.contextLinkDao().findByPair(event.id, draftId)
        check(link?.state == ContextLinkState.PROPOSED) {
            "the proposed link was not stored: $proposed -> $link"
        }
        draftId
    }

    private data class Drafts(
        val ready: Long,
        val needsCategory: Long,
        val unreadable: Long,
        val duplicateA: Long,
        val duplicateB: Long,
        val linked: Long,
    )

    private suspend fun storeDraft(
        key: String,
        amount: Long?,
        memo: String,
        amountState: IntakeFieldState = IntakeFieldState.PRESENT,
        parseStatus: IntakeParseStatus = IntakeParseStatus.PARSED,
    ): Long = database.pendingBankDraftDao().store(
        PendingBankDraftEntity(
            deliveryId = "s109-$key-${UUID.randomUUID()}",
            sourcePackage = "synthetic.fixture",
            notificationKey = null,
            postedAtEpochMillis = System.currentTimeMillis(),
            bank = "VIETINBANK",
            parseStatus = parseStatus,
            amountState = amountState,
            amountVnd = amount,
            amountUnresolvedReason = if (amountState == IntakeFieldState.UNRESOLVED) {
                "UNREADABLE"
            } else {
                null
            },
            direction = IntakeDirection.DEBIT,
            momentState = IntakeFieldState.PRESENT,
            statedDate = LocalDate.now(ZoneId.of(ZONE)).toString(),
            statedTime = "15:00",
            momentUnresolvedReason = null,
            memoState = IntakeFieldState.PRESENT,
            memo = memo,
            memoUnresolvedReason = null,
            createdAtEpochMillis = System.currentTimeMillis(),
        ),
    )

    private suspend fun prepareReview(draftId: Long, categoryId: Long, amountText: String) {
        val draft = database.pendingBankDraftDao().findById(draftId) ?: error("draft missing")
        val outcome = commands.saveDraftReview(
            WorkflowCallerScope.User,
            SaveDraftReviewRequest(
                idempotencyKey = "s109-prep-${UUID.randomUUID()}",
                draftId = draftId,
                expectedRevision = draft.reviewRevision,
                edits = BankDraftReviewEdits(
                    amountText = amountText,
                    dateText = draft.statedDate.orEmpty(),
                    timeText = draft.statedTime.orEmpty(),
                    memoText = draft.memo.orEmpty(),
                    type = TransactionType.EXPENSE,
                    categoryId = categoryId,
                    categoryResolved = true,
                ),
            ),
        )
        check(outcome is WorkflowCommandOutcome.Applied) { "review preparation refused" }
    }

    private fun formatVnd(amount: Long): String {
        val digits = amount.toString()
        val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
        return "$grouped ₫"
    }

    private fun screenshot(name: String) {
        Thread.sleep(350)
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: error("the device returned no screenshot")
        val directory = context.getExternalFilesDir("s109")?.apply { mkdirs() } ?: return
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        android.util.Log.i("S109UI", "screenshot=$name")
    }

    private companion object {
        const val ISOLATED_PACKAGE = "io.github.triet4p.aegisf.s109evidence"
        const val ZONE = "Asia/Ho_Chi_Minh"
    }
}
