package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.data.laya.LayaCandidatePlanner
import io.github.triet4p.aegisf.data.laya.LayaContextDecision
import io.github.triet4p.aegisf.data.laya.LayaEffectiveContext
import io.github.triet4p.aegisf.data.laya.LayaEffectiveInput
import io.github.triet4p.aegisf.data.laya.LayaEvaluation
import io.github.triet4p.aegisf.data.laya.LayaRuntimeContract
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S7.3: the durable classification work coalesces revisions, survives process recreation,
 * and never spends inference the stored state does not justify.
 *
 * These cases drive the real Room database, the real command boundary, the real snapshot
 * repository, and the real queue. The one collaborator that is stood in for is the model
 * itself (the pinned Laya export is a device artifact), so the fake engine records the
 * exact questions it is asked, how many run at once, and how many run in total. Every
 * acceptance claim — one job per draft, the newest question asked, a waiting or running
 * job resumed, a missing model recorded honestly, a resolved draft never asked, a burst
 * preserved — is observed in the stored rows, not in the fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClassificationWorkLifecycleTest {

    /**
     * A classifier that records what it was asked and can be held, failed, or superseded.
     *
     * [gateFirstCall] holds only the first question, which is how the tests edit the draft
     * while one inference is in flight. The block runs on the boundary's own IO context,
     * the same place the real native call would block.
     */
    private class ScriptedEngine : ClassificationEngine {
        private val asked = AtomicInteger(0)
        private val active = AtomicInteger(0)
        private val peak = AtomicInteger(0)
        private val texts = Collections.synchronizedList(mutableListOf<String>())
        private val gateLock = Object()
        private var gatedCall = 0
        private var entered = CountDownLatch(0)
        private var release = CountDownLatch(0)

        /**
         * Holds one chosen call so the test can change stored state while that question is
         * in flight. Every call before it runs and returns, so a test can let one answer be
         * stored first and hold the next one.
         */
        fun gateCall(number: Int) {
            synchronized(gateLock) {
                gatedCall = number
                entered = CountDownLatch(1)
                release = CountDownLatch(1)
            }
        }

        @Volatile
        var unavailable: LayaRuntimeContract.State? = null

        @Volatile
        var supersedeFirstCall: Boolean = false

        @Volatile
        var categoryId: Long = 0L

        val calls: Int get() = asked.get()

        val peakOverlap: Int get() = peak.get()

        /** The description of each question, in the order the model was asked. */
        val questions: List<String> get() = synchronized(texts) { texts.toList() }

        fun awaitGateEntered() {
            assertTrue("the model never reached the gated call", entered.await(5, TimeUnit.SECONDS))
        }

        fun releaseGate() {
            release.countDown()
        }

        override suspend fun classify(
            context: LayaEffectiveContext,
            categories: List<CategoryEntity>,
        ): ClassificationEngineResult {
            val call = asked.incrementAndGet()
            texts += context.primaryText
            val held = synchronized(gateLock) {
                if (call == gatedCall) entered to release else null
            }
            val now = active.incrementAndGet()
            peak.updateAndGet { previous -> maxOf(previous, now) }
            try {
                if (held != null) {
                    held.first.countDown()
                    held.second.await(10, TimeUnit.SECONDS)
                }
                if (supersedeFirstCall && call == 1) {
                    return ClassificationEngineResult.Superseded
                }
                unavailable?.let { return ClassificationEngineResult.Unavailable(it) }
                val category = categories.firstOrNull { it.id == categoryId }
                    ?: categories.firstOrNull()
                    ?: return ClassificationEngineResult.Decided(
                        LayaContextDecision(
                            decision = LayaCandidatePlanner.Decision.Unknown(
                                LayaCandidatePlanner.ManualReason.NO_ACTIVE_CATEGORIES,
                            ),
                            effectiveInput = LayaEffectiveInput(
                                text = context.primaryText,
                                digest = "digest-of-${context.primaryText}",
                                includedReadings = context.eventReadings,
                                droppedReadings = emptyList(),
                                tokenCount = 4,
                                roomTokens = 128,
                            ),
                            evaluation = null,
                        ),
                    )
                return ClassificationEngineResult.Decided(
                    LayaContextDecision(
                        decision = LayaCandidatePlanner.Decision.Category(
                            categoryId = category.id,
                            snapshotName = category.name,
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
                            categoryIds = listOf(category.id),
                            probabilities = listOf(0.75),
                            unknownShare = 0.25,
                            confidence = 0.5,
                        ),
                    ),
                )
            } finally {
                active.decrementAndGet()
            }
        }
    }

    private lateinit var context: Context
    private lateinit var database: LedgerDatabase
    private lateinit var engine: ScriptedEngine
    private lateinit var commands: WorkflowCommandService
    private lateinit var queue: BankDraftClassificationQueue
    private var queueHolder: BankDraftClassificationQueue? = null
    private var foodCategoryId: Long = 0L

    @Before
    fun setUp() = runBlocking<Unit> {
        WorkflowClaimGuard.reset()
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val now = System.currentTimeMillis()
        foodCategoryId = database.categoryDao().insert(
            CategoryEntity(name = "Food", createdAtEpochMillis = now, updatedAtEpochMillis = now),
        )
        engine = ScriptedEngine().apply { categoryId = foodCategoryId }
        commands = WorkflowCommandService(
            database = database,
            engine = engine,
            findRememberedCategory = { null },
            onAcceptedChange = { change -> queueHolder?.onAcceptedChange(change) },
        )
        queue = newQueue()
    }

    @After
    fun tearDown() {
        engine.releaseGate()
        queue.close()
        queueHolder = null
        runBlocking { delay(50L) }
        database.close()
        WorkflowClaimGuard.reset()
    }

    private fun newQueue(): BankDraftClassificationQueue =
        BankDraftClassificationQueue(
            database = database,
            commands = commands,
            retryBackoffMillis = 10L,
        ).also { queueHolder = it }

    // --- Cases -------------------------------------------------------------

    @Test
    fun `raw queue request does not reuse a fresh translated snapshot`() = runBlocking<Unit> {
        val draftId = storeDraft("Bánh mì", "translated-before-queue")
        val draft = database.pendingBankDraftDao().findById(draftId)!!
        val repository = ClassificationSnapshotRepository(database)
        val source = repository.translationSource(
            ClassificationTranslationBinding(draftId, draft.reviewRevision, ""),
        )!!
        val translation = "Bought bread this morning."
        assertTrue(
            commands.requestHostedEnglishClassification(
                WorkflowCallerScope.User,
                ClassificationRequest(
                    idempotencyKey = "translated-before-queue-operation",
                    draftId = draftId,
                    description = draft.classificationDescription(),
                    expectedReviewRevision = draft.reviewRevision,
                ),
                HostedEnglishClassificationInput(translation, source.binding),
            ) is WorkflowCommandOutcome.Applied,
        )
        assertEquals(1, engine.calls)
        assertEquals(translation, engine.questions.single())
        assertEquals(
            ClassificationInputKind.HOSTED_ENGLISH_TRANSLATION.name,
            snapshotOf(draftId)!!.classificationInputKind,
        )

        queue.admit(draftId)
        val work = awaitWork(draftId) { it.state == CLASSIFICATION_WORK_RESULT }
        assertNotNull(work.snapshotId)
        assertEquals(2, engine.calls)
        assertEquals(listOf(translation, "Bánh mì"), engine.questions)
        assertEquals(
            ClassificationInputKind.STORED_SOURCE.name,
            snapshotOf(draftId)!!.classificationInputKind,
        )
        assertTrue(database.transactionDao().findAll().isEmpty())
    }

    @Test
    fun `marking an older attempt preserves and runs a concurrently retargeted question`() =
        runBlocking<Unit> {
            queue.close()
            queueHolder = null
            WorkflowClaimGuard.reset()

            val draftId = storeDraft("older memo", "mark-executing-race")
            val snapshots = ClassificationSnapshotRepository(database)
            val oldEffective = snapshots.effectiveContextFor(draftId, "older memo")!!
            val oldQuestion = classificationQuestionDigest(
                description = "older memo",
                context = oldEffective.context,
                dependencies = oldEffective.dependencies,
            )
            assertTrue(
                saveMemo(draftId, 0, "newest memo", "mark-executing-newer") is
                    WorkflowCommandOutcome.Applied,
            )
            val newestEffective = snapshots.effectiveContextFor(draftId, "newest memo")!!
            val newestQuestion = classificationQuestionDigest(
                description = "newest memo",
                context = newestEffective.context,
                dependencies = newestEffective.dependencies,
            )

            val now = System.currentTimeMillis()
            val dao = database.classificationWorkDao()
            val jobId = dao.insert(
                ClassificationWorkEntity(
                    draftId = draftId,
                    state = CLASSIFICATION_WORK_PENDING,
                    requestedQuestion = oldQuestion,
                    executedQuestion = null,
                    attemptCount = 0,
                    snapshotId = null,
                    statusReason = null,
                    ownerToken = null,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                ),
            )
            assertEquals(1, dao.claim(jobId, "older-attempt", now))
            assertEquals(
                1,
                dao.retarget(
                    jobId,
                    CLASSIFICATION_WORK_RUNNING,
                    newestQuestion,
                    now + 1,
                ),
            )
            assertEquals(
                1,
                dao.markExecuting(jobId, "older-attempt", oldQuestion, oldQuestion),
            )

            val job = dao.findById(jobId)!!
            assertEquals(oldQuestion, job.executedQuestion)
            assertEquals(newestQuestion, job.requestedQuestion)
            assertTrue("settlement must see the newer request", job.requestedQuestion != oldQuestion)
            assertEquals(
                1,
                dao.requeue(
                    jobId,
                    "older-attempt",
                    attempts = 0,
                    reason = ClassificationWorkReason.CONTEXT_CHANGED,
                    now = now + 2,
                ),
            )

            queue = newQueue()
            val result = awaitWork(draftId) { it.state == CLASSIFICATION_WORK_RESULT }
            assertEquals("the newer question owns the resumed result", newestQuestion, result.executedQuestion)
            assertEquals(listOf("newest memo"), engine.questions)
            assertEquals(1, engine.calls)
            assertEquals("digest-of-newest memo", snapshotOf(draftId)?.effectiveInputDigest)
            assertEquals(0, database.transactionDao().findAll().size)
        }

    @Test
    fun `rapid edits keep the newest question and ask the model once per revision`() =
        runBlocking<Unit> {
            val draftId = storeDraft("coffee one", "coalesce-1")
            engine.gateCall(1)
            queue.admit(draftId)
            engine.awaitGateEntered()

            // Two accepted edits while the first question is being answered. The user's
            // newest wording is the only one that may reach the model after this.
            assertTrue(saveMemo(draftId, 0, "coffee two", "coalesce-2") is WorkflowCommandOutcome.Applied)
            assertTrue(saveMemo(draftId, 1, "coffee three", "coalesce-3") is WorkflowCommandOutcome.Applied)

            engine.releaseGate()
            awaitSnapshot(draftId)
            val job = awaitWork(draftId) { it.state == CLASSIFICATION_WORK_RESULT }

            assertEquals("the skipped middle wording was never asked", listOf("coffee one", "coffee three"), engine.questions)
            assertEquals(2, engine.calls)
            assertEquals("one job, re-asked for the newest question", 1, job.attemptCount)
            assertEquals(0L, database.classificationWorkDao().countInState(CLASSIFICATION_WORK_PENDING))
            assertEquals(1L, database.classificationWorkDao().countInState(CLASSIFICATION_WORK_RESULT))
            assertEquals(
                "the stored prediction is the newest wording",
                "digest-of-coffee three",
                snapshotOf(draftId)?.effectiveInputDigest,
            )
        }

    @Test
    fun `an accepted edit that does not change the model input asks no second inference`() =
        runBlocking<Unit> {
            val draftId = storeDraft("coffee", "relevance-1")
            queue.admit(draftId)
            awaitSnapshot(draftId)
            awaitWork(draftId) { it.state == CLASSIFICATION_WORK_RESULT }
            assertEquals(1, engine.calls)

            // An amount-only correction is an accepted command, but the model reads the
            // description and the linked readings, not the amount: no inference is spent.
            val corrected = commands.saveDraftReview(
                WorkflowCallerScope.User,
                SaveDraftReviewRequest(
                    idempotencyKey = "relevance-amount",
                    draftId = draftId,
                    expectedRevision = 0,
                    edits = reviewEdits(amountText = "60,000", memoText = "coffee"),
                ),
            )
            assertTrue("the correction was refused: $corrected", corrected is WorkflowCommandOutcome.Applied)
            delay(200L)
            assertEquals("an amount edit is not a classification input", 1, engine.calls)
            assertEquals(CLASSIFICATION_WORK_RESULT, workOf(draftId)?.state)

            // Changing the description is relevant: the next question is the new wording.
            assertTrue(saveMemo(draftId, 1, "coffee beans", "relevance-memo") is WorkflowCommandOutcome.Applied)
            awaitSnapshotText(draftId, "digest-of-coffee beans")
            assertEquals(2, engine.calls)
            assertEquals(CLASSIFICATION_WORK_RESULT, workOf(draftId)?.state)
        }

    @Test
    fun `a waiting job and an interrupted running job are resumed by the next process`() =
        runBlocking<Unit> {
            val interrupted = storeDraft("interrupted memo", "recreate-1")
            val waiting = storeDraft("waiting memo", "recreate-2")
            // End this process's worker; the rows written below are exactly what a process
            // that died mid-work leaves behind.
            queue.close()
            queueHolder = null
            WorkflowClaimGuard.reset()
            val now = System.currentTimeMillis()
            database.classificationWorkDao().insert(
                ClassificationWorkEntity(
                    draftId = interrupted,
                    state = CLASSIFICATION_WORK_RUNNING,
                    requestedQuestion = "question-of-the-dead-process",
                    executedQuestion = "question-of-the-dead-process",
                    attemptCount = 1,
                    snapshotId = null,
                    statusReason = null,
                    ownerToken = "dead-process-attempt",
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                ),
            )
            database.classificationWorkDao().insert(
                ClassificationWorkEntity(
                    draftId = waiting,
                    state = CLASSIFICATION_WORK_PENDING,
                    requestedQuestion = "queued-before-the-restart",
                    executedQuestion = null,
                    attemptCount = 0,
                    snapshotId = null,
                    statusReason = null,
                    ownerToken = null,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                ),
            )

            // The next process starts the queue: the interrupted running job is resumed.
            queue = newQueue()
            awaitWork(interrupted) { it.state == CLASSIFICATION_WORK_RESULT }
            awaitWork(waiting) { it.state == CLASSIFICATION_WORK_RESULT }
            assertNotNull(snapshotOf(interrupted))
            assertNotNull(snapshotOf(waiting))
            assertEquals(2, engine.calls)
            assertEquals("one inference at a time", 1, engine.peakOverlap)
            assertEquals(0L, database.classificationWorkDao().countInState(CLASSIFICATION_WORK_RUNNING))
            assertEquals(0, database.transactionDao().findAll().size)
        }

    @Test
    fun `a missing model records a failed job and a re-read delivery retries it`() =
        runBlocking<Unit> {
            engine.unavailable = LayaRuntimeContract.State.Missing
            val draftId = storeDraft("missing model memo", "missing-1")
            queue.admit(draftId)

            val failed = awaitWork(draftId) { it.state == CLASSIFICATION_WORK_FAILED }
            assertEquals(ClassificationWorkReason.MODEL_OR_INFERENCE_FAILED, failed.statusReason)
            assertNull("a missing model stores no guessed prediction", snapshotOf(draftId))
            assertNotNull("the draft is still stored and reviewable", database.pendingBankDraftDao().findById(draftId))
            assertEquals(1, engine.calls)

            // A reconnect re-reads the active notification and re-reports the same draft:
            // that is the app's recovery signal, so the recorded miss is re-armed.
            engine.unavailable = null
            queue.admit(draftId)
            awaitSnapshot(draftId)
            val resumed = awaitWork(draftId) { it.state == CLASSIFICATION_WORK_RESULT }
            assertNull("a recovered job carries no failure", resumed.statusReason)
            assertEquals(2, engine.calls)
            assertEquals(0, database.transactionDao().findAll().size)
        }

    @Test
    fun `resolving a draft cancels its queued job before the model is asked`() =
        runBlocking<Unit> {
            val busy = storeDraft("busy memo", "cancel-1")
            val resolved = storeDraft("resolved memo", "cancel-2")
            engine.gateCall(1)
            queue.admit(busy)
            engine.awaitGateEntered()
            queue.admit(resolved)

            val saved = commands.saveDraftReview(
                WorkflowCallerScope.User,
                SaveDraftReviewRequest(
                    idempotencyKey = "cancel-resolve",
                    draftId = resolved,
                    expectedRevision = 0,
                    edits = reviewEdits(
                        memoText = "resolved memo",
                        categoryId = foodCategoryId,
                        categoryResolved = true,
                    ),
                ),
            )
            assertTrue("the user's choice was refused: $saved", saved is WorkflowCommandOutcome.Applied)
            val cancelled = workOf(resolved)
            assertEquals(CLASSIFICATION_WORK_CANCELLED, cancelled?.state)
            assertEquals(ClassificationWorkReason.DRAFT_RESOLVED, cancelled?.statusReason)

            engine.releaseGate()
            awaitSnapshot(busy)
            delay(200L)
            assertEquals("only the busy draft reached the model", listOf("busy memo"), engine.questions)
            assertNull("a resolved draft is never classified", snapshotOf(resolved))
            assertTrue(
                "the user's category choice stands",
                checkNotNull(database.pendingBankDraftDao().findById(resolved)).reviewCategoryResolved,
            )
        }

    @Test
    fun `a burst keeps one durable job per draft and classifies one at a time`() =
        runBlocking<Unit> {
            val drafts = (1..8).map { index -> storeDraft("burst $index", "burst-$index") }
            engine.gateCall(1)
            for (draftId in drafts) queue.admit(draftId)
            engine.awaitGateEntered()

            // Every admitted draft has its own job while one question is in flight: the
            // waiting work is durable, not an in-memory channel that a burst can fill.
            assertEquals(7L, database.classificationWorkDao().countInState(CLASSIFICATION_WORK_PENDING))
            assertEquals(1L, database.classificationWorkDao().countInState(CLASSIFICATION_WORK_RUNNING))
            assertEquals(8, database.pendingBankDraftDao().findAll().size)

            engine.releaseGate()
            for (draftId in drafts) awaitSnapshot(draftId)
            assertEquals(8, engine.calls)
            assertEquals("one inference at a time", 1, engine.peakOverlap)
            assertEquals(0L, database.classificationWorkDao().countInState(CLASSIFICATION_WORK_PENDING))
            assertEquals(0, database.transactionDao().findAll().size)
        }

    @Test
    fun `a superseded inference requeues the job instead of failing it`() = runBlocking<Unit> {
        engine.supersedeFirstCall = true
        val draftId = storeDraft("superseded memo", "supersede-1")
        queue.admit(draftId)

        awaitSnapshot(draftId)
        val job = awaitWork(draftId) { it.state == CLASSIFICATION_WORK_RESULT }
        assertEquals("the superseded question was asked again", 2, engine.calls)
        assertEquals(2, job.attemptCount)
        assertNull(job.statusReason)
    }

    // --- A delayed result never lands on a moved-on draft -------------------

    @Test
    fun `a delayed result cannot overwrite the user's own category choice`() = runBlocking<Unit> {
        val draftId = storeDraft("correction memo", "correction-1")
        queue.admit(draftId)
        awaitSnapshot(draftId)
        awaitWork(draftId) { it.state == CLASSIFICATION_WORK_RESULT }
        val first = snapshotOf(draftId)!!

        // A new wording re-arms the job; its question is held while the user resolves the
        // draft through the same command boundary.
        engine.gateCall(2)
        assertTrue(saveMemo(draftId, 0, "correction memo two", "correction-2") is WorkflowCommandOutcome.Applied)
        engine.awaitGateEntered()
        val resolved = commands.saveDraftReview(
            WorkflowCallerScope.User,
            SaveDraftReviewRequest(
                idempotencyKey = "correction-resolve",
                draftId = draftId,
                expectedRevision = 1,
                edits = reviewEdits(
                    memoText = "correction memo two",
                    categoryId = foodCategoryId,
                    categoryResolved = true,
                ),
            ),
        )
        assertTrue("the user's choice was refused: $resolved", resolved is WorkflowCommandOutcome.Applied)

        engine.releaseGate()
        awaitWork(draftId) { it.state == CLASSIFICATION_WORK_CANCELLED }

        // The stored prediction is still the first one, marked stale; the delayed answer
        // never replaced its values and never touched the user's choice.
        val stored = snapshotOf(draftId)!!
        assertEquals(first.suggestedCategoryId, stored.suggestedCategoryId)
        assertEquals(first.predictionTime, stored.predictionTime)
        assertNotNull("the moved-on prediction is not left looking fresh", stored.staleReason)
        val reviewed = database.pendingBankDraftDao().findById(draftId)!!
        assertEquals(foodCategoryId, reviewed.reviewCategoryId)
        assertTrue(reviewed.reviewCategoryResolved)
        assertEquals(0, database.transactionDao().findAll().size)
    }

    @Test
    fun `a delayed result cannot survive a source unlink`() = runBlocking<Unit> {
        val noteText = "Fuel 50,000 at 15:00"
        val captureId = CaptureMessageRepository(
            database = database,
            clock = SystemLedgerClock,
            timezoneId = { "Asia/Ho_Chi_Minh" },
        ).saveQuickCapture(noteText)
        val eventId = (
            EventContextRepository(database, SystemLedgerClock).recordEvent(
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = NoteEventInterpretation(
                    spanStart = 0,
                    spanEnd = noteText.length,
                    normalizedText = noteText,
                    amountVnd = 50_000L,
                    merchant = "Petrol",
                ),
                extractionVersion = "extract-v1",
            ) as NoteEventWriteResult.Stored
            ).eventId
        val draftId = storeDraft("unlink memo", "unlink-race")
        assertTrue(
            commands.proposeLink(
                WorkflowCallerScope.User,
                ProposeLinkRequest(
                    idempotencyKey = "unlink-race-propose",
                    eventId = eventId,
                    draftId = draftId,
                ),
            ) is WorkflowCommandOutcome.Applied,
        )
        val proposed = database.contextLinkDao().findByPair(eventId, draftId)!!
        assertTrue(
            commands.attachLink(
                WorkflowCallerScope.User,
                AttachLinkRequest(
                    idempotencyKey = "unlink-race-attach",
                    eventId = eventId,
                    draftId = draftId,
                    expectedRevision = proposed.revision,
                ),
            ) is WorkflowCommandOutcome.Applied,
        )
        val attached = database.contextLinkDao().findByPair(eventId, draftId)!!

        engine.gateCall(1)
        queue.admit(draftId)
        engine.awaitGateEntered()
        val unlinked = commands.unlinkLink(
            WorkflowCallerScope.User,
            UnlinkLinkRequest(
                idempotencyKey = "unlink-race-unlink",
                eventId = eventId,
                draftId = draftId,
                expectedRevision = attached.revision,
            ),
        )
        assertTrue("the unlink was refused: $unlinked", unlinked is WorkflowCommandOutcome.Applied)
        engine.releaseGate()

        val job = awaitWork(draftId) { it.state == CLASSIFICATION_WORK_RESULT }
        assertNull(job.statusReason)
        assertEquals("the stale answer was asked again", 2, engine.calls)
        val stored = snapshotOf(draftId)!!
        val postUnlink = ClassificationSnapshotRepository(database)
            .effectiveContextFor(draftId, "unlink memo")!!
        assertEquals(postUnlink.dependencies.contextRevision, stored.contextRevision)
        assertEquals("the unlinked reading is not part of the stored answer", "", stored.eventIds)
        assertNull(
            ClassificationSnapshotRepository(database).snapshotFor(draftId)!!.staleReason,
        )
        assertEquals(0, database.transactionDao().findAll().size)
    }

    @Test
    fun `a delayed result cannot survive an interpretation text edit`() = runBlocking<Unit> {
        val noteText = "Fuel 50,000 at 15:00"
        val captureId = CaptureMessageRepository(
            database = database,
            clock = SystemLedgerClock,
            timezoneId = { "Asia/Ho_Chi_Minh" },
        ).saveQuickCapture(noteText)
        val eventId = (
            EventContextRepository(database, SystemLedgerClock).recordEvent(
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = NoteEventInterpretation(
                    spanStart = 0,
                    spanEnd = noteText.length,
                    normalizedText = noteText,
                    amountVnd = 50_000L,
                    merchant = "Petrol",
                ),
                extractionVersion = "extract-v1",
            ) as NoteEventWriteResult.Stored
            ).eventId
        val draftId = storeDraft("reading memo", "reading-race")
        assertTrue(
            commands.proposeLink(
                WorkflowCallerScope.User,
                ProposeLinkRequest(
                    idempotencyKey = "reading-race-propose",
                    eventId = eventId,
                    draftId = draftId,
                ),
            ) is WorkflowCommandOutcome.Applied,
        )

        engine.gateCall(1)
        queue.admit(draftId)
        engine.awaitGateEntered()
        // Only the interpretation text changes: the provenance card's reading is what the
        // stored snapshots must follow.
        val revised = commands.updateEventInterpretation(
            WorkflowCallerScope.User,
            UpdateEventRequest(
                idempotencyKey = "reading-race-revise",
                captureId = captureId,
                expectedCaptureRevision = 0,
                expectedEventRevision = database.noteEventDao().findById(eventId)!!.revision,
                interpretation = NoteEventInterpretation(
                    spanStart = 0,
                    spanEnd = noteText.length,
                    normalizedText = "Fuel 55,000 at 15:05",
                    amountVnd = 55_000L,
                    merchant = "Petrol",
                ),
                extractionVersion = "extract-v1",
            ),
        )
        assertTrue("the interpretation edit was refused: $revised", revised is WorkflowCommandOutcome.Applied)
        engine.releaseGate()

        val job = awaitWork(draftId) { it.state == CLASSIFICATION_WORK_RESULT }
        assertNull(job.statusReason)
        assertEquals("the stale answer was asked again", 2, engine.calls)
        val stored = snapshotOf(draftId)!!
        val postEdit = ClassificationSnapshotRepository(database)
            .effectiveContextFor(draftId, "reading memo")!!
        assertEquals(postEdit.dependencies.contextRevision, stored.contextRevision)
        assertEquals(listOf(eventId), stored.eventIds.toIdList())
        assertNull(
            ClassificationSnapshotRepository(database).snapshotFor(draftId)!!.staleReason,
        )
    }

    @Test
    fun `a delayed result cannot store the category it was archived against`() = runBlocking<Unit> {
        val transportId = database.categoryDao().insert(
            CategoryEntity(
                name = "Transport",
                createdAtEpochMillis = POSTED_AT,
                updatedAtEpochMillis = POSTED_AT,
            ),
        )
        val draftId = storeDraft("category memo", "archive-race")
        engine.categoryId = foodCategoryId
        engine.gateCall(1)
        queue.admit(draftId)
        engine.awaitGateEntered()

        val archived = commands.archiveCategory(
            WorkflowCallerScope.User,
            ArchiveCategoryRequest(
                idempotencyKey = "archive-race-archive",
                categoryId = foodCategoryId,
            ),
        )
        assertTrue("the archive was refused: $archived", archived is WorkflowCommandOutcome.Applied)
        engine.releaseGate()

        val job = awaitWork(draftId) { it.state == CLASSIFICATION_WORK_RESULT }
        assertNull(job.statusReason)
        assertEquals("the stale answer was asked again", 2, engine.calls)
        val stored = snapshotOf(draftId)!!
        assertEquals("only the still-active category may be stored", transportId, stored.suggestedCategoryId)
        assertEquals(listOf(transportId), stored.candidateIds.toIdList())
        assertNull(
            ClassificationSnapshotRepository(database).snapshotFor(draftId)!!.staleReason,
        )
        assertTrue(database.transactionDao().findAll().isEmpty())
    }

    @Test
    fun `a question restored by an unlink is asked and stored again`() = runBlocking<Unit> {
        val noteText = "Fuel 50,000 at 15:00"
        val captureId = CaptureMessageRepository(
            database = database,
            clock = SystemLedgerClock,
            timezoneId = { "Asia/Ho_Chi_Minh" },
        ).saveQuickCapture(noteText)
        val eventId = (
            EventContextRepository(database, SystemLedgerClock).recordEvent(
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = NoteEventInterpretation(
                    spanStart = 0,
                    spanEnd = noteText.length,
                    normalizedText = noteText,
                    amountVnd = 50_000L,
                    merchant = "Petrol",
                ),
                extractionVersion = "extract-v1",
            ) as NoteEventWriteResult.Stored
            ).eventId
        val draftId = storeDraft("restore memo", "restore-1")
        queue.admit(draftId)
        awaitSnapshot(draftId)
        assertEquals(emptyList<Long>(), snapshotOf(draftId)!!.eventIds.toIdList())

        // The reading joins the context: the question changes and its answer is stored.
        assertTrue(
            commands.proposeLink(
                WorkflowCallerScope.User,
                ProposeLinkRequest(
                    idempotencyKey = "restore-propose",
                    eventId = eventId,
                    draftId = draftId,
                ),
            ) is WorkflowCommandOutcome.Applied,
        )
        val proposed = database.contextLinkDao().findByPair(eventId, draftId)!!
        assertTrue(
            commands.attachLink(
                WorkflowCallerScope.User,
                AttachLinkRequest(
                    idempotencyKey = "restore-attach",
                    eventId = eventId,
                    draftId = draftId,
                    expectedRevision = proposed.revision,
                ),
            ) is WorkflowCommandOutcome.Applied,
        )
        awaitSnapshotEventIds(draftId, listOf(eventId))

        // The source is unlinked, so the original question is posed again. It is a new
        // attempt, not a replay of the answer written before the reading existed.
        val attached = database.contextLinkDao().findByPair(eventId, draftId)!!
        assertTrue(
            commands.unlinkLink(
                WorkflowCallerScope.User,
                UnlinkLinkRequest(
                    idempotencyKey = "restore-unlink",
                    eventId = eventId,
                    draftId = draftId,
                    expectedRevision = attached.revision,
                ),
            ) is WorkflowCommandOutcome.Applied,
        )
        awaitSnapshotEventIds(draftId, emptyList())

        val restored = snapshotOf(draftId)!!
        assertNull(restored.staleReason)
        val effective = ClassificationSnapshotRepository(database)
            .effectiveContextFor(draftId, "restore memo")!!
        assertEquals(effective.dependencies.contextRevision, restored.contextRevision)
        assertEquals(0, database.transactionDao().findAll().size)
    }

    @Test
    fun `user cancel discards a running answer and an explicit retry asks again`() =
        runBlocking<Unit> {
            val draftId = storeDraft("cancel memo", "cancel-1")
            engine.gateCall(1)
            queue.admit(draftId)
            engine.awaitGateEntered()

            val live = WorkflowQueryService(database)
            assertEquals(
                ClassificationWorkState.RUNNING,
                live.observeClassificationWorkStatus(draftId).first()!!.state,
            )
            assertTrue(queue.cancel(draftId))
            engine.releaseGate()
            // Let the abandoned native work end and give its durable claim back.
            delay(300L)

            val cancelled = workOf(draftId)!!
            assertEquals(CLASSIFICATION_WORK_CANCELLED, cancelled.state)
            assertEquals(ClassificationWorkReason.USER_CANCELLED, cancelled.statusReason)
            assertEquals("the check ran once and was not re-asked", 1, engine.calls)
            assertNull("a cancelled running answer is discarded", snapshotOf(draftId))
            val status = live.observeClassificationWorkStatus(draftId).first()!!
            assertEquals(ClassificationWorkState.CANCELLED, status.state)
            assertEquals(ClassificationWorkStatusReason.USER_CANCELLED, status.reason)
            assertTrue(database.transactionDao().findAll().isEmpty())

            // A duplicate delivery never quietly restarts a check the user cancelled.
            queue.admit(draftId)
            delay(200L)
            assertEquals(CLASSIFICATION_WORK_CANCELLED, workOf(draftId)!!.state)
            assertEquals(1, engine.calls)

            // The explicit retry re-arms the same question and stores a suggestion.
            assertTrue(queue.retry(draftId))
            val result = awaitWork(draftId) { it.state == CLASSIFICATION_WORK_RESULT }
            assertNull(result.statusReason)
            assertEquals("the retry asked the model once more", 2, engine.calls)
            assertNotNull(snapshotOf(draftId))
            assertEquals(
                ClassificationWorkState.RESULT,
                live.observeClassificationWorkStatus(draftId).first()!!.state,
            )
            assertTrue(database.transactionDao().findAll().isEmpty())
        }

    // --- Fixtures and observation -----------------------------------------

    private suspend fun storeDraft(memo: String, key: String): Long =
        database.pendingBankDraftDao().store(
            PendingBankDraftEntity(
                deliveryId = draftDeliveryId(BANK_PACKAGE, key, POSTED_AT),
                sourcePackage = BANK_PACKAGE,
                notificationKey = key,
                postedAtEpochMillis = POSTED_AT,
                bank = "VIETINBANK",
                parseStatus = IntakeParseStatus.PARSED,
                amountState = IntakeFieldState.PRESENT,
                amountVnd = 50_000L,
                amountUnresolvedReason = null,
                direction = IntakeDirection.DEBIT,
                momentState = IntakeFieldState.PRESENT,
                statedDate = "2026-10-16",
                statedTime = "07:38",
                momentUnresolvedReason = null,
                memoState = IntakeFieldState.PRESENT,
                memo = memo,
                memoUnresolvedReason = null,
                createdAtEpochMillis = POSTED_AT,
            ),
        )

    private suspend fun saveMemo(
        draftId: Long,
        expectedRevision: Int,
        memo: String,
        key: String,
    ): WorkflowCommandOutcome = commands.saveDraftReview(
        WorkflowCallerScope.User,
        SaveDraftReviewRequest(
            idempotencyKey = key,
            draftId = draftId,
            expectedRevision = expectedRevision,
            edits = reviewEdits(memoText = memo),
        ),
    )

    private fun reviewEdits(
        amountText: String = "50,000",
        memoText: String,
        categoryId: Long? = null,
        categoryResolved: Boolean = false,
    ): BankDraftReviewEdits = BankDraftReviewEdits(
        amountText = amountText,
        dateText = "16/10/2026",
        timeText = "07:38",
        memoText = memoText,
        type = TransactionType.EXPENSE,
        categoryId = categoryId,
        categoryResolved = categoryResolved,
    )

    private suspend fun workOf(draftId: Long): ClassificationWorkEntity? =
        database.classificationWorkDao().findForDraft(draftId)

    private suspend fun snapshotOf(draftId: Long) =
        database.classificationSnapshotDao().findForDraft(draftId)

    private suspend fun awaitWork(
        draftId: Long,
        predicate: (ClassificationWorkEntity) -> Boolean,
    ): ClassificationWorkEntity = withContext(Dispatchers.IO) {
        try {
            withTimeout(10_000L) {
                var job = workOf(draftId)
                while (job == null || !predicate(job)) {
                    delay(10L)
                    job = workOf(draftId)
                }
                checkNotNull(job)
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError(
                "job=${workOf(draftId)} calls=${engine.calls} questions=${engine.questions}",
                timeout,
            )
        }
    }

    private suspend fun awaitSnapshot(draftId: Long) = withContext(Dispatchers.IO) {
        withTimeout(10_000L) {
            var snapshot = snapshotOf(draftId)
            while (snapshot == null) {
                delay(10L)
                snapshot = snapshotOf(draftId)
            }
            checkNotNull(snapshot)
        }
    }

    private suspend fun awaitSnapshotEventIds(draftId: Long, eventIds: List<Long>) =
        withContext(Dispatchers.IO) {
            withTimeout(10_000L) {
                var snapshot = snapshotOf(draftId)
                while (snapshot == null ||
                    snapshot.staleReason != null ||
                    snapshot.eventIds.toIdList() != eventIds
                ) {
                    delay(10L)
                    snapshot = snapshotOf(draftId)
                }
                checkNotNull(snapshot)
            }
        }

    private suspend fun awaitSnapshotText(draftId: Long, digest: String) =
        withContext(Dispatchers.IO) {
            withTimeout(10_000L) {
                var snapshot = snapshotOf(draftId)
                while (snapshot?.effectiveInputDigest != digest) {
                    delay(10L)
                    snapshot = snapshotOf(draftId)
                }
                checkNotNull(snapshot)
            }
        }

    private companion object {
        const val BANK_PACKAGE = "com.vietinbank.ipay"
        const val POSTED_AT = 1_797_000_000_000L
    }
}
