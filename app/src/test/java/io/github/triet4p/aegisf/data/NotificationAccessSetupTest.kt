package io.github.triet4p.aegisf.data

import android.app.Notification
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.notification.BankNotificationListener
import io.github.triet4p.aegisf.notification.ListenerConnection
import io.github.triet4p.aegisf.notification.isIntakePermitted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The S3.1 setup boundaries: the allowlist grammar, its persistence, the intake gate,
 * and the listener's delayed-initialization ordering.
 *
 * Grammar and persistence tests pin behaviors that are easy to get wrong in a settings
 * flow: display names and blanks are refused; add/remove round-trips through the real
 * preferences file; the intake decision needs both the system grant and an exact
 * allowlist match, so revoking access or listing nothing stops everything.
 *
 * Ordering tests pin the S3.1-A3 threading contract on the real listener plus the real
 * store: `onListenerConnected` performs no storage I/O on the main-thread callback and
 * publishes `Connected` only after the allowlist load completes, so a notification
 * delivered at bind time is never judged against the transient empty snapshot —
 * fail-closed and undecided, never a false deny on empty state — and a disconnect in
 * flight cancels the load with no stale `Connected` published afterwards. At S3.1 every
 * callback is effect-free (no intake exists to lose); these tests assert that ordering
 * and readiness, not recognized-content state. No notification text or extras are
 * exercised by this test class — this task stores choices, not message bodies.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationAccessSetupTest {

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
    fun `a fresh install allows nothing`() {
        assertTrue(store.loadAllowedPackages().isEmpty())
        assertFalse(
            isIntakePermitted(
                accessGranted = true,
                allowedPackages = store.loadAllowedPackages(),
                packageName = "com.example.bank",
            ),
        )
    }

    @Test
    fun `a stored package needs access and an exact match`() {
        store.addPackage("com.example.bank")
        val allowed = store.loadAllowedPackages()

        assertTrue(
            isIntakePermitted(
                accessGranted = true,
                allowedPackages = allowed,
                packageName = "com.example.bank",
            ),
        )
        // Revocation stops intake regardless of the allowlist.
        assertFalse(
            isIntakePermitted(
                accessGranted = false,
                allowedPackages = allowed,
                packageName = "com.example.bank",
            ),
        )
        // A lookalike package is not permitted: matching is exact, not prefix-based.
        assertFalse(
            isIntakePermitted(
                accessGranted = true,
                allowedPackages = allowed,
                packageName = "com.example.bank.evil",
            ),
        )
        assertFalse(
            isIntakePermitted(
                accessGranted = true,
                allowedPackages = allowed,
                packageName = "COM.EXAMPLE.BANK",
            ),
        )
        // An unrelated app is never permitted by a grant alone.
        assertFalse(
            isIntakePermitted(
                accessGranted = true,
                allowedPackages = allowed,
                packageName = "com.example.game",
            ),
        )
    }

    @Test
    fun `adding accepts the application id shape and trims surrounding spaces`() {
        assertEquals("com.example.bank", store.addPackage("  com.example.bank  "))
        assertEquals(setOf("com.example.bank"), store.loadAllowedPackages())
    }

    @Test
    fun `adding the same package twice keeps it once`() {
        store.addPackage("com.example.bank")
        store.addPackage("com.example.bank")
        assertEquals(setOf("com.example.bank"), store.loadAllowedPackages())
    }

    @Test
    fun `display names blanks and single words are refused and store nothing`() {
        val invalidNames = listOf(
            "",
            "   ",
            "VietinBank",
            "My Bank App",
            "com.example.bank!",
            "com..bank",
            ".com.bank",
            "com.bank.",
            "bank",
            "1com.bank",
        )
        invalidNames.forEach { input ->
            assertThrows(LedgerValidationException::class.java) {
                store.addPackage(input)
            }
        }
        assertTrue(store.loadAllowedPackages().isEmpty())
        // A refused add after a valid one leaves the valid entry untouched.
        store.addPackage("com.example.bank")
        assertThrows(LedgerValidationException::class.java) {
            store.addPackage("Not A Package")
        }
        assertEquals(setOf("com.example.bank"), store.loadAllowedPackages())
    }

    @Test
    fun `removal narrows future intake without touching anything else`() {
        store.addPackage("com.example.first")
        store.addPackage("com.example.second")
        assertTrue(store.removePackage("com.example.first"))
        assertEquals(setOf("com.example.second"), store.loadAllowedPackages())
        // Removing what is not stored reports false and changes nothing.
        assertFalse(store.removePackage("com.example.first"))
        assertEquals(setOf("com.example.second"), store.loadAllowedPackages())
    }

    @Test
    fun `the allowlist survives a fresh store over the same preferences`() {
        store.addPackage("com.example.bank")
        val reopened = NotificationAccessStore(context)
        assertEquals(setOf("com.example.bank"), reopened.loadAllowedPackages())
    }

    @Test
    fun `listener bind keeps the allowlist empty until the load completes`() = runTest {
        store.addPackage("com.example.bank")
        BankNotificationListener.updateAllowedPackages(emptySet())
        val dispatcher = StandardTestDispatcher(testScheduler)
        var listener: BankNotificationListener? = null

        try {
            listener = Robolectric.buildService(BankNotificationListener::class.java)
                .create()
                .get()
            listener.ioDispatcher = dispatcher
            listener.onListenerConnected()

            // The callback returned without doing storage I/O on the calling thread:
            // the snapshot is still empty and readiness is not overstated.
            assertEquals(emptySet<String>(), BankNotificationListener.allowedPackages)
            assertTrue(
                BankNotificationListener.connection.value is ListenerConnection.Disconnected,
            )

            advanceUntilIdle()

            // Only after the load completes is the stored snapshot effective and
            // readiness published.
            assertEquals(setOf("com.example.bank"), BankNotificationListener.allowedPackages)
            assertTrue(
                BankNotificationListener.connection.value is ListenerConnection.Connected,
            )
        } finally {
            listener?.ioDispatcher = Dispatchers.IO
            listener?.onListenerDisconnected()
            advanceUntilIdle()
        }
    }

    @Test
    fun `a bind-time post makes no decision on the transient empty snapshot`() = runTest {
        store.addPackage("com.example.bank")
        BankNotificationListener.updateAllowedPackages(emptySet())
        val dispatcher = StandardTestDispatcher(testScheduler)
        var listener: BankNotificationListener? = null

        try {
            listener = Robolectric.buildService(BankNotificationListener::class.java)
                .create()
                .get()
            listener.ioDispatcher = dispatcher
            listener.onListenerConnected()

            // A permitted callback arrives BEFORE the load completes. The consumer-visible
            // state must not show a deny judged on empty state: the snapshot stays empty
            // (nothing evaluated against it) and the service stays undecided.
            listener.onNotificationPosted(postedNotification("com.example.bank"))
            assertEquals(emptySet<String>(), BankNotificationListener.allowedPackages)
            assertTrue(
                BankNotificationListener.connection.value is ListenerConnection.Disconnected,
            )

            // When the load completes, the ordering holds: the stored allowlist — not a
            // transient empty default — is what the steady state consults, with no false
            // deny recorded in between.
            advanceUntilIdle()
            assertEquals(setOf("com.example.bank"), BankNotificationListener.allowedPackages)
            assertTrue(
                BankNotificationListener.connection.value is ListenerConnection.Connected,
            )
            assertTrue(
                isIntakePermitted(
                    accessGranted = true,
                    allowedPackages = BankNotificationListener.allowedPackages,
                    packageName = "com.example.bank",
                ),
            )
            assertFalse(
                isIntakePermitted(
                    accessGranted = true,
                    allowedPackages = BankNotificationListener.allowedPackages,
                    packageName = "com.example.game",
                ),
            )
        } finally {
            listener?.ioDispatcher = Dispatchers.IO
            listener?.onListenerDisconnected()
            advanceUntilIdle()
        }
    }

    @Test
    fun `a disconnect in flight publishes no stale connected afterwards`() = runTest {
        store.addPackage("com.example.bank")
        BankNotificationListener.updateAllowedPackages(emptySet())
        val dispatcher = StandardTestDispatcher(testScheduler)
        var listener: BankNotificationListener? = null

        try {
            listener = Robolectric.buildService(BankNotificationListener::class.java)
                .create()
                .get()
            listener.ioDispatcher = dispatcher
            listener.onListenerConnected()

            // Revocation unbinds the service while the load is still in flight.
            listener.onListenerDisconnected()
            advanceTimeBy(10_000)
            advanceUntilIdle()

            // The cancelled load must not publish a stale Connected nor a snapshot
            // refresh after the unbind: the service stays fail-closed and empty.
            assertTrue(
                BankNotificationListener.connection.value is ListenerConnection.Disconnected,
            )
            assertEquals(emptySet<String>(), BankNotificationListener.allowedPackages)
        } finally {
            listener?.ioDispatcher = Dispatchers.IO
            listener?.onListenerDisconnected()
            advanceUntilIdle()
        }
    }

    /**
     * A real `StatusBarNotification` carrying routing metadata only.
     *
     * The notification body is an empty platform `Notification` — no title, text, or
     * extras are set or read — so this exercises the actual listener callback path
     * without any message content.
     */
    @Suppress("DEPRECATION")
    private fun postedNotification(packageName: String): StatusBarNotification {
        return StatusBarNotification(
            packageName,
            packageName,
            1,
            null,
            Process.myUid(),
            0,
            0,
            Notification(),
            Process.myUserHandle(),
            System.currentTimeMillis(),
        )
    }
}
