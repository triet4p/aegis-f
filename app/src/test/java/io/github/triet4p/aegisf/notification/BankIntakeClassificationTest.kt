package io.github.triet4p.aegisf.notification

import android.app.Notification
import android.content.Context
import android.content.SharedPreferences
import android.service.notification.StatusBarNotification
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.data.BankDraftClassificationQueue
import io.github.triet4p.aegisf.data.BankDraftReviewEdits
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.ClassificationEngine
import io.github.triet4p.aegisf.data.ClassificationEngineResult
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.IntakeRepository
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.NotificationAccessStore
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.PredictionSource
import io.github.triet4p.aegisf.data.RememberCategoryRuleRequest
import io.github.triet4p.aegisf.data.SaveDraftReviewRequest
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowClaimGuard
import io.github.triet4p.aegisf.data.laya.LayaCandidatePlanner
import io.github.triet4p.aegisf.data.laya.LayaContextDecision
import io.github.triet4p.aegisf.data.laya.LayaEffectiveContext
import io.github.triet4p.aegisf.data.laya.LayaEffectiveInput
import io.github.triet4p.aegisf.data.laya.LayaEvaluation
import io.github.triet4p.aegisf.data.laya.LayaRuntimeContract
import io.github.triet4p.aegisf.data.rememberedCategoryRuleKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S7.2: a supported bank notification schedules one bounded classification work item after
 * its draft is durably and deduplicably stored.
 *
 * These cases drive the real listener, the real privacy gate, the real parser, the real Room
 * database, the real intake repository and the real command boundary. The one collaborator
 * that is stood in for is the classifier itself: the pinned Laya export is a 1.6 GB device
 * artifact, so the fake engine here records exactly how often the boundary asks for a
 * decision and how many decisions overlap. Everything the acceptance talks about — one
 * draft, one logical work item, no second inference for a replayed delivery, intake that
 * survives a missing or busy model, no ledger row — is observed in the stored state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BankIntakeClassificationTest {

    /**
     * The classifier the boundary asks, counting the questions it is asked.
     *
     * [gate] blocks the answer until the test releases it, which is how a busy model is
     * reproduced without a real device runtime. Blocking runs on the boundary's own IO
     * dispatcher, the same place the real native call would block.
     */
    private class RecordingEngine : ClassificationEngine {
        private val asked = AtomicInteger(0)
        private val active = AtomicInteger(0)
        private val peak = AtomicInteger(0)
        private val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)

        @Volatile
        var unavailable: LayaRuntimeContract.State? = null

        /** Blocks the first answer until [open]. */
        @Volatile
        var gated: Boolean = false

        @Volatile
        var categoryId: Long = 0L

        val calls: Int get() = asked.get()
        val peakOverlap: Int get() = peak.get()

        fun awaitEntered() {
            assertTrue("the model was never asked", entered.await(5, TimeUnit.SECONDS))
        }

        fun open() {
            release.countDown()
        }

        override suspend fun classify(
            context: LayaEffectiveContext,
            categories: List<CategoryEntity>,
        ): ClassificationEngineResult {
            asked.incrementAndGet()
            val now = active.incrementAndGet()
            peak.updateAndGet { previous -> maxOf(previous, now) }
            try {
                if (gated) {
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
                unavailable?.let { return ClassificationEngineResult.Unavailable(it) }
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
            } finally {
                active.decrementAndGet()
            }
        }
    }

    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private lateinit var database: LedgerDatabase
    private lateinit var engine: RecordingEngine
    private lateinit var commands: WorkflowCommandService
    private lateinit var queue: BankDraftClassificationQueue
    private lateinit var repository: IntakeRepository
    private lateinit var listener: BankNotificationListener
    private var foodCategoryId: Long = 0L

    @Before
    fun setUp() = runBlocking<Unit> {
        context = ApplicationProvider.getApplicationContext()
        preferences = context.getSharedPreferences(
            NotificationAccessStore.FILE_NAME,
            Context.MODE_PRIVATE,
        )
        preferences.edit().clear().commit()
        NotificationAccessStore(context).addPackage(BANK_PACKAGE)
        WorkflowClaimGuard.reset()
        BankNotificationListener.updateAllowedPackages(emptySet())
        BankNotificationListener.updateIntakeRepository(null)

        database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val now = System.currentTimeMillis()
        foodCategoryId = database.categoryDao().insert(
            CategoryEntity(name = "Food", createdAtEpochMillis = now, updatedAtEpochMillis = now),
        )
        engine = RecordingEngine().apply { categoryId = foodCategoryId }
        commands = WorkflowCommandService(
            database = database,
            engine = engine,
            findRememberedCategory = { description ->
                val key = rememberedCategoryRuleKey(description)
                if (key == null) null else database.rememberedCategoryRuleDao()
                    .findActiveCategory(key)
            },
        )
        queue = BankDraftClassificationQueue(database = database, commands = commands)
        repository = IntakeRepository(
            database = database,
            onDraftStored = queue::enqueue,
        )

        listener = Robolectric.buildService(BankNotificationListener::class.java).create().get()
        listener.ioDispatcher = Dispatchers.IO
        listener.activeNotificationsForTest = { emptyArray() }
        BankNotificationListener.updateIntakeRepository(repository)
        listener.onListenerConnected()
        withTimeout(5_000L) {
            BankNotificationListener.connection.first { it is ListenerConnection.Connected }
        }
    }

    @After
    fun tearDown() {
        engine.open()
        BankNotificationListener.updateIntakeRepository(null)
        BankNotificationListener.updateAllowedPackages(emptySet())
        listener.onListenerDisconnected()
        listener.onDestroy()
        queue.close()
        database.close()
        preferences.edit().clear().commit()
        WorkflowClaimGuard.reset()
    }

    @Test
    fun `a supported notification stores one draft one work item and one prediction`() =
        runBlocking<Unit> {
            listener.onNotificationPosted(supportedNotification(id = 101))

            val drafts = awaitDrafts(1)
            val draft = drafts.single()
            assertEquals(IntakeParseStatus.PARSED, draft.parseStatus)
            assertEquals(IntakeReviewStatus.PENDING, draft.reviewStatus)

            val snapshot = awaitSnapshot(draft.id)
            assertEquals(PredictionSource.MODEL, PredictionSource.valueOf(snapshot.decisionSource))
            assertEquals(foodCategoryId, snapshot.suggestedCategoryId)
            assertEquals(1, engine.calls)
            assertEquals("one logical work item was recorded", 1L, classificationWorkItems())
            assertTrue("a draft never becomes ledger money by itself", ledgerIsEmpty())
        }

    @Test
    fun `a replayed delivery adds no draft and asks the model no second time`() = runBlocking<Unit> {
        val posted = ContentReadProbe(BANK_PACKAGE, 102, supportedContent())
        listener.onNotificationPosted(posted)
        val first = awaitDrafts(1).single()
        awaitSnapshot(first.id)
        assertEquals(1, engine.calls)

        // The same delivery is delivered again — the platform re-reads an active post
        // after a reconnect — and a genuinely different notification arrives afterwards.
        listener.onNotificationPosted(posted)
        listener.onNotificationPosted(supportedNotification(id = 103))

        val drafts = awaitDrafts(2)
        val second = drafts.first { it.id != first.id }
        awaitSnapshot(second.id)
        assertEquals("no second draft for a replayed delivery", 2, drafts.size)
        // The single-worker queue accepted the replayed item before the second draft, so
        // reaching the second draft's prediction proves the replay was already answered.
        assertEquals("the replay reuses the recorded request", 2, engine.calls)
        assertEquals(2L, classificationWorkItems())
        assertTrue(ledgerIsEmpty())
    }

    @Test
    fun `an unavailable model keeps the draft and leaves the classification missing`() =
        runBlocking {
            engine.unavailable = LayaRuntimeContract.State.Missing

            listener.onNotificationPosted(supportedNotification(id = 104))

            val draft = awaitDrafts(1).single()
            awaitEngineCalls(1)
            assertNull("a missing model never stores a guessed prediction", snapshotOf(draft.id))
            assertEquals(
                "a failed request leaves the key free for a later attempt",
                0L,
                classificationWorkItems(),
            )
            assertTrue(ledgerIsEmpty())
        }

    @Test
    fun `a busy model queues the next draft without holding up persistence`() = runBlocking<Unit> {
        engine.gated = true
        listener.onNotificationPosted(supportedNotification(id = 105))
        engine.awaitEntered()

        listener.onNotificationPosted(supportedNotification(id = 106))

        val stored = awaitDrafts(2)
        assertEquals("both drafts wait as parsed rows while the model is busy", 2, stored.size)
        assertEquals(1, engine.calls)

        engine.open()
        for (draft in stored) awaitSnapshot(draft.id)
        assertEquals(2, engine.calls)
        assertEquals("one inference at a time", 1, engine.peakOverlap)
        assertTrue(ledgerIsEmpty())
    }

    @Test
    fun `privacy-rejected content stores no draft and schedules nothing`() = runBlocking<Unit> {
        listener.onNotificationPosted(
            ContentReadProbe(
                BANK_PACKAGE,
                107,
                notification(
                    title = "Security alert",
                    text = "Your OTP is 123456. Do not share it.",
                ),
            ),
        )

        awaitStored(0)
        assertEquals(0, database.pendingBankDraftDao().findAll().size)
        assertEquals(0, engine.calls)
        assertEquals(0L, classificationWorkItems())
        assertTrue(ledgerIsEmpty())
    }

    @Test
    fun `an unsupported format draft is stored without classification work`() = runBlocking<Unit> {
        listener.onNotificationPosted(
            ContentReadProbe(
                BANK_PACKAGE,
                108,
                notification(
                    title = "Tin biến động số dư",
                    text = "VietinBank: ưu đãi hoàn tiền 50,000 VND",
                ),
            ),
        )

        val draft = awaitDrafts(1).single()
        assertEquals(IntakeParseStatus.UNSUPPORTED_FORMAT, draft.parseStatus)
        awaitStored(0)
        assertEquals(0, engine.calls)
        assertEquals(0L, classificationWorkItems())
        assertTrue(ledgerIsEmpty())
    }

    @Test
    fun `a saved exact-description rule answers before the model`() = runBlocking<Unit> {
        val remembered = commands.rememberCategoryRule(
            WorkflowCallerScope.User,
            RememberCategoryRuleRequest(
                idempotencyKey = "rule-lunch",
                description = "S72 lunch",
                categoryId = foodCategoryId,
            ),
        )
        assertTrue("the rule was refused: $remembered", remembered is WorkflowCommandOutcome.Applied)

        listener.onNotificationPosted(supportedNotification(id = 109))

        val draft = awaitDrafts(1).single()
        val snapshot = awaitSnapshot(draft.id)
        assertEquals(
            PredictionSource.REMEMBERED_RULE,
            PredictionSource.valueOf(snapshot.decisionSource),
        )
        assertEquals(foodCategoryId, snapshot.suggestedCategoryId)
        assertEquals("a rule answers before inference", 0, engine.calls)
        assertEquals(1L, classificationWorkItems())
        assertTrue(ledgerIsEmpty())
    }

    @Test
    fun `a draft the user already categorised is not classified automatically`() = runBlocking<Unit> {
        val capturing = object : IntakeRepository(database) {
            @Volatile var storedId: Long = 0L
            override suspend fun storePermitted(
                sourcePackage: String,
                notificationKey: String?,
                postedAtEpochMillis: Long,
                content: PermittedNotificationContent,
            ): Long = super.storePermitted(
                sourcePackage,
                notificationKey,
                postedAtEpochMillis,
                content,
            ).also { storedId = it }
        }
        BankNotificationListener.updateIntakeRepository(capturing)
        listener.onNotificationPosted(supportedNotification(id = 110))
        withTimeout(5_000L) {
            while (capturing.storedId == 0L) delay(10L)
        }
        val resolvedId = capturing.storedId

        // The user's own choice, stored through the one command boundary.
        val resolved = commands.saveDraftReview(
            WorkflowCallerScope.User,
            SaveDraftReviewRequest(
                idempotencyKey = "resolve-110",
                draftId = resolvedId,
                expectedRevision = 0,
                edits = BankDraftReviewEdits(
                    amountText = "",
                    dateText = "",
                    timeText = "",
                    memoText = "S72 lunch",
                    type = null,
                    categoryId = foodCategoryId,
                    categoryResolved = true,
                ),
            ),
        )
        assertTrue("the choice was refused: $resolved", resolved is WorkflowCommandOutcome.Applied)

        // Admit the resolved draft, then one fresh draft. Arrival order makes the first
        // item's skip observable: the second draft's prediction can only be stored after
        // the resolved draft's work item has been answered.
        queue.enqueue(resolvedId)
        BankNotificationListener.updateIntakeRepository(repository)
        listener.onNotificationPosted(supportedNotification(id = 111))

        val fresh = awaitDrafts(2).first { it.id != resolvedId }
        val freshSnapshot = awaitSnapshot(fresh.id)
        assertEquals(fresh.id, freshSnapshot.draftId)
        assertEquals("only the fresh draft was sent to the model", 1, engine.calls)
        assertEquals(1L, classificationWorkItems())
        assertNull("the user's draft keeps its settled state", snapshotOf(resolvedId))

        val stillResolved = database.pendingBankDraftDao().findById(resolvedId)
        assertTrue(checkNotNull(stillResolved).reviewCategoryResolved)
        assertEquals(foodCategoryId, stillResolved?.reviewCategoryId)
        assertTrue(ledgerIsEmpty())
    }

    // --- Observation helpers ------------------------------------------------

    private suspend fun awaitDrafts(count: Int): List<PendingBankDraftEntity> =
        withContext(Dispatchers.IO) {
            withTimeout(10_000L) {
                var drafts = database.pendingBankDraftDao().findAll()
                while (drafts.size < count) {
                    delay(10L)
                    drafts = database.pendingBankDraftDao().findAll()
                }
                drafts
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

    private suspend fun snapshotOf(draftId: Long) =
        database.classificationSnapshotDao().findForDraft(draftId)

    private suspend fun awaitEngineCalls(count: Int) = withContext(Dispatchers.IO) {
        withTimeout(10_000L) {
            while (engine.calls < count) delay(10L)
        }
    }

    /** Waits until at least [count] drafts are stored, then lets the work settle a moment. */
    private suspend fun awaitStored(count: Int) {
        if (count > 0) awaitDrafts(count)
        withContext(Dispatchers.IO) { delay(250L) }
    }

    private suspend fun classificationWorkItems(): Long =
        database.workflowOperationDao().countOf("REQUEST_CLASSIFICATION")

    private suspend fun ledgerIsEmpty(): Boolean =
        database.transactionDao().findAll().isEmpty()

    // --- Notification fixtures ----------------------------------------------

    private fun supportedNotification(id: Int): StatusBarNotification =
        ContentReadProbe(BANK_PACKAGE, id, supportedContent())

    private fun supportedContent(): Notification = notification(
        title = "Tin biến động số dư",
        text = "VietinBank: 16/10/2026 07:38\n" +
            "TK: Redacted\n" +
            "GD: -50,000 VND\n" +
            "SDC: xxx,000 VND\n" +
            "ND: S72 lunch",
    )

    private fun notification(title: String, text: String): Notification = Notification().apply {
        extras.putCharSequence(Notification.EXTRA_TITLE, title)
        extras.putCharSequence(Notification.EXTRA_TEXT, text)
    }

    private companion object {
        const val BANK_PACKAGE = "com.vietinbank.ipay"
    }
}
