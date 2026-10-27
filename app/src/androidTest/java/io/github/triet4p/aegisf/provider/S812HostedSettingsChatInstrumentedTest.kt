package io.github.triet4p.aegisf.provider



import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.triet4p.aegisf.AegisApplication
import io.github.triet4p.aegisf.MainActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class S812HostedSettingsChatInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val store: HostedProviderSettingsStore
        get() = (compose.activity.application as AegisApplication)
            .container.hostedProviderSettingsStore

    @After
    fun removeSyntheticDeviceCredentials() {
        runCatching { store.deleteAll() }
    }

    @Test
    fun retainedOfflineManualReviewIntakeAndLayaRoutesRemainReachable() {
        selectPrimaryDestination("Dashboard")

        selectPrimaryDestination("Manual entry")
        waitForText("MANUAL")
        compose.onNodeWithText("Record a transaction").performClick()
        waitForText("NEW TRANSACTION")
        compose.onNodeWithText("Back").performClick()
        waitForText("MANUAL")

        selectPrimaryDestination("Review notifications")
        waitForText("REVIEW NOTIFICATIONS")
        selectPrimaryDestination("Dashboard")
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Bank notification settings").performScrollTo().performClick()
        waitForText("Bank notifications")
        compose.onNodeWithText("Back").performClick()

        compose.onNodeWithText("Model setup").performScrollTo().performClick()
        waitForText("Local model status")
    }

    private fun selectPrimaryDestination(description: String) {
        compose.onNodeWithContentDescription(description, substring = true).performClick()
    }

    private fun waitForText(
        text: String,
        substring: Boolean = false,
        timeoutMillis: Long = 180_000,
    ) {
        compose.waitUntil(timeoutMillis = timeoutMillis) {
            compose.onAllNodesWithText(text, substring = substring)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
