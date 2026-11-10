package io.github.triet4p.aegisf.ui

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.triet4p.aegisf.AegisApplication
import io.github.triet4p.aegisf.MainActivity
import io.github.triet4p.aegisf.agent.AgentRunHistory
import io.github.triet4p.aegisf.agent.AgentToolDeclarations
import io.github.triet4p.aegisf.agent.WorkflowRunHost
import io.github.triet4p.aegisf.agent.WorkflowRunOutcome
import io.github.triet4p.aegisf.agent.WorkflowRunState
import io.github.triet4p.aegisf.agent.WorkflowRunStep
import io.github.triet4p.aegisf.agent.WorkflowOperationKeySource
import io.github.triet4p.aegisf.agent.WorkflowSkillCatalog
import io.github.triet4p.aegisf.agent.WorkflowVerifiedEffect
import io.github.triet4p.aegisf.data.AegisLanguage
import io.github.triet4p.aegisf.data.ContextLinkState
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.LinkOperationQuery
import io.github.triet4p.aegisf.data.NoteEventInterpretation
import io.github.triet4p.aegisf.data.NoteEventTimeAnchor
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.ProposeLinkRequest
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.SaveCaptureRequest
import io.github.triet4p.aegisf.data.UpdateEventRequest
import io.github.triet4p.aegisf.data.UserCaptureKind
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowChange
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.privacy.MaskedHostedGateway
import io.github.triet4p.aegisf.privacy.MaskedPromptBoundary
import io.github.triet4p.aegisf.privacy.SensitiveVault
import io.github.triet4p.aegisf.provider.HostedConversation
import io.github.triet4p.aegisf.provider.HostedGenerationAdapter
import io.github.triet4p.aegisf.provider.HostedAdapters
import io.github.triet4p.aegisf.provider.HostedGenerationResult
import io.github.triet4p.aegisf.provider.HostedProviderCapabilities
import io.github.triet4p.aegisf.provider.HostedProviderConfig
import io.github.triet4p.aegisf.provider.HostedProviderFailure
import io.github.triet4p.aegisf.provider.HostedProviderPresets
import io.github.triet4p.aegisf.provider.HostedProviderSettingsStore
import io.github.triet4p.aegisf.provider.HostedProtocol
import io.github.triet4p.aegisf.provider.HostedResponsePrivacyGuard
import io.github.triet4p.aegisf.provider.HostedToolCall
import io.github.triet4p.aegisf.provider.HostedToolDeclaration
import io.github.triet4p.aegisf.provider.HostedToolResult
import io.github.triet4p.aegisf.provider.HostedTurn
import io.github.triet4p.aegisf.provider.HostedTurnFinish
import io.github.triet4p.aegisf.provider.HostedTurnProgress
import io.github.triet4p.aegisf.provider.SavedHostedProvider
import io.github.triet4p.aegisf.provider.ToolCallDisposition
import io.github.triet4p.aegisf.provider.UrlConnectionHttpTransport
import io.github.triet4p.aegisf.ui.chat.AiChatScreen
import io.github.triet4p.aegisf.ui.theme.AegisTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.room.Room

/**
 * S10.5 actual Compose Chat evidence on isolated fixtures.
 *
 * Most tests use a scripted provider seam: a local adapter emits visible chunks with real delays
 * so Stop, late-chunk suppression, the next message and the history reload are exercised through
 * the screen's own state and the production executor/history. Each test owns an in-memory
 * database; no production row is read or written.
 *
 * The opt-in `s105_real` stage is not scripted: it sends one synthetic prompt to the
 * owner-authorized DeepSeek route through the production adapter, masking gateway, executor and
 * history, over an emptied caller scope. It runs only in the isolated evidence package
 * (`io.github.triet4p.aegisf.s812evidence`) so no owner note, draft or profile can enter the
 * request, and its credential arrives in a pushed file that is deleted immediately.
 */
