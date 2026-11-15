package io.github.triet4p.aegisf.ui

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.triet4p.aegisf.MainActivity
import io.github.triet4p.aegisf.notification.isNotificationAccessGranted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Android notification-listener grant on an isolated package only. */
@RunWith(AndroidJUnit4::class)
class S115NotificationAccessInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun notificationAccessChangesRefreshTheSettingsSurfaceOnReturn() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(ISOLATED_PACKAGE, context.packageName)
        val initiallyGranted = isNotificationAccessGranted(context)
        val component = "${context.packageName}/io.github.triet4p.aegisf.notification.BankNotificationListener"

        try {
            setAccess(context, component, granted = false)
            selectPrimaryDestination("Home")
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Bank notifications").performScrollTo().performClick()
            waitForText("Bank notifications")
            waitForText("Not granted")

            // Enter the system-owned screen through the product UI, change only this fixture
            // package's real notification-listener grant, then return through Android lifecycle.
            compose.onNodeWithText("Open notification access").performClick()
            runShell("cmd notification allow_listener $component")
            runShell("input keyevent KEYCODE_BACK")
            compose.waitUntil(timeoutMillis = 30_000) {
                isNotificationAccessGranted(context) &&
                    compose.onAllNodesWithText("Granted").fetchSemanticsNodes().isNotEmpty()
            }

            compose.onNodeWithText("Review notification access").performClick()
            runShell("cmd notification disallow_listener $component")
            runShell("input keyevent KEYCODE_BACK")
            compose.waitUntil(timeoutMillis = 30_000) {
                !isNotificationAccessGranted(context) &&
                    compose.onAllNodesWithText("Not granted").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText("Disconnected · access revoked")
                        .fetchSemanticsNodes().isNotEmpty()
            }
            assertFalse(isNotificationAccessGranted(context))
            assertTrue(
                compose.onAllNodesWithText("Disconnected · access revoked")
                    .fetchSemanticsNodes().isNotEmpty(),
            )
        } finally {
            runCatching { setAccess(context, component, initiallyGranted) }
        }
    }

    private fun selectPrimaryDestination(description: String) {
        compose.onNode(
            hasContentDescription(description, substring = true) and isSelectable(),
        ).performClick()
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun setAccess(context: Context, component: String, granted: Boolean) {
        val command = if (granted) "allow_listener" else "disallow_listener"
        runShell("cmd notification $command $component")
        compose.waitUntil(timeoutMillis = 30_000) {
            isNotificationAccessGranted(context) == granted
        }
    }

    private fun runShell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    private companion object {
        const val ISOLATED_PACKAGE = "io.github.triet4p.aegisf.s111evidence"
    }
}
