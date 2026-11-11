package io.github.triet4p.aegisf.ui

import android.content.Context
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.triet4p.aegisf.MainActivity
import io.github.triet4p.aegisf.agent.AgentRunHistory
import io.github.triet4p.aegisf.agent.WorkflowIntent
import io.github.triet4p.aegisf.agent.WorkflowRunRequest
import io.github.triet4p.aegisf.data.AegisLanguage
import io.github.triet4p.aegisf.data.AegisThemeMode
import io.github.triet4p.aegisf.data.CategoryQuery
import io.github.triet4p.aegisf.data.CategoryState
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.LedgerDateRange
import io.github.triet4p.aegisf.data.LedgerRecordQuery
import io.github.triet4p.aegisf.data.NotificationAccessStore
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.SaveManualTransactionRequest
import io.github.triet4p.aegisf.data.TransactionType
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowChange
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.notification.ListenerRebindOutcome
import io.github.triet4p.aegisf.notification.accessGrantedForTest
import io.github.triet4p.aegisf.notification.appInfoSettingsIntent
import io.github.triet4p.aegisf.notification.notificationAccessSettingsIntent
import io.github.triet4p.aegisf.notification.rebinderForTest
import io.github.triet4p.aegisf.notification.requestListenerRebind
import io.github.triet4p.aegisf.privacy.MaskedPromptBoundary
import io.github.triet4p.aegisf.privacy.SensitiveVault
import io.github.triet4p.aegisf.provider.HostedHttpRequest
import io.github.triet4p.aegisf.provider.HostedHttpResponse
import io.github.triet4p.aegisf.provider.HostedProviderSettingsStore
import io.github.triet4p.aegisf.provider.HttpTransport
import io.github.triet4p.aegisf.ui.navigation.SettingsHubScreen
import io.github.triet4p.aegisf.ui.theme.AegisTheme
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S10.8 actual Compose category/rule/settings evidence on isolated fixtures.
 *
 * Each test renders production screens through the real theme over an in-memory Room database or
 * the app's encrypted provider store. Assertions read the stored state as well as the visible
 * words. The opt-in package guard plus the cleared, isolated preferences keep this off the owner's
 * installed package and data.
 */
