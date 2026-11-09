package io.github.triet4p.aegisf.provider



import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.triet4p.aegisf.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class S812HostedSettingsChatInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun retainedOfflineManualReviewAndIntakeRoutesRemainReachable() {
        // The S10.2 shell: Home is a launcher, the record form and Settings are focused
        // children, and the Review inbox is its own root destination.
        selectPrimaryDestination("Home")

        compose.onNodeWithText("Manual record").performClick()
        waitForText("NEW TRANSACTION")
        compose.onNodeWithText("Back").performClick()
        waitForText("Manual record")

        selectPrimaryDestination("Review")
        waitForText("REVIEW NOTIFICATIONS")
        selectPrimaryDestination("Home")
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Bank notification settings").performScrollTo().performClick()
        waitForText("Bank notifications")
        compose.onNodeWithText("Back").performClick()

    }

    private fun selectPrimaryDestination(description: String) {
        // The Homes launchers also carry the destination name ("Open Review"), so a tab is
        // selected by its tab role rather than by a substring that also matches a launcher.
        compose.onNode(
            hasContentDescription(description, substring = true) and isSelectable(),
        ).performClick()
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
