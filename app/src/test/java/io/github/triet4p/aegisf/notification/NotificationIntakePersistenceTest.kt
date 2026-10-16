package io.github.triet4p.aegisf.notification

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.IntakeRepository
import io.github.triet4p.aegisf.data.NotificationAccessStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The S3.4 callback-to-persistence wiring on the real listener: what reaches the
 * database, on which dispatcher, and in which readiness order.
 *
 * The listener consults a recording [IntakeRepository] that notes which dispatcher ran
 * the store call and which drafts it received, while the privacy boundary and the
 * parser stay real. The fixtures are the user's own redacted VietinBank sample
 * (`artifacts/sprint-3/handoffs/vietinbank-notification-context.md`); which field the
 * bank really uses is device evidence for a later task, so the split here is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationIntakePersistenceTest {

    private class RecordingIntakeRepository : IntakeRepository(
        draftDao = RecordingDraftDao(),
    ) {
        val storedPackages = mutableListOf<String>()
        var storedOnTestDispatcher = false

        override suspend fun storePermitted(
            sourcePackage: String,
            notificationKey: String?,
            postedAtEpochMillis: Long,
            content: PermittedNotificationContent,
        ): Long {
            // Runs on the listener's ioDispatcher (this test's dispatcher here,
            // Dispatchers.IO in production), never inline in the main-thread callback:
            // the callback only launches the write, and flushing the dispatcher runs it.
            storedOnTestDispatcher = true
            storedPackages += sourcePackage
            return super.storePermitted(sourcePackage, notificationKey, postedAtEpochMillis, content)
        }
    }

    private class RecordingDraftDao : io.github.triet4p.aegisf.data.PendingBankDraftDao() {
        val rows = mutableListOf<io.github.triet4p.aegisf.data.PendingBankDraftEntity>()

        override suspend fun storedDraftId(deliveryId: String, evidenceDigest: String): Long? =
            rows.firstOrNull {
                it.deliveryId == deliveryId && it.evidenceDigest == evidenceDigest
            }?.id

        override suspend fun earliestDraftWithEvidence(evidenceDigest: String): Long? =
            rows.filter { it.evidenceDigest == evidenceDigest }.minOfOrNull { it.id }

        override suspend fun insertIgnoringStoredIdentity(
            draft: io.github.triet4p.aegisf.data.PendingBankDraftEntity,
        ): Long {
            if (storedDraftId(draft.deliveryId, draft.evidenceDigest) != null) {
                return -1L
            }
            val stored = draft.copy(id = (rows.maxOfOrNull { it.id } ?: 0L) + 1L)
            rows += stored
            return stored.id
        }

        override suspend fun findById(id: Long) = rows.firstOrNull { it.id == id }

        override suspend fun findAll() = rows.toList()

        override fun observeAll() = kotlinx.coroutines.flow.MutableStateFlow(rows.toList())
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
        store = NotificationAccessStore(context)
        Dispatchers.setMain(StandardTestDispatcher())
        BankNotificationListener.updateIntakeRepository(null)
        BankNotificationListener.updateAllowedPackages(emptySet())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        BankNotificationListener.updateIntakeRepository(null)
        BankNotificationListener.updateAllowedPackages(emptySet())
    }

    @Test
    fun `a permitted post persists one parsed draft on the background dispatcher`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val listener = Robolectric.buildService(BankNotificationListener::class.java)
            .create()
            .get()
        listener.ioDispatcher = dispatcher
        val recorder = RecordingIntakeRepository()
        BankNotificationListener.updateIntakeRepository(recorder)

        try {
            store.addPackage(bankPackage)
            BankNotificationListener.updateAllowedPackages(bankPackages)
            listener.onListenerConnected()
            advanceUntilIdle()

            listener.onNotificationPosted(
                ContentReadProbe(bankPackage, 1, notification(text = vietinBankText)),
            )
            // The background step runs on the listener's ioDispatcher (this test's
            // dispatcher), not inline in the callback: flushing it persists the draft.
            advanceUntilIdle()

            assertEquals(listOf(bankPackage), recorder.storedPackages)
            assertTrue(recorder.storedOnTestDispatcher)
            val stored = recorder.drafts().single()
            assertEquals(50_000L, stored.amountVnd)
            assertEquals(IntakeDirection.DEBIT, stored.direction)
            assertEquals("2026-10-16", stored.statedDate)
            assertEquals("07:38", stored.statedTime)
            assertEquals(IntakeFieldState.UNRESOLVED, stored.memoState)
        } finally {
            listener.ioDispatcher = Dispatchers.IO
            listener.onListenerDisconnected()
            advanceUntilIdle()
        }
    }

    @Test
    fun `a rejected post persists nothing`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val listener = Robolectric.buildService(BankNotificationListener::class.java)
            .create()
            .get()
        listener.ioDispatcher = dispatcher
        val recorder = RecordingIntakeRepository()
        BankNotificationListener.updateIntakeRepository(recorder)

        try {
            store.addPackage(bankPackage)
            BankNotificationListener.updateAllowedPackages(bankPackages)
            listener.onListenerConnected()
            advanceUntilIdle()

            listener.onNotificationPosted(
                ContentReadProbe(bankPackage, 1, notification(text = "Your OTP is 482913")),
            )
            val unlisted = ContentReadProbe(
                "com.example.game",
                2,
                notification(text = vietinBankText),
            )
            listener.onNotificationPosted(unlisted)
            advanceUntilIdle()

            assertTrue(recorder.storedPackages.isEmpty())
            assertEquals(0, unlisted.contentReads())
        } finally {
            listener.ioDispatcher = Dispatchers.IO
            listener.onListenerDisconnected()
            advanceUntilIdle()
        }
    }

    @Test
    fun `an init window post persists after readiness in arrival order`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val listener = Robolectric.buildService(BankNotificationListener::class.java)
            .create()
            .get()
        listener.ioDispatcher = dispatcher
        val recorder = RecordingIntakeRepository()
        BankNotificationListener.updateIntakeRepository(recorder)

        try {
            store.addPackage(bankPackage)
            BankNotificationListener.updateAllowedPackages(emptySet())

            listener.onListenerConnected()
            val early = ContentReadProbe(bankPackage, 1, notification(text = vietinBankText))
            // Bound but not ready: the callback queues without reading content.
            listener.onNotificationPosted(early)
            assertEquals(0, early.contentReads())
            assertTrue(recorder.storedPackages.isEmpty())

            advanceUntilIdle()
            assertEquals(bankPackages, BankNotificationListener.allowedPackages)
            assertTrue(recorder.storedPackages == listOf(bankPackage))
            assertTrue(early.contentReads() > 0)
        } finally {
            listener.ioDispatcher = Dispatchers.IO
            listener.onListenerDisconnected()
            advanceUntilIdle()
        }
    }

    @Test
    fun `revocation before readiness leaves no post revoke draft`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val listener = Robolectric.buildService(BankNotificationListener::class.java)
            .create()
            .get()
        listener.ioDispatcher = dispatcher
        val recorder = RecordingIntakeRepository()
        BankNotificationListener.updateIntakeRepository(recorder)

        try {
            store.addPackage(bankPackage)
            BankNotificationListener.updateAllowedPackages(emptySet())

            listener.onListenerConnected()
            listener.onNotificationPosted(
                ContentReadProbe(bankPackage, 1, notification(text = vietinBankText)),
            )
            listener.onListenerDisconnected()
            advanceUntilIdle()

            assertTrue(recorder.storedPackages.isEmpty())
        } finally {
            listener.ioDispatcher = Dispatchers.IO
            listener.onListenerDisconnected()
            advanceUntilIdle()
        }
    }

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

    private companion object {
        const val bankPackage = "com.vietinbank.ipay"
        const val CREATED_AT = 1_760_000_001_000L

        val bankPackages = setOf(bankPackage)

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
