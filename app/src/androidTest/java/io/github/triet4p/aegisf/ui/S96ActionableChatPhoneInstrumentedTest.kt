package io.github.triet4p.aegisf.ui

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.verticalScroll
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.triet4p.aegisf.MainActivity
import io.github.triet4p.aegisf.agent.WorkflowRunOutcome
import io.github.triet4p.aegisf.agent.WorkflowRunState
import io.github.triet4p.aegisf.agent.WorkflowRunStep
import io.github.triet4p.aegisf.agent.WorkflowVerifiedEffect
import io.github.triet4p.aegisf.data.ContextLinkState
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.NoteEventInterpretation
import io.github.triet4p.aegisf.data.NoteEventTimeAnchor
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.ProposeLinkRequest
import io.github.triet4p.aegisf.data.SaveCaptureRequest
import io.github.triet4p.aegisf.data.UpdateEventRequest
import io.github.triet4p.aegisf.data.UserCaptureKind
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
/**
 * S9.6 actual phone Chat surface: the settled outcome renders live cards with source
 * excerpt/time interpretation, the actual stored operation state, and explicit actions.
 * Live attachment renders the stored draft line; explicit unlink restores the event's own
 * context with the draft pending and the ledger empty; reopening re-reads the live state.
 */
@RunWith(AndroidJUnit4::class)
class S96ActionableChatPhoneInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun chatOutcomeShowsLiveCardsAndUnlinkPersistsAcrossReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("io.github.triet4p.aegisf.s812evidence", context.packageName)
        val id = UUID.randomUUID().toString().replace("-", "")
        val database = Room.databaseBuilder(
            context,
            LedgerDatabase::class.java,
            "s96_chat_${id}.db",
        ).build()
        try {
            val commands = WorkflowCommandService(database)
            val query = WorkflowQueryService(database)
            val captureId = runBlocking {
                val stored = commands.saveCapture(
                    WorkflowCallerScope.User,
                    SaveCaptureRequest("s96-chat-$id", "Fuel 120,000 VND at 15:00", UserCaptureKind.QUICK_CAPTURE),
                )
                check(stored is WorkflowCommandOutcome.Applied) { "capture refused: $stored" }
                (stored.change as io.github.triet4p.aegisf.data.WorkflowChange.CaptureSaved).captureId
            }
            val draftId = runBlocking {
                database.pendingBankDraftDao().store(
                    PendingBankDraftEntity(
                        deliveryId = "s96-chat-$id",
                        sourcePackage = "synthetic.fixture",
                        notificationKey = null,
                        postedAtEpochMillis = 1_800_000_000_000L,
                        bank = "Synthetic bank",
                        parseStatus = IntakeParseStatus.PARSED,
                        amountState = IntakeFieldState.PRESENT,
                        amountVnd = 120000,
                        amountUnresolvedReason = null,
                        direction = IntakeDirection.DEBIT,
                        momentState = IntakeFieldState.PRESENT,
                        statedDate = "2026-11-04",
                        statedTime = "15:01",
                        momentUnresolvedReason = null,
                        memoState = IntakeFieldState.PRESENT,
                        memo = "Fuel station",
                        memoUnresolvedReason = null,
                        createdAtEpochMillis = 1,
                    ),
                )
            }
            val (eventId, linkId) = runBlocking {
                val stored = commands.updateEventInterpretation(
                    WorkflowCallerScope.User,
                    UpdateEventRequest(
                        idempotencyKey = "s96-chat-event-$id",
                        captureId = captureId,
                        expectedCaptureRevision = 0,
                        expectedEventRevision = null,
                        extractionVersion = "s96-chat/v1",
                        interpretation = NoteEventInterpretation(
                            spanStart = 0,
                            spanEnd = 4,
                            normalizedText = "Fuel",
                            amountVnd = 120000,
                            merchant = "Fuel station",
                            timeAnchor = NoteEventTimeAnchor(date = "2026-11-04", time = "15:00"),
                        ),
                    ),
                )
                check(stored is WorkflowCommandOutcome.Applied) { "event refused: $stored" }
                val event = (stored.change as io.github.triet4p.aegisf.data.WorkflowChange.EventStored).eventId
                val proposed = commands.proposeLink(
                    WorkflowCallerScope.User,
                    ProposeLinkRequest("s96-chat-propose-$id", event, draftId),
                )
                val linkId = query.linkOperations(
                    io.github.triet4p.aegisf.data.LinkOperationQuery(
                        eventId = event,
                        draftId = draftId,
                        bounds = io.github.triet4p.aegisf.data.QueryPageRequest(10, 0),
                    ),
                ).items.single().linkId
                event to linkId
            }
            val liveRevision = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                query.linkOperations(
                    io.github.triet4p.aegisf.data.LinkOperationQuery(
                        eventId = eventId,
                        draftId = draftId,
                        bounds = io.github.triet4p.aegisf.data.QueryPageRequest(10, 0),
                    ),
                ).items.single().revision
            }
            val storedLinkId = linkId
            val outcome = WorkflowRunOutcome.Completed(
                answer = "The fuel reading is stored and attached to its draft by arrival.",
                verifiedEffects = listOf(
                    WorkflowVerifiedEffect.EventReading(eventId, captureId, 0),
                    WorkflowVerifiedEffect.LinkState(
                        linkId = storedLinkId,
                        eventId = eventId,
                        draftId = draftId,
                        state = ContextLinkState.ACCEPTED,
                        revision = liveRevision,
                    ),
                ),
                turns = 2,
                toolCalls = 2,
                trace = listOf(WorkflowRunStep(WorkflowRunState.COMPLETED)),
            )
            compose.runOnUiThread {
                compose.activity.setContent {
                    androidx.compose.foundation.layout.Column(
                        modifier = androidx.compose.ui.Modifier.verticalScroll(
                            androidx.compose.foundation.rememberScrollState(),
                        ),
                    ) {
                        val state = ChatAgentRunState().apply { this.outcome = outcome }
                        ChatOutcomeSection(
                            outcome = outcome,
                            agentRunState = state,
                            agentRunScope = CoroutineScope(Dispatchers.Main),
                            queryService = query,
                            commandService = commands,
                            onRecordEvent = {},
                            onContextChanged = {},
                            onRetry = { _, _ -> },
                        )
                    }
                }
            }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Event #$eventId", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("pending · bank draft #$draftId", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Unlink draft #$draftId").performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("can be attached to another draft", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            runBlocking {
                val stored = database.contextLinkDao().findByPair(eventId, draftId)
                assertTrue(stored == null || stored.state != ContextLinkState.ACCEPTED)
                assertEquals(IntakeReviewStatus.PENDING, database.pendingBankDraftDao().findById(draftId)!!.reviewStatus)
                assertTrue(database.transactionDao().findAll().isEmpty())
            }
            compose.runOnUiThread {
                compose.activity.setContent {
                    androidx.compose.foundation.layout.Column(
                        modifier = androidx.compose.ui.Modifier.verticalScroll(
                            androidx.compose.foundation.rememberScrollState(),
                        ),
                    ) {
                        val state = ChatAgentRunState().apply { this.outcome = outcome }
                        ChatOutcomeSection(
                            outcome = outcome,
                            agentRunState = state,
                            agentRunScope = CoroutineScope(Dispatchers.Main),
                            queryService = query,
                            commandService = commands,
                            onRecordEvent = {},
                            onContextChanged = {},
                            onRetry = { _, _ -> },
                        )
                    }
                }
            }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Attached · event #$eventId", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun chatOutcomeAttachTapStoresAcceptedLinkWithoutLedgerWrites() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("io.github.triet4p.aegisf.s812evidence", context.packageName)
        val id = UUID.randomUUID().toString().replace("-", "")
        val database = Room.databaseBuilder(
            context,
            LedgerDatabase::class.java,
            "s96_attach_${id}.db",
        ).build()
        try {
            val commands = WorkflowCommandService(database)
            val query = WorkflowQueryService(database)
            val captureId = runBlocking {
                val stored = commands.saveCapture(
                    WorkflowCallerScope.User,
                    SaveCaptureRequest("s96-attach-$id", "Fuel 120,000 VND at 15:00", UserCaptureKind.QUICK_CAPTURE),
                )
                check(stored is WorkflowCommandOutcome.Applied) { "capture refused: $stored" }
                (stored.change as io.github.triet4p.aegisf.data.WorkflowChange.CaptureSaved).captureId
            }
            val draftId = runBlocking {
                database.pendingBankDraftDao().store(
                    PendingBankDraftEntity(
                        deliveryId = "s96-attach-$id",
                        sourcePackage = "synthetic.fixture",
                        notificationKey = null,
                        postedAtEpochMillis = 1_800_000_000_000L,
                        bank = "Synthetic bank",
                        parseStatus = IntakeParseStatus.PARSED,
                        amountState = IntakeFieldState.PRESENT,
                        amountVnd = 120000,
                        amountUnresolvedReason = null,
                        direction = IntakeDirection.DEBIT,
                        momentState = IntakeFieldState.PRESENT,
                        statedDate = "2026-11-04",
                        statedTime = "15:01",
                        momentUnresolvedReason = null,
                        memoState = IntakeFieldState.PRESENT,
                        memo = "Fuel station",
                        memoUnresolvedReason = null,
                        createdAtEpochMillis = 1,
                    ),
                )
            }
            runBlocking {
                database.pendingBankDraftDao().store(
                    PendingBankDraftEntity(
                        deliveryId = "s96-attach-decoy-$id",
                        sourcePackage = "synthetic.fixture",
                        notificationKey = null,
                        postedAtEpochMillis = 1_800_000_000_001L,
                        bank = "Synthetic bank",
                        parseStatus = IntakeParseStatus.PARSED,
                        amountState = IntakeFieldState.PRESENT,
                        amountVnd = 120000,
                        amountUnresolvedReason = null,
                        direction = IntakeDirection.DEBIT,
                        momentState = IntakeFieldState.PRESENT,
                        statedDate = "2026-11-04",
                        statedTime = "15:01",
                        momentUnresolvedReason = null,
                        memoState = IntakeFieldState.PRESENT,
                        memo = "Fuel station",
                        memoUnresolvedReason = null,
                        createdAtEpochMillis = 2,
                    ),
                )
            }
            val eventId = runBlocking {
                val stored = commands.updateEventInterpretation(
                    WorkflowCallerScope.User,
                    UpdateEventRequest(
                        idempotencyKey = "s96-attach-event-$id",
                        captureId = captureId,
                        expectedCaptureRevision = 0,
                        expectedEventRevision = null,
                        extractionVersion = "s96-chat/v1",
                        interpretation = NoteEventInterpretation(
                            spanStart = 0,
                            spanEnd = 4,
                            normalizedText = "Fuel",
                            amountVnd = 120000,
                            merchant = "Fuel station",
                            timeAnchor = NoteEventTimeAnchor(date = "2026-11-04", time = "15:00"),
                        ),
                    ),
                )
                check(stored is WorkflowCommandOutcome.Applied) { "event refused: $stored" }
                (stored.change as io.github.triet4p.aegisf.data.WorkflowChange.EventStored).eventId
            }
            runBlocking {
                val proposed = commands.proposeLink(
                    WorkflowCallerScope.User,
                    ProposeLinkRequest("s96-attach-propose-$id", eventId, draftId),
                )
                check(proposed is WorkflowCommandOutcome.Applied) { "propose refused: $proposed" }
            }
            val (liveRevision, liveLinkId) = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                val link = query.linkOperations(
                    io.github.triet4p.aegisf.data.LinkOperationQuery(
                        eventId = eventId,
                        draftId = draftId,
                        bounds = io.github.triet4p.aegisf.data.QueryPageRequest(10, 0),
                    ),
                ).items.single()
                link.revision to link.linkId
            }
            val outcome = WorkflowRunOutcome.Completed(
                answer = "The fuel reading is proposed to its draft.",
                verifiedEffects = listOf(
                    WorkflowVerifiedEffect.EventReading(eventId, captureId, 0),
                    WorkflowVerifiedEffect.LinkState(
                        linkId = liveLinkId,
                        eventId = eventId,
                        draftId = draftId,
                        state = ContextLinkState.PROPOSED,
                        revision = liveRevision,
                    ),
                ),
                turns = 2,
                toolCalls = 2,
                trace = listOf(WorkflowRunStep(WorkflowRunState.COMPLETED)),
            )
            compose.runOnUiThread {
                compose.activity.setContent {
                    androidx.compose.foundation.layout.Column(
                        modifier = androidx.compose.ui.Modifier.verticalScroll(
                            androidx.compose.foundation.rememberScrollState(),
                        ),
                    ) {
                        val state = ChatAgentRunState().apply { this.outcome = outcome }
                        ChatOutcomeSection(
                            outcome = outcome,
                            agentRunState = state,
                            agentRunScope = CoroutineScope(Dispatchers.Main),
                            queryService = query,
                            commandService = commands,
                            onRecordEvent = {},
                            onContextChanged = {},
                            onRetry = { _, _ -> },
                        )
                    }
                }
            }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Attach draft", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Attach draft").performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("attached. The bank amount and ledger are unchanged", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            runBlocking {
                val stored = database.contextLinkDao().findByPair(eventId, draftId)!!
                assertEquals(ContextLinkState.ACCEPTED, stored.state)
                assertEquals(IntakeReviewStatus.PENDING, database.pendingBankDraftDao().findById(draftId)!!.reviewStatus)
                assertTrue(database.transactionDao().findAll().isEmpty())
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun chatOutcomeRejectPersistsRefusalOnAmbiguousPair() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("io.github.triet4p.aegisf.s812evidence", context.packageName)
        val id = UUID.randomUUID().toString().replace("-", "")
        val database = Room.databaseBuilder(
            context,
            LedgerDatabase::class.java,
            "s96_reject_${id}.db",
        ).build()
        try {
            val commands = WorkflowCommandService(database)
            val query = WorkflowQueryService(database)
            val captureId = runBlocking {
                val stored = commands.saveCapture(
                    WorkflowCallerScope.User,
                    SaveCaptureRequest("s96-reject-$id", "Fuel 120,000 VND at 15:00", UserCaptureKind.QUICK_CAPTURE),
                )
                check(stored is WorkflowCommandOutcome.Applied) { "capture refused: $stored" }
                (stored.change as io.github.triet4p.aegisf.data.WorkflowChange.CaptureSaved).captureId
            }
            val draftId = runBlocking {
                database.pendingBankDraftDao().store(
                    PendingBankDraftEntity(
                        deliveryId = "s96-reject-a-$id",
                        sourcePackage = "synthetic.fixture",
                        notificationKey = null,
                        postedAtEpochMillis = 1_800_000_000_000L,
                        bank = "Synthetic bank",
                        parseStatus = IntakeParseStatus.PARSED,
                        amountState = IntakeFieldState.PRESENT,
                        amountVnd = 120000,
                        amountUnresolvedReason = null,
                        direction = IntakeDirection.DEBIT,
                        momentState = IntakeFieldState.PRESENT,
                        statedDate = "2026-11-04",
                        statedTime = "15:00",
                        momentUnresolvedReason = null,
                        memoState = IntakeFieldState.PRESENT,
                        memo = "Fuel station",
                        memoUnresolvedReason = null,
                        createdAtEpochMillis = 1,
                    ),
                )
            }
            runBlocking {
                database.pendingBankDraftDao().store(
                    PendingBankDraftEntity(
                        deliveryId = "s96-reject-b-$id",
                        sourcePackage = "synthetic.fixture",
                        notificationKey = null,
                        postedAtEpochMillis = 1_800_000_000_001L,
                        bank = "Synthetic bank",
                        parseStatus = IntakeParseStatus.PARSED,
                        amountState = IntakeFieldState.PRESENT,
                        amountVnd = 120000,
                        amountUnresolvedReason = null,
                        direction = IntakeDirection.DEBIT,
                        momentState = IntakeFieldState.PRESENT,
                        statedDate = "2026-11-04",
                        statedTime = "15:00",
                        momentUnresolvedReason = null,
                        memoState = IntakeFieldState.PRESENT,
                        memo = "Fuel station",
                        memoUnresolvedReason = null,
                        createdAtEpochMillis = 2,
                    ),
                )
            }
            val eventId = runBlocking {
                val stored = commands.updateEventInterpretation(
                    WorkflowCallerScope.User,
                    UpdateEventRequest(
                        idempotencyKey = "s96-reject-event-$id",
                        captureId = captureId,
                        expectedCaptureRevision = 0,
                        expectedEventRevision = null,
                        extractionVersion = "s96-chat/v1",
                        interpretation = NoteEventInterpretation(
                            spanStart = 0,
                            spanEnd = 4,
                            normalizedText = "Fuel",
                            amountVnd = 120000,
                            merchant = "Fuel station",
                            timeAnchor = NoteEventTimeAnchor(date = "2026-11-04", time = "15:00"),
                        ),
                    ),
                )
                check(stored is WorkflowCommandOutcome.Applied) { "event refused: $stored" }
                (stored.change as io.github.triet4p.aegisf.data.WorkflowChange.EventStored).eventId
            }
            runBlocking {
                val proposed = commands.proposeLink(
                    WorkflowCallerScope.User,
                    ProposeLinkRequest("s96-reject-propose-$id", eventId, draftId),
                )
                check(proposed is WorkflowCommandOutcome.Applied) { "propose refused: $proposed" }
            }
            val (linkRevision, linkId) = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                val link = query.linkOperations(
                    io.github.triet4p.aegisf.data.LinkOperationQuery(
                        eventId = eventId,
                        draftId = draftId,
                        bounds = io.github.triet4p.aegisf.data.QueryPageRequest(10, 0),
                    ),
                ).items.single()
                link.revision to link.linkId
            }
            val outcome = WorkflowRunOutcome.Completed(
                answer = "The fuel reading is proposed to one of two matching drafts.",
                verifiedEffects = listOf(
                    WorkflowVerifiedEffect.EventReading(eventId, captureId, 0),
                    WorkflowVerifiedEffect.LinkState(
                        linkId = linkId,
                        eventId = eventId,
                        draftId = draftId,
                        state = ContextLinkState.PROPOSED,
                        revision = linkRevision,
                    ),
                ),
                turns = 2,
                toolCalls = 3,
                trace = listOf(WorkflowRunStep(WorkflowRunState.COMPLETED)),
            )
            compose.runOnUiThread {
                compose.activity.setContent {
                    androidx.compose.foundation.layout.Column(
                        modifier = androidx.compose.ui.Modifier.verticalScroll(
                            androidx.compose.foundation.rememberScrollState(),
                        ),
                    ) {
                        val state = ChatAgentRunState().apply { this.outcome = outcome }
                        ChatOutcomeSection(
                            outcome = outcome,
                            agentRunState = state,
                            agentRunScope = CoroutineScope(Dispatchers.Main),
                            queryService = query,
                            commandService = commands,
                            onRecordEvent = {},
                            onContextChanged = {},
                            onRetry = { _, _ -> },
                        )
                    }
                }
            }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Reject", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Reject").performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("That decision is retained", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            runBlocking {
                val stored = database.contextLinkDao().findByPair(eventId, draftId)!!
                assertEquals(ContextLinkState.REJECTED, stored.state)
                assertEquals(IntakeReviewStatus.PENDING, database.pendingBankDraftDao().findById(draftId)!!.reviewStatus)
                assertTrue(database.transactionDao().findAll().isEmpty())
            }
        } finally {
            database.close()
        }
    }
}
