package io.github.triet4p.aegisf.notification

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeRepository
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.NotificationAccessStore
import io.github.triet4p.aegisf.data.TransactionInput
import io.github.triet4p.aegisf.data.LedgerRepository
import io.github.triet4p.aegisf.data.RecordSource
import io.github.triet4p.aegisf.data.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
 * The S3.7 lifecycle recovery on the real listener: connected/disconnected handling, the
 * supported rebind gate, and current-active-only reconciliation through the same
 * allowlist/security/group filter and the same persisted delivery/evidence identity a
 * live callback uses.
 *
 * Every case runs the production [reconcileActivePosts] / [requestListenerRebind] plus
 * the real listener connect flow against the production database builder
 * [LedgerDatabase.build], with the active-notification answer supplied by the test as the
 * fake system side. The fixtures are the user's own redacted VietinBank sample
 * (`artifacts/sprint-3/handoffs/vietinbank-notification-context.md`), kept verbatim:
 * no account or balance value is asserted anywhere, and no transaction reference is
 * invented — equal evidence from two deliveries stays two drafts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListenerRecoveryTest {

    /**
     * The real production store with a test-side completion signal.
     *
     * Every call delegates to the real database-backed [IntakeRepository] first — the
     * stored row, the returned id, and the S3.5 delivery/evidence identity are the
     * production ones — and only then completes [stored] with that id. The signal
     * therefore proves the real write happened rather than echoing the call.
     */
    private class SignalingIntakeRepository(
        database: LedgerDatabase,
    ) : IntakeRepository(database.pendingBankDraftDao()) {
        val stored = kotlinx.coroutines.CompletableDeferred<Long>()

        override suspend fun storePermitted(
            sourcePackage: String,
            notificationKey: String?,
            postedAtEpochMillis: Long,
            content: PermittedNotificationContent,
        ): Long {
            val id = super.storePermitted(
                sourcePackage,
                notificationKey,
                postedAtEpochMillis,
                content,
            )
            if (!stored.isCompleted) {
                stored.complete(id)
            }
            return id
        }
    }

    private lateinit var context: Context
    private lateinit var store: NotificationAccessStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(
            NotificationAccessStore.FILE_NAME,
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
        context.deleteDatabase(LedgerDatabase.NAME)
        store = NotificationAccessStore(context)
        Dispatchers.setMain(StandardTestDispatcher())
        BankNotificationListener.updateIntakeRepository(null)
        BankNotificationListener.updateAllowedPackages(emptySet())
        accessGrantedForTest = null
        rebinderForTest = null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        BankNotificationListener.updateIntakeRepository(null)
        BankNotificationListener.updateAllowedPackages(emptySet())
        accessGrantedForTest = null
        rebinderForTest = null
    }

    @Test
    fun `reconnect reconciles the still active post and ignores removed history`() = runTest {
        val database = LedgerDatabase.build(context)
        val intake = SignalingIntakeRepository(database)
        BankNotificationListener.updateIntakeRepository(intake)
        store.addPackage(bankPackage)
        val active = posted(bankPackage, ACTIVE_KEY, POSTED_AT, notification(text = vietinBankText))
        val removed = posted(bankPackage, REMOVED_KEY, POSTED_AT - 60_000L, notification(text = vietinBankText))

        // Room suspends its transactions on its own background executor, outside the
        // test scheduler's virtual time: run the listener on a real dispatcher and
        // await the signaled real write with real time.
        val listener = Robolectric.buildService(BankNotificationListener::class.java).create().get()
        listener.ioDispatcher = Dispatchers.IO.limitedParallelism(1)
        listener.activeNotificationsForTest = { arrayOf(active) }
        try {
            listener.onListenerConnected()
            val storedId = kotlinx.coroutines.withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(10_000L) { intake.stored.await() }
            }

            val drafts = intake.drafts()
            assertEquals(1, drafts.size)
            assertEquals(storedId, drafts.single().id)
            assertEquals(ACTIVE_KEY, drafts.single().notificationKey)
            assertEquals(50_000L, drafts.single().amountVnd)
            assertEquals(IntakeReviewStatus.PENDING, drafts.single().reviewStatus)
            assertTrue(removed.postTime < drafts.single().postedAtEpochMillis)
        } finally {
            listener.activeNotificationsForTest = null
            listener.ioDispatcher = Dispatchers.IO
            listener.onListenerDisconnected()
            advanceUntilIdle()
            database.close()
        }
    }

    @Test
    fun `reconciling the same active post again writes nothing`() = runTest {
        val database = LedgerDatabase.build(context)
        val intake = SignalingIntakeRepository(database)
        BankNotificationListener.updateIntakeRepository(intake)
        store.addPackage(bankPackage)
        val active = posted(bankPackage, ACTIVE_KEY, POSTED_AT, notification(text = vietinBankText))

        val listener = Robolectric.buildService(BankNotificationListener::class.java).create().get()
        listener.ioDispatcher = Dispatchers.IO.limitedParallelism(1)
        listener.activeNotificationsForTest = { arrayOf(active) }
        try {
            listener.onListenerConnected()
            val firstId = kotlinx.coroutines.withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(10_000L) { intake.stored.await() }
            }

            // The system still shows the same post after a second reconnect: same delivery
            // and same evidence, so the persisted identity turns it into no write.
            listener.onListenerDisconnected()
            advanceUntilIdle()
            listener.onListenerConnected()
            advanceUntilIdle()

            assertEquals(1, intake.drafts().size)
            assertEquals(firstId, intake.drafts().single().id)
        } finally {
            listener.activeNotificationsForTest = null
            listener.ioDispatcher = Dispatchers.IO
            listener.onListenerDisconnected()
            advanceUntilIdle()
            database.close()
        }
    }

    @Test
    fun `reconciliation rejects unlisted otp and group posts without storing bodies`() = runTest {
        val database = LedgerDatabase.build(context)
        val intake = IntakeRepository(database.pendingBankDraftDao())
        BankNotificationListener.updateIntakeRepository(intake)
        store.addPackage(bankPackage)
        val active = listOf(
            posted("com.example.game", 11, notification(text = vietinBankText)),
            posted(bankPackage, 12, notification(text = "Your OTP is 482913. Do not share it.")),
            posted(bankPackage, 13, notification(text = vietinBankText, groupSummary = true)),
        )

        val dispatcher = StandardTestDispatcher(testScheduler)
        val listener = Robolectric.buildService(BankNotificationListener::class.java).create().get()
        listener.ioDispatcher = dispatcher
        listener.activeNotificationsForTest = { active.toTypedArray() }
        try {
            listener.onListenerConnected()
            advanceUntilIdle()

            assertTrue(intake.drafts().isEmpty())
        } finally {
            listener.activeNotificationsForTest = null
            listener.ioDispatcher = Dispatchers.IO
            listener.onListenerDisconnected()
            advanceUntilIdle()
            database.close()
        }
    }

    @Test
    fun `a disconnect mid reconcile stops further writes without touching drafts`() = runTest {
        val database = LedgerDatabase.build(context)
        val firstStored = kotlinx.coroutines.CompletableDeferred<Long>()
        val recording = object : IntakeRepository(database.pendingBankDraftDao()) {
            override suspend fun storePermitted(
                sourcePackage: String,
                notificationKey: String?,
                postedAtEpochMillis: Long,
                content: PermittedNotificationContent,
            ): Long {
                val stored = super.storePermitted(
                    sourcePackage,
                    notificationKey,
                    postedAtEpochMillis,
                    content,
                )
                if (notificationKey == ACTIVE_KEY && !firstStored.isCompleted) {
                    firstStored.complete(stored)
                }
                return stored
            }
        }
        BankNotificationListener.updateIntakeRepository(recording)
        store.addPackage(bankPackage)
        val active = listOf(
            posted(bankPackage, ACTIVE_KEY, POSTED_AT, notification(text = vietinBankText)),
            posted(bankPackage, "second-active", POSTED_AT + 60_000L, notification(text = vietinBankText)),
        )

        val listener = Robolectric.buildService(BankNotificationListener::class.java).create().get()
        listener.ioDispatcher = Dispatchers.IO.limitedParallelism(1)
        listener.activeNotificationsForTest = { active.toTypedArray() }
        try {
            listener.onListenerConnected()
            // The first still-active post completes its real write; the disconnect then
            // cancels the reconciliation before the second write starts.
            kotlinx.coroutines.withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(10_000L) { firstStored.await() }
            }
            listener.onListenerDisconnected()
            advanceUntilIdle()

            // The first still-active post was stored before the disconnect; the second one
            // never becomes a post-disconnect draft, while the stored draft is untouched.
            assertEquals(1, recording.drafts().size)
            assertEquals(ACTIVE_KEY, recording.drafts().single().notificationKey)
        } finally {
            listener.activeNotificationsForTest = null
            listener.ioDispatcher = Dispatchers.IO
            listener.onListenerDisconnected()
            advanceUntilIdle()
            database.close()
        }
    }

    @Test
    fun `an unavailable active answer reconciles nothing and keeps the durable drafts`() = runTest {
        val database = LedgerDatabase.build(context)
        store.addPackage(bankPackage)
        // A draft persisted before this connect: an active answer the system cannot give must
        // leave the durable drafts exactly as they are — not cleared, not rewritten, and not
        // duplicated by a spurious write.
        val durableId = IntakeRepository(database.pendingBankDraftDao())
            .storePermitted(bankPackage, ACTIVE_KEY, POSTED_AT, sampleContent())
        val writes = mutableListOf<String>()
        BankNotificationListener.updateIntakeRepository(
            object : IntakeRepository(database.pendingBankDraftDao()) {
                override suspend fun storePermitted(
                    sourcePackage: String,
                    notificationKey: String?,
                    postedAtEpochMillis: Long,
                    content: PermittedNotificationContent,
                ): Long {
                    writes += "$sourcePackage|$notificationKey|$postedAtEpochMillis"
                    return super.storePermitted(
                        sourcePackage,
                        notificationKey,
                        postedAtEpochMillis,
                        content,
                    )
                }
            },
        )

        // The connect load runs on this thread through the test dispatcher, so a failure inside
        // the reconciliation surfaces from `advanceUntilIdle()` and fails this test, which is
        // what an unguarded read of an unavailable active set does.
        val listener = Robolectric.buildService(BankNotificationListener::class.java).create().get()
        listener.ioDispatcher = StandardTestDispatcher(testScheduler)
        // The system side reports no active set at all, which is not the same answer as an empty
        // one: this is the null the platform's own read returns before it has a binding —
        // Robolectric's system side substitutes an empty array for it just as API 28+ does.
        listener.activeNotificationsForTest = { null }
        try {
            listener.onListenerConnected()
            advanceUntilIdle()

            assertTrue(writes.isEmpty())
            val drafts = IntakeRepository(database.pendingBankDraftDao()).drafts()
            assertEquals(1, drafts.size)
            assertEquals(durableId, drafts.single().id)
            assertEquals(ACTIVE_KEY, drafts.single().notificationKey)
            assertEquals(IntakeReviewStatus.PENDING, drafts.single().reviewStatus)
        } finally {
            listener.activeNotificationsForTest = null
            listener.ioDispatcher = Dispatchers.IO
            listener.onListenerDisconnected()
            advanceUntilIdle()
            database.close()
        }
    }

    @Test
    fun `a confirmed draft is never reopened by its own re read post`() = runTest {
        val database = LedgerDatabase.build(context)
        val intake = IntakeRepository(database.pendingBankDraftDao())
        val ledger = LedgerRepository(
            database.categoryDao(),
            database.transactionDao(),
            database.rememberedCategoryRuleDao(),
        )
        val reconciled = kotlinx.coroutines.CompletableDeferred<Long>()
        val signaling = object : IntakeRepository(database.pendingBankDraftDao()) {
            override suspend fun storePermitted(
                sourcePackage: String,
                notificationKey: String?,
                postedAtEpochMillis: Long,
                content: PermittedNotificationContent,
            ): Long {
                // Delegates to the real store — the terminal row keeps its state and link
                // through the replay lookup — then signals the replay resolved.
                val id = super.storePermitted(
                    sourcePackage,
                    notificationKey,
                    postedAtEpochMillis,
                    content,
                )
                if (!reconciled.isCompleted) {
                    reconciled.complete(id)
                }
                return id
            }
        }
        BankNotificationListener.updateIntakeRepository(signaling)
        store.addPackage(bankPackage)
        val draftId = intake.storePermitted(bankPackage, ACTIVE_KEY, POSTED_AT, sampleContent())
        val transactionId = ledger.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountVnd = 50_000L,
                occurredAtEpochMillis = POSTED_AT,
                description = "VietinBank debit",
                source = RecordSource.BANK_NOTIFICATION,
            ),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE `pending_bank_drafts` SET `reviewStatus` = ?, `confirmedTransactionId` = ? WHERE `id` = ?",
            arrayOf<Any?>(IntakeReviewStatus.CONFIRMED.name, transactionId, draftId),
        )
        val active = posted(bankPackage, ACTIVE_KEY, POSTED_AT, notification(text = vietinBankText))

        val listener = Robolectric.buildService(BankNotificationListener::class.java).create().get()
        listener.ioDispatcher = Dispatchers.IO.limitedParallelism(1)
        listener.activeNotificationsForTest = { arrayOf(active) }
        try {
            listener.onListenerConnected()
            val replayId = kotlinx.coroutines.withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(10_000L) { reconciled.await() }
            }

            assertEquals(draftId, replayId)
            val stored = intake.drafts().single()
            assertEquals(IntakeReviewStatus.CONFIRMED, stored.reviewStatus)
            assertEquals(transactionId, stored.confirmedTransactionId)
            assertEquals(1, ledger.transactions().size)
            assertEquals(IntakeDirection.DEBIT, stored.direction)
            assertEquals(IntakeFieldState.UNRESOLVED, stored.memoState)
        } finally {
            listener.activeNotificationsForTest = null
            listener.ioDispatcher = Dispatchers.IO
            listener.onListenerDisconnected()
            advanceUntilIdle()
            database.close()
        }
    }

    @Test
    fun `disconnect and restart keep terminal identities and drafts`() = runTest {
        val first = LedgerDatabase.build(context)
        val draftId: Long
        val transactionId: Long
        try {
            val intake = IntakeRepository(first.pendingBankDraftDao())
            val ledger = LedgerRepository(
                first.categoryDao(),
                first.transactionDao(),
                first.rememberedCategoryRuleDao(),
            )
            draftId = intake.storePermitted(bankPackage, ACTIVE_KEY, POSTED_AT, sampleContent())
            transactionId = ledger.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = 50_000L,
                    occurredAtEpochMillis = POSTED_AT,
                    description = "VietinBank debit",
                    source = RecordSource.BANK_NOTIFICATION,
                ),
            )
            first.openHelper.writableDatabase.execSQL(
                "UPDATE `pending_bank_drafts` SET `reviewStatus` = ?, `confirmedTransactionId` = ? WHERE `id` = ?",
                arrayOf<Any?>(IntakeReviewStatus.CONFIRMED.name, transactionId, draftId),
            )
        } finally {
            first.close()
        }

        // The process restarts: the persisted terminal draft and its ledger record are
        // recovered, and the still-active delivery resolves to the same row.
        val restarted = LedgerDatabase.build(context)
        try {
            val intake = IntakeRepository(restarted.pendingBankDraftDao())
            val ledger = LedgerRepository(
                restarted.categoryDao(),
                restarted.transactionDao(),
                restarted.rememberedCategoryRuleDao(),
            )
            assertEquals(draftId, intake.drafts().single().id)
            assertEquals(IntakeReviewStatus.CONFIRMED, intake.drafts().single().reviewStatus)
            assertEquals(1, ledger.transactions().size)
            val replay = intake.storePermitted(bankPackage, ACTIVE_KEY, POSTED_AT, sampleContent())
            assertEquals(draftId, replay)
            assertEquals(1, intake.drafts().size)
            assertEquals(1, ledger.transactions().size)
        } finally {
            restarted.close()
        }
    }

    @Test
    fun `the supported rebind is refused without access and requested with it`() {
        var calls = 0
        val refused = requestListenerRebind(
            context,
            accessGranted = false,
            rebinder = { calls += 1 },
        )
        assertEquals(ListenerRebindOutcome.RefusedNoAccess, refused)
        assertEquals(0, calls)

        var requestedComponent: ComponentName? = null
        val requested = requestListenerRebind(
            context,
            accessGranted = true,
            rebinder = { component ->
                calls += 1
                requestedComponent = component
            },
        )
        assertEquals(ListenerRebindOutcome.Requested, requested)
        assertEquals(1, calls)
        assertEquals(
            ComponentName(context, BankNotificationListener::class.java),
            requestedComponent,
        )
    }

    @Test
    fun `revoked access reconciles nothing without reading content`() {
        val probe = ContentReadProbe(bankPackage, 1, notification(text = vietinBankText))
        val reconciled = reconcileActivePosts(
            active = listOf(probe),
            accessGranted = false,
            allowedPackages = setOf(bankPackage),
        )
        assertTrue(reconciled.isEmpty())
        assertEquals(0, probe.contentReads())
    }

    @Test
    fun `requestActiveReconciliation while connected reconciles active posts without reconnect`() = runTest {
        val database = LedgerDatabase.build(context)
        val intake = SignalingIntakeRepository(database)
        BankNotificationListener.updateIntakeRepository(intake)
        store.addPackage(bankPackage)
        val active = posted(bankPackage, ACTIVE_KEY, POSTED_AT, notification(text = vietinBankText))

        val listener = Robolectric.buildService(BankNotificationListener::class.java).create().get()
        listener.ioDispatcher = Dispatchers.IO.limitedParallelism(1)
        listener.activeNotificationsForTest = { emptyArray() }
        try {
            listener.onListenerConnected()
            advanceUntilIdle()
            assertTrue(intake.drafts().isEmpty())

            // Notification arrives while listener is connected; active reconciliation updates drafts.
            listener.activeNotificationsForTest = { arrayOf(active) }
            BankNotificationListener.requestActiveReconciliation(context)
            kotlinx.coroutines.withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(10_000L) { intake.stored.await() }
            }

            assertEquals(1, intake.drafts().size)
            assertEquals(ACTIVE_KEY, intake.drafts().single().notificationKey)
        } finally {
            listener.activeNotificationsForTest = null
            listener.ioDispatcher = Dispatchers.IO
            listener.onListenerDisconnected()
            advanceUntilIdle()
            database.close()
        }
    }

    @Test
    fun `requestActiveReconciliation while disconnected requests rebind when access is granted`() {
        accessGrantedForTest = true
        var rebindCalls = 0
        rebinderForTest = { rebindCalls += 1 }
        try {
            BankNotificationListener.requestActiveReconciliation(context)
            assertEquals(1, rebindCalls)
        } finally {
            accessGrantedForTest = null
            rebinderForTest = null
        }
    }

    @Test
    fun `disconnect with granted access automatically requests rebind`() = runTest {
        val listener = Robolectric.buildService(BankNotificationListener::class.java).create().get()
        listener.ioDispatcher = StandardTestDispatcher(testScheduler)
        accessGrantedForTest = true
        var rebindCalls = 0
        rebinderForTest = { rebindCalls += 1 }
        try {
            listener.onListenerConnected()
            advanceUntilIdle()
            listener.onListenerDisconnected()
            assertEquals(1, rebindCalls)
        } finally {
            accessGrantedForTest = null
            rebinderForTest = null
            listener.ioDispatcher = Dispatchers.IO
        }
    }

    /** A real posted notification carrying routing metadata plus the supported fields. */
    private fun posted(
        packageName: String,
        key: String,
        postTime: Long,
        content: Notification,
    ): StatusBarNotification = KeyedPost(packageName, key, postTime, content)

    /** A real posted notification carrying routing metadata plus the supported fields. */
    private fun posted(
        packageName: String,
        id: Int,
        content: Notification,
    ): StatusBarNotification = ContentReadProbe(packageName, id, content)

    /** A notification carrying only the supported fields that were actually posted. */
    private fun notification(
        title: String? = null,
        text: String? = null,
        bigText: String? = null,
        groupSummary: Boolean = false,
    ): Notification = Notification().apply {
        if (title != null) {
            extras.putCharSequence(Notification.EXTRA_TITLE, title)
        }
        if (text != null) {
            extras.putCharSequence(Notification.EXTRA_TEXT, text)
        }
        if (bigText != null) {
            extras.putCharSequence(Notification.EXTRA_BIG_TEXT, bigText)
        }
        flags = if (groupSummary) Notification.FLAG_GROUP_SUMMARY else 0
    }

    private fun sampleContent(): PermittedNotificationContent = PermittedNotificationContent(
        title = vietinBankTitle,
        text = vietinBankText,
        bigText = null,
    )

    private companion object {
        const val bankPackage = "com.vietinbank.ipay"
        const val ACTIVE_KEY = "0|com.vietinbank.ipay|7|active|700"
        const val REMOVED_KEY = "0|com.vietinbank.ipay|6|removed|600"
        const val POSTED_AT = 1_760_000_000_000L
        const val vietinBankTitle = "Tin biến động số dư"

        /** The body of the user's VietinBank sample, verbatim. */
        val vietinBankText = listOf(
            "VietinBank: 16/10/2026 07:38",
            "TK: Redacted",
            "GD: -50,000 VND",
            "SDC: xxx,000 VND",
            "ND: ........",
        ).joinToString("\n")
    }
}

/**
 * A real posted notification with a fixed platform key and post time.
 *
 * `StatusBarNotification` derives both from its constructor arguments, so a test that
 * needs a precise delivery identity wraps the probe instead of reimplementing the
 * platform class: only the key and the post time are pinned, and content reads are
 * still counted by the shared probe.
 */
private class KeyedPost(
    packageName: String,
    private val fixedKey: String,
    private val fixedPostTime: Long,
    content: Notification,
) : ContentReadProbe(packageName, fixedKey.hashCode(), content) {
    override fun getKey(): String = fixedKey

    override fun getPostTime(): Long = fixedPostTime
}
