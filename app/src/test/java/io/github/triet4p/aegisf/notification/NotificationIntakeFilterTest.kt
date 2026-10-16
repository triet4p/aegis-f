package io.github.triet4p.aegisf.notification

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
 * The S3.2 privacy boundary: what may be read, what may become intake, and in which order.
 *
 * These tests run the real filter and the real listener callback against real
 * `StatusBarNotification` and `Notification` objects, not a reimplementation. The ordering
 * guarantee — a package outside the allowlist is dropped before any content field is touched
 * — is asserted through [ContentReadProbe], which counts requests for a notification's
 * content object; the positive case in each test proves the probe and the boundary both run,
 * so a zero count means "never read" rather than "never wired".
 *
 * The content cases are the ones a consumer would notice if they went wrong: an OTP or
 * security alert becoming a draft, a group summary being read as one transaction, an empty
 * or fully redacted payload being treated as a financial success, or a partly redacted bank
 * notification being thrown away even though its transaction line is readable.
 *
 * The VietinBank fixture is the user's own redacted sample
 * (`artifacts/sprint-3/handoffs/vietinbank-notification-context.md`), kept verbatim: the
 * account line is redacted and the memo is dots, so it must stay a candidate that a later
 * parser can read — never a confirmed transaction, and never a security rejection. Which
 * field the bank really uses (title, text, or big text) is device evidence for a later task,
 * so the split used here is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationIntakeFilterTest {

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
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        BankNotificationListener.updateAllowedPackages(emptySet())
    }

    @Test
    fun `a package outside the allowlist is dropped before its content is read`() {
        // The text is the most sensitive case there is: if any of it were read before the
        // package gate, this notification would be the one to expose.
        val untrusted = ContentReadProbe(
            "com.example.game",
            1,
            notification(title = "Aegis Bank", text = "Your OTP is 482913. Do not share it."),
        )
        assertEquals(
            NotificationIntakeDecision.NotPermitted,
            decideNotificationIntake(untrusted, bankPackages),
        )
        assertEquals(0, untrusted.contentReads())

        // No bank app listed: everything is refused for the same reason, and still nothing
        // is read. An allowlisted-looking package name alone is never enough.
        val unlisted = ContentReadProbe(bankPackage, 2, notification(text = "GD: -50,000 VND"))
        assertEquals(
            NotificationIntakeDecision.NotPermitted,
            decideNotificationIntake(unlisted, emptySet()),
        )
        assertEquals(0, unlisted.contentReads())
    }

    @Test
    fun `a partly redacted bank notification stays a candidate with its readable line`() {
        val probe = ContentReadProbe(
            bankPackage,
            1,
            notification(title = vietinBankTitle, text = vietinBankText),
        )

        assertEquals(
            NotificationIntakeDecision.Permitted(
                PermittedNotificationContent(
                    title = vietinBankTitle,
                    text = vietinBankText,
                    bigText = null,
                ),
            ),
            decideNotificationIntake(probe, bankPackages),
        )
        assertTrue(probe.contentReads() > 0)
        // The redacted account line and the dotted memo are carried through unchanged:
        // redaction is not silently rewritten, and the readable GD line is not discarded
        // with them. A Permitted decision holds only text — no amount, direction, date, or
        // confirmation — so this is a candidate, not a financial transaction.
    }

    @Test
    fun `a payload that is only redaction or placeholders is rejected`() {
        val unusable = listOf(
            notification(text = "........"),
            notification(title = "Redacted", text = "•••• ••••"),
            notification(text = "****"),
            notification(text = "xxxx xxxx", bigText = "____"),
            notification(text = "TK: Redacted\nND: ........"),
            notification(bigText = "[redacted]"),
        )

        unusable.forEachIndexed { index, content ->
            assertEquals(
                "payload $index",
                NotificationIntakeDecision.Rejected(NotificationIntakeRejection.RedactedOnly),
                decideNotificationIntake(ContentReadProbe(bankPackage, index, content), bankPackages),
            )
        }
    }

    @Test
    fun `a payload with no supported text is rejected`() {
        val empty = listOf(
            notification(),
            notification(title = "   ", text = "\n"),
        )

        empty.forEachIndexed { index, content ->
            assertEquals(
                "payload $index",
                NotificationIntakeDecision.Rejected(NotificationIntakeRejection.NoContent),
                decideNotificationIntake(ContentReadProbe(bankPackage, index, content), bankPackages),
            )
        }
    }

    @Test
    fun `otp and security messages are rejected without keeping their text`() {
        val security = listOf(
            notification(
                title = "Aegis Bank",
                text = "Your OTP is 482913. Do not share this code with anyone.",
            ),
            notification(
                title = "VietinBank",
                text = "Mã OTP của bạn là 482913. Không chia sẻ mã này với bất kỳ ai.",
            ),
            // The code can arrive in the expanded text while the collapsed line looks like
            // an ordinary transaction, so every supported field is screened.
            notification(
                title = "VietinBank",
                text = "GD: -50,000 VND",
                bigText = "Mã xác thực giao dịch: 482913",
            ),
            notification(title = "VietinBank", text = "A new device signed in. If this was not you, call us."),
        )

        security.forEachIndexed { index, content ->
            assertEquals(
                "payload $index",
                NotificationIntakeDecision.Rejected(NotificationIntakeRejection.SecurityContent),
                decideNotificationIntake(ContentReadProbe(bankPackage, index, content), bankPackages),
            )
        }
        // A rejected decision carries the reason only: no field of the message above can be
        // read back out of it, so an OTP body cannot be retained or shown by any consumer.
    }

    @Test
    fun `a group summary is rejected even when its text looks financial`() {
        val summary = notification(
            title = "VietinBank",
            text = "3 new transactions of 50,000 VND",
            groupSummary = true,
        )

        assertEquals(
            NotificationIntakeDecision.Rejected(NotificationIntakeRejection.GroupSummary),
            decideNotificationIntake(ContentReadProbe(bankPackage, 1, summary), bankPackages),
        )
    }

    @Test
    fun `title text and big text are each supported`() {
        assertEquals(
            NotificationIntakeDecision.Permitted(
                PermittedNotificationContent(title = "Tin biến động số dư", text = null, bigText = null),
            ),
            decideNotificationIntake(
                ContentReadProbe(bankPackage, 1, notification(title = "Tin biến động số dư")),
                bankPackages,
            ),
        )
        assertEquals(
            NotificationIntakeDecision.Permitted(
                PermittedNotificationContent(title = null, text = "GD: -50,000 VND", bigText = null),
            ),
            decideNotificationIntake(
                ContentReadProbe(bankPackage, 2, notification(text = "GD: -50,000 VND")),
                bankPackages,
            ),
        )
        assertEquals(
            NotificationIntakeDecision.Permitted(
                PermittedNotificationContent(
                    title = "VietinBank",
                    text = "Tin biến động số dư",
                    bigText = "GD: -50,000 VND\nSDC: 500,000 VND",
                ),
            ),
            decideNotificationIntake(
                ContentReadProbe(
                    bankPackage,
                    3,
                    notification(
                        title = "VietinBank",
                        text = "Tin biến động số dư",
                        bigText = "GD: -50,000 VND\nSDC: 500,000 VND",
                    ),
                ),
                bankPackages,
            ),
        )
    }

    @Test
    fun `the listener reads no content before the allowlist snapshot is ready`() = runTest {
        store.addPackage(bankPackage)
        BankNotificationListener.updateAllowedPackages(emptySet())
        val dispatcher = StandardTestDispatcher(testScheduler)
        val listener = Robolectric.buildService(BankNotificationListener::class.java)
            .create()
            .get()
        listener.ioDispatcher = dispatcher

        try {
            // Bound, but the allowlist load is still in flight: the callback must not read
            // package or content, so a permitted-looking post is queued — never judged on
            // the transient empty snapshot — and processed once the snapshot is ready.
            listener.onListenerConnected()
            val beforeReady = ContentReadProbe(bankPackage, 1, notification(text = "GD: -50,000 VND"))
            listener.onNotificationPosted(beforeReady)
            assertEquals(0, beforeReady.contentReads())

            // The same listener, once the stored allowlist has loaded, reads the content
            // of that package — the queued post first, then a post that arrives ready —
            // so the zero above means "not ready", not "not wired".
            advanceUntilIdle()
            assertEquals(bankPackages, BankNotificationListener.allowedPackages)
            assertTrue(beforeReady.contentReads() > 0)
            val ready = ContentReadProbe(bankPackage, 2, notification(text = "GD: -50,000 VND"))
            listener.onNotificationPosted(ready)
            advanceUntilIdle()
            assertTrue(ready.contentReads() > 0)
        } finally {
            listener.ioDispatcher = Dispatchers.IO
            listener.onListenerDisconnected()
            advanceUntilIdle()
        }
    }

    @Test
    fun `the listener reads content only for an allowlisted package`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val listener = Robolectric.buildService(BankNotificationListener::class.java)
            .create()
            .get()
        listener.ioDispatcher = dispatcher

        try {
            listener.onListenerConnected()
            advanceUntilIdle()
            BankNotificationListener.updateAllowedPackages(bankPackages)

            val otherApp = ContentReadProbe("com.example.game", 1, notification(text = "Your OTP is 482913"))
            listener.onNotificationPosted(otherApp)
            assertEquals(0, otherApp.contentReads())

            val bankApp = ContentReadProbe(bankPackage, 2, notification(text = "GD: -50,000 VND"))
            listener.onNotificationPosted(bankApp)
            advanceUntilIdle()
            assertTrue(bankApp.contentReads() > 0)
        } finally {
            listener.ioDispatcher = Dispatchers.IO
            listener.onListenerDisconnected()
            advanceUntilIdle()
        }
    }

    @Test
    fun `the listener reads no content after access is revoked`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val listener = Robolectric.buildService(BankNotificationListener::class.java)
            .create()
            .get()
        listener.ioDispatcher = dispatcher

        try {
            listener.onListenerConnected()
            advanceUntilIdle()
            BankNotificationListener.updateAllowedPackages(bankPackages)

            // Revocation unbinds the service; the allowlist is still in memory, and no post
            // may be processed after it.
            listener.onListenerDisconnected()
            advanceUntilIdle()
            val probe = ContentReadProbe(bankPackage, 1, notification(text = "GD: -50,000 VND"))
            listener.onNotificationPosted(probe)
            assertEquals(0, probe.contentReads())
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
        /** The bank package the user listed on the phone (see the S3.1 evidence review). */
        const val bankPackage = "com.vietinbank.ipay"

        val bankPackages = setOf(bankPackage)

        /** The heading line of the user's VietinBank sample. */
        const val vietinBankTitle = "Tin biến động số dư"

        /**
         * The body of the user's VietinBank sample, verbatim: redacted account line,
         * readable transaction line, masked balance, placeholder memo.
         */
        val vietinBankText = listOf(
            "VietinBank: 16/10/2026 07:38",
            "TK: Redacted",
            "GD: -50,000 VND",
            "SDC: xxx,000 VND",
            "ND: ........",
        ).joinToString("\n")
    }
}
