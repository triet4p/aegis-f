package io.github.triet4p.aegisf.ui

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.triet4p.aegisf.MainActivity
import io.github.triet4p.aegisf.data.AttachLinkRequest
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.NoteEventInterpretation
import io.github.triet4p.aegisf.data.NoteEventTimeAnchor
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.ProposeLinkRequest
import io.github.triet4p.aegisf.data.ReviseCaptureRequest
import io.github.triet4p.aegisf.data.ReviewCategoryOrigin
import io.github.triet4p.aegisf.data.SaveCaptureRequest
import io.github.triet4p.aegisf.data.SelectDraftCategoryRequest
import io.github.triet4p.aegisf.data.UpdateEventRequest
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowChange
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.data.categoryCatalogRevision
import io.github.triet4p.aegisf.ui.review.ReviewInboxScreen
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real review surface on an isolated phone database: a draft whose category was chosen by
 * the hosted workflow from its note context stops being presented as ready when that note is
 * edited, and the change is visible without a manual refresh. The database and the ledger are
 * asserted too, so the card is not mistaken for the stored state.
 */
@RunWith(AndroidJUnit4::class)
class S97ReviewRevalidationPhoneInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun hostedCategoryReopensWhenTheLinkedNoteChanges() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("io.github.triet4p.aegisf.s812evidence", context.packageName)
        val id = UUID.randomUUID().toString().replace("-", "")
        val database = Room.databaseBuilder(
            context.applicationContext,
            LedgerDatabase::class.java,
            "s97_review_$id.db",
        ).build()
        try {
            val commands = WorkflowCommandService(database, timezoneId = { "Asia/Ho_Chi_Minh" })
            val queries = WorkflowQueryService(database)
            val captureId = runBlocking { storeLinkedFixture(commands, database, id) }
            val categoryId = runBlocking {
                database.categoryDao().insert(
                    CategoryEntity(
                        name = "Fuel",
                        createdAtEpochMillis = System.currentTimeMillis(),
                        updatedAtEpochMillis = System.currentTimeMillis(),
                    ),
                )
            }
            val draftId = runBlocking {
                database.contextLinkDao().findForEvent(
                    database.noteEventDao().findForCapture(captureId).single().id,
                ).single().draftId
            }
            val captureRevision = runBlocking {
                database.captureMessageDao().findById(captureId)!!.revision
            }
            runBlocking {
                val draft = database.pendingBankDraftDao().findById(draftId)!!
                val hosted = commands.selectDraftCategory(
                    WorkflowCallerScope.AgentSession(
                        "s97-review-$id-hosted",
                        setOf(captureId),
                        setOf(draftId),
                    ),
                    SelectDraftCategoryRequest(
                        "s97-review-$id-hosted-pick",
                        draftId,
                        draft.reviewRevision,
                        categoryId,
                        queries.draftContextSnapshot(draftId).second,
                        categoryCatalogRevision(queries.categoryCatalog()),
                    ),
                )
                assertTrue("hosted category was refused: $hosted", hosted is WorkflowCommandOutcome.Applied)
            }

            compose.runOnUiThread {
                compose.activity.setContent {
                    ReviewInboxScreen(queryService = queries, onOpenDraft = {})
                }
            }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Category: Fuel")
                    .fetchSemanticsNodes().isNotEmpty()
            }

            val outcome = runBlocking {
                commands.reviseCapture(
                    WorkflowCallerScope.User,
                    ReviseCaptureRequest(
                        "s97-review-$id-edit",
                        captureId,
                        captureRevision,
                        "S97 edited note for review revalidation",
                    ),
                )
            }
            assertTrue("the note edit was refused: $outcome", outcome is WorkflowCommandOutcome.Applied)

            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Category: not chosen")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            runBlocking {
                val draft = database.pendingBankDraftDao().findById(draftId)!!
                assertNull(draft.reviewCategoryId)
                assertFalse(draft.reviewCategoryResolved)
                assertEquals(ReviewCategoryOrigin.NONE, draft.reviewCategoryOrigin)
                assertEquals(IntakeReviewStatus.PENDING, draft.reviewStatus)
                assertTrue(database.transactionDao().findAll().isEmpty())
            }
        } finally {
            database.close()
        }
    }

    private suspend fun storeLinkedFixture(
        commands: WorkflowCommandService,
        database: LedgerDatabase,
        id: String,
    ): Long {
        val body = "S97 review fixture fuel 50000 VND on 2026-11-05 at 15:00"
        val saved = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest("s97-review-$id-capture", body),
        ) as WorkflowCommandOutcome.Applied
        val captureId = (saved.change as WorkflowChange.CaptureSaved).captureId
        val stored = commands.updateEventInterpretation(
            WorkflowCallerScope.User,
            UpdateEventRequest(
                idempotencyKey = "s97-review-$id-event",
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = NoteEventInterpretation(
                    spanStart = 0,
                    spanEnd = body.length,
                    normalizedText = "S97 review fuel purchase",
                    amountVnd = 50_000L,
                    merchant = "Synthetic merchant",
                    timeAnchor = NoteEventTimeAnchor(
                        date = "2026-11-05",
                        time = "15:00",
                        timezoneId = "Asia/Ho_Chi_Minh",
                    ),
                ),
                extractionVersion = "s97-review-phone-fixture",
            ),
        ) as WorkflowCommandOutcome.Applied
        val eventId = (stored.change as WorkflowChange.EventStored).eventId
        val draftId = database.pendingBankDraftDao().store(
            PendingBankDraftEntity(
                deliveryId = "s97-review-$id-draft",
                sourcePackage = "synthetic.fixture",
                notificationKey = null,
                postedAtEpochMillis = System.currentTimeMillis(),
                bank = "Synthetic bank",
                parseStatus = IntakeParseStatus.PARSED,
                amountState = IntakeFieldState.PRESENT,
                amountVnd = 57_000L,
                amountUnresolvedReason = null,
                direction = IntakeDirection.DEBIT,
                momentState = IntakeFieldState.PRESENT,
                statedDate = "2026-11-05",
                statedTime = "15:00",
                momentUnresolvedReason = null,
                memoState = IntakeFieldState.PRESENT,
                memo = "S97 synthetic purchase",
                memoUnresolvedReason = null,
                createdAtEpochMillis = System.currentTimeMillis(),
            ),
        )
        val proposed = commands.proposeLink(
            WorkflowCallerScope.User,
            ProposeLinkRequest("s97-review-$id-proposal", eventId, draftId),
        ) as WorkflowCommandOutcome.Applied
        val revision = (proposed.change as WorkflowChange.LinkDecided).revision
        val attached = commands.attachLink(
            WorkflowCallerScope.User,
            AttachLinkRequest("s97-review-$id-attach", eventId, draftId, revision),
        )
        check(attached is WorkflowCommandOutcome.Applied) { "attachment refused: $attached" }
        return captureId
    }
}
