package io.github.triet4p.aegisf.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.triet4p.aegisf.AegisApplication
import io.github.triet4p.aegisf.MainActivity
import io.github.triet4p.aegisf.data.AegisLanguage
import io.github.triet4p.aegisf.data.AegisThemeMode
import io.github.triet4p.aegisf.ui.navigation.AegisAppShell
import io.github.triet4p.aegisf.ui.theme.AegisTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * The S10.2 shell's layout contract at the largest supported text scale, rendered on the phone.
 *
 * The system font-scale setting is the owner's and is not changed by this test. Instead the real
 * shell is composed with a 1.5× `fontScale` in the same process and against the same app services,
 * and the test proves the labelled destinations are still on screen and every root target keeps
 * its 48dp-or-larger touch area. The screenshot written to the app's external files directory is
 * the rendered evidence for the matching phone check.
 */
@RunWith(AndroidJUnit4::class)
class S102ShellLayoutInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun shellLabelsAndTargetsHoldAtOneAndAHalfTextScale() {
        val activity = compose.activity
        val app = activity.application as AegisApplication
        activity.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density, fontScale = 1.5f),
            ) {
                AegisTheme(
                    themeMode = AegisThemeMode.SYSTEM,
                    language = AegisLanguage.ENGLISH,
                ) {
                    AegisAppShell(
                        queryService = app.container.workflowQueryService,
                        commandService = app.container.workflowCommandService,
                        notificationAccessStore = app.container.notificationAccessStore,
                        hostedProviderSettingsStore = app.container.hostedProviderSettingsStore,
                        createHostedWorkflowHost = app.container::createHostedWorkflowHost,
                        agentRunHistory = app.container.agentRunHistory,
                        language = AegisLanguage.ENGLISH,
                        onLanguageChange = {},
                        themeMode = AegisThemeMode.SYSTEM,
                        onThemeModeChange = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        // Every labelled root destination and every Home launcher is still composed and placed.
        listOf("Home", "Ledger", "Notes", "AI Chat", "Review").forEach { label ->
            val nodes = compose.onAllNodesWithText(label, substring = true).fetchSemanticsNodes()
            assertTrue("root label '$label' is missing at 1.5x", nodes.isNotEmpty())
        }
        listOf(
            "Ledger", "Analytics", "Notes", "AI Chat",
            "Review", "Manual record", "Categories & rules", "Settings",
        ).forEach { label ->
            val nodes = compose.onAllNodesWithText(label, substring = true).fetchSemanticsNodes()
            assertTrue("launcher '$label' is missing at 1.5x", nodes.isNotEmpty())
        }

        // 48dp minimum touch targets, measured on the phone's own density.
        val minimumPx = 48 * activity.resources.displayMetrics.density
        listOf("Open Ledger", "Open Settings", "Hide amounts").forEach { description ->
            val node = compose.onAllNodesWithContentDescription(description, substring = true)
                .fetchSemanticsNodes()
                .firstOrNull()
            assertTrue("control '$description' is missing at 1.5x", node != null)
            val bounds = node!!.boundsInRoot
            assertTrue(
                "'$description' is smaller than 48dp: ${bounds.width}x${bounds.height}px",
                bounds.width >= minimumPx && bounds.height >= minimumPx,
            )
        }

        val root = compose.onRoot().captureToImage().asAndroidBitmap()
        writeScreenshot("shell-1.5x", root)
    }

    @Test
    fun recordFormAndDestinationSurviveActivityRecreation() {
        compose.waitForIdle()

        compose.onNodeWithText("Manual record", substring = true).performClick()
        compose.waitForIdle()
        val fields = compose.onAllNodes(hasSetTextAction())
        assertTrue(fields.fetchSemanticsNodes().isNotEmpty())
        fields[0].performTextInput("777")
        compose.waitForIdle()

        // A real activity recreation on the phone: the route and the unsaved amount must come
        // back rather than a root screen with an empty form.
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertTrue(
            "the record form did not survive recreation",
            compose.onAllNodesWithText("New transaction", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        val restoredAmount = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
            .firstOrNull()
            ?.config
            ?.getOrNull(SemanticsProperties.EditableText)
            ?.text
            .orEmpty()
        assertEquals("777", restoredAmount)
        writeScreenshot("form-after-recreation", compose.onRoot().captureToImage().asAndroidBitmap())

        // Leave the probe form without writing anything.
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("Discard").performClick()
        compose.waitForIdle()
        assertTrue(
            "the discarded form did not return to Home",
            compose.onAllNodesWithText("What would you like to do?", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )

        // A root destination survives recreation too, not only a form route.
        compose.onNodeWithContentDescription("Notes").performClick()
        compose.waitForIdle()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertTrue(
            "the Notes destination did not survive recreation",
            compose.onAllNodesWithText("NEW QUICK CAPTURE", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        writeScreenshot("notes-after-recreation", compose.onRoot().captureToImage().asAndroidBitmap())
    }

    private fun writeScreenshot(name: String, bitmap: Bitmap) {
        val directory = InstrumentationRegistry.getInstrumentation()
            .targetContext.getExternalFilesDir("s102")
            ?.apply { mkdirs() }
            ?: return
        FileOutputStream(File(directory, "$name.png")).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
