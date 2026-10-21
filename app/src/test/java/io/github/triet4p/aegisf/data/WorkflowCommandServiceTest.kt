package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.data.laya.LayaCandidatePlanner
import io.github.triet4p.aegisf.data.laya.LayaContextDecision
import io.github.triet4p.aegisf.data.laya.LayaEffectiveContext
import io.github.triet4p.aegisf.data.laya.LayaEffectiveInput
import io.github.triet4p.aegisf.data.laya.LayaEvaluation
import io.github.triet4p.aegisf.notification.PermittedNotificationContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * The behavior of the application's one preparation-command boundary.
 *
 * These run against the real Room file, so an idempotency record, a refused request, a
 * conflict, and a stored prediction are observed the way the app observes them after a
 * restart — not as a mocked return value.
 *
 * The classifier is the one collaborator that is stood in for: the local model is not
 * installed on a host test machine, and what these tests pin is that the boundary asks it
 * exactly when it says it does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkflowCommandServiceTest {
    private class FixedClock(@Volatile var now: Long = CAPTURE_TIME) : LedgerClock {
        override fun nowEpochMillis(): Long = now
    }

    /**
     * A gate the test opens when it wants the model call to go on.
     *
     * Only the first call waits at the gate, so a test can hold one attempt inside the
     * model while another attempt asks for the same key.
     */
    private class Gate {
        private val entered = CompletableDeferred<Unit>()
        private val opened = CompletableDeferred<Unit>()
        private val seen = AtomicInteger(0)

        suspend fun enter() {
            if (seen.incrementAndGet() == 1) {
                entered.complete(Unit)
                opened.await()
            }
        }

        suspend fun awaitEntered() {
            entered.await()
        }

        fun open() {
            opened.complete(Unit)
        }
    }

    /** A classifier that counts how often it was asked and answers with one decision. */
    private class RecordingEngine(
        var categoryId: Long = FOOD_CATEGORY_ID,
        var onClassify: (suspend () -> Unit)? = null,
        val gate: Gate? = null,
    ) : ClassificationEngine {
        private val asked = AtomicInteger(0)

        /** The reason the classifier fails with, or null when it answers. */
        @Volatile
        var failure: String? = null

        val calls: Int
            get() = asked.get()

        override suspend fun classify(
            context: LayaEffectiveContext,
            categories: List<CategoryEntity>,
        ): ClassificationEngineResult {
            asked.incrementAndGet()
            onClassify?.invoke()
            gate?.enter()
            failure?.let { reason ->
                return ClassificationEngineResult.Failed(reason)
            }
            return ClassificationEngineResult.Decided(
                LayaContextDecision(
                    decision = LayaCandidatePlanner.Decision.Category(
                        categoryId = categoryId,
                        snapshotName = "Food",
                        source = LayaCandidatePlanner.SuggestionSource.MODEL,
                        confidence = 0.5,
                    ),
                    effectiveInput = LayaEffectiveInput(
                        text = context.primaryText,
                        digest = "digest-of-${context.primaryText}",
                        includedReadings = context.eventReadings,
                        droppedReadings = emptyList(),
                        tokenCount = 4,
                        roomTokens = 128,
                    ),
                    evaluation = LayaEvaluation(
                        categoryIds = listOf(categoryId),
                        probabilities = listOf(0.75),
                        unknownShare = 0.25,
                        confidence = 0.5,
                    ),
                ),
            )
        }
    }

    private lateinit var context: Context
    private lateinit var clock: FixedClock
    private lateinit var engine: RecordingEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(LedgerDatabase.NAME)
        clock = FixedClock()
        engine = RecordingEngine()
        // The in-process guard outlives the database it belongs to, so every case
        // starts with no key held: a case that leaves a key held would otherwise keep
        // the next case from taking it.
        WorkflowClaimGuard.reset()
    }

    private fun open(): LedgerDatabase = LedgerDatabase.build(context)

    private fun commands(
        database: LedgerDatabase,
        findRememberedCategory: suspend (String) -> CategoryEntity? = { null },
        engine: ClassificationEngine = this.engine,
        claimLeaseMillis: Long = 60_000L,
    ): WorkflowCommandService = WorkflowCommandService(
        database = database,
        clock = clock,
        timezoneId = { CAPTURE_ZONE },
        engine = engine,
        findRememberedCategory = findRememberedCategory,
        claimLeaseMillis = claimLeaseMillis,
    )

    /**
     * Waits until the claim under a key is given back.
     *
     * The claim is given back by the model work itself, when the work has really ended,
     * so the test waits for the observable state instead of assuming it. The key is free
     * again when the row is gone *and* no attempt holds the key in the process.
     */
    private suspend fun awaitClaimGivenBack(database: LedgerDatabase, key: String) {
        repeat(500) {
            if (database.workflowOperationDao().findByKey(key) == null &&
                !WorkflowClaimGuard.isHeld(key)
            ) {
                return
            }
            withContext(Dispatchers.IO) { delay(10) }
        }
        fail("the claim under $key was not given back")
    }

    /**
     * Makes the extension of the claim under one key fail.
     *
     * The trigger aborts the write that keeps the claim alive, and only that write: the
     * release of the claim and the writing of its outcome are not touched. The claim
     * therefore fails the way a database that cannot be written fails.
     */
    private fun failRenewalOf(database: LedgerDatabase, key: String) {
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_renewal_of_claim
            BEFORE UPDATE OF leaseExpiresAtEpochMillis ON workflow_operations
            WHEN OLD.idempotencyKey = '$key' AND NEW.leaseExpiresAtEpochMillis IS NOT NULL
            BEGIN
                SELECT RAISE(ABORT, 'the claim could not be extended');
            END
            """.trimIndent(),
        )
    }

    // --- Idempotency --------------------------------------------------------

    @Test
    fun `a repeated save request stores one capture and changes nothing the second time`() =
        runTest {
            val database = open()
            try {
                val commands = commands(database)
                val request = SaveCaptureRequest(
                    idempotencyKey = "save-note-1",
                    bodyText = "Fuel 50,000 at 15:00",
                )

                val first = commands.saveCapture(WorkflowCallerScope.User, request)
                assertTrue("the first request was refused: $first", first is WorkflowCommandOutcome.Applied)
                val change = (first as WorkflowCommandOutcome.Applied).change
                assertTrue(change is WorkflowChange.CaptureSaved)
                val captureId = (change as WorkflowChange.CaptureSaved).captureId

                val second = commands.saveCapture(WorkflowCallerScope.User, request)
                assertTrue("the repeat was not a no-change: $second", second is WorkflowCommandOutcome.NoChange)
                val repeated = (second as WorkflowCommandOutcome.NoChange).change
                assertTrue(repeated is WorkflowChange.CaptureSaved)
                assertEquals(captureId, (repeated as WorkflowChange.CaptureSaved).captureId)

                // One capture, one revision, one text: the repeat changed nothing.
                val stored = database.captureMessageDao().findAllIncludingTombstones()
                assertEquals(1, stored.size)
                assertEquals("Fuel 50,000 at 15:00", stored.single().bodyText)
                assertEquals(0, stored.single().revision)
            } finally {
                database.close()
            }
        }

    @Test
    fun `a repeated request after a restart changes nothing`() = runTest {
        val database = open()
        val captureId = when (val first = commands(database).saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest(idempotencyKey = "save-note-1", bodyText = "Fuel 50,000 at 15:00"),
        )) {
            is WorkflowCommandOutcome.Applied -> (first.change as WorkflowChange.CaptureSaved).captureId
            else -> error("the first request was refused: $first")
        }
        database.close()

        val reopened = open()
        try {
            val again = commands(reopened).saveCapture(
                WorkflowCallerScope.User,
                SaveCaptureRequest(idempotencyKey = "save-note-1", bodyText = "Fuel 50,000 at 15:00"),
            )
            assertTrue("the repeat was not a no-change: $again", again is WorkflowCommandOutcome.NoChange)
            assertEquals(1, reopened.captureMessageDao().findAllIncludingTombstones().size)
            assertNotNull(reopened.captureMessageDao().findById(captureId))
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `two callers racing for one key apply the change once`() = runTest {
        val database = open()
        try {
            val request = SaveCaptureRequest(
                idempotencyKey = "save-note-race",
                bodyText = "Circle K snack 20,000",
            )
            val outcomes = coroutineScope {
                val first = async(Dispatchers.IO) {
                    commands(database).saveCapture(WorkflowCallerScope.User, request)
                }
                val second = async(Dispatchers.IO) {
                    commands(database).saveCapture(WorkflowCallerScope.User, request)
                }
                listOf(first.await(), second.await())
            }
            assertEquals(1, database.captureMessageDao().findAllIncludingTombstones().size)
            assertEquals(1, outcomes.count { it is WorkflowCommandOutcome.Applied })
            assertEquals(1, outcomes.count { it is WorkflowCommandOutcome.NoChange })
        } finally {
            database.close()
        }
    }

    @Test
    fun `the same key with a different request is a conflict and changes nothing`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            commands.saveCapture(
                WorkflowCallerScope.User,
                SaveCaptureRequest(idempotencyKey = "save-note-1", bodyText = "Fuel 50,000 at 15:00"),
            )

            val changed = commands.saveCapture(
                WorkflowCallerScope.User,
                SaveCaptureRequest(idempotencyKey = "save-note-1", bodyText = "Something else"),
            )
            assertTrue("the changed request was not a conflict: $changed", changed is WorkflowCommandOutcome.Conflict)
            val stored = database.captureMessageDao().findAllIncludingTombstones()
            assertEquals(1, stored.size)
            assertEquals("Fuel 50,000 at 15:00", stored.single().bodyText)
        } finally {
            database.close()
        }
    }

    @Test
    fun `the same key from another caller is a conflict and changes nothing`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            commands.saveCapture(
                WorkflowCallerScope.User,
                SaveCaptureRequest(idempotencyKey = "save-note-1", bodyText = "Fuel 50,000 at 15:00"),
            )

            val other = commands.saveCapture(
                WorkflowCallerScope.AgentSession(
                    scopeId = "agent-1",
                    captureIds = emptySet(),
                    draftIds = emptySet(),
                ),
                SaveCaptureRequest(idempotencyKey = "save-note-1", bodyText = "Fuel 50,000 at 15:00"),
            )
            assertTrue("the other caller was not a conflict: $other", other is WorkflowCommandOutcome.Conflict)
            assertEquals(1, database.captureMessageDao().findAllIncludingTombstones().size)
        } finally {
            database.close()
        }
    }

    // --- Revisions -----------------------------------------------------------

    @Test
    fun `a stale capture revision is a conflict and changes nothing`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val captureId = saveCapture(commands, "save-note-1", "Fuel 50,000 at 15:00")

            val revised = commands.reviseCapture(
                WorkflowCallerScope.User,
                ReviseCaptureRequest(
                    idempotencyKey = "revise-note-1",
                    captureId = captureId,
                    expectedRevision = 0,
                    bodyText = "Fuel 55,000 at 15:05",
                ),
            )
            assertTrue("the revision was refused: $revised", revised is WorkflowCommandOutcome.Applied)

            val stale = commands.reviseCapture(
                WorkflowCallerScope.User,
                ReviseCaptureRequest(
                    idempotencyKey = "revise-note-2",
                    captureId = captureId,
                    expectedRevision = 0,
                    bodyText = "Fuel 60,000 at 15:10",
                ),
            )
            assertTrue("the stale revision was not a conflict: $stale", stale is WorkflowCommandOutcome.Conflict)
            val stored = database.captureMessageDao().findById(captureId)!!
            assertEquals(1, stored.revision)
            assertEquals("Fuel 55,000 at 15:05", stored.bodyText)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a stale reading revision is a conflict and changes nothing`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val captureId = saveCapture(commands, "save-note-1", NOTE_TEXT)

            val stored = commands.updateEventInterpretation(
                WorkflowCallerScope.User,
                UpdateEventRequest(
                    idempotencyKey = "event-1",
                    captureId = captureId,
                    expectedCaptureRevision = 0,
                    interpretation = reading("Fuel 50,000 at 15:00", "Fuel 50,000 at 15:00"),
                    extractionVersion = EXTRACTION_VERSION,
                ),
            )
            assertTrue("the reading was refused: $stored", stored is WorkflowCommandOutcome.Applied)

            // The capture moves on: the text the reading was taken from is gone.
            commands.reviseCapture(
                WorkflowCallerScope.User,
                ReviseCaptureRequest(
                    idempotencyKey = "revise-note-1",
                    captureId = captureId,
                    expectedRevision = 0,
                    bodyText = "Fuel 50,000 at 15:00; Circle K snack 20,000 at 15:15 edited",
                ),
            )

            val stale = commands.updateEventInterpretation(
                WorkflowCallerScope.User,
                UpdateEventRequest(
                    idempotencyKey = "event-2",
                    captureId = captureId,
                    expectedCaptureRevision = 0,
                    interpretation = reading("Fuel 50,000 at 15:00", "Fuel 55,000 at 15:05"),
                    extractionVersion = EXTRACTION_VERSION,
                ),
            )
            assertTrue("the stale reading was not a conflict: $stale", stale is WorkflowCommandOutcome.Conflict)
            // The reading the caller wanted to revise was never stored against the old text.
            val events = database.noteEventDao().findForCapture(captureId)
            assertEquals(1, events.size)
            assertTrue(
                "the stale reading was stored against the old text",
                events.none { it.normalizedText == "Fuel 55,000 at 15:05" },
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `a stale link revision is a conflict and changes nothing`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val captureId = saveCapture(commands, "save-note-1", NOTE_TEXT)
            val eventId = storeEvent(commands, captureId, "event-1")
            val draftId = storeDraft(database, "fuel-key", 50_000L)

            commands.proposeLink(
                WorkflowCallerScope.User,
                ProposeLinkRequest(
                    idempotencyKey = "propose-1",
                    eventId = eventId,
                    draftId = draftId,
                ),
            )

            val stale = commands.attachLink(
                WorkflowCallerScope.User,
                AttachLinkRequest(
                    idempotencyKey = "attach-1",
                    eventId = eventId,
                    draftId = draftId,
                    expectedRevision = 7,
                ),
            )
            assertTrue("the stale attach was not a conflict: $stale", stale is WorkflowCommandOutcome.Conflict)
            val link = database.contextLinkDao().findByPair(eventId, draftId)!!
            assertEquals(ContextLinkState.PROPOSED, link.state)
            assertEquals(0, link.revision)
        } finally {
            database.close()
        }
    }

    // --- Caller scope --------------------------------------------------------

    @Test
    fun `an agent cannot change a capture outside its own scope`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val mine = saveCapture(commands, "save-note-mine", "Fuel 50,000 at 15:00")
            val other = saveCapture(commands, "save-note-other", "Circle K snack 20,000")
            val agent = WorkflowCallerScope.AgentSession(
                scopeId = "agent-1",
                captureIds = setOf(mine),
                draftIds = emptySet(),
            )

            val refused = commands.reviseCapture(
                agent,
                ReviseCaptureRequest(
                    idempotencyKey = "revise-other",
                    captureId = other,
                    expectedRevision = 0,
                    bodyText = "not yours",
                ),
            )
            assertTrue("the other capture was not refused: $refused", refused is WorkflowCommandOutcome.Denied)
            assertEquals(
                WorkflowDenialReason.CALLER_SCOPE,
                (refused as WorkflowCommandOutcome.Denied).reason,
            )
            val stored = database.captureMessageDao().findById(other)!!
            assertEquals("Circle K snack 20,000", stored.bodyText)
            assertEquals(0, stored.revision)
        } finally {
            database.close()
        }
    }

    @Test
    fun `an unauthorized caller cannot tell a missing target from a foreign one`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val mine = saveCapture(commands, "save-note-mine", NOTE_TEXT)
            val foreign = saveCapture(commands, "save-note-foreign", NOTE_TEXT)
            val foreignEvent = storeEvent(commands, foreign, "event-foreign")
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val agent = WorkflowCallerScope.AgentSession(
                scopeId = "agent-1",
                captureIds = setOf(mine),
                draftIds = setOf(draftId),
            )

            val missing = commands.proposeLink(
                agent,
                ProposeLinkRequest(
                    idempotencyKey = "propose-missing",
                    eventId = 9_999L,
                    draftId = draftId,
                ),
            )
            val outOfScope = commands.proposeLink(
                agent,
                ProposeLinkRequest(
                    idempotencyKey = "propose-foreign",
                    eventId = foreignEvent,
                    draftId = draftId,
                ),
            )
            val missingAttach = commands.attachLink(
                agent,
                AttachLinkRequest(
                    idempotencyKey = "attach-missing",
                    eventId = 9_999L,
                    draftId = draftId,
                    expectedRevision = 0,
                ),
            )
            val missingUnlink = commands.unlinkLink(
                agent,
                UnlinkLinkRequest(
                    idempotencyKey = "unlink-missing",
                    eventId = 9_999L,
                    draftId = draftId,
                    expectedRevision = 0,
                ),
            )
            listOf(missing, outOfScope, missingAttach, missingUnlink).forEach { outcome ->
                assertTrue("the request was not refused: $outcome", outcome is WorkflowCommandOutcome.Denied)
                assertEquals(
                    WorkflowDenialReason.CALLER_SCOPE,
                    (outcome as WorkflowCommandOutcome.Denied).reason,
                )
            }
            // Nothing was written for any of them.
            assertTrue(database.contextLinkDao().findForDraft(draftId).isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun `a key another caller used is refused like an unused one`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val mine = saveCapture(commands, "save-note-mine", NOTE_TEXT)
            val foreign = saveCapture(commands, "save-note-foreign", NOTE_TEXT)
            val agent = WorkflowCallerScope.AgentSession(
                scopeId = "agent-1",
                captureIds = setOf(mine),
                draftIds = emptySet(),
            )

            // The user's own request already used this key.
            commands.saveCapture(
                WorkflowCallerScope.User,
                SaveCaptureRequest(idempotencyKey = "shared-key", bodyText = NOTE_TEXT),
            )

            val withUsedKey = commands.reviseCapture(
                agent,
                ReviseCaptureRequest(
                    idempotencyKey = "shared-key",
                    captureId = foreign,
                    expectedRevision = 0,
                    bodyText = "not yours",
                ),
            )
            val withUnusedKey = commands.reviseCapture(
                agent,
                ReviseCaptureRequest(
                    idempotencyKey = "unused-key",
                    captureId = foreign,
                    expectedRevision = 0,
                    bodyText = "not yours",
                ),
            )
            assertTrue("the used key was not refused: $withUsedKey", withUsedKey is WorkflowCommandOutcome.Denied)
            assertTrue("the unused key was not refused: $withUnusedKey", withUnusedKey is WorkflowCommandOutcome.Denied)
            // The same refusal either way: the use of another caller's key is not
            // observable to a caller that may not make the request at all.
            assertEquals(
                (withUsedKey as WorkflowCommandOutcome.Denied).reason,
                (withUnusedKey as WorkflowCommandOutcome.Denied).reason,
            )
            // The foreign capture is untouched.
            val stored = database.captureMessageDao().findById(foreign)!!
            assertEquals(NOTE_TEXT, stored.bodyText)
            assertEquals(0, stored.revision)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a key another caller used is a conflict that names no record of theirs`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val mine = saveCapture(commands, "save-note-mine", NOTE_TEXT)
            val agent = WorkflowCallerScope.AgentSession(
                scopeId = "agent-1",
                captureIds = setOf(mine),
                draftIds = emptySet(),
            )
            commands.saveCapture(
                WorkflowCallerScope.User,
                SaveCaptureRequest(idempotencyKey = "shared-key", bodyText = NOTE_TEXT),
            )

            // The agent may act on its own capture, but the key belongs to the user's
            // request: the answer is a conflict that names neither the other caller nor
            // what that caller changed.
            val refused = commands.reviseCapture(
                agent,
                ReviseCaptureRequest(
                    idempotencyKey = "shared-key",
                    captureId = mine,
                    expectedRevision = 0,
                    bodyText = "another text",
                ),
            )
            assertTrue("the foreign key was not a conflict: $refused", refused is WorkflowCommandOutcome.Conflict)
            val reason = (refused as WorkflowCommandOutcome.Conflict).reason
            assertTrue("the conflict named the other caller: $reason", !reason.contains("user"))
            // The capture is untouched.
            val stored = database.captureMessageDao().findById(mine)!!
            assertEquals(NOTE_TEXT, stored.bodyText)
            assertEquals(0, stored.revision)
        } finally {
            database.close()
        }
    }

    @Test
    fun `an agent cannot attach a link the app cannot prove`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val captureId = saveCapture(commands, "save-note-1", NOTE_TEXT)
            val eventId = storeEvent(
                commands,
                captureId,
                "event-1",
                span = "Circle K snack 20,000 at 15:15",
                amountVnd = 20_000L,
            )
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val agent = WorkflowCallerScope.AgentSession(
                scopeId = "agent-1",
                captureIds = setOf(captureId),
                draftIds = setOf(draftId),
            )

            // The reading states another amount than the draft: the app cannot prove that
            // this reading describes this draft, so the pair is the user's to resolve.
            commands.proposeLink(
                agent,
                ProposeLinkRequest(
                    idempotencyKey = "propose-1",
                    eventId = eventId,
                    draftId = draftId,
                ),
            )
            val refused = commands.attachLink(
                agent,
                AttachLinkRequest(
                    idempotencyKey = "attach-1",
                    eventId = eventId,
                    draftId = draftId,
                    expectedRevision = 0,
                ),
            )
            assertTrue("the attach was not refused: $refused", refused is WorkflowCommandOutcome.Denied)
            assertEquals(
                WorkflowDenialReason.ATTACH_POLICY,
                (refused as WorkflowCommandOutcome.Denied).reason,
            )
            val link = database.contextLinkDao().findByPair(eventId, draftId)!!
            assertEquals(ContextLinkState.PROPOSED, link.state)

            // The user's own decision is not subject to that policy: the user may attach
            // the pair the app cannot prove.
            val attached = commands.attachLink(
                WorkflowCallerScope.User,
                AttachLinkRequest(
                    idempotencyKey = "attach-2",
                    eventId = eventId,
                    draftId = draftId,
                    expectedRevision = 0,
                ),
            )
            assertTrue("the user's own attach was refused: $attached", attached is WorkflowCommandOutcome.Applied)
        } finally {
            database.close()
        }
    }

    @Test
    fun `an agent can attach a link the app proves`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val captureId = saveCapture(commands, "save-note-1", NOTE_TEXT)
            val eventId = storeEvent(commands, captureId, "event-1")
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val agent = WorkflowCallerScope.AgentSession(
                scopeId = "agent-1",
                captureIds = setOf(captureId),
                draftIds = setOf(draftId),
            )

            commands.proposeLink(
                agent,
                ProposeLinkRequest(
                    idempotencyKey = "propose-1",
                    eventId = eventId,
                    draftId = draftId,
                ),
            )
            // The evidence the proposal rests on is the app's own.
            val proposed = database.contextLinkDao().findByPair(eventId, draftId)!!
            assertTrue(
                "the proposal carries no app evidence: ${proposed.evidence}",
                isTrustedLinkEvidence(proposed.evidence),
            )

            val attached = commands.attachLink(
                agent,
                AttachLinkRequest(
                    idempotencyKey = "attach-1",
                    eventId = eventId,
                    draftId = draftId,
                    expectedRevision = 0,
                ),
            )
            assertTrue("the proven attach was refused: $attached", attached is WorkflowCommandOutcome.Applied)
            val link = database.contextLinkDao().findByPair(eventId, draftId)!!
            assertEquals(ContextLinkState.ACCEPTED, link.state)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a reason somebody stated cannot make an agent attach a link`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val captureId = saveCapture(commands, "save-note-1", NOTE_TEXT)
            val eventId = storeEvent(commands, captureId, "event-1")
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val agent = WorkflowCallerScope.AgentSession(
                scopeId = "agent-1",
                captureIds = setOf(captureId),
                draftIds = setOf(draftId),
            )

            // A link row that carries a reason a caller wrote — what a request field used
            // to store — is not evidence the app computed, however plausible it reads.
            database.contextLinkDao().insert(
                ContextLinkEntity(
                    eventId = eventId,
                    draftId = draftId,
                    state = ContextLinkState.PROPOSED,
                    evidence = "same day, same amount",
                    revision = 0,
                    userOverride = null,
                    activeEventId = eventId,
                    createdAtEpochMillis = CAPTURE_TIME,
                ),
            )
            val refused = commands.attachLink(
                agent,
                AttachLinkRequest(
                    idempotencyKey = "attach-1",
                    eventId = eventId,
                    draftId = draftId,
                    expectedRevision = 0,
                ),
            )
            assertTrue("the stated reason was not refused: $refused", refused is WorkflowCommandOutcome.Denied)
            assertEquals(
                WorkflowDenialReason.ATTACH_POLICY,
                (refused as WorkflowCommandOutcome.Denied).reason,
            )
            val link = database.contextLinkDao().findByPair(eventId, draftId)!!
            assertEquals(ContextLinkState.PROPOSED, link.state)
            assertEquals(0, link.revision)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a proposal whose evidence moved on cannot be attached by an agent`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val captureId = saveCapture(commands, "save-note-1", NOTE_TEXT)
            val eventId = storeEvent(commands, captureId, "event-1")
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val agent = WorkflowCallerScope.AgentSession(
                scopeId = "agent-1",
                captureIds = setOf(captureId),
                draftIds = setOf(draftId),
            )
            commands.proposeLink(
                agent,
                ProposeLinkRequest(
                    idempotencyKey = "propose-1",
                    eventId = eventId,
                    draftId = draftId,
                ),
            )

            // The draft moves on: the evidence the proposal was made on is not the
            // evidence the app can read now.
            val draft = database.pendingBankDraftDao().findById(draftId)!!
            val saved = reviewCommands(database, clock).saveReview(
                draftId = draftId,
                expectedRevision = draft.reviewRevision,
                edits = BankDraftReviewEdits(
                    amountText = draft.amountVnd.toString(),
                    dateText = draft.statedDate.orEmpty(),
                    timeText = draft.statedTime.orEmpty(),
                    memoText = draft.memo.orEmpty(),
                    type = null,
                    categoryId = null,
                    categoryResolved = false,
                ),
            )
            assertTrue("the review was not saved: $saved", saved is BankDraftReviewResult.Saved)

            val refused = commands.attachLink(
                agent,
                AttachLinkRequest(
                    idempotencyKey = "attach-1",
                    eventId = eventId,
                    draftId = draftId,
                    expectedRevision = 0,
                ),
            )
            assertTrue("the stale evidence was not refused: $refused", refused is WorkflowCommandOutcome.Conflict)
            val link = database.contextLinkDao().findByPair(eventId, draftId)!!
            assertEquals(ContextLinkState.PROPOSED, link.state)
            assertEquals(0, link.revision)
        } finally {
            database.close()
        }
    }

    @Test
    fun `the evidence of one pair does not attach another pair`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val captureId = saveCapture(commands, "save-note-1", NOTE_TEXT)
            val provenId = storeEvent(commands, captureId, "event-1")
            val otherId = storeEvent(
                commands,
                captureId,
                "event-2",
                span = "Circle K snack 20,000 at 15:15",
                amountVnd = 20_000L,
            )
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val agent = WorkflowCallerScope.AgentSession(
                scopeId = "agent-1",
                captureIds = setOf(captureId),
                draftIds = setOf(draftId),
            )
            commands.proposeLink(
                agent,
                ProposeLinkRequest(
                    idempotencyKey = "propose-1",
                    eventId = provenId,
                    draftId = draftId,
                ),
            )
            commands.proposeLink(
                agent,
                ProposeLinkRequest(
                    idempotencyKey = "propose-2",
                    eventId = otherId,
                    draftId = draftId,
                ),
            )

            // The other pair rests on its own evidence, which does not prove it.
            val refused = commands.attachLink(
                agent,
                AttachLinkRequest(
                    idempotencyKey = "attach-1",
                    eventId = otherId,
                    draftId = draftId,
                    expectedRevision = 0,
                ),
            )
            assertTrue("the other pair was attached: $refused", refused is WorkflowCommandOutcome.Denied)
            assertEquals(
                WorkflowDenialReason.ATTACH_POLICY,
                (refused as WorkflowCommandOutcome.Denied).reason,
            )
            assertEquals(
                ContextLinkState.PROPOSED,
                database.contextLinkDao().findByPair(otherId, draftId)!!.state,
            )
            // The proven pair is untouched by the refused one.
            assertEquals(
                ContextLinkState.PROPOSED,
                database.contextLinkDao().findByPair(provenId, draftId)!!.state,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `a refusal of a link is the user's own and stays refused`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val captureId = saveCapture(commands, "save-note-1", NOTE_TEXT)
            val eventId = storeEvent(commands, captureId, "event-1")
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val agent = WorkflowCallerScope.AgentSession(
                scopeId = "agent-1",
                captureIds = setOf(captureId),
                draftIds = setOf(draftId),
            )

            val refused = commands.rejectLink(
                agent,
                RejectLinkRequest(
                    idempotencyKey = "reject-1",
                    eventId = eventId,
                    draftId = draftId,
                    expectedRevision = 0,
                ),
            )
            assertTrue("the agent's refusal was not refused: $refused", refused is WorkflowCommandOutcome.Denied)
            assertEquals(
                WorkflowDenialReason.USER_DECISION_REQUIRED,
                (refused as WorkflowCommandOutcome.Denied).reason,
            )

            commands.proposeLink(
                WorkflowCallerScope.User,
                ProposeLinkRequest(
                    idempotencyKey = "propose-1",
                    eventId = eventId,
                    draftId = draftId,
                ),
            )
            val rejected = commands.rejectLink(
                WorkflowCallerScope.User,
                RejectLinkRequest(
                    idempotencyKey = "reject-2",
                    eventId = eventId,
                    draftId = draftId,
                    expectedRevision = 0,
                    userOverride = "USER_REJECTED",
                ),
            )
            assertTrue("the refusal was not applied: $rejected", rejected is WorkflowCommandOutcome.Applied)

            // The refused pair is never recreated, by anyone.
            val again = commands.proposeLink(
                WorkflowCallerScope.User,
                ProposeLinkRequest(
                    idempotencyKey = "propose-2",
                    eventId = eventId,
                    draftId = draftId,
                ),
            )
            assertTrue("the refused pair was recreated: $again", again is WorkflowCommandOutcome.Denied)
            assertEquals(
                WorkflowDenialReason.LINK_ALREADY_REJECTED,
                (again as WorkflowCommandOutcome.Denied).reason,
            )
            val link = database.contextLinkDao().findByPair(eventId, draftId)!!
            assertEquals(ContextLinkState.REJECTED, link.state)
            assertEquals("USER_REJECTED", link.userOverride)
        } finally {
            database.close()
        }
    }

    @Test
    fun `unlinking restores the event context and keeps the bank evidence`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val captureId = saveCapture(commands, "save-note-1", NOTE_TEXT)
            val eventId = storeEvent(commands, captureId, "event-1")
            val draftId = storeDraft(database, "fuel-key", 50_000L)

            commands.proposeLink(
                WorkflowCallerScope.User,
                ProposeLinkRequest(
                    idempotencyKey = "propose-1",
                    eventId = eventId,
                    draftId = draftId,
                ),
            )
            commands.attachLink(
                WorkflowCallerScope.User,
                AttachLinkRequest(
                    idempotencyKey = "attach-1",
                    eventId = eventId,
                    draftId = draftId,
                    expectedRevision = 0,
                ),
            )
            assertEquals(NoteEventStatus.INTERPRETED, database.noteEventDao().findById(eventId)!!.status)

            val unlinked = commands.unlinkLink(
                WorkflowCallerScope.User,
                UnlinkLinkRequest(
                    idempotencyKey = "unlink-1",
                    eventId = eventId,
                    draftId = draftId,
                    expectedRevision = 1,
                ),
            )
            assertTrue("the unlink was refused: $unlinked", unlinked is WorkflowCommandOutcome.Applied)

            // The event keeps its own reading and holds no draft any more.
            val event = database.noteEventDao().findById(eventId)!!
            assertEquals("Fuel 50,000 at 15:00", event.normalizedText)
            assertEquals(NoteEventStatus.INTERPRETED, event.status)
            assertTrue(database.contextLinkDao().findByPair(eventId, draftId) == null)

            // The bank evidence is untouched: the draft and its parsed fields are as they were.
            val draft = database.pendingBankDraftDao().findById(draftId)!!
            assertEquals(IntakeReviewStatus.PENDING, draft.reviewStatus)
            assertEquals(BANK_MEMO, draft.memo)
            assertEquals(50_000L, draft.amountVnd)
            assertNull(draft.confirmedTransactionId)
        } finally {
            database.close()
        }
    }

    // --- Classification ------------------------------------------------------

    @Test
    fun `a repeated classification request asks the model once`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val request = ClassificationRequest(
                idempotencyKey = "classify-1",
                draftId = draftId,
                description = BANK_MEMO,
            )

            val first = commands.requestClassification(WorkflowCallerScope.User, request)
            assertTrue("the classification was refused: $first", first is WorkflowCommandOutcome.Applied)
            assertEquals(1, engine.calls)

            val second = commands.requestClassification(WorkflowCallerScope.User, request)
            assertTrue("the repeat was not a no-change: $second", second is WorkflowCommandOutcome.NoChange)
            assertEquals("the model was asked a second time", 1, engine.calls)

            // One stored prediction, and the draft's own fields are untouched.
            val stored = database.classificationSnapshotDao().findForDraft(draftId)
            assertNotNull(stored)
            assertEquals(PredictionSource.MODEL.name, stored!!.decisionSource)
            val draft = database.pendingBankDraftDao().findById(draftId)!!
            assertEquals(IntakeReviewStatus.PENDING, draft.reviewStatus)
            assertNull(draft.reviewCategoryId)
            assertEquals(0, draft.reviewRevision)
        } finally {
            database.close()
        }
    }

    @Test
    fun `two concurrent classification requests ask the model once`() = runTest {
        val database = open()
        try {
            val gate = Gate()
            val counting = RecordingEngine(gate = gate)
            val commands = commands(database, engine = counting)
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val request = ClassificationRequest(
                idempotencyKey = "classify-1",
                draftId = draftId,
                description = BANK_MEMO,
            )

            val first = async(Dispatchers.IO) {
                commands.requestClassification(WorkflowCallerScope.User, request)
            }
            // The first attempt holds the claim on the key and is inside the model.
            gate.awaitEntered()
            val second = commands.requestClassification(WorkflowCallerScope.User, request)

            assertEquals("the model was asked twice", 1, counting.calls)
            assertTrue(
                "the second request was not answered as running: $second",
                second is WorkflowCommandOutcome.Failed,
            )
            assertTrue(
                "the running request was not named: ${(second as WorkflowCommandOutcome.Failed).reason}",
                (second as WorkflowCommandOutcome.Failed).reason.contains("already running"),
            )

            gate.open()
            val firstOutcome = first.await()
            assertTrue("the first request was refused: $firstOutcome", firstOutcome is WorkflowCommandOutcome.Applied)

            // The finished request is answered from the record; the model is not asked again.
            val third = commands.requestClassification(WorkflowCallerScope.User, request)
            assertTrue("the repeat was not a no-change: $third", third is WorkflowCommandOutcome.NoChange)
            assertEquals("the model was asked again", 1, counting.calls)
            assertEquals(1, database.classificationSnapshotDao().findForDraft(draftId)?.let { 1 } ?: 0)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a concurrent request with another payload under the same key is a conflict`() = runTest {
        val database = open()
        try {
            val gate = Gate()
            val counting = RecordingEngine(gate = gate)
            val commands = commands(database, engine = counting)
            val draftId = storeDraft(database, "fuel-key", 50_000L)

            val first = async(Dispatchers.IO) {
                commands.requestClassification(
                    WorkflowCallerScope.User,
                    ClassificationRequest(
                        idempotencyKey = "classify-1",
                        draftId = draftId,
                        description = BANK_MEMO,
                    ),
                )
            }
            gate.awaitEntered()
            val changed = commands.requestClassification(
                WorkflowCallerScope.User,
                ClassificationRequest(
                    idempotencyKey = "classify-1",
                    draftId = draftId,
                    description = "Another memo",
                ),
            )
            assertTrue("the changed request was not a conflict: $changed", changed is WorkflowCommandOutcome.Conflict)
            assertEquals("the model was asked twice", 1, counting.calls)

            gate.open()
            first.await()
        } finally {
            database.close()
        }
    }

    @Test
    fun `a claim left by an interrupted attempt is taken over by a later attempt`() = runTest {
        val database = open()
        try {
            val counting = RecordingEngine()
            val commands = commands(database, engine = counting)
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val request = ClassificationRequest(
                idempotencyKey = "classify-crash",
                draftId = draftId,
                description = BANK_MEMO,
            )
            // The claim of an attempt that is gone: the process died with the row still
            // pending and its lease ended. Nothing of that attempt is alive in this
            // process, so the claim may be taken over.
            database.workflowOperationDao().insert(
                WorkflowOperationEntity(
                    idempotencyKey = request.idempotencyKey,
                    scopeId = WorkflowCallerScope.User.scopeId,
                    command = WorkflowCommandService.COMMAND_REQUEST_CLASSIFICATION,
                    payloadDigest = canonicalDependencyDigest(
                        listOf(
                            WorkflowCommandService.COMMAND_REQUEST_CLASSIFICATION,
                            draftId.toString(),
                            request.description,
                            null,
                        ),
                    ),
                    outcome = OPERATION_OUTCOME_PENDING,
                    changeSummary = null,
                    ownerToken = "the-attempt-that-was-interrupted",
                    leaseExpiresAtEpochMillis = clock.now - 1L,
                    createdAtEpochMillis = clock.now - 10_000L,
                ),
            )

            val taken = commands.requestClassification(WorkflowCallerScope.User, request)

            assertTrue("the takeover was refused: $taken", taken is WorkflowCommandOutcome.Applied)
            assertEquals("the model was not asked", 1, counting.calls)
            val operation = database.workflowOperationDao().findByKey("classify-crash")!!
            assertEquals(OPERATION_OUTCOME_APPLIED, operation.outcome)
            assertNull(operation.ownerToken)
        } finally {
            database.close()
        }
    }

    @Test
    fun `an attempt whose claim was taken over writes nothing`() = runTest {
        val database = open()
        try {
            val gate = Gate()
            val counting = RecordingEngine(gate = gate)
            val commands = commands(database, engine = counting, claimLeaseMillis = 300L)
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val request = ClassificationRequest(
                idempotencyKey = "classify-1",
                draftId = draftId,
                description = BANK_MEMO,
            )

            val first = async(Dispatchers.IO) {
                commands.requestClassification(WorkflowCallerScope.User, request)
            }
            gate.awaitEntered()
            val row = database.workflowOperationDao().findByKey("classify-1")!!
            // Another process took the claim over: the row carries that attempt's token
            // now, so the extension of this attempt's claim finds no claim of its own.
            val taken = database.workflowOperationDao().takeOverClaim(
                id = row.id,
                expectedOwnerToken = row.ownerToken!!,
                ownerToken = "the-attempt-of-another-process",
                leaseExpiresAtEpochMillis = clock.now + 60_000L,
                pendingOutcome = OPERATION_OUTCOME_PENDING,
            )
            assertEquals(1, taken)

            // The attempt that lost its claim is failed, and it writes nothing.
            val outcome = first.await()
            assertTrue("the lost claim was not reported: $outcome", outcome is WorkflowCommandOutcome.Failed)
            assertTrue(
                "the lost claim was not named: ${(outcome as WorkflowCommandOutcome.Failed).reason}",
                (outcome as WorkflowCommandOutcome.Failed).reason.contains("could not be kept"),
            )
            assertNull(database.classificationSnapshotDao().findForDraft(draftId))
            val operation = database.workflowOperationDao().findByKey("classify-1")!!
            assertEquals(OPERATION_OUTCOME_PENDING, operation.outcome)
            assertEquals("the-attempt-of-another-process", operation.ownerToken)
            assertEquals("the model was asked twice", 1, counting.calls)

            // The key is still held by the attempt that took it over: the model is not
            // asked again while that claim stands.
            val second = commands.requestClassification(WorkflowCallerScope.User, request)
            assertTrue("the second request was not refused: $second", second is WorkflowCommandOutcome.Failed)
            assertEquals("the model was asked twice", 1, counting.calls)

            gate.open()
        } finally {
            database.close()
        }
    }

    @Test
    fun `a claim that cannot be renewed fails the request and keeps the model to one call`() =
        runTest {
            val database = open()
            try {
                val gate = Gate()
                val counting = RecordingEngine(gate = gate)
                // A short lease and a real clock: the claim is extended while the model
                // works, and the extension is what fails here.
                val service = WorkflowCommandService(
                    database = database,
                    clock = SystemLedgerClock,
                    timezoneId = { CAPTURE_ZONE },
                    engine = counting,
                    claimLeaseMillis = 300L,
                )
                val draftId = storeDraft(database, "fuel-key", 50_000L)
                val request = ClassificationRequest(
                    idempotencyKey = "classify-1",
                    draftId = draftId,
                    description = BANK_MEMO,
                )
                // The extension of the claim fails: the database refuses the write that
                // keeps the claim alive.
                failRenewalOf(database, "classify-1")

                val first = async(Dispatchers.IO) {
                    service.requestClassification(WorkflowCallerScope.User, request)
                }
                gate.awaitEntered()

                // The claim could not be kept: the request is failed rather than
                // carried out under a claim the attempt cannot vouch for.
                val outcome = first.await()
                assertTrue("the request was not failed: $outcome", outcome is WorkflowCommandOutcome.Failed)
                assertTrue(
                    "the failure was not named: ${(outcome as WorkflowCommandOutcome.Failed).reason}",
                    (outcome as WorkflowCommandOutcome.Failed).reason.contains("could not be kept"),
                )
                assertNull(
                    "the request was carried out anyway",
                    database.classificationSnapshotDao().findForDraft(draftId),
                )

                // The model work is still running and the key is still held: the lease
                // ends, but no attempt takes the claim over and no attempt asks the
                // model a second time for one request.
                withContext(Dispatchers.IO) { delay(500) }
                val second = service.requestClassification(WorkflowCallerScope.User, request)
                assertTrue("the second request was not refused: $second", second is WorkflowCommandOutcome.Failed)
                assertTrue(
                    "the running request was not named: ${(second as WorkflowCommandOutcome.Failed).reason}",
                    (second as WorkflowCommandOutcome.Failed).reason.contains("already running"),
                )
                assertEquals("the model was asked twice", 1, counting.calls)

                // The work ends: the key is free again and the request may be made.
                gate.open()
                awaitClaimGivenBack(database, "classify-1")
                val third = service.requestClassification(WorkflowCallerScope.User, request)
                assertTrue("the retry was refused: $third", third is WorkflowCommandOutcome.Applied)
                assertEquals(2, counting.calls)
            } finally {
                database.close()
            }
        }

    @Test
    fun `a claim outlives an inference that is longer than its lease`() = runTest {
        val database = open()
        try {
            val gate = Gate()
            val counting = RecordingEngine(gate = gate)
            // A lease of a third of a second, and a real clock: the model's answer takes
            // longer than the lease would last, so the claim has to be kept alive while
            // the model works.
            val service = WorkflowCommandService(
                database = database,
                clock = SystemLedgerClock,
                timezoneId = { CAPTURE_ZONE },
                engine = counting,
                claimLeaseMillis = 300L,
            )
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val request = ClassificationRequest(
                idempotencyKey = "classify-1",
                draftId = draftId,
                description = BANK_MEMO,
            )

            val first = async(Dispatchers.IO) {
                service.requestClassification(WorkflowCallerScope.User, request)
            }
            gate.awaitEntered()
            // Real time passes while the model is working: longer than the lease.
            withContext(Dispatchers.IO) { delay(700) }

            // The claim is still held: the second attempt does not take it over and does
            // not ask the model a second time.
            val second = service.requestClassification(WorkflowCallerScope.User, request)
            assertEquals("the model was asked twice", 1, counting.calls)
            assertTrue(
                "the second request was not answered as running: $second",
                second is WorkflowCommandOutcome.Failed,
            )
            assertTrue(
                "the running request was not named: ${(second as WorkflowCommandOutcome.Failed).reason}",
                (second as WorkflowCommandOutcome.Failed).reason.contains("already running"),
            )

            // The first attempt finishes and stores its answer: the claim was its own.
            gate.open()
            val firstOutcome = first.await()
            assertTrue("the first request was refused: $firstOutcome", firstOutcome is WorkflowCommandOutcome.Applied)
            assertNotNull(database.classificationSnapshotDao().findForDraft(draftId))
            val operation = database.workflowOperationDao().findByKey("classify-1")!!
            assertEquals(OPERATION_OUTCOME_APPLIED, operation.outcome)
            assertNull(operation.ownerToken)
        } finally {
            database.close()
        }
    }

    @Test
    fun `an attempt between taking the key and writing the claim keeps a competitor away`() =
        runTest {
            val database = open()
            val taken = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            try {
                val counting = RecordingEngine()
                // Two command services over one database: the second one is another
                // attempt of the same request.
                val first = commands(database, engine = counting)
                val second = commands(database, engine = counting)
                val draftId = storeDraft(database, "fuel-key", 50_000L)
                val request = ClassificationRequest(
                    idempotencyKey = "classify-1",
                    draftId = draftId,
                    description = BANK_MEMO,
                )
                WorkflowClaimGuard.pointHook = { key, point ->
                    if (key == "classify-1" && point == WorkflowClaimGuard.Point.KEY_TAKEN) {
                        taken.complete(Unit)
                        resume.await()
                    }
                }

                val attempt = async(Dispatchers.IO) {
                    first.requestClassification(WorkflowCallerScope.User, request)
                }
                taken.await()
                // The attempt holds the key in the process and the durable claim is not
                // written yet: this is the gap in which a competitor used to find the
                // claim and take it over while its owner was still alive.
                assertNull(database.workflowOperationDao().findByKey("classify-1"))

                val competing = second.requestClassification(WorkflowCallerScope.User, request)
                assertTrue(
                    "the competitor was not refused: $competing",
                    competing is WorkflowCommandOutcome.Failed,
                )
                assertTrue(
                    "the running request was not named: ${(competing as WorkflowCommandOutcome.Failed).reason}",
                    (competing as WorkflowCommandOutcome.Failed).reason.contains("already running"),
                )
                assertEquals("the model was asked", 0, counting.calls)
                assertNull(
                    "the competitor wrote a claim of its own",
                    database.workflowOperationDao().findByKey("classify-1"),
                )

                // The attempt goes on: it writes the claim and asks the model once.
                resume.complete(Unit)
                val outcome = attempt.await()
                assertTrue("the first request was refused: $outcome", outcome is WorkflowCommandOutcome.Applied)
                assertEquals("the model was asked twice", 1, counting.calls)
                val operation = database.workflowOperationDao().findByKey("classify-1")!!
                assertEquals(OPERATION_OUTCOME_APPLIED, operation.outcome)
                assertNull(operation.ownerToken)
                assertFalse("the key is still held", WorkflowClaimGuard.isHeld("classify-1"))

                // The key is free again: the request is answered from the record, and
                // the model is not asked a second time.
                val again = second.requestClassification(WorkflowCallerScope.User, request)
                assertTrue(
                    "the repeat was not answered from the record: $again",
                    again is WorkflowCommandOutcome.NoChange,
                )
                assertEquals("the model was asked twice", 1, counting.calls)
            } finally {
                // The paused attempt is let go, so that a failed assertion is reported
                // as the failure it is and not as a timeout.
                resume.complete(Unit)
                WorkflowClaimGuard.pointHook = null
                database.close()
            }
        }

    @Test
    fun `a competitor with another payload is a conflict before the claim is written`() =
        runTest {
            val database = open()
            val taken = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            try {
                val counting = RecordingEngine()
                val first = commands(database, engine = counting)
                val second = commands(database, engine = counting)
                val draftId = storeDraft(database, "fuel-key", 50_000L)
                val request = ClassificationRequest(
                    idempotencyKey = "classify-1",
                    draftId = draftId,
                    description = BANK_MEMO,
                )
                WorkflowClaimGuard.pointHook = { key, point ->
                    if (key == "classify-1" && point == WorkflowClaimGuard.Point.KEY_TAKEN) {
                        taken.complete(Unit)
                        resume.await()
                    }
                }

                val attempt = async(Dispatchers.IO) {
                    first.requestClassification(WorkflowCallerScope.User, request)
                }
                taken.await()
                // The attempt holds the key in the process and the durable claim is not
                // written yet: the window in which there is no record to compare a
                // competitor's request with.
                assertNull(database.workflowOperationDao().findByKey("classify-1"))

                // The same key with another request is a conflict in this window as
                // well: the key names one request.
                val changed = second.requestClassification(
                    WorkflowCallerScope.User,
                    ClassificationRequest(
                        idempotencyKey = "classify-1",
                        draftId = draftId,
                        description = "Another memo",
                    ),
                )
                assertTrue(
                    "the changed request was not a conflict: $changed",
                    changed is WorkflowCommandOutcome.Conflict,
                )
                assertEquals(
                    "the conflict named the other request",
                    WorkflowCommandService.CONFLICT_KEY_IN_USE,
                    (changed as WorkflowCommandOutcome.Conflict).reason,
                )
                assertEquals("the model was asked", 0, counting.calls)
                assertNull(
                    "the competitor wrote a claim of its own",
                    database.workflowOperationDao().findByKey("classify-1"),
                )

                // The attempt goes on: it writes the claim and asks the model once.
                resume.complete(Unit)
                val outcome = attempt.await()
                assertTrue("the first request was refused: $outcome", outcome is WorkflowCommandOutcome.Applied)
                assertEquals("the model was asked twice", 1, counting.calls)
                val operation = database.workflowOperationDao().findByKey("classify-1")!!
                assertEquals(OPERATION_OUTCOME_APPLIED, operation.outcome)
                assertNull(operation.ownerToken)

                // The key is free again: the request is answered from the record, and
                // the model is not asked a second time.
                val again = second.requestClassification(WorkflowCallerScope.User, request)
                assertTrue(
                    "the repeat was not answered from the record: $again",
                    again is WorkflowCommandOutcome.NoChange,
                )
                assertEquals("the model was asked twice", 1, counting.calls)
            } finally {
                resume.complete(Unit)
                WorkflowClaimGuard.pointHook = null
                database.close()
            }
        }

    @Test
    fun `a competitor of another caller is a conflict before the claim is written`() = runTest {
        val database = open()
        val taken = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        try {
            val counting = RecordingEngine()
            val first = commands(database, engine = counting)
            val second = commands(database, engine = counting)
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val request = ClassificationRequest(
                idempotencyKey = "classify-1",
                draftId = draftId,
                description = BANK_MEMO,
            )
            // Another caller that may act on this draft: the key of the running
            // request is not this caller's key.
            val agent = WorkflowCallerScope.AgentSession(
                scopeId = "agent-1",
                captureIds = emptySet(),
                draftIds = setOf(draftId),
            )
            WorkflowClaimGuard.pointHook = { key, point ->
                if (key == "classify-1" && point == WorkflowClaimGuard.Point.KEY_TAKEN) {
                    taken.complete(Unit)
                    resume.await()
                }
            }

            val attempt = async(Dispatchers.IO) {
                first.requestClassification(WorkflowCallerScope.User, request)
            }
            taken.await()
            assertNull(database.workflowOperationDao().findByKey("classify-1"))

            val foreign = second.requestClassification(agent, request)
            assertTrue(
                "the other caller was not a conflict: $foreign",
                foreign is WorkflowCommandOutcome.Conflict,
            )
            val reason = (foreign as WorkflowCommandOutcome.Conflict).reason
            assertEquals(
                "the conflict did not use the one wording",
                WorkflowCommandService.CONFLICT_KEY_IN_USE,
                reason,
            )
            // The answer never says whose record holds the key: it names neither the
            // other caller nor what that caller changed, and not even whether that
            // record was applied.
            assertTrue("the conflict named a principal: $reason", !reason.contains("user"))
            assertTrue("the conflict named the request: $reason", !reason.contains(BANK_MEMO))
            assertEquals("the model was asked", 0, counting.calls)
            assertNull(
                "the competitor wrote a claim of its own",
                database.workflowOperationDao().findByKey("classify-1"),
            )

            // The attempt goes on: it writes the claim and asks the model once.
            resume.complete(Unit)
            val outcome = attempt.await()
            assertTrue("the first request was refused: $outcome", outcome is WorkflowCommandOutcome.Applied)
            assertEquals("the model was asked twice", 1, counting.calls)
        } finally {
            resume.complete(Unit)
            WorkflowClaimGuard.pointHook = null
            database.close()
        }
    }

    @Test
    fun `a caller that may not touch the draft is refused while a claim is written`() = runTest {
        val database = open()
        val taken = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        try {
            val counting = RecordingEngine()
            val first = commands(database, engine = counting)
            val second = commands(database, engine = counting)
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val request = ClassificationRequest(
                idempotencyKey = "classify-1",
                draftId = draftId,
                description = BANK_MEMO,
            )
            // A caller that may not act on this draft at all.
            val outsider = WorkflowCallerScope.AgentSession(
                scopeId = "agent-2",
                captureIds = emptySet(),
                draftIds = emptySet(),
            )
            WorkflowClaimGuard.pointHook = { key, point ->
                if (key == "classify-1" && point == WorkflowClaimGuard.Point.KEY_TAKEN) {
                    taken.complete(Unit)
                    resume.await()
                }
            }

            val attempt = async(Dispatchers.IO) {
                first.requestClassification(WorkflowCallerScope.User, request)
            }
            taken.await()

            // The caller's own authority is settled before the key is even looked at:
            // the request is refused, and the refusal says nothing about the key.
            val refused = second.requestClassification(outsider, request)
            assertTrue(
                "the request was not refused: $refused",
                refused is WorkflowCommandOutcome.Denied,
            )
            assertEquals(
                "the refusal named another rule",
                WorkflowDenialReason.CALLER_SCOPE,
                (refused as WorkflowCommandOutcome.Denied).reason,
            )
            val refusedReason = (refused as WorkflowCommandOutcome.Denied).reason
            // The same refusal is given when the key was never used at all: the
            // existence of a record the caller may not touch is not observable.
            val freeKey = second.requestClassification(
                outsider,
                ClassificationRequest(
                    idempotencyKey = "classify-2",
                    draftId = draftId,
                    description = BANK_MEMO,
                ),
            )
            assertTrue(
                "the free key was not refused: $freeKey",
                freeKey is WorkflowCommandOutcome.Denied,
            )
            assertEquals(
                "the refusal told the caller that the key was in use",
                (freeKey as WorkflowCommandOutcome.Denied).reason,
                refusedReason,
            )
            assertEquals("the model was asked", 0, counting.calls)
            assertNull(
                "the refused request wrote a claim of its own",
                database.workflowOperationDao().findByKey("classify-2"),
            )

            // The attempt goes on: the refusal disturbed nothing, and the model is
            // asked once.
            resume.complete(Unit)
            val outcome = attempt.await()
            assertTrue("the first request was refused: $outcome", outcome is WorkflowCommandOutcome.Applied)
            assertEquals("the model was asked twice", 1, counting.calls)
        } finally {
            resume.complete(Unit)
            WorkflowClaimGuard.pointHook = null
            database.close()
        }
    }

    @Test
    fun `an attempt whose claim was written keeps the claim when the lease ends`() = runTest {
        val database = open()
        val written = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        try {
            val counting = RecordingEngine()
            val first = commands(database, engine = counting)
            val second = commands(database, engine = counting)
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val request = ClassificationRequest(
                idempotencyKey = "classify-1",
                draftId = draftId,
                description = BANK_MEMO,
            )
            WorkflowClaimGuard.pointHook = { key, point ->
                if (key == "classify-1" && point == WorkflowClaimGuard.Point.CLAIM_WRITTEN) {
                    written.complete(Unit)
                    resume.await()
                }
            }

            val attempt = async(Dispatchers.IO) {
                first.requestClassification(WorkflowCallerScope.User, request)
            }
            written.await()
            val claimed = database.workflowOperationDao().findByKey("classify-1")!!
            assertEquals(OPERATION_OUTCOME_PENDING, claimed.outcome)
            assertEquals("the model was asked before the claim was written", 0, counting.calls)

            // The lease of the claim ends while the attempt is still alive: the claim
            // is not taken over, and the model is not asked a second time.
            clock.now += 60_000L
            val competing = second.requestClassification(WorkflowCallerScope.User, request)
            assertTrue(
                "the competitor was not refused: $competing",
                competing is WorkflowCommandOutcome.Failed,
            )
            assertTrue(
                "the running request was not named: ${(competing as WorkflowCommandOutcome.Failed).reason}",
                (competing as WorkflowCommandOutcome.Failed).reason.contains("already running"),
            )
            assertEquals("the model was asked", 0, counting.calls)
            assertEquals(
                "the claim was taken over",
                claimed.ownerToken,
                database.workflowOperationDao().findByKey("classify-1")!!.ownerToken,
            )

            // The attempt goes on, asks the model once, and stores its answer.
            resume.complete(Unit)
            val outcome = attempt.await()
            assertTrue("the first request was refused: $outcome", outcome is WorkflowCommandOutcome.Applied)
            assertEquals("the model was asked twice", 1, counting.calls)
            assertNotNull(database.classificationSnapshotDao().findForDraft(draftId))
            val operation = database.workflowOperationDao().findByKey("classify-1")!!
            assertEquals(OPERATION_OUTCOME_APPLIED, operation.outcome)
            assertNull(operation.ownerToken)
        } finally {
            resume.complete(Unit)
            WorkflowClaimGuard.pointHook = null
            database.close()
        }
    }

    @Test
    fun `the attempt that took a claim over keeps the key from a competitor`() = runTest {
        val database = open()
        val written = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        try {
            val counting = RecordingEngine()
            val first = commands(database, engine = counting)
            val second = commands(database, engine = counting)
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val request = ClassificationRequest(
                idempotencyKey = "classify-1",
                draftId = draftId,
                description = BANK_MEMO,
            )
            // The claim of an attempt that is gone: the row is pending, its lease has
            // ended, and no live attempt holds the key.
            database.workflowOperationDao().insert(
                WorkflowOperationEntity(
                    idempotencyKey = request.idempotencyKey,
                    scopeId = WorkflowCallerScope.User.scopeId,
                    command = WorkflowCommandService.COMMAND_REQUEST_CLASSIFICATION,
                    payloadDigest = canonicalDependencyDigest(
                        listOf(
                            WorkflowCommandService.COMMAND_REQUEST_CLASSIFICATION,
                            draftId.toString(),
                            request.description,
                            null,
                        ),
                    ),
                    outcome = OPERATION_OUTCOME_PENDING,
                    changeSummary = null,
                    ownerToken = "the-attempt-that-was-interrupted",
                    leaseExpiresAtEpochMillis = clock.now - 1L,
                    createdAtEpochMillis = clock.now - 10_000L,
                ),
            )
            WorkflowClaimGuard.pointHook = { key, point ->
                if (key == "classify-1" && point == WorkflowClaimGuard.Point.CLAIM_WRITTEN) {
                    written.complete(Unit)
                    resume.await()
                }
            }

            val attempt = async(Dispatchers.IO) {
                second.requestClassification(WorkflowCallerScope.User, request)
            }
            written.await()
            // The takeover moved the claim: the row names the attempt that took it.
            val claimed = database.workflowOperationDao().findByKey("classify-1")!!
            assertEquals(OPERATION_OUTCOME_PENDING, claimed.outcome)
            assertTrue(
                "the claim was not taken over",
                claimed.ownerToken != "the-attempt-that-was-interrupted",
            )
            assertEquals("the model was asked before the claim was taken over", 0, counting.calls)

            // A competitor under the same key finds the claim and does not take it
            // over: the attempt that took it over is alive.
            val competing = first.requestClassification(WorkflowCallerScope.User, request)
            assertTrue(
                "the competitor was not refused: $competing",
                competing is WorkflowCommandOutcome.Failed,
            )
            assertTrue(
                "the running request was not named: ${(competing as WorkflowCommandOutcome.Failed).reason}",
                (competing as WorkflowCommandOutcome.Failed).reason.contains("already running"),
            )
            assertEquals("the model was asked", 0, counting.calls)
            assertEquals(
                "the claim was taken over again",
                claimed.ownerToken,
                database.workflowOperationDao().findByKey("classify-1")!!.ownerToken,
            )

            // The attempt that took the claim over asks the model once.
            resume.complete(Unit)
            val outcome = attempt.await()
            assertTrue("the request was refused: $outcome", outcome is WorkflowCommandOutcome.Applied)
            assertEquals("the model was asked twice", 1, counting.calls)
            assertNotNull(database.classificationSnapshotDao().findForDraft(draftId))
            val operation = database.workflowOperationDao().findByKey("classify-1")!!
            assertEquals(OPERATION_OUTCOME_APPLIED, operation.outcome)
            assertNull(operation.ownerToken)
        } finally {
            resume.complete(Unit)
            WorkflowClaimGuard.pointHook = null
            database.close()
        }
    }

    @Test
    fun `an attempt whose claim was taken over before the model is asked does not ask the model`() =
        runTest {
            val database = open()
            val written = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            try {
                val counting = RecordingEngine()
                val commands = commands(database, engine = counting)
                val draftId = storeDraft(database, "fuel-key", 50_000L)
                val request = ClassificationRequest(
                    idempotencyKey = "classify-1",
                    draftId = draftId,
                    description = BANK_MEMO,
                )
                WorkflowClaimGuard.pointHook = { key, point ->
                    if (key == "classify-1" && point == WorkflowClaimGuard.Point.CLAIM_WRITTEN) {
                        written.complete(Unit)
                        resume.await()
                    }
                }

                val attempt = async(Dispatchers.IO) {
                    commands.requestClassification(WorkflowCallerScope.User, request)
                }
                written.await()
                val claimed = database.workflowOperationDao().findByKey("classify-1")!!
                // Another attempt took the claim over while this attempt was starting
                // up: the row names that attempt now.
                val taken = database.workflowOperationDao().takeOverClaim(
                    id = claimed.id,
                    expectedOwnerToken = claimed.ownerToken!!,
                    ownerToken = "the-attempt-of-another-process",
                    leaseExpiresAtEpochMillis = clock.now + 60_000L,
                    pendingOutcome = OPERATION_OUTCOME_PENDING,
                )
                assertEquals(1, taken)

                // The attempt goes on and finds that the claim is not its own: it does
                // not ask the model, and it writes nothing.
                resume.complete(Unit)
                val outcome = attempt.await()
                assertTrue(
                    "the lost claim was not reported: $outcome",
                    outcome is WorkflowCommandOutcome.Failed,
                )
                assertTrue(
                    "the lost claim was not named: ${(outcome as WorkflowCommandOutcome.Failed).reason}",
                    (outcome as WorkflowCommandOutcome.Failed).reason.contains("could not be kept"),
                )
                assertEquals("the model was asked", 0, counting.calls)
                assertNull(database.classificationSnapshotDao().findForDraft(draftId))
                val operation = database.workflowOperationDao().findByKey("classify-1")!!
                assertEquals(OPERATION_OUTCOME_PENDING, operation.outcome)
                assertEquals("the-attempt-of-another-process", operation.ownerToken)
            } finally {
                resume.complete(Unit)
                WorkflowClaimGuard.pointHook = null
                database.close()
            }
        }

    @Test
    fun `a key of another caller and a changed request are answered alike`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val mine = saveCapture(commands, "save-note-mine", NOTE_TEXT)
            val agent = WorkflowCallerScope.AgentSession(
                scopeId = "agent-1",
                captureIds = setOf(mine),
                draftIds = emptySet(),
            )
            // The key of another caller.
            commands.saveCapture(
                WorkflowCallerScope.User,
                SaveCaptureRequest(idempotencyKey = "shared-key", bodyText = NOTE_TEXT),
            )
            val foreign = commands.reviseCapture(
                agent,
                ReviseCaptureRequest(
                    idempotencyKey = "shared-key",
                    captureId = mine,
                    expectedRevision = 0,
                    bodyText = "another text",
                ),
            )
            // The caller's own key, used with another request.
            commands.reviseCapture(
                agent,
                ReviseCaptureRequest(
                    idempotencyKey = "own-key",
                    captureId = mine,
                    expectedRevision = 0,
                    bodyText = "first text",
                ),
            )
            val own = commands.reviseCapture(
                agent,
                ReviseCaptureRequest(
                    idempotencyKey = "own-key",
                    captureId = mine,
                    expectedRevision = 1,
                    bodyText = "another text",
                ),
            )

            assertTrue("the foreign key was not a conflict: $foreign", foreign is WorkflowCommandOutcome.Conflict)
            assertTrue("the changed request was not a conflict: $own", own is WorkflowCommandOutcome.Conflict)
            // The two are answered with the same words: the answer never says whose
            // record holds the key.
            val foreignReason = (foreign as WorkflowCommandOutcome.Conflict).reason
            val ownReason = (own as WorkflowCommandOutcome.Conflict).reason
            assertEquals(foreignReason, ownReason)
            assertTrue("the conflict named a principal: $foreignReason", !foreignReason.contains("user"))
            // The request under the foreign key changed nothing.
            val stored = database.captureMessageDao().findById(mine)!!
            assertEquals("first text", stored.bodyText)
            assertEquals(1, stored.revision)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a cancelled classification leaves its key free once its model work has ended`() =
        runTest {
            val database = open()
            try {
                val gate = Gate()
                val counting = RecordingEngine(gate = gate)
                val commands = commands(database, engine = counting)
                val draftId = storeDraft(database, "fuel-key", 50_000L)
                val request = ClassificationRequest(
                    idempotencyKey = "classify-1",
                    draftId = draftId,
                    description = BANK_MEMO,
                )

                val cancelled = async(Dispatchers.IO) {
                    commands.requestClassification(WorkflowCallerScope.User, request)
                }
                gate.awaitEntered()
                cancelled.cancel()
                cancelled.join()

                // The model work is not cancelled with the caller: the local model is a
                // native runtime whose execution cannot be interrupted. The key is held
                // while that work runs, so no attempt can ask the model a second time.
                assertNotNull(
                    "the key was given back while the model work was running",
                    database.workflowOperationDao().findByKey("classify-1"),
                )
                gate.open()

                // The work has really ended: the key is free again.
                awaitClaimGivenBack(database, "classify-1")
                assertNull(database.workflowOperationDao().findByKey("classify-1"))

                // The cancelled attempt left the key free: the work may be asked for again.
                val retry = commands.requestClassification(WorkflowCallerScope.User, request)
                assertTrue("the retry was refused: $retry", retry is WorkflowCommandOutcome.Applied)
                assertEquals(2, counting.calls)
            } finally {
                database.close()
            }
        }

    @Test
    fun `a failed classification leaves its key free`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val request = ClassificationRequest(
                idempotencyKey = "classify-1",
                draftId = draftId,
                description = BANK_MEMO,
            )

            engine.failure = "the local model could not answer"
            val failed = commands.requestClassification(WorkflowCallerScope.User, request)
            assertTrue("the failure was not reported: $failed", failed is WorkflowCommandOutcome.Failed)
            assertEquals(
                "the local model could not answer",
                (failed as WorkflowCommandOutcome.Failed).reason,
            )
            assertNull(database.classificationSnapshotDao().findForDraft(draftId))

            // The key is free again, so the corrected request may use it.
            engine.failure = null
            val retried = commands.requestClassification(WorkflowCallerScope.User, request)
            assertTrue("the retry was refused: $retried", retried is WorkflowCommandOutcome.Applied)
            assertEquals(2, engine.calls)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a finished classification is answered from the record after a restart`() = runTest {
        val database = open()
        val draftId = storeDraft(database, "fuel-key", 50_000L)
        val request = ClassificationRequest(
            idempotencyKey = "classify-1",
            draftId = draftId,
            description = BANK_MEMO,
        )
        val first = commands(database).requestClassification(WorkflowCallerScope.User, request)
        assertTrue("the first request was refused: $first", first is WorkflowCommandOutcome.Applied)
        database.close()

        val reopened = open()
        try {
            val again = commands(reopened).requestClassification(WorkflowCallerScope.User, request)
            assertTrue("the repeat was not a no-change: $again", again is WorkflowCommandOutcome.NoChange)
            assertEquals("the model was asked again", 1, engine.calls)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `a saved rule answers the classification without asking the model`() = runTest {
        val database = open()
        try {
            val categoryId = LedgerRepository(
                categoryDao = database.categoryDao(),
                transactionDao = database.transactionDao(),
                rememberedCategoryRuleDao = database.rememberedCategoryRuleDao(),
                captureMessageDao = database.captureMessageDao(),
                clock = clock,
            ).addCategory("Food")
            val food = database.categoryDao().findById(categoryId)!!
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val commands = commands(database, findRememberedCategory = { description ->
                if (description == BANK_MEMO) food else null
            })

            val outcome = commands.requestClassification(
                WorkflowCallerScope.User,
                ClassificationRequest(
                    idempotencyKey = "classify-1",
                    draftId = draftId,
                    description = BANK_MEMO,
                ),
            )
            assertTrue("the classification was refused: $outcome", outcome is WorkflowCommandOutcome.Applied)
            val change = (outcome as WorkflowCommandOutcome.Applied).change
            assertTrue(change is WorkflowChange.Classified)
            assertEquals(PredictionSource.REMEMBERED_RULE, (change as WorkflowChange.Classified).source)
            assertEquals(categoryId, change.suggestedCategoryId)
            assertEquals("the model was asked although a saved rule answered", 0, engine.calls)

            val stored = database.classificationSnapshotDao().findForDraft(draftId)!!
            assertEquals(PredictionSource.REMEMBERED_RULE.name, stored.decisionSource)
            assertEquals(categoryId, stored.suggestedCategoryId)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a refused classification request never reaches the model`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val agent = WorkflowCallerScope.AgentSession(
                scopeId = "agent-1",
                captureIds = emptySet(),
                draftIds = emptySet(),
            )

            val refused = commands.requestClassification(
                agent,
                ClassificationRequest(
                    idempotencyKey = "classify-1",
                    draftId = draftId,
                    description = BANK_MEMO,
                ),
            )
            assertTrue("the request was not refused: $refused", refused is WorkflowCommandOutcome.Denied)
            assertEquals(0, engine.calls)
            assertNull(database.classificationSnapshotDao().findForDraft(draftId))
        } finally {
            database.close()
        }
    }

    @Test
    fun `text that is blocked for safety never reaches the model`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val draftId = storeDraft(database, "fuel-key", 50_000L)

            val refused = commands.requestClassification(
                WorkflowCallerScope.User,
                ClassificationRequest(
                    idempotencyKey = "classify-1",
                    draftId = draftId,
                    description = "Your OTP is 382K26A0",
                ),
            )
            assertTrue(
                "the blocked text was not refused: $refused",
                refused is WorkflowCommandOutcome.NeedsClarification,
            )
            assertEquals(0, engine.calls)
            assertNull(database.classificationSnapshotDao().findForDraft(draftId))
        } finally {
            database.close()
        }
    }

    @Test
    fun `a late prediction never replaces a newer context`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val captureId = saveCapture(commands, "save-note-1", NOTE_TEXT)
            val eventId = storeEvent(commands, captureId, "event-1")
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            commands.proposeLink(
                WorkflowCallerScope.User,
                ProposeLinkRequest(
                    idempotencyKey = "propose-1",
                    eventId = eventId,
                    draftId = draftId,
                ),
            )

            // The context moves on while the model is working.
            engine.onClassify = {
                commands.updateEventInterpretation(
                    WorkflowCallerScope.User,
                    UpdateEventRequest(
                        idempotencyKey = "event-2",
                        captureId = captureId,
                        expectedCaptureRevision = 0,
                        interpretation = reading("Fuel 50,000 at 15:00", "Fuel 55,000 at 15:05"),
                        extractionVersion = EXTRACTION_VERSION,
                    ),
                )
            }

            val outcome = commands.requestClassification(
                WorkflowCallerScope.User,
                ClassificationRequest(
                    idempotencyKey = "classify-1",
                    draftId = draftId,
                    description = BANK_MEMO,
                ),
            )
            assertTrue("the late prediction was not a conflict: $outcome", outcome is WorkflowCommandOutcome.Conflict)
            assertNull(database.classificationSnapshotDao().findForDraft(draftId))
        } finally {
            database.close()
        }
    }

    @Test
    fun `the user's own category choice is preserved over a prediction`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val categoryId = LedgerRepository(
                categoryDao = database.categoryDao(),
                transactionDao = database.transactionDao(),
                rememberedCategoryRuleDao = database.rememberedCategoryRuleDao(),
                captureMessageDao = database.captureMessageDao(),
                clock = clock,
            ).addCategory("Food")
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            val draft = database.pendingBankDraftDao().findById(draftId)!!
            val saved = reviewCommands(database, clock).saveReview(
                draftId = draftId,
                expectedRevision = draft.reviewRevision,
                edits = BankDraftReviewEdits(
                    amountText = draft.amountVnd.toString(),
                    dateText = draft.statedDate.orEmpty(),
                    timeText = draft.statedTime.orEmpty(),
                    memoText = draft.memo.orEmpty(),
                    type = null,
                    categoryId = categoryId,
                    categoryResolved = true,
                ),
            )
            assertTrue("the review was not saved: $saved", saved is BankDraftReviewResult.Saved)

            val outcome = commands.requestClassification(
                WorkflowCallerScope.User,
                ClassificationRequest(
                    idempotencyKey = "classify-1",
                    draftId = draftId,
                    description = BANK_MEMO,
                ),
            )
            assertTrue("the prediction was not refused: $outcome", outcome is WorkflowCommandOutcome.NoChange)
            val reviewed = database.pendingBankDraftDao().findById(draftId)!!
            assertEquals(categoryId, reviewed.reviewCategoryId)
            assertTrue(reviewed.reviewCategoryResolved)
        } finally {
            database.close()
        }
    }

    // --- The ledger is untouched --------------------------------------------

    @Test
    fun `the preparation commands never change the confirmed ledger`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val ledger = LedgerRepository(
                categoryDao = database.categoryDao(),
                transactionDao = database.transactionDao(),
                rememberedCategoryRuleDao = database.rememberedCategoryRuleDao(),
                captureMessageDao = database.captureMessageDao(),
                clock = clock,
            )
            val categoryId = ledger.addCategory("Food")
            ledger.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = 120_000L,
                    occurredAtEpochMillis = CAPTURE_TIME,
                    description = "Lunch",
                    categoryId = categoryId,
                ),
            )
            val before = WorkflowQueryService(database).confirmedLedgerAggregate(
                LedgerDateRange(
                    fromEpochMillis = 0L,
                    throughExclusiveEpochMillis = CAPTURE_TIME + 1_000L,
                    zoneId = CAPTURE_ZONE,
                ),
            )

            val captureId = saveCapture(commands, "save-note-1", NOTE_TEXT)
            val eventId = storeEvent(commands, captureId, "event-1")
            val draftId = storeDraft(database, "fuel-key", 50_000L)
            commands.proposeLink(
                WorkflowCallerScope.User,
                ProposeLinkRequest(
                    idempotencyKey = "propose-1",
                    eventId = eventId,
                    draftId = draftId,
                ),
            )
            commands.requestClassification(
                WorkflowCallerScope.User,
                ClassificationRequest(
                    idempotencyKey = "classify-1",
                    draftId = draftId,
                    description = BANK_MEMO,
                ),
            )

            val after = WorkflowQueryService(database).confirmedLedgerAggregate(
                LedgerDateRange(
                    fromEpochMillis = 0L,
                    throughExclusiveEpochMillis = CAPTURE_TIME + 1_000L,
                    zoneId = CAPTURE_ZONE,
                ),
            )
            assertEquals(before.spending.totalVnd, after.spending.totalVnd)
            assertEquals(before.spending.recordCount, after.spending.recordCount)
            assertEquals(120_000L, after.spending.totalVnd)
            assertEquals(before.excludedDraftCount, after.excludedDraftCount)
        } finally {
            database.close()
        }
    }

    @Test
    fun `the recorded operation holds the request's identity and not its text`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            commands.saveCapture(
                WorkflowCallerScope.User,
                SaveCaptureRequest(idempotencyKey = "save-note-1", bodyText = "Fuel 50,000 at 15:00"),
            )

            val operations = WorkflowQueryService(database).workflowOperations(
                WorkflowOperationQuery(bounds = QueryPageRequest(LIST_PAGE_SIZE)),
            )
            assertEquals(1, operations.items.size)
            val operation = operations.items.single()
            assertEquals("save-note-1", operation.idempotencyKey)
            assertEquals(WorkflowCallerScope.User.scopeId, operation.scopeId)
            assertEquals("SAVE_CAPTURE", operation.command)
            assertTrue(operation.change is WorkflowChange.CaptureSaved)
            // The record names the request; it does not hold the text it was about.
            assertTrue(
                "the operation record holds the request's text",
                operation.change.toString().contains("Fuel").not(),
            )
        } finally {
            database.close()
        }
    }

    // --- Fixtures ------------------------------------------------------------

    private suspend fun saveCapture(
        commands: WorkflowCommandService,
        key: String,
        text: String,
    ): Long {
        val outcome = commands.saveCapture(
            WorkflowCallerScope.User,
            SaveCaptureRequest(idempotencyKey = key, bodyText = text),
        )
        assertTrue("the capture was refused: $outcome", outcome is WorkflowCommandOutcome.Applied)
        return (outcome as WorkflowCommandOutcome.Applied).change.let {
            (it as WorkflowChange.CaptureSaved).captureId
        }
    }

    private suspend fun storeEvent(
        commands: WorkflowCommandService,
        captureId: Long,
        key: String,
        span: String = "Fuel 50,000 at 15:00",
        amountVnd: Long? = 50_000L,
        anchorDate: String? = DRAFT_DATE,
    ): Long {
        val outcome = commands.updateEventInterpretation(
            WorkflowCallerScope.User,
            UpdateEventRequest(
                idempotencyKey = key,
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = reading(span, span, amountVnd, anchorDate),
                extractionVersion = EXTRACTION_VERSION,
            ),
        )
        val change = when (outcome) {
            is WorkflowCommandOutcome.Applied -> outcome.change
            is WorkflowCommandOutcome.NoChange -> outcome.change
            else -> error("the reading was refused: $outcome")
        }
        return when (change) {
            is WorkflowChange.EventStored -> change.eventId
            is WorkflowChange.EventRevised -> change.eventId
            else -> error("the reading was not stored: $change")
        }
    }

    private fun reading(
        span: String,
        normalizedText: String,
        amountVnd: Long? = 50_000L,
        anchorDate: String? = DRAFT_DATE,
    ) = NoteEventInterpretation(
        spanStart = NOTE_TEXT.indexOf(span),
        spanEnd = NOTE_TEXT.indexOf(span) + span.length,
        normalizedText = normalizedText,
        amountVnd = amountVnd,
        merchant = "Petrol",
        timeAnchor = anchorDate?.let { NoteEventTimeAnchor(date = it, time = "15:00") },
    )

    private suspend fun storeDraft(
        database: LedgerDatabase,
        key: String,
        amountVnd: Long,
    ): Long = IntakeRepository(database, clock).storePermitted(
        sourcePackage = BANK_PACKAGE,
        notificationKey = key,
        postedAtEpochMillis = POSTED_AT + key.length,
        content = PermittedNotificationContent(
            title = "Transaction notice",
            text = listOf(
                "VietinBank: 16/10/2026 07:38",
                "TK: account-redacted",
                "GD: -$amountVnd VND",
                "ND: $BANK_MEMO",
            ).joinToString("\n"),
            bigText = null,
        ),
    )

    private companion object {
        const val BANK_PACKAGE = "com.example.bank"
        const val BANK_MEMO = "Circle K store"
        const val CAPTURE_ZONE = "Asia/Bangkok"
        const val CAPTURE_TIME = 1_797_000_000_000L
        const val POSTED_AT = 1_797_000_000_000L
        const val EXTRACTION_VERSION = "extract-v1"
        const val FOOD_CATEGORY_ID = 1L
        const val NOTE_TEXT = "Fuel 50,000 at 15:00; Circle K snack 20,000 at 15:15"

        /** The calendar day the fixture's bank draft states. */
        const val DRAFT_DATE = "2026-10-16"
    }
}