@RunWith(AndroidJUnit4::class)
class S105ChatInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var database: LedgerDatabase
    private lateinit var commands: WorkflowCommandService
    private lateinit var queries: WorkflowQueryService
    private lateinit var history: AgentRunHistory
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun progressiveStreamingStopKeepsPartialTextAndAllowsTheNextMessage() {
        prepare()
        val adapter = ScriptedStreamingAdapter(
            scripts = listOf(
                StreamScript(listOf("First part. ", "Second part. ", "Third part."), delayMillis = 2_500),
                StreamScript(listOf("Second reply."), delayMillis = 0),
            ),
        )
        val runState = ChatAgentRunState()
        startScreen(runState, adapter)

        typePrompt("Explain how a budget works")
        compose.onNodeWithTag("chat-send").assertIsEnabled().performClick()
        waitForAssistantText("First part", timeoutMillis = 20_000)
        screenshot("510-chat-streaming-en")
        emit("510 streamingText=${assistantText()}")

        compose.onNodeWithTag("chat-stop").performClick()
        val stoppedText = assistantText()
        assertTrue("the received partial text was lost", stoppedText.contains("First part"))
        assertTrue("the stopped state was not shown", shownText().contains("Stopped"))
        screenshot("511-chat-stopped-en")
        emit("511 stoppedText=$stoppedText")

        Thread.sleep(3_200)
        val afterWait = assistantText()
        assertEquals("a late chunk was appended after Stop", stoppedText, afterWait)
        emit("511 lateChunks=0 afterWait=$afterWait")

        typePrompt("Second question")
        compose.onNodeWithTag("chat-send").assertIsEnabled().performClick()
        waitForAssistantText("Second reply", timeoutMillis = 20_000)
        screenshot("512-chat-next-message-en")
        emit("512 nextMessage=${assistantText()}")

        val runs = runBlocking { database.agentRunDao().findRecent(10) }
        assertEquals("the stop and next message created unexpected runs", 2, runs.size)
        assertTrue("the stopped run was not durably cancelled", runs.any { it.state == "CANCELLED" })
        assertTrue("the next message did not complete", runs.any { it.state == "SUCCEEDED" })
        val userLines = runBlocking {
            runs.sumOf { history.conversationForRun(it.runId).count { line -> line.role == "USER" } }
        }
        assertEquals("a repeated user line was stored", 2, userLines)
    }

    @Test
    fun historyLoadsAStoredTranscriptOnceAndReportsItsStatus() {
        prepare()
        val adapter = ScriptedStreamingAdapter(
            scripts = listOf(StreamScript(listOf("Answer one."), delayMillis = 0)),
        )
        val runState = ChatAgentRunState()
        startScreen(runState, adapter)

        typePrompt("My stored question")
        compose.onNodeWithTag("chat-send").performClick()
        waitForAssistantText("Answer one", timeoutMillis = 20_000)
        compose.onNodeWithContentDescription("History").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Conversation history").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("My stored question").fetchSemanticsNodes().isNotEmpty()
        }
        // Let a transient system heads-up notification clear before the full-screen capture;
        // the evidence must not contain unrelated personal notification content.
        Thread.sleep(3_000)
        screenshot("513-chat-history-sheet-en")
        emit("513 historyRows=${shownText()}")

        compose.onNodeWithContentDescription("My stored question. Completed").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Saved conversation").fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("514-chat-history-loaded-en")
        val userCopies = compose.onAllNodesWithText("My stored question").fetchSemanticsNodes().size
        assertEquals("history load duplicated the stored user turn", 1, userCopies)
        assertTrue(shownText().contains("Completed"))
        emit("514 userCopies=$userCopies text=${shownText()}")
    }

    @Test
    fun streamingDoesNotYankTheReaderAndTheJumpActionReturnsToLatest() {
        prepare()
        val longFirst = "A".repeat(1_500)
        val adapter = ScriptedStreamingAdapter(
            scripts = listOf(
                StreamScript(listOf(longFirst, "B".repeat(400)), delayMillis = 2_500),
            ),
        )
        val runState = ChatAgentRunState()
        startScreen(runState, adapter)

        typePrompt("Give me a long answer")
        compose.onNodeWithTag("chat-send").performClick()
        waitForAssistantText("A".repeat(200), timeoutMillis = 20_000)
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("chat-jump-latest").fetchSemanticsNodes().isEmpty()
        }
        repeat(3) {
            compose.onNodeWithTag("chat-transcript").performTouchInput { swipeDown() }
            compose.waitForIdle()
        }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("chat-jump-latest").fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("520-chat-scrolled-up-en")
        val beforeSecondChunk = assistantText()
        compose.waitUntil(20_000) { assistantText() != beforeSecondChunk }
        assertTrue(
            "a new chunk pulled the reader back to the bottom",
            compose.onAllNodesWithTag("chat-jump-latest").fetchSemanticsNodes().isNotEmpty(),
        )
        screenshot("521-chat-still-scrolled-up-en")
        compose.onNodeWithTag("chat-jump-latest").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("chat-jump-latest").fetchSemanticsNodes().isEmpty()
        }
        screenshot("522-chat-jumped-latest-en")
        emit("520-522 bottomOnly=true")
    }

    @Test
    fun providerFailureShowsGuidanceAndDoesNotOfferAnUnlimitedRetry() {
        prepare()
        val adapter = ScriptedStreamingAdapter(
            scripts = List(6) {
                StreamScript(
                    chunks = emptyList(),
                    delayMillis = 0,
                    failure = HostedProviderFailure.Transport("synthetic transport failure"),
                )
            },
        )
        val runState = ChatAgentRunState()
        startScreen(runState, adapter)

        typePrompt("A question while the provider is unavailable")
        compose.onNodeWithTag("chat-send").performClick()
        compose.waitUntil(60_000) {
            compose.onAllNodesWithText("The provider couldn't be reached", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("524-chat-provider-failure-en")
        emit("524 failureShown=true")
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Retry").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Retry").performClick()
        compose.waitUntil(60_000) {
            compose.onAllNodesWithText("can't be retried automatically", substring = true)
                .fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("stored outcome is uncertain", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("525-chat-retry-limit-en")
        emit("525 retryLimit=true")
    }

    @Test
    fun durableHistoryReadRestoresTheStoredConversationWithoutDuplication() {
        prepare()
        val adapter = ScriptedStreamingAdapter(
            scripts = listOf(StreamScript(listOf("Durable answer."), delayMillis = 0)),
        )
        val firstState = ChatAgentRunState()
        startScreen(firstState, adapter)
        typePrompt("A durable question")
        compose.onNodeWithTag("chat-send").performClick()
        waitForAssistantText("Durable answer", timeoutMillis = 20_000)

        // This is the re-entry read a fresh screen performs: the durable row and its visible
        // conversation are read again, with no transient run state and no resume attempt.
        val latest = runBlocking { history.recentRuns(1).single() }
        val lines = runBlocking { history.conversationForRun(latest.runId) }
        val freshState = ChatAgentRunState()
        freshState.restoreConversation(
            run = latest,
            lines = lines,
            interruptedLine = "Interrupted.",
            clarificationExpiredLine = "Expired.",
        )

        assertTrue(freshState.messages.any { it is ChatTranscriptEntry.User })
        assertTrue(
            freshState.messages.any { entry ->
                entry is ChatTranscriptEntry.Assistant && entry.text == "Durable answer."
            },
        )
        assertFalse("the re-read state started a live run", freshState.busy)
        assertEquals("the re-read state duplicated a turn", 2, freshState.messages.size)
        assertEquals("SUCCEEDED", freshState.restoredRunState)
        assertEquals(1, runBlocking { database.agentRunDao().findRecent(10) }.size)
        emit("523 durableRestore=true messages=${freshState.messages.size}")
    }

    @Test
    fun outcomeCardsAttachRejectAndUnlinkOnTheCurrentChatScreen() {
        prepare()
        val eventOne = seedEvent(
            text = "Fuel 120,000 VND at 15:00",
            excerpt = "Fuel",
            amount = 120_000,
            merchant = "Fuel station",
            date = "2026-11-04",
            time = "15:00",
        )
        val eventTwo = seedEvent(
            text = "Coffee 45,000 VND at 09:00",
            excerpt = "Coffee",
            amount = 45_000,
            merchant = "Corner cafe",
            date = "2026-11-04",
            time = "09:00",
        )
        val draftOne = seedDraft(120_000, "Fuel station")
        val draftTwo = seedDraft(45_000, "Corner cafe")
        val linkOne = propose(eventOne, draftOne)
        val linkTwo = propose(eventTwo, draftTwo)

        // A concurrent device edit advanced link one after the run reported revision zero.
        runBlocking(Dispatchers.IO) {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE context_links SET revision = revision + 1 WHERE id = $linkOne",
            )
        }
        val outcome = WorkflowRunOutcome.Completed(
            answer = "Two drafts are waiting; attach or reject each one explicitly.",
            verifiedEffects = listOf(
                WorkflowVerifiedEffect.LinkState(
                    linkId = linkOne,
                    eventId = eventOne,
                    draftId = draftOne,
                    state = ContextLinkState.PROPOSED,
                    revision = 0,
                ),
                WorkflowVerifiedEffect.LinkState(
                    linkId = linkTwo,
                    eventId = eventTwo,
                    draftId = draftTwo,
                    state = ContextLinkState.PROPOSED,
                    revision = 0,
                ),
            ),
            turns = 2,
            toolCalls = 2,
            trace = listOf(WorkflowRunStep(WorkflowRunState.COMPLETED)),
        )
        val runState = ChatAgentRunState().apply { this.outcome = outcome }
        startScreen(runState, ScriptedStreamingAdapter(emptyList()))

        compose.onNodeWithTag("chat-transcript")
            .performScrollToNode(hasTestTag("chat-link-$linkOne-attach"))
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Changed since this run", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("540-chat-outcome-links-en")
        emit("540 competingLinks=2 staleRevisionShown=true")

        compose.onNodeWithTag("chat-link-$linkOne-attach").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Draft #$draftOne attached", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        val attached = runBlocking { database.contextLinkDao().findByPair(eventOne, draftOne) }
        val stillProposed = runBlocking { database.contextLinkDao().findByPair(eventTwo, draftTwo) }
        assertEquals(ContextLinkState.ACCEPTED, attached?.state)
        assertEquals(ContextLinkState.PROPOSED, stillProposed?.state)
        assertTrue("attaching a note link changed the ledger", runBlocking {
            database.transactionDao().findAll().isEmpty()
        })
        screenshot("541-chat-link-attached-en")
        emit("541 attached=$draftOne only=true ledger=unchanged")

        compose.onNodeWithTag("chat-transcript")
            .performScrollToNode(hasTestTag("chat-link-$linkTwo-reject"))
        compose.onNodeWithTag("chat-link-$linkTwo-reject").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("rejected. That decision is retained", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(
            ContextLinkState.REJECTED,
            runBlocking { database.contextLinkDao().findByPair(eventTwo, draftTwo) }?.state,
        )
        screenshot("542-chat-link-rejected-en")
        emit("542 rejected=$draftTwo retained=true")

        compose.onNodeWithTag("chat-transcript")
            .performScrollToNode(hasTestTag("chat-link-$linkOne-unlink"))
        compose.onNodeWithTag("chat-link-$linkOne-unlink").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Draft unlinked", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "unlink did not remove the active association",
            runBlocking { database.contextLinkDao().findByPair(eventOne, draftOne) } == null,
        )
        assertEquals(
            IntakeReviewStatus.PENDING,
            runBlocking { database.pendingBankDraftDao().findById(draftOne) }?.reviewStatus,
        )
        assertTrue("unlink changed the ledger", runBlocking {
            database.transactionDao().findAll().isEmpty()
        })
        screenshot("543-chat-link-unlinked-en")
        emit("543 unlinked=$draftOne ledger=unchanged")
    }

    @Test
    fun staleLinkRevisionIsRefusedAndTheCardReloadsTheStoredState() {
        prepare()
        val event = seedEvent(
            text = "Fuel 120,000 VND at 15:00",
            excerpt = "Fuel",
            amount = 120_000,
            merchant = "Fuel station",
            date = "2026-11-04",
            time = "15:00",
        )
        val draft = seedDraft(120_000, "Fuel station")
        val link = propose(event, draft)
        val outcome = WorkflowRunOutcome.Completed(
            answer = "One draft is waiting.",
            verifiedEffects = listOf(
                WorkflowVerifiedEffect.LinkState(
                    linkId = link,
                    eventId = event,
                    draftId = draft,
                    state = ContextLinkState.PROPOSED,
                    revision = 0,
                ),
            ),
            turns = 1,
            toolCalls = 1,
            trace = listOf(WorkflowRunStep(WorkflowRunState.COMPLETED)),
        )
        val runState = ChatAgentRunState().apply { this.outcome = outcome }
        startScreen(runState, ScriptedStreamingAdapter(emptyList()))
        compose.onNodeWithTag("chat-transcript")
            .performScrollToNode(hasTestTag("chat-link-$link-reject"))
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Reject").fetchSemanticsNodes().isNotEmpty()
        }

        // The stored link changes after this card read it; the decision still carries the
        // revision it saw, so the application must refuse rather than overwrite the newer state.
        runBlocking(Dispatchers.IO) {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE context_links SET revision = revision + 1 WHERE id = $link",
            )
        }
        compose.onNodeWithTag("chat-link-$link-reject").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Conflict:", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        val stored = runBlocking { database.contextLinkDao().findByPair(event, draft) }
        assertEquals(ContextLinkState.PROPOSED, stored?.state)
        // The concurrent bump was the only write: the refused decision never advanced revision.
        assertEquals(1, stored?.revision)
        screenshot("544-chat-link-stale-refused-en")
        emit("544 staleRefused=true state=${stored?.state} revision=${stored?.revision}")
    }

    @Test
    fun outcomeCardCopyIsBilingualOnTheCurrentChatScreen() {
        prepare()
        val event = seedEvent(
            text = "Coffee 45,000 VND at 09:00",
            excerpt = "Coffee",
            amount = 45_000,
            merchant = "Corner cafe",
            date = "2026-11-04",
            time = "09:00",
        )
        val draft = seedDraft(45_000, "Corner cafe")
        val link = propose(event, draft)
        val outcome = WorkflowRunOutcome.Completed(
            answer = "One draft is waiting.",
            verifiedEffects = listOf(
                WorkflowVerifiedEffect.LinkState(
                    linkId = link,
                    eventId = event,
                    draftId = draft,
                    state = ContextLinkState.PROPOSED,
                    revision = 0,
                ),
            ),
            turns = 1,
            toolCalls = 1,
            trace = listOf(WorkflowRunStep(WorkflowRunState.COMPLETED)),
        )
        val runState = ChatAgentRunState().apply { this.outcome = outcome }
        startScreen(runState, ScriptedStreamingAdapter(emptyList()), AegisLanguage.ENGLISH)
        compose.onNodeWithTag("chat-transcript")
            .performScrollToNode(hasTestTag("chat-link-$link-attach"))
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Attach draft").fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("545-chat-outcome-cards-en")
        emit("545 englishAttach=true")

        startScreen(runState, ScriptedStreamingAdapter(emptyList()), AegisLanguage.VIETNAMESE)
        compose.onNodeWithTag("chat-transcript")
            .performScrollToNode(hasTestTag("chat-link-$link-attach"))
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Đính kèm bản nháp").fetchSemanticsNodes().isNotEmpty()
        }
        val vietnamese = shownText()
        assertTrue("the link heading stayed English", vietnamese.contains("Đề xuất"))
        assertTrue("the reject action stayed English", vietnamese.contains("Từ chối"))
        assertFalse(
            "the outcome card still shows English action copy",
            vietnamese.contains("Attach draft") || vietnamese.contains("Suggested"),
        )
        screenshot("546-chat-outcome-cards-vi")
        emit("546 vietnameseOutcome=true")
    }

    @Test
    fun scriptedClarificationRunsThroughTheCurrentChatScreenAndContinues() {
        prepare()
        seedDraft(45_000, "Corner cafe")
        val runState = ChatAgentRunState()
        startScreen(runState, ClarifyingScriptedAdapter())

        typePrompt("Which saved draft should this note use?")
        compose.onNodeWithTag("chat-send").assertIsEnabled().performClick()
        compose.waitUntil(30_000) {
            runState.outcome is WorkflowRunOutcome.Clarifying
        }
        emit("547 scriptedOutcome=${runState.outcome?.javaClass?.simpleName}")
        compose.onNodeWithTag("chat-transcript")
            .performScrollToNode(hasTestTag("chat-clarification"))
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("chat-clarification").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(shownText().contains("Which saved draft did you mean?"))
        // The clarification card is the one primary action while the run waits for an answer.
        compose.onNodeWithTag("chat-composer").performTextInput("Another question")
        compose.onNodeWithTag("chat-send").assertIsNotEnabled()
        compose.onNodeWithTag("chat-composer").performTextClearance()
        screenshot("547-chat-clarification-ask-en")
        emit("547 clarificationShown=true composerBlocked=true")

        compose.onNodeWithTag("chat-transcript")
            .performScrollToNode(hasTestTag("chat-clarification-answer"))
        compose.onNodeWithTag("chat-clarification-answer").performTextInput("The cafe draft")
        compose.onNodeWithTag("chat-transcript").performScrollToNode(hasText("Continue"))
        compose.onNodeWithText("Continue").performClick()
        compose.waitUntil(30_000) {
            runState.outcome is WorkflowRunOutcome.Completed
        }
        compose.waitUntil(20_000) {
            assistantText().contains("Thanks, I noted the draft")
        }
        val runs = runBlocking { database.agentRunDao().findRecent(10) }
        assertEquals("the clarification created an extra run", 1, runs.size)
        assertEquals("SUCCEEDED", runs.single().state)
        screenshot("548-chat-clarification-answered-en")
        emit("548 clarificationContinued=true runs=${runs.size}")
    }

    /**
     * Injects one authorized DeepSeek route into the isolated evidence package.
     *
     * The owner-supplied credential is read from `files/s105_secret.env`, which is pushed over
     * `run-as`, never echoed, and deleted in `finally`. It is written through the production
     * encrypted store and activated; the emitted line names only the profile identity, protocol,
     * endpoint host, model and equality booleans, never the credential or its length.
     *
     * Guarded to the isolated evidence package: a run against the owner's package cannot rewrite
     * the owner's saved provider profiles.
     */
    @Test
    fun provisionRealProviderFromInjectedSecret() {
        org.junit.Assume.assumeTrue(
            "real-provider provisioning was not requested",
            InstrumentationRegistry.getArguments().getString("s105_provision") == "yes",
        )
        assertEquals(ISOLATED_PACKAGE, context.packageName)
        val secretFile = File(context.filesDir, SECRET_FILE)
        require(secretFile.isFile) { "no injected secret file ${secretFile.name}" }
        val values = secretFile.readLines().mapNotNull { line ->
            val index = line.indexOf('=')
            if (index <= 0) null else line.substring(0, index) to line.substring(index + 1).trim()
        }.toMap()
        val keyName = InstrumentationRegistry.getArguments().getString("s105_key")
            ?: "OPENCODE_GO_API_KEY"
        val credential = requireNotNull(values[keyName]) { "$keyName missing from the injected file" }
        require(credential.isNotBlank()) { "the injected credential is blank" }
        try {
            val store = HostedProviderSettingsStore(context)
            val profiles = store.load().filterNot { it.id == REAL_PROFILE_ID }
            val preset = HostedProviderPresets.byId(REAL_PRESET_ID)
            val profile = preset.profile(
                name = "S10.5 authorized DeepSeek",
                credential = credential,
            ).copy(id = REAL_PROFILE_ID)
            store.save(profiles + profile)
            store.activate(REAL_PROFILE_ID)
            val reloaded = requireNotNull(store.active()) { "the injected profile is not active" }
            emit(
                "S105CONFIG provisioned id=${reloaded.id} protocol=${reloaded.protocol} " +
                    "model=${reloaded.model} host=${java.net.URI(reloaded.endpoint).host} " +
                    "credentialNonBlank=${reloaded.credential.isNotBlank()} " +
                    "credentialMatch=${reloaded.credential == credential}",
            )
        } finally {
            secretFile.delete()
            emit("S105CONFIG secretDeleted=${!secretFile.exists()}")
        }
    }

    /** Removes the injected profile from the isolated package and clears a stale active marker. */
    @Test
    fun removeInjectedProviderAfterVerification() {
        org.junit.Assume.assumeTrue(
            "real-provider cleanup was not requested",
            InstrumentationRegistry.getArguments().getString("s105_cleanup") == "yes",
        )
        assertEquals(ISOLATED_PACKAGE, context.packageName)
        val store = HostedProviderSettingsStore(context)
        if (store.load().any { it.id == REAL_PROFILE_ID }) {
            store.delete(REAL_PROFILE_ID)
        } else {
            store.deleteAll()
        }
        emit(
            "S105CONFIG cleaned remaining=${store.load().size} " +
                "active=${runCatching { store.active() }.getOrNull()?.id}",
        )
    }

    /**
     * The real stream over a durable, isolated file database that the next process can reopen.
     *
     * The production adapter, gateway, executor and history run for real; the session scope is
     * emptied and the database is the dedicated `s105_durable.db` file, not the app's ledger.
     * The synthetic prompt is the only business content that can cross the boundary. Progress is
     * sampled through the screen's own state; the received partial text, Stop and the next
     * message are exercised exactly as the production screen does.
     */
    @Test
    fun realProviderStreamsProgressivelyStopsAndAcceptsTheNextMessageInADurableFileDatabase() {
        org.junit.Assume.assumeTrue(
            "durable real-provider walkthrough was not requested",
            InstrumentationRegistry.getArguments().getString("s105_realdurable") == "yes",
        )
        assertEquals(ISOLATED_PACKAGE, context.packageName)
        val container = (context.applicationContext as AegisApplication).container
        requireAuthorizedProvider(container.hostedProviderSettingsStore)
        context.deleteDatabase(DURABLE_DB)
        val durable = Room.databaseBuilder(context, LedgerDatabase::class.java, DURABLE_DB).build()
        val durableCommands = WorkflowCommandService(durable)
        val durableQueries = WorkflowQueryService(durable)
        val durableHistory = AgentRunHistory(durable)
        val runState = ChatAgentRunState()
        val streamedChunks = java.util.concurrent.atomic.AtomicInteger(0)
        try {
            setChatContent {
                AegisTheme(language = io.github.triet4p.aegisf.data.AegisLanguage.ENGLISH) {
                    val scope = rememberCoroutineScope()
                    AiChatScreen(
                        queryService = durableQueries,
                        commandService = durableCommands,
                        activeProvider = container.hostedProviderSettingsStore::active,
                        networkAvailable = true,
                        createHostedWorkflowHost = { session, config ->
                            realHost(
                                session,
                                config,
                                durableCommands,
                                durableQueries,
                                streamedChunks,
                            )
                        },
                        agentRunHistory = durableHistory,
                        agentRunState = remember { runState },
                        agentRunScope = scope,
                        onOpenProviderSettings = {},
                        onRecordEvent = {},
                    )
                }
            }
            compose.waitUntil(20_000) {
                compose.onAllNodesWithTag("chat-composer").fetchSemanticsNodes().isNotEmpty()
            }

            typePrompt(LIVE_PROMPT)
            val sendBounds = compose.onNodeWithTag("chat-send").fetchSemanticsNode().boundsInWindow
            awaitSendEnabled()
            compose.onNodeWithTag("chat-send").performClick()

            // The provider transport counts its real SSE chunks, so the test knows text is
            // arriving without a semantics read that waits for UI idleness. Two live frames
            // captured mid-stream show the answer growing; Stop is then pressed while the
            // provider still has content to send.
            awaitStreamedChunks(streamedChunks, 5)
            val frameA = requireNotNull(capturePng()) { "the device returned no live frame" }
            writePng("620-durable-streaming-a", frameA)
            awaitStreamedChunks(streamedChunks, 30)
            val frameB = requireNotNull(capturePng()) { "the device returned no live frame" }
            writePng("620-durable-streaming-b", frameB)
            assertFalse("the two live frames are identical", frameA.contentEquals(frameB))
            emit("620 durableChunks=${streamedChunks.get()}")
            // Confirm more content is still arriving (and keep the UI pumped) before Stop.
            awaitStreamedChunks(streamedChunks, 45)
            tapAt(sendBounds.center.x, sendBounds.center.y)
            compose.waitUntil(60_000) { shownText().contains("Stopped") }
            val stopped = assistantVisibleText()
            assertTrue("the durable exchange lost the received partial text", stopped.length >= 24)
            screenshot("621-durable-stopped")
            Thread.sleep(4_000)
            assertEquals(
                "a late chunk appeared after Stop in the durable exchange",
                stopped,
                assistantVisibleText(),
            )
            emit("621 durableStopLength=${stopped.length} noLateChunk=true")

            // Reveal the received answer from its own beginning so the rendered headings,
            // emphasis and rule can be inspected in the actual surface, not only its tail.
            repeat(3) {
                compose.onNodeWithTag("chat-transcript").performTouchInput { swipeDown() }
                compose.waitForIdle()
            }
            screenshot("621b-durable-stopped-start")

            typePrompt(NEXT_PROMPT)
            awaitSendEnabled()
            compose.onNodeWithTag("chat-send").performClick()
            compose.waitUntil(120_000) { assistantVisibleText().contains("READY") }
            screenshot("622-durable-next")
            emit("622 durableNext=${assistantVisibleText().take(120)}")

            val runs = runBlocking { AgentRunHistory(durable).recentRuns(10) }
            assertEquals("the durable exchange created unexpected runs", 2, runs.size)
            assertTrue("the durable Stop did not persist", runs.any { it.state == "CANCELLED" })
            assertTrue(
                "the durable next message did not complete",
                runs.any { it.state == "SUCCEEDED" },
            )
            emit("622 durableRuns=${runs.map { it.state }}")
        } finally {
            durable.close()
        }
    }

    /**
     * Re-entry after a real process kill: a fresh process reopens the durable file database and
     * must show the completed conversation read back from its rows.
     *
     * The host force-stops the isolated package between the durable live stage and this test, so
     * no in-memory transcript survives. The restored transcript must appear once, no Stop action
     * may suggest a resumed generation, and the provider-host factory fails the test if anything
     * tries to start a request.
     */
    @Test
    fun completedDurableConversationIsRestoredAfterProcessDeath() {
        org.junit.Assume.assumeTrue(
            "durable re-entry check was not requested",
            InstrumentationRegistry.getArguments().getString("s105_realreentry") == "yes",
        )
        assertEquals(ISOLATED_PACKAGE, context.packageName)
        val container = (context.applicationContext as AegisApplication).container
        requireAuthorizedProvider(container.hostedProviderSettingsStore)
        assertTrue(
            "the durable live database was not found",
            context.getDatabasePath(DURABLE_DB).isFile,
        )
        val durable = Room.databaseBuilder(context, LedgerDatabase::class.java, DURABLE_DB).build()
        val durableCommands = WorkflowCommandService(durable)
        val durableQueries = WorkflowQueryService(durable)
        val durableHistory = AgentRunHistory(durable)
        try {
            val storedRuns = runBlocking { durableHistory.recentRuns(10) }
            assertEquals("the re-entry check needs the live stage's two runs", 2, storedRuns.size)
            val completed = storedRuns.single { it.state == "SUCCEEDED" }
            assertTrue(
                "the completed live run has no stored answer",
                runBlocking { durableHistory.conversationForRun(completed.runId) }
                    .any { it.role == "AGENT" && it.text.contains("READY") },
            )

            val restoredState = ChatAgentRunState()
            setChatContent {
                AegisTheme(language = io.github.triet4p.aegisf.data.AegisLanguage.ENGLISH) {
                    val scope = rememberCoroutineScope()
                    AiChatScreen(
                        queryService = durableQueries,
                        commandService = durableCommands,
                        activeProvider = container.hostedProviderSettingsStore::active,
                        networkAvailable = true,
                        createHostedWorkflowHost = { _, _ ->
                            error("re-entry must not start a provider run")
                        },
                        agentRunHistory = durableHistory,
                        agentRunState = remember { restoredState },
                        agentRunScope = scope,
                        onOpenProviderSettings = {},
                        onRecordEvent = {},
                    )
                }
            }
            compose.waitUntil(20_000) {
                compose.onAllNodesWithTag("chat-composer").fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitUntil(60_000) { shownText().contains("READY") }
            screenshot("623-durable-reentry-after-process-death")
            emit("623 reentryShown=${shownText().take(300)}")
            assertEquals(
                "re-entry duplicated the stored user turn",
                1,
                compose.onAllNodesWithText(NEXT_PROMPT).fetchSemanticsNodes().size,
            )
            assertTrue(
                "re-entry offered Stop for a conversation that is not running",
                compose.onAllNodesWithTag("chat-stop").fetchSemanticsNodes().isEmpty(),
            )
            assertEquals(
                "re-entry admitted a new run",
                2,
                runBlocking { durableHistory.recentRuns(10) }.size,
            )
            assertTrue(
                "the restored transcript is not labelled as saved",
                shownText().contains("Saved conversation"),
            )
            assertTrue(
                "the restored transcript lost its completed state",
                shownText().contains("Completed"),
            )

            compose.onNodeWithContentDescription("History").performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodesWithText("Conversation history").fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitUntil(30_000) {
                compose.onAllNodesWithContentDescription("$NEXT_PROMPT. Completed")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            screenshot("624-durable-history-after-process-death")
            compose.onNodeWithContentDescription("$NEXT_PROMPT. Completed").performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodesWithText("Conversation history").fetchSemanticsNodes().isEmpty()
            }
            assertEquals(
                "selecting the stored conversation duplicated its user turn",
                1,
                compose.onAllNodesWithText(NEXT_PROMPT).fetchSemanticsNodes().size,
            )
            assertTrue(
                "selecting the stored conversation lost the saved answer",
                shownText().contains("READY"),
            )
            screenshot("625-durable-history-loaded")
            emit("625 reentryLoaded=true duplicates=0")
        } finally {
            durable.close()
        }
    }

    /** Rejects a live stage whose active route is not the owner-authorized DeepSeek service. */
    private fun requireAuthorizedProvider(
        store: HostedProviderSettingsStore,
    ): SavedHostedProvider {
        val active = requireNotNull(runCatching { store.active() }.getOrNull()) {
            "no active hosted provider profile is configured in the isolated package"
        }
        val providerHost = java.net.URI(active.endpoint).host.orEmpty().lowercase()
        assertTrue(
            "provider route is outside the authorized DeepSeek services: $providerHost",
            providerHost == "opencode.ai" || providerHost == "api.deepseek.com",
        )
        assertTrue(
            "provider model is not a DeepSeek model: ${active.model}",
            active.model.contains("deepseek", ignoreCase = true),
        )
        return active
    }

    /** Waits until the real composer's Send action is enabled for the typed prompt. */
    private fun awaitSendEnabled() {
        compose.waitUntil(30_000) {
            runCatching { compose.onNodeWithTag("chat-send").assertIsEnabled() }.isSuccess
        }
    }

    @Test
    fun staleAttachedContextBlocksSendingUntilResolved() {
        prepare()
        val (captureId, _) = seedCapture("Current note text")
        val runState = ChatAgentRunState().apply {
            noteContext = ChatNoteContext(captureId = captureId, text = "Old note text")
        }
        val adapter = ScriptedStreamingAdapter(
            scripts = listOf(StreamScript(listOf("Should not be sent."), delayMillis = 0)),
        )
        startScreen(runState, adapter)
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("The attached note changed", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        typePrompt("Use this note")
        compose.onNodeWithTag("chat-send").assertIsNotEnabled()
        screenshot("515-chat-stale-context-en")
        emit("515 blocked=true")

        compose.onNodeWithText("Use current text").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Use current text").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("chat-send").assertIsEnabled()
        screenshot("516-chat-context-updated-en")
        emit("516 blocked=false")
    }

    @Test
    fun withoutAProviderChatShowsGuidanceAndCannotSend() {
        prepare()
        var opened = false
        val runState = ChatAgentRunState()
        setChatContent {
            AegisTheme(language = io.github.triet4p.aegisf.data.AegisLanguage.ENGLISH) {
                val scope = rememberCoroutineScope()
                AiChatScreen(
                    queryService = queries,
                    commandService = commands,
                    activeProvider = { null },
                    networkAvailable = true,
                    createHostedWorkflowHost = { session, config ->
                        host(session, config, ScriptedStreamingAdapter(emptyList()))
                    },
                    agentRunHistory = history,
                    agentRunState = remember { runState },
                    agentRunScope = scope,
                    onOpenProviderSettings = { opened = true },
                    onRecordEvent = {},
                )
            }
        }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("No AI provider is set up").fetchSemanticsNodes().isNotEmpty()
        }
        typePrompt("Question without a provider")
        compose.onNodeWithTag("chat-send").assertIsNotEnabled()
        compose.onNodeWithText("Open AI settings").performClick()
        assertTrue("the focused provider route was not requested", opened)
        screenshot("517-chat-no-provider-en")
        emit("517 noProvider=true")
    }

    @Test
    fun offlineChatShowsTheWarningAndCannotSend() {
        prepare()
        val runState = ChatAgentRunState()
        setChatContent {
            AegisTheme(language = io.github.triet4p.aegisf.data.AegisLanguage.ENGLISH) {
                val scope = rememberCoroutineScope()
                AiChatScreen(
                    queryService = queries,
                    commandService = commands,
                    activeProvider = { fakeProvider() },
                    networkAvailable = false,
                    createHostedWorkflowHost = { session, config ->
                        host(session, config, ScriptedStreamingAdapter(emptyList()))
                    },
                    agentRunHistory = history,
                    agentRunState = remember { runState },
                    agentRunScope = scope,
                    onOpenProviderSettings = {},
                    onRecordEvent = {},
                )
            }
        }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("You're offline").fetchSemanticsNodes().isNotEmpty()
        }
        typePrompt("Question while offline")
        compose.onNodeWithTag("chat-send").assertIsNotEnabled()
        screenshot("519-chat-offline-en")
        emit("519 offline=true")
    }

    @Test
    fun composerAndSendRemainUsableAtOneAndAHalfText() {
        prepare()
        val runState = ChatAgentRunState()
        setChatContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 1.5f),
            ) {
                AegisTheme(language = io.github.triet4p.aegisf.data.AegisLanguage.ENGLISH) {
                    val scope = rememberCoroutineScope()
                    AiChatScreen(
                        queryService = queries,
                        commandService = commands,
                        activeProvider = { fakeProvider() },
                        networkAvailable = true,
                        createHostedWorkflowHost = { session, config ->
                            host(session, config, ScriptedStreamingAdapter(emptyList()))
                        },
                        agentRunHistory = history,
                        agentRunState = remember { runState },
                        agentRunScope = scope,
                        onOpenProviderSettings = {},
                        onRecordEvent = {},
                    )
                }
            }
        }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("chat-composer").fetchSemanticsNodes().isNotEmpty()
        }
        typePrompt("A longer question at larger text, with a second line that must stay readable")
        compose.onNodeWithTag("chat-composer").performTextInput("\nAnd a second line.")
        compose.onNodeWithTag("chat-send").assertIsEnabled()
        screenshot("518-chat-large-text-en")
        emit("518 largeText=ok")
    }

    private fun seedEvent(
        text: String,
        excerpt: String,
        amount: Long,
        merchant: String,
        date: String,
        time: String,
    ): Long = runBlocking {
        val id = UUID.randomUUID().toString()
        val storedCapture = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest("s105-outcome-capture-$id", text, UserCaptureKind.QUICK_CAPTURE),
        )
        check(storedCapture is WorkflowCommandOutcome.Applied) { "capture refused: $storedCapture" }
        val captureId = (storedCapture.change as WorkflowChange.CaptureSaved).captureId
        val spanEnd = text.indexOf(excerpt) + excerpt.length
        val storedEvent = commands.updateEventInterpretation(
            WorkflowCallerScope.User,
            UpdateEventRequest(
                idempotencyKey = "s105-outcome-event-$id",
                captureId = captureId,
                expectedCaptureRevision = 0,
                expectedEventRevision = null,
                extractionVersion = "s105-chat/v1",
                interpretation = NoteEventInterpretation(
                    spanStart = 0,
                    spanEnd = spanEnd,
                    normalizedText = excerpt,
                    amountVnd = amount,
                    merchant = merchant,
                    timeAnchor = NoteEventTimeAnchor(date = date, time = time),
                ),
            ),
        )
        check(storedEvent is WorkflowCommandOutcome.Applied) { "event refused: $storedEvent" }
        (storedEvent.change as WorkflowChange.EventStored).eventId
    }

    private fun seedDraft(amount: Long, memo: String): Long = runBlocking {
        val id = UUID.randomUUID().toString()
        database.pendingBankDraftDao().store(
            PendingBankDraftEntity(
                deliveryId = "s105-outcome-draft-$id",
                sourcePackage = "synthetic.fixture",
                notificationKey = null,
                postedAtEpochMillis = 1_800_000_000_000L,
                bank = "Synthetic bank",
                parseStatus = IntakeParseStatus.PARSED,
                amountState = IntakeFieldState.PRESENT,
                amountVnd = amount,
                amountUnresolvedReason = null,
                direction = IntakeDirection.DEBIT,
                momentState = IntakeFieldState.PRESENT,
                statedDate = "2026-11-04",
                statedTime = "15:01",
                momentUnresolvedReason = null,
                memoState = IntakeFieldState.PRESENT,
                memo = memo,
                memoUnresolvedReason = null,
                createdAtEpochMillis = 1,
            ),
        )
    }

    private fun propose(eventId: Long, draftId: Long): Long = runBlocking {
        val applied = commands.proposeLink(
            WorkflowCallerScope.User,
            ProposeLinkRequest("s105-outcome-propose-${UUID.randomUUID()}", eventId, draftId),
        )
        check(applied is WorkflowCommandOutcome.Applied) { "proposal refused: $applied" }
        (applied.change as WorkflowChange.LinkDecided).linkId
    }

    /**
     * A scripted provider that asks the user one bounded question about a real stored draft.
     *
     * The draft reference is the one the host issued in the `list_drafts` result, so the
     * clarification travels through the production tool runtime, masking and run state instead
     * of a fabricated outcome. No network request is made and no production row is touched.
     */
    private class ClarifyingScriptedAdapter : HostedGenerationAdapter {
        override val protocol: HostedProtocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS
        override val streamingEnabled: Boolean = true
        private var next = 0
        private var draftHandle: String? = null

        override fun begin(systemInstruction: String, userInstruction: String): HostedConversation =
            ScriptedConversation(protocol)

        override suspend fun generate(
            conversation: HostedConversation,
            tools: List<HostedToolDeclaration>,
        ): HostedGenerationResult = nextTurn()

        override suspend fun streamGuarded(
            conversation: HostedConversation,
            tools: List<HostedToolDeclaration>,
            responseGuard: HostedResponsePrivacyGuard,
            onProgress: (HostedTurnProgress) -> Unit,
        ): HostedGenerationResult = nextTurn()

        override fun continueWithToolResults(
            conversation: HostedConversation,
            results: List<HostedToolResult>,
            hostNotice: String?,
        ): HostedConversation {
            if (draftHandle == null) {
                draftHandle = results.firstOrNull()?.outputJson?.let { payload ->
                    org.json.JSONObject(payload)
                        .optJSONObject("data")
                        ?.optJSONArray("items")
                        ?.optJSONObject(0)
                        ?.optString("ref")
                        ?.takeIf { it.isNotBlank() }
                }
            }
            return ScriptedConversation(protocol)
        }

        private fun nextTurn(): HostedGenerationResult {
            val index = next++
            return when (index) {
                0 -> toolTurn(
                    name = "list_drafts",
                    argumentsJson = """{"review_status":"PENDING","duplicates_only":false,"cursor":null}""",
                )
                1 -> {
                    val handle = draftHandle ?: error("no draft reference was observed")
                    toolTurn(
                        name = "ask_user",
                        argumentsJson = org.json.JSONObject()
                            .put("subject_ref", handle)
                            .put("field", "candidate")
                            .put("question", "Which saved draft did you mean?")
                            .toString(),
                    )
                }
                else -> completeTurn("Thanks, I noted the draft you meant.")
            }
        }

        private fun toolTurn(name: String, argumentsJson: String): HostedGenerationResult {
            val callId = "s105-call-$next-$name"
            val call = HostedToolCall(
                callId = callId,
                name = name,
                argumentsJson = argumentsJson,
                arguments = null,
                disposition = ToolCallDisposition.READY,
            )
            return HostedGenerationResult.Turn(
                HostedTurn(
                    protocol = protocol,
                    requestedModel = MODEL,
                    reportedModel = MODEL,
                    assistantText = null,
                    toolCalls = listOf(call),
                    finish = HostedTurnFinish.ToolCalls,
                    usage = null,
                    conversation = ScriptedConversation(protocol, listOf(callId)),
                ),
            )
        }

        private fun completeTurn(text: String): HostedGenerationResult =
            HostedGenerationResult.Turn(
                HostedTurn(
                    protocol = protocol,
                    requestedModel = MODEL,
                    reportedModel = MODEL,
                    assistantText = text,
                    toolCalls = emptyList(),
                    finish = HostedTurnFinish.Complete,
                    usage = null,
                    conversation = null,
                ),
            )
    }

    /**
     * Replaces the host activity's content with the chat under test.
     *
     * `createComposeRule` looks for a test-package ComponentActivity that this app does not
     * declare; MainActivity is the real, declared host. Its first composition is replaced on the
     * UI thread, so the test still runs the screen in the app's own process with the test's
     * isolated database and scripted provider seam.
     */
    private fun setChatContent(content: @Composable () -> Unit) {
        compose.activity.runOnUiThread {
            compose.activity.setContent(content = content)
        }
        compose.waitForIdle()
    }

    private fun prepare() {
        database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        commands = WorkflowCommandService(database)
        queries = WorkflowQueryService(database)
        history = AgentRunHistory(database)
    }

    private fun startScreen(
        runState: ChatAgentRunState,
        adapter: HostedGenerationAdapter,
        language: AegisLanguage = AegisLanguage.ENGLISH,
    ) {
        setChatContent {
            AegisTheme(language = language) {
                val scope = rememberCoroutineScope()
                AiChatScreen(
                    queryService = queries,
                    commandService = commands,
                    activeProvider = { fakeProvider() },
                    networkAvailable = true,
                    createHostedWorkflowHost = { session, config -> host(session, config, adapter) },
                    agentRunHistory = history,
                    agentRunState = remember { runState },
                    agentRunScope = scope,
                    onOpenProviderSettings = {},
                    onRecordEvent = {},
                )
            }
        }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("chat-composer").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun host(
        session: WorkflowCallerScope.AgentSession,
        config: HostedProviderConfig,
        adapter: HostedGenerationAdapter,
    ): WorkflowRunHost {
        val boundary = MaskedPromptBoundary(SensitiveVault(session.scopeId))
        return WorkflowRunHost(
            scope = session,
            boundary = boundary,
            gateway = MaskedHostedGateway(adapter, boundary),
            declarations = AgentToolDeclarations.load(context.assets::open),
            skills = WorkflowSkillCatalog.load(context.assets::open),
            commands = commands,
            queries = queries,
            operationKeys = WorkflowOperationKeySource { UUID.randomUUID().toString() },
        )
    }

    /**
     * A real provider host over the test's isolated services, with an empty caller scope.
     *
     * The owner's credential is used only to build the production adapter/gateway; the request
     * scope contains no captures or drafts, so the synthetic prompt is the only business content
     * that can cross the boundary. Nothing from the production database is read or written.
     */
    private fun realHost(
        session: WorkflowCallerScope.AgentSession,
        config: HostedProviderConfig,
        hostCommands: WorkflowCommandService = commands,
        hostQueries: WorkflowQueryService = queries,
        streamedChunks: java.util.concurrent.atomic.AtomicInteger? = null,
    ): WorkflowRunHost {
        val emptyScope = session.copy(
            captureIds = emptySet(),
            draftIds = emptySet(),
            draftScopeComplete = true,
        )
        val boundary = MaskedPromptBoundary(SensitiveVault(emptyScope.scopeId))
        val baseAdapter = HostedAdapters.create(config, UrlConnectionHttpTransport())
        val adapter = if (streamedChunks == null) {
            baseAdapter
        } else {
            CountingAdapter(baseAdapter, streamedChunks)
        }
        return WorkflowRunHost(
            scope = emptyScope,
            boundary = boundary,
            gateway = MaskedHostedGateway(adapter, boundary),
            declarations = AgentToolDeclarations.load(context.assets::open),
            skills = WorkflowSkillCatalog.load(context.assets::open),
            commands = hostCommands,
            queries = hostQueries,
            operationKeys = WorkflowOperationKeySource { UUID.randomUUID().toString() },
        )
    }

    /**
     * Counts already-guarded public answer fragments so a live test can act while text is
     * actually arriving. Private reasoning deltas are never counted: they are not answer text.
     */
    private class CountingAdapter(
        private val inner: HostedGenerationAdapter,
        private val chunks: java.util.concurrent.atomic.AtomicInteger,
    ) : HostedGenerationAdapter {
        override val protocol: HostedProtocol get() = inner.protocol
        override val streamingEnabled: Boolean get() = inner.streamingEnabled

        override fun begin(systemInstruction: String, userInstruction: String): HostedConversation =
            inner.begin(systemInstruction, userInstruction)

        override suspend fun generate(
            conversation: HostedConversation,
            tools: List<HostedToolDeclaration>,
        ): HostedGenerationResult = inner.generate(conversation, tools)

        override suspend fun generateGuarded(
            conversation: HostedConversation,
            tools: List<HostedToolDeclaration>,
            responseGuard: HostedResponsePrivacyGuard,
        ): HostedGenerationResult =
            inner.generateGuarded(conversation, tools, responseGuard)

        override suspend fun streamGuarded(
            conversation: HostedConversation,
            tools: List<HostedToolDeclaration>,
            responseGuard: HostedResponsePrivacyGuard,
            onProgress: (HostedTurnProgress) -> Unit,
        ): HostedGenerationResult =
            inner.streamGuarded(conversation, tools, responseGuard) { item ->
                if (item is HostedTurnProgress.PublicText) chunks.incrementAndGet()
                onProgress(item)
            }

        override fun continueWithToolResults(
            conversation: HostedConversation,
            results: List<HostedToolResult>,
            hostNotice: String?,
        ): HostedConversation =
            inner.continueWithToolResults(conversation, results, hostNotice)

        override fun providerIdentity(): io.github.triet4p.aegisf.provider.HostedProviderIdentity? =
            inner.providerIdentity()

        override fun responseMetadataPolicy():
            io.github.triet4p.aegisf.provider.HostedResponseMetadataPolicy? =
            inner.responseMetadataPolicy()

        override fun appendUserNotice(
            conversation: HostedConversation,
            notice: String,
        ): HostedConversation = inner.appendUserNotice(conversation, notice)
    }

    private fun fakeProvider(): SavedHostedProvider = SavedHostedProvider(
        id = "s105-fake",
        name = "Synthetic provider",
        protocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
        endpoint = "https://example.invalid/v1/chat/completions",
        model = MODEL,
        credential = "synthetic-test-credential",
        maxOutputTokens = null,
        reasoningEffort = null,
        temperature = null,
        parallelToolCalls = null,
        capabilities = HostedProviderCapabilities(
            supportsToolCalls = true,
            supportsParallelToolCalls = false,
            supportsReasoningBudget = false,
            supportsTemperature = false,
        ),
    )

    private fun seedCapture(text: String): Pair<Long, String> {
        val stored = runBlocking {
            commands.saveCapture(
                WorkflowCallerScope.User,
                SaveCaptureRequest("s105-note-${UUID.randomUUID()}", text),
            )
        }
        check(stored is WorkflowCommandOutcome.Applied) { "capture refused: $stored" }
        return (stored.change as WorkflowChange.CaptureSaved).captureId to text
    }

    private fun typePrompt(text: String) {
        compose.onNodeWithTag("chat-composer").performTextClearance()
        compose.onNodeWithTag("chat-composer").performTextInput(text)
    }

    private fun waitForAssistantText(fragment: String, timeoutMillis: Long) {
        compose.waitUntil(timeoutMillis) {
            assistantText().contains(fragment)
        }
    }

    private fun assistantText(): String {
        val nodes = compose.onAllNodesWithTag("chat-assistant-text").fetchSemanticsNodes()
        val node = nodes.lastOrNull() ?: return ""
        return node.config[SemanticsProperties.Text].joinToString("") { it.text }
    }

    /** The visible assistant answer without the live streaming caret. */
    private fun assistantVisibleText(): String = assistantText().replace("▍", "").trim()

    private fun shownText(): String = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        .joinToString(" ") { root -> collectText(root) }

    private fun collectText(node: androidx.compose.ui.semantics.SemanticsNode): String = buildString {
        val text = runCatching { node.config[SemanticsProperties.Text] }.getOrNull()
        append(text?.joinToString("") { it.text }.orEmpty())
        append(' ')
        node.children.forEach { child -> append(collectText(child)) }
    }

    private fun screenshot(name: String) {
        hideKeyboard()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: error("the device returned no screenshot")
        val file = File(context.filesDir, "s105-$name.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        emit("S105 screenshot=$name file=${file.absolutePath} bytes=${file.length()}")
    }

    /** One PNG of the current screen, captured without hiding the IME. */
    private fun capturePng(): ByteArray? {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: return null
        val bytes = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes)
        return bytes.toByteArray()
    }

    /** Stores one already-captured screen frame under the test's private files directory. */
    private fun writePng(name: String, data: ByteArray) {
        File(context.filesDir, "s105-$name.png").writeBytes(data)
        emit("S105 screenshot=$name bytes=${data.size}")
    }

    /** Waits for the real transport to deliver at least [target] SSE chunks. */
    private fun awaitStreamedChunks(
        counter: java.util.concurrent.atomic.AtomicInteger,
        target: Int,
    ) {
        // waitUntil drives the Compose test dispatcher, which the screen's coroutine scope
        // needs in order to run; a bare Thread.sleep would leave the exchange unpumped.
        compose.waitUntil(90_000) { counter.get() >= target }
    }

    /**
     * Injects one touchscreen tap at a window coordinate.
     *
     * Used for Stop while a fast real stream keeps the UI continuously recomposing: a semantics
     * click waits for idleness and could therefore arrive after the answer settled.
     */
    private fun tapAt(x: Float, y: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = android.os.SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(
            downTime,
            downTime,
            android.view.MotionEvent.ACTION_DOWN,
            x,
            y,
            0,
        )
        val up = android.view.MotionEvent.obtain(
            downTime,
            downTime + 80,
            android.view.MotionEvent.ACTION_UP,
            x,
            y,
            0,
        )
        try {
            instrumentation.sendPointerSync(down)
            instrumentation.sendPointerSync(up)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    /** Hides the IME so a state capture shows the whole transcript, not the keyboard. */
    private fun hideKeyboard() {
        val manager = context.getSystemService(InputMethodManager::class.java) ?: return
        compose.activity.runOnUiThread {
            manager.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
        // Let the IME window finish hiding before the screenshot captures the layout.
        Thread.sleep(700)
    }

    private fun emit(message: String) {
        android.util.Log.i("S105UI", message)
    }

    private class StreamScript(
        val chunks: List<String>,
        val delayMillis: Long,
        val failure: HostedProviderFailure? = null,
    )

    private class ScriptedConversation(
        override val protocol: HostedProtocol,
        override val pendingCallIds: List<String> = emptyList(),
    ) : HostedConversation

    private class ScriptedStreamingAdapter(
        private val scripts: List<StreamScript>,
    ) : HostedGenerationAdapter {
        override val protocol: HostedProtocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS
        override val streamingEnabled: Boolean = true
        private var next = 0

        override fun begin(systemInstruction: String, userInstruction: String): HostedConversation =
            ScriptedConversation(protocol)

        override suspend fun generate(
            conversation: HostedConversation,
            tools: List<HostedToolDeclaration>,
        ): HostedGenerationResult = completeTurn("")

        override suspend fun streamGuarded(
            conversation: HostedConversation,
            tools: List<HostedToolDeclaration>,
            responseGuard: HostedResponsePrivacyGuard,
            onProgress: (HostedTurnProgress) -> Unit,
        ): HostedGenerationResult {
            val script = scripts.getOrNull(next) ?: StreamScript(listOf(""), 0)
            next += 1
            script.failure?.let { failure ->
                return HostedGenerationResult.ProviderFailure(failure)
            }
            script.chunks.forEach { chunk ->
                onProgress(HostedTurnProgress.PublicText(chunk))
                if (script.delayMillis > 0) delay(script.delayMillis)
            }
            return completeTurn(script.chunks.joinToString(""))
        }

        override fun continueWithToolResults(
            conversation: HostedConversation,
            results: List<HostedToolResult>,
            hostNotice: String?,
        ): HostedConversation = ScriptedConversation(protocol)

        private fun completeTurn(text: String): HostedGenerationResult =
            HostedGenerationResult.Turn(
                HostedTurn(
                    protocol = protocol,
                    requestedModel = MODEL,
                    reportedModel = MODEL,
                    assistantText = text,
                    toolCalls = emptyList(),
                    finish = HostedTurnFinish.Complete,
                    usage = null,
                    conversation = null,
                ),
            )
    }

    private companion object {
        const val MODEL = "s105-scripted-model"

        /** The isolated debug package used for real live probes; never the owner's package. */
        const val ISOLATED_PACKAGE = "io.github.triet4p.aegisf.s812evidence"

        /** The temporary profile the owner-authorized live stage injects and later removes. */
        const val REAL_PROFILE_ID = "s105-authorized-deepseek"
        const val REAL_PRESET_ID = "opencode-go-deepseek"
        const val SECRET_FILE = "s105_secret.env"

        /** Synthetic, non-personal prompts used by the live stages. */
        const val LIVE_PROMPT =
            "Write about 1200 words of generic advice on keeping a desk tidy. " +
                "Do not ask for or use any personal or financial data."
        const val NEXT_PROMPT = "Reply with the single word READY and nothing else."

        /** The dedicated file database the two durable live stages share across processes. */
        const val DURABLE_DB = "s105_durable.db"
    }
}
