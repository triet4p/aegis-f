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
        private val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val texts = Collections.synchronizedList(mutableListOf<String>())

        @Volatile
        var gateFirstCall: Boolean = false

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

        fun awaitFirstCallEntered() {
            assertTrue("the model was never asked", entered.await(5, TimeUnit.SECONDS))
        }

        fun releaseFirstCall() {
            release.countDown()
        }

        override suspend fun classify(
            context: LayaEffectiveContext,
            categories: List<CategoryEntity>,
        ): ClassificationEngineResult {
            val call = asked.incrementAndGet()
            texts += context.primaryText
            val now = active.incrementAndGet()
            peak.updateAndGet { previous -> maxOf(previous, now) }
            try {
                if (gateFirstCall && call == 1) {
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
                if (supersedeFirstCall && call == 1) {
                    return ClassificationEngineResult.Superseded
                }
                unavailable?.let { return ClassificationEngineResult.Unavailable(it) }
                val category = categories.firstOrNull { it.id == categoryId }
                    ?: categories.first()
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
        engine.releaseFirstCall()
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
    fun `rapid edits keep the newest question and ask the model once per revision`() =
        runBlocking<Unit> {
            val draftId = storeDraft("coffee one", "coalesce-1")
            engine.gateFirstCall = true
            queue.admit(draftId)
            engine.awaitFirstCallEntered()

            // Two accepted edits while the first question is being answered. The user's
            // newest wording is the only one that may reach the model after this.
            assertTrue(saveMemo(draftId, 0, "coffee two", "coalesce-2") is WorkflowCommandOutcome.Applied)
            assertTrue(saveMemo(draftId, 1, "coffee three", "coalesce-3") is WorkflowCommandOutcome.Applied)

            engine.releaseFirstCall()
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
            engine.gateFirstCall = true
            queue.admit(busy)
            engine.awaitFirstCallEntered()
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

            engine.releaseFirstCall()
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
            engine.gateFirstCall = true
            for (draftId in drafts) queue.admit(draftId)
            engine.awaitFirstCallEntered()

            // Every admitted draft has its own job while one question is in flight: the
            // waiting work is durable, not an in-memory channel that a burst can fill.
            assertEquals(7L, database.classificationWorkDao().countInState(CLASSIFICATION_WORK_PENDING))
            assertEquals(1L, database.classificationWorkDao().countInState(CLASSIFICATION_WORK_RUNNING))
            assertEquals(8, database.pendingBankDraftDao().findAll().size)

            engine.releaseFirstCall()
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