@RunWith(AndroidJUnit4::class)
class S108SettingsInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var database: LedgerDatabase
    private lateinit var commands: WorkflowCommandService
    private lateinit var queries: WorkflowQueryService
    private lateinit var providerStore: HostedProviderSettingsStore
    private lateinit var accessStore: NotificationAccessStore
    private var databaseOpen = false
    private var navigatedUp = false
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        accessGrantedForTest = null
        if (::providerStore.isInitialized) runCatching { providerStore.deleteAll() }
        context.getSharedPreferences(NotificationAccessStore.FILE_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        if (databaseOpen) {
            database.close()
            databaseOpen = false
        }
    }

    // --- Categories -------------------------------------------------------

    @Test
    fun categoriesAddRenameArchiveAndDeleteWithReferenceSafety() {
        prepare()
        assertEquals("io.github.triet4p.aegisf.s108evidence", context.packageName)
        val food = insertCategory("Food")
        val transport = insertCategory("Transport")
        val recordId = runBlocking { seedRecord(45_000, food, "Synthetic lunch") }
        assertEquals(45_000L to 0L, totals())

        showCategoryRules()
        awaitTag("category-add")
        awaitText("Food")
        screenshot("s108-01-categories-en")

        // A collision is refused with the typed name intact; nothing is stored.
        compose.onNodeWithTag("category-add").performClick()
        awaitTag("category-name")
        typeField("category-name", "Food")
        compose.onNodeWithTag("category-save").performClick()
        awaitText("Another active category already uses this name.")
        assertEquals(2, runBlocking { queries.categories(ALL_CATEGORIES).items.size })

        typeField("category-name", "Pets")
        compose.onNodeWithTag("category-save").performClick()
        awaitText("Pets")
        val pets = runBlocking { database.categoryDao().findActiveIdByName("Pets") }
            ?: error("the added category was not stored")

        compose.onNodeWithTag("category-edit-$pets").performScrollTo().performClick()
        awaitTag("category-name")
        typeField("category-name", "Pets & Vet")
        compose.onNodeWithTag("category-save").performClick()
        awaitText("Pets & Vet")

        scrollToRow("category-row-$pets")
        compose.onNodeWithTag("category-edit-$pets").performClick()
        awaitTag("category-archive")
        compose.onNodeWithTag("category-archive").performClick()
        awaitText("Archive this category?")
        screenshot("s108-02-archive-confirm-en")
        compose.onNodeWithText("Archive", useUnmergedTree = true).performClick()
        awaitText("Category archived. Past records stay attached.")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Category archived. Past records stay attached.")
                .fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("category-filter-archived").performClick()
        awaitText("Pets & Vet")
        assertTrue(
            "an archived row must not repeat the full archived state twice",
            compose.onAllNodesWithText("Archived · history kept").fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            "the archived row must keep a textual badge and a separate caption",
            compose.onAllNodesWithText("Archived").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("History kept").fetchSemanticsNodes().isNotEmpty(),
        )
        screenshot("s108-03-categories-archived-en")

        // A referenced category is not deletable: the dialog names the exact references and
        // offers Archive instead, and no destructive button is present.
        compose.onNodeWithTag("category-filter-all").performClick()
        awaitTag("category-filter-all")
        scrollToRow("category-row-$food")
        compose.onNodeWithTag("category-delete-$food").performClick()
        awaitText("permanent deletion is blocked", substring = true)
        assertTrue(
            "a referenced category must not offer Delete permanently",
            compose.onAllNodesWithText("Delete permanently").fetchSemanticsNodes().isEmpty(),
        )
        screenshot("s108-04-delete-impact-en")
        val blockedStored = runBlocking { queries.findLedgerRecord(recordId) }
            ?: error("record lost while delete was blocked")
        assertEquals(45_000L, blockedStored.amountVnd)
        assertEquals(food, blockedStored.categoryId)
        assertEquals(45_000L to 0L, totals())
        assertEquals(food, runBlocking { queries.findCategory(food) }?.id)

        // Archive is the supported path for a referenced category; history stays attached.
        compose.onNodeWithText("Archive instead").performClick()
        awaitText("Category archived. Past records stay attached.")
        val archivedStored = runBlocking { queries.findLedgerRecord(recordId) }
            ?: error("record lost after archive")
        assertEquals(food, archivedStored.categoryId)
        assertEquals(45_000L to 0L, totals())
        assertTrue(runBlocking { queries.findCategory(food) }!!.archived)

        // A category with no references is still deletable through the same UI.
        scrollToRow("category-row-$transport")
        compose.onNodeWithTag("category-delete-$transport").performClick()
        awaitText("Nothing stored uses this category", substring = true)
        compose.onNodeWithText("Delete permanently").performClick()
        awaitTrue {
            compose.onAllNodesWithTag("category-row-$transport").fetchSemanticsNodes().isEmpty()
        }
        assertNull(runBlocking { queries.findCategory(transport) })
        assertEquals(45_000L to 0L, totals())
    }

    @Test
    fun categoryDeleteRechecksReferencesInsideTheTransaction() {
        prepare()
        val temporary = insertCategory("Temporary")
        showCategoryRules()
        awaitTag("category-add")
        scrollToRow("category-row-$temporary")
        compose.onNodeWithTag("category-delete-$temporary").performClick()
        awaitText("Nothing stored uses this category", substring = true)
        screenshot("s108-04b-delete-unreferenced-en")

        // A reference appears after the dialog read its impact. The delete transaction must
        // re-check and refuse; the category and the new record stay exactly as stored.
        val recordId = runBlocking { seedRecord(23_000, temporary, "Synthetic race") }
        compose.onNodeWithText("Delete permanently").performClick()
        awaitText("The category is still in use", substring = true)
        awaitText("permanent deletion is blocked", substring = true)
        assertTrue(
            "the stale dialog must remove the destructive action",
            compose.onAllNodesWithText("Delete permanently").fetchSemanticsNodes().isEmpty(),
        )
        assertNotNull(runBlocking { queries.findCategory(temporary) })
        assertEquals(
            temporary,
            runBlocking { queries.findLedgerRecord(recordId) }?.categoryId,
        )
        assertEquals(23_000L to 0L, totals())
        screenshot("s108-04c-delete-race-blocked-en")
    }

    @Test
    fun rulesAddChangeAndRemoveExactDescriptionRules() {
        prepare()
        val food = insertCategory("Food")
        val transport = insertCategory("Transport")
        runBlocking {
            rememberRule("Circle K", food)
        }
        showCategoryRules()
        awaitTag("category-tab-rules")
        compose.onNodeWithTag("category-tab-rules").performClick()
        awaitTag("rule-add")
        awaitText("circle k", substring = true)
        screenshot("s108-05-rules-en")

        compose.onNodeWithTag("rule-add").performClick()
        awaitTag("rule-sheet")
        compose.onNodeWithTag("rule-save").performClick()
        awaitText("Enter the exact description this rule matches.")
        typeField("rule-description", "Grab ride")
        awaitTag("rule-category-$transport")
        compose.onNodeWithTag("rule-category-$transport").performScrollTo().performClick()
        compose.onNodeWithTag("rule-save").performClick()
        awaitTag("rule-row-grab ride")
        assertEquals(transport, runBlocking { ruleCategory("grab ride") })

        // A duplicate exact description is refused without replacing the standing rule.
        compose.onNodeWithTag("rule-add").performClick()
        awaitTag("rule-sheet")
        typeField("rule-description", "Circle K")
        compose.onNodeWithTag("rule-category-$transport").performScrollTo().performClick()
        compose.onNodeWithTag("rule-save").performClick()
        awaitText("A saved rule already applies to this exact description.")
        compose.onNodeWithText("Cancel").performClick()

        compose.onNodeWithTag("rule-edit-grab ride").performScrollTo().performClick()
        awaitTag("rule-sheet")
        compose.onNodeWithTag("rule-category-$food").performScrollTo().performClick()
        compose.onNodeWithTag("rule-save").performClick()
        awaitTrue { runBlocking { ruleCategory("grab ride") } == food }

        compose.onNodeWithTag("rule-remove-grab ride").performScrollTo().performClick()
        awaitText("Remove this rule?")
        compose.onNodeWithText("Remove rule").performClick()
        awaitTrue {
            compose.onAllNodesWithTag("rule-row-grab ride").fetchSemanticsNodes().isEmpty()
        }
        assertNull(runBlocking { ruleCategory("grab ride") })
    }

    // --- Settings hub and children ---------------------------------------

    @Test
    fun categoriesAndRulesShowReadErrorWhenStorageCannotBeRead() {
        prepare()
        database.close()
        databaseOpen = false

        showCategoryRules()
        awaitText("These lists could not be read")
        awaitText("Nothing was changed. The stored categories and rules are still here.")
        screenshot("s108-16-read-error-en")
        compose.onNodeWithTag("category-tab-rules").performClick()
        awaitText("These lists could not be read")
    }

    @Test
    fun ruleEditRefusesACategoryArchivedWhileTheSheetIsOpen() {
        prepare()
        val food = insertCategory("Food")
        runBlocking { rememberRule("gym", food) }
        showCategoryRules()
        awaitTag("category-tab-rules")
        compose.onNodeWithTag("category-tab-rules").performClick()
        awaitTag("rule-edit-gym")
        compose.onNodeWithTag("rule-edit-gym").performScrollTo().performClick()
        awaitTag("rule-sheet")

        runBlocking {
            val archived = commands.archiveCategory(
                WorkflowCallerScope.User,
                io.github.triet4p.aegisf.data.ArchiveCategoryRequest(
                    idempotencyKey = "s108-stale-archive-${UUID.randomUUID()}",
                    categoryId = food,
                ),
            )
            check(archived is WorkflowCommandOutcome.Applied) { "archive refused: $archived" }
        }
        compose.onNodeWithTag("rule-save").performClick()
        awaitText("That category is no longer available.", substring = true)
        assertEquals(food, runBlocking { ruleCategory("gym") })
        assertTrue(runBlocking { queries.findCategory(food) }!!.archived)
        screenshot("s108-17-rule-stale-category-en")
    }

    @Test
    fun settingsHubIsANavigationHubAndEachRowOpensItsChild() {
        prepare()
        var bank = false
        var provider = false
        var skills = false
        var privacy = false
        var language: AegisLanguage? = null
        var theme: AegisThemeMode? = null
        showSettingsHub(
            language = AegisLanguage.ENGLISH,
            onLanguageChange = { language = it },
            themeMode = AegisThemeMode.SYSTEM,
            onThemeModeChange = { theme = it },
            onBank = { bank = true },
            onProvider = { provider = true },
            onSkills = { skills = true },
            onPrivacy = { privacy = true },
        )
        awaitText("Settings")
        screenshot("s108-06-settings-hub-en")

        // The hub does not stack provider forms, credentials or destructive controls.
        assertTrue(
            compose.onAllNodesWithText("Credential").fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            compose.onAllNodesWithText("HTTPS endpoint").fetchSemanticsNodes().isEmpty(),
        )

        compose.onNodeWithText("Bank notifications").performScrollTo().performClick()
        assertTrue(bank)
        compose.onNodeWithText("Provider & model").performScrollTo().performClick()
        assertTrue(provider)
        compose.onNodeWithText("Skills & history").performScrollTo().performClick()
        assertTrue(skills)
        compose.onNodeWithText("Privacy & retention").performScrollTo().performClick()
        assertTrue(privacy)

        compose.onNodeWithText("Language").performScrollTo().performClick()
        awaitText("Choose language")
        compose.onAllNodesWithText("Tiếng Việt").onLast().performClick()
        assertEquals(AegisLanguage.VIETNAMESE, language)
        awaitTrue {
            compose.onAllNodesWithText("Choose language").fetchSemanticsNodes().isEmpty()
        }

        compose.onNodeWithText("Appearance").performScrollTo().performClick()
        awaitText("Follow system")
        compose.onNodeWithText("Dark").performClick()
        assertEquals(AegisThemeMode.DARK, theme)
    }

    @Test
    fun bankSettingsShowsDiagnosticsAndManagesTheAllowlist() {
        prepare()
        accessGrantedForTest = true
        accessStore.addPackage("com.example.already")

        showBankSettings()
        awaitText("Notification access")
        awaitText("Granted")
        awaitText("1 app")
        screenshot("s108-07-bank-settings-en")
        compose.onNodeWithTag("bank-add").performScrollTo()
        screenshot("s108-07b-bank-allowlist-en")

        compose.onNodeWithTag("bank-add").performClick()
        awaitTag("bank-package")
        typeField("bank-package", "MB Bank", scroll = false)
        compose.onNodeWithText("Save").performClick()
        awaitText("That package name cannot be stored.")

        typeField("bank-package", "com.mb.mobile", scroll = false)
        compose.onNodeWithText("Save").performClick()
        awaitTrue {
            accessStore.loadAllowedPackages().contains("com.mb.mobile")
        }
        compose.onNodeWithTag("bank-remove-com.mb.mobile").performScrollTo().performClick()
        awaitText("Remove this bank app?")
        compose.onNodeWithTag("bank-remove-confirm").performClick()
        awaitTrue {
            !accessStore.loadAllowedPackages().contains("com.mb.mobile")
        }
        assertTrue(accessStore.loadAllowedPackages().contains("com.example.already"))

        compose.onNodeWithTag("bank-refresh").performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun providerProfileIsTestedSavedActivatedAndDeletedWithoutExposingTheCredential() {
        prepare()
        providerStore.deleteAll()
        val transport = RecordingTransport(openAiChatBody("deepseek-v4.1-flash"))
        showProvider(transport = transport)
        awaitTag("provider-save")

        compose.onNodeWithTag("provider-protocol").performScrollTo().performClick()
        compose.onAllNodesWithText("OpenAI-compatible chat completions").onLast().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("provider-preset").performScrollTo().performClick()
        compose.onAllNodesWithText("OpenCode Go · DeepSeek V4.1 Flash").onLast().performClick()
        typeField("provider-name", "Synthetic Provider")
        typeField("provider-credential", SECRET)

        compose.onNodeWithTag("provider-save").performClick()
        awaitText("Connection verified. Provider settings saved on this device.")
        assertEquals(1, transport.calls)
        hideKeyboard()
        compose.onNodeWithTag("provider-status").performScrollTo()
        screenshot("s108-08-provider-saved-en")
        val stored = providerStore.load().single()
        assertEquals("Synthetic Provider", stored.name)
        assertEquals("deepseek-v4.1-flash", stored.model)
        assertEquals(SECRET, stored.credential)
        assertTrue(
            compose.onAllNodesWithText(SECRET).fetchSemanticsNodes().isEmpty(),
        )
        compose.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasTestTag("provider-profile-${stored.id}"))
        screenshot("s108-08b-provider-profile-en")

        compose.onNodeWithTag("provider-use-${stored.id}").performScrollTo().performClick()
        awaitTrue { providerStore.active()?.id == stored.id }

        compose.onNodeWithTag("provider-delete-${stored.id}").performScrollTo().performClick()
        awaitText("Delete this provider profile?")
        compose.onNodeWithTag("provider-delete-confirm").performClick()
        awaitTrue { providerStore.load().isEmpty() }
    }

    @Test
    fun providerOfflineAndInvalidOutputKeepEditsAndSaveNothing() {
        prepare()
        providerStore.deleteAll()
        val transport = ThrowingTransport()
        showProvider(transport = transport)
        awaitTag("provider-save")

        compose.onNodeWithTag("provider-protocol").performScrollTo().performClick()
        compose.onAllNodesWithText("OpenAI-compatible chat completions").onLast().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("provider-preset").performScrollTo().performClick()
        compose.onAllNodesWithText("OpenCode Go · DeepSeek V4.1 Flash").onLast().performClick()
        typeField("provider-name", "Offline Provider")
        typeField("provider-credential", SECRET)
        typeField("provider-output", "10")
        compose.onNodeWithTag("provider-save").performClick()
        awaitText("An explicit output limit must be at least", substring = true)
        assertEquals(0, transport.calls)

        typeField("provider-output", "")
        compose.onNodeWithTag("provider-save").performClick()
        awaitText("Provider is offline or unreachable", substring = true)
        assertEquals(1, transport.calls)
        assertTrue(providerStore.load().isEmpty())
        assertTrue(
            compose.onAllNodesWithText("Offline Provider").fetchSemanticsNodes().isNotEmpty(),
        )
        hideKeyboard()
        compose.onNodeWithTag("provider-status").performScrollTo()
        screenshot("s108-09-provider-offline-en")

        navigatedUp = false
        compose.onNodeWithContentDescription("Back").performClick()
        awaitText("Discard changes?")
        compose.onNodeWithText("Keep editing").performClick()
        assertFalse(navigatedUp)
        compose.onNodeWithContentDescription("Back").performClick()
        awaitText("Discard changes?")
        compose.onNodeWithText("Discard").performClick()
        awaitTrue { navigatedUp }
        assertTrue(providerStore.load().isEmpty())
    }

    @Test
    fun providerRefusesToSaveWithoutAUsableNetworkPath() {
        prepare()
        providerStore.deleteAll()
        val transport = RecordingTransport(openAiChatBody("deepseek-v4.1-flash"))
        showProvider(networkAvailable = false, transport = transport)
        awaitTag("provider-save")

        compose.onNodeWithTag("provider-save").performClick()
        awaitText("No network connection", substring = true)
        assertEquals(0, transport.calls)
        assertTrue(providerStore.load().isEmpty())
        compose.onNodeWithTag("provider-status").performScrollTo()
        screenshot("s108-15-provider-no-network-en")
    }

    @Test
    fun systemDestinationsResolveAndReconnectRefusesWithoutAccess() {
        val accessIntent = notificationAccessSettingsIntent()
        val appInfoIntent = appInfoSettingsIntent(context)
        assertNotNull(
            "notification-access settings destination is not resolvable",
            context.packageManager.resolveActivity(accessIntent, 0),
        )
        assertNotNull(
            "app-info settings destination is not resolvable",
            context.packageManager.resolveActivity(appInfoIntent, 0),
        )

        var rebinds = 0
        val previous = rebinderForTest
        rebinderForTest = { rebinds += 1 }
        try {
            assertEquals(
                ListenerRebindOutcome.RefusedNoAccess,
                requestListenerRebind(context, accessGranted = false),
            )
            assertEquals("a refused rebind must not touch the system", 0, rebinds)
        } finally {
            rebinderForTest = previous
        }
    }

    @Test
    fun skillsAndHistoryShowBoundedWorkflowsAndStoredConversation() {
        prepare()
        val history = AgentRunHistory(database)
        runBlocking {
            val scope = WorkflowCallerScope.AgentSession(
                scopeId = "s108-history",
                captureIds = emptySet(),
                draftIds = emptySet(),
            )
            history.admitRun(
                scope = scope,
                request = WorkflowRunRequest(
                    intents = setOf(WorkflowIntent.INTERPRET_EVENTS),
                    userRequest = "Synthetic history question",
                ),
                boundary = MaskedPromptBoundary(SensitiveVault("s108-history")),
                maxOutputTokens = null,
            )
        }
        var chatOpened = false
        showSkillsHistory(history, onOpenChat = { chatOpened = true })
        awaitText("Read note details")
        awaitText("Find bank drafts")
        awaitText("Synthetic history question")
        screenshot("s108-10-skills-history-en")
        compose.onNodeWithTag("skills-open-chat").performScrollTo().performClick()
        assertTrue(chatOpened)
    }

    @Test
    fun privacyDisclosureNamesLimitsWithoutFakeBackupControls() {
        prepare()
        showPrivacy()
        awaitText("ON THIS DEVICE")
        awaitText("Android backup: disabled")
        awaitText("No backup or restore control is provided in this release.")
        assertTrue(
            compose.onAllNodesWithText("Back up now").fetchSemanticsNodes().isEmpty(),
        )
        screenshot("s108-11-privacy-en")
    }

    @Test
    fun vietnameseLargeTextKeepsTasksReachableAndLocalized() {
        prepare()
        insertCategory("Food")
        showCategoryRules(language = AegisLanguage.VIETNAMESE, fontScale = 1.5f)
        awaitTag("category-add")
        awaitText("Danh mục & quy tắc")
        awaitText("Thêm danh mục")
        awaitText("Đang dùng")
        compose.onNodeWithTag("category-add").performClick()
        awaitTag("category-name")
        typeField("category-name", "Thú cưng")
        compose.onNodeWithTag("category-save").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Đã thêm danh mục.").fetchSemanticsNodes().isEmpty()
        }
        val petsId = runBlocking {
            var found: Long? = null
            repeat(40) {
                found = database.categoryDao().findActiveIdByName("Thú cưng")
                if (found != null) return@runBlocking found
                Thread.sleep(250)
            }
            found
        } ?: error("the Vietnamese category was not stored")
        scrollToRow("category-row-$petsId")
        awaitText("Thú cưng")
        screenshot("s108-12-categories-vi-large")

        showProvider(
            language = AegisLanguage.VIETNAMESE,
            fontScale = 1.5f,
            transport = RecordingTransport(openAiChatBody("deepseek-v4.1-flash")),
        )
        awaitTag("provider-save")
        awaitText("Nhà cung cấp & mô hình")
        assertTrue(
            compose.onAllNodesWithText("Provider & model").fetchSemanticsNodes().isEmpty(),
        )
        typeField("provider-name", "Nhà cung cấp")
        screenshot("s108-13-provider-vi-ime")
        hideKeyboard()
        compose.onNodeWithTag("provider-save").assertIsDisplayed()
        compose.onNodeWithTag("provider-protocol").performScrollTo().performClick()
        awaitText("Giao thức")
        screenshot("s108-14-provider-vi-large")
    }

    // --- harness ---------------------------------------------------------

    private fun prepare() {
        database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        databaseOpen = true
        commands = WorkflowCommandService(database)
        queries = WorkflowQueryService(database)
        providerStore = HostedProviderSettingsStore(context)
        providerStore.deleteAll()
        accessStore = NotificationAccessStore(context)
        context.getSharedPreferences(NotificationAccessStore.FILE_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        accessGrantedForTest = null
        navigatedUp = false
    }

    private fun showCategoryRules(
        language: AegisLanguage = AegisLanguage.ENGLISH,
        fontScale: Float = 1f,
    ) {
        setContent(language, fontScale) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            ) {
                CategoryRulesScreen(
                    commandService = commands,
                    queryService = queries,
                    onNavigateUp = { navigatedUp = true },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun showSettingsHub(
        language: AegisLanguage,
        onLanguageChange: (AegisLanguage) -> Unit,
        themeMode: AegisThemeMode,
        onThemeModeChange: (AegisThemeMode) -> Unit,
        onBank: () -> Unit,
        onProvider: () -> Unit,
        onSkills: () -> Unit,
        onPrivacy: () -> Unit,
    ) {
        setContent(language, fontScale = 1f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            ) {
                SettingsHubScreen(
                    language = language,
                    onLanguageChange = onLanguageChange,
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    notificationAccessStore = accessStore,
                    hostedProviderSettingsStore = providerStore,
                    onOpenBankNotifications = onBank,
                    onOpenHostedProvider = onProvider,
                    onOpenSkillsHistory = onSkills,
                    onOpenPrivacy = onPrivacy,
                    onNavigateUp = { navigatedUp = true },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun showBankSettings(
        language: AegisLanguage = AegisLanguage.ENGLISH,
        fontScale: Float = 1f,
    ) {
        setContent(language, fontScale) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            ) {
                BankNotificationSettingsScreen(
                    accessStore = accessStore,
                    queryService = queries,
                    onNavigateUp = { navigatedUp = true },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun showProvider(
        language: AegisLanguage = AegisLanguage.ENGLISH,
        fontScale: Float = 1f,
        networkAvailable: Boolean = true,
        transport: HttpTransport,
    ) {
        setContent(language, fontScale) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            ) {
                HostedProviderSettingsScreen(
                    store = providerStore,
                    onNavigateUp = { navigatedUp = true },
                    networkAvailable = networkAvailable,
                    transport = transport,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun showSkillsHistory(
        history: AgentRunHistory,
        onOpenChat: () -> Unit,
    ) {
        setContent(AegisLanguage.ENGLISH, fontScale = 1f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            ) {
                SettingsSkillsHistoryScreen(
                    agentRunHistory = history,
                    onOpenChat = onOpenChat,
                    onNavigateUp = { navigatedUp = true },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun showPrivacy() {
        setContent(AegisLanguage.ENGLISH, fontScale = 1f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            ) {
                SettingsPrivacyScreen(onNavigateUp = { navigatedUp = true })
            }
        }
        compose.waitForIdle()
    }

    private fun setContent(
        language: AegisLanguage,
        fontScale: Float,
        content: @Composable () -> Unit,
    ) {
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                AegisTheme(themeMode = AegisThemeMode.LIGHT, language = language) {
                    val base = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(base.density, fontScale),
                    ) {
                        content()
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Scrolls the category LazyColumn to one row, including a row it has not composed yet. */
    private fun scrollToRow(tag: String) {
        compose.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitText(text: String, substring: Boolean = false) {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(text, substring = substring)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitTrue(condition: () -> Boolean) {
        compose.waitUntil(20_000) { condition() }
    }

    private fun typeField(tag: String, value: String, scroll: Boolean = true) {
        val field = compose.onNodeWithTag(tag)
        if (scroll) field.performScrollTo()
        field.performClick()
        field.performTextClearance()
        field.performTextInput(value)
        compose.waitForIdle()
    }

    private fun insertCategory(name: String): Long = runBlocking {
        database.categoryDao().insert(
            io.github.triet4p.aegisf.data.CategoryEntity(
                name = name,
                createdAtEpochMillis = System.currentTimeMillis(),
                updatedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun seedRecord(amountVnd: Long, categoryId: Long, description: String): Long {
        val outcome = commands.saveManualTransaction(
            WorkflowCallerScope.User,
            SaveManualTransactionRequest(
                idempotencyKey = "s108-seed-${UUID.randomUUID()}",
                type = TransactionType.EXPENSE,
                amountVnd = amountVnd,
                occurredAtEpochMillis = System.currentTimeMillis(),
                description = description,
                categoryId = categoryId,
            ),
        )
        check(outcome is WorkflowCommandOutcome.Applied) { "seed refused: $outcome" }
        return ((outcome.change) as WorkflowChange.TransactionSaved).transactionId
    }

    private suspend fun rememberRule(description: String, categoryId: Long) {
        val outcome = commands.rememberCategoryRule(
            WorkflowCallerScope.User,
            io.github.triet4p.aegisf.data.RememberCategoryRuleRequest(
                idempotencyKey = "s108-rule-${UUID.randomUUID()}",
                description = description,
                categoryId = categoryId,
            ),
        )
        check(outcome is WorkflowCommandOutcome.Applied) { "rule refused: $outcome" }
    }

    private suspend fun ruleCategory(patternKey: String): Long? =
        queries.categoryRules(
            io.github.triet4p.aegisf.data.CategoryRuleQuery(
                QueryPageRequest(limit = 100, offset = 0),
            ),
        ).items.firstOrNull { it.patternKey == patternKey }?.categoryId

    private fun totals(): Pair<Long, Long> = runBlocking {
        val aggregate = queries.confirmedLedgerAggregate(
            LedgerDateRange.closed(
                fromEpochMillis = 0L,
                throughExclusiveEpochMillis = 4_000_000_000_000L,
                zoneId = "Asia/Ho_Chi_Minh",
            ),
        )
        aggregate.spending.totalVnd to aggregate.income.totalVnd
    }

    private fun screenshot(name: String) {
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
                .close()
        }
        Thread.sleep(400)
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: error("the device returned no screenshot")
        val file = File(context.filesDir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        android.util.Log.i("S108UI", "S108 screenshot=$name file=${file.absolutePath} bytes=${file.length()}")
    }

    private fun hideKeyboard() {
        val manager = context.getSystemService(InputMethodManager::class.java) ?: return
        compose.activity.runOnUiThread {
            manager.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
        Thread.sleep(700)
        compose.waitForIdle()
    }

    private class RecordingTransport(private val response: HostedHttpResponse) : HttpTransport {
        var calls = 0
        override suspend fun post(request: HostedHttpRequest): HostedHttpResponse {
            calls += 1
            return response
        }
    }

    private class ThrowingTransport : HttpTransport {
        var calls = 0
        override suspend fun post(request: HostedHttpRequest): HostedHttpResponse {
            calls += 1
            throw IOException("offline")
        }
    }

    private fun openAiChatBody(model: String): HostedHttpResponse = HostedHttpResponse(
        status = 200,
        headers = mapOf("Content-Type" to "application/json"),
        body = JSONObject()
            .put("id", "chatcmpl-s108")
            .put("object", "chat.completion")
            .put("created", 1)
            .put("model", model)
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("finish_reason", "stop")
                        .put(
                            "message",
                            JSONObject()
                                .put("role", "assistant")
                                .put("content", "READY"),
                        ),
                ),
            )
            .toString(),
    )

    private companion object {
        const val SECRET = "s108-synthetic-credential-1234"
        val ALL_CATEGORIES = CategoryQuery(
            state = CategoryState.ALL,
            bounds = QueryPageRequest(limit = 500, offset = 0),
        )
    }
}
