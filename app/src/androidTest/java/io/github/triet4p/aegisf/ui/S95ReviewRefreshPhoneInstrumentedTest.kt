package io.github.triet4p.aegisf.ui

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.triet4p.aegisf.MainActivity
import io.github.triet4p.aegisf.data.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real review surface against a fresh synthetic database; no default database is read or copied. */
@RunWith(AndroidJUnit4::class)
class S95ReviewRefreshPhoneInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun visibleDraftUpdatesAfterCategorySaveWithoutRefreshOrNavigation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("io.github.triet4p.aegisf.s812evidence", context.packageName)
        val id = UUID.randomUUID().toString()
        val database = Room.databaseBuilder(context, LedgerDatabase::class.java, "s95_ui_${id.replace("-", "")}.db").build()
        try {
            val commands = WorkflowCommandService(database)
            val query = WorkflowQueryService(database)
            val (draftId, categoryId) = runBlocking {
                val category = database.categoryDao().insert(CategoryEntity(name = "Food", createdAtEpochMillis = 1, updatedAtEpochMillis = 1))
                suspend fun draft(key: String, unresolved: Boolean): Long = database.pendingBankDraftDao().store(
                    PendingBankDraftEntity(
                        deliveryId = "$id-$key", sourcePackage = "synthetic.fixture", notificationKey = null,
                        postedAtEpochMillis = 1_800_000_000_000L, bank = "Synthetic bank",
                        parseStatus = IntakeParseStatus.PARSED,
                        amountState = if (unresolved) IntakeFieldState.UNRESOLVED else IntakeFieldState.PRESENT,
                        amountVnd = if (unresolved) null else 20000, amountUnresolvedReason = null,
                        direction = IntakeDirection.DEBIT, momentState = IntakeFieldState.PRESENT,
                        statedDate = "2026-11-04", statedTime = "15:15", momentUnresolvedReason = null,
                        memoState = IntakeFieldState.PRESENT, memo = key, memoUnresolvedReason = null,
                        createdAtEpochMillis = if (unresolved) 1 else 2,
                    ),
                )
                draft("conflict", true)
                draft("snack", false) to category
            }
            compose.runOnUiThread {
                compose.activity.setContent {
                    BankDraftReviewScreen(query, commands, draftId = null, onSelectDraft = {}, onNavigateUp = {})
                }
            }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Category not chosen yet").fetchSemanticsNodes().size == 1 &&
                    compose.onAllNodesWithText("Needs attention: bank data needs attention").fetchSemanticsNodes().size == 1
            }
            val result = runBlocking {
                val draft = database.pendingBankDraftDao().findById(draftId)!!
                commands.selectDraftCategory(
                    WorkflowCallerScope.User,
                    SelectDraftCategoryRequest("$id-category", draftId, draft.reviewRevision, categoryId),
                )
            }
            assertTrue(result is WorkflowCommandOutcome.Applied)
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Category chosen — confirm or dismiss").fetchSemanticsNodes().size == 1 &&
                    compose.onAllNodesWithText("Category not chosen yet").fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithText("Needs attention: bank data needs attention").fetchSemanticsNodes().size == 1
            }
            runBlocking {
                assertEquals(IntakeReviewStatus.PENDING, database.pendingBankDraftDao().findById(draftId)!!.reviewStatus)
                assertTrue(database.transactionDao().findAll().isEmpty())
            }
        } finally {
            database.close()
        }
    }
}
