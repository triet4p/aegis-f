package io.github.triet4p.aegisf.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.triet4p.aegisf.agent.AgentRunHistory
import io.github.triet4p.aegisf.agent.WorkflowRunHost
import io.github.triet4p.aegisf.data.AegisLanguage
import io.github.triet4p.aegisf.data.AegisThemeMode
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.NotificationAccessStore
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.provider.HostedProviderConfig
import io.github.triet4p.aegisf.provider.HostedProviderSettingsStore
import io.github.triet4p.aegisf.ui.BankDraftReviewScreen
import io.github.triet4p.aegisf.ui.BankNotificationSettingsScreen
import io.github.triet4p.aegisf.ui.CategoryManagerScreen
import io.github.triet4p.aegisf.ui.ChatAgentRunState
import io.github.triet4p.aegisf.ui.DashboardListScope
import io.github.triet4p.aegisf.ui.DashboardScopeState
import io.github.triet4p.aegisf.ui.DashboardScreen
import io.github.triet4p.aegisf.ui.DashboardScopedListScreen
import io.github.triet4p.aegisf.ui.HostedProviderSettingsScreen
import io.github.triet4p.aegisf.ui.QuickCaptureScreen
import io.github.triet4p.aegisf.ui.TransactionDetailScreen
import io.github.triet4p.aegisf.ui.TransactionEditorScreen
import io.github.triet4p.aegisf.ui.TransactionFilterState
import io.github.triet4p.aegisf.ui.TransactionListScreen
import io.github.triet4p.aegisf.ui.chat.AiChatScreen
import io.github.triet4p.aegisf.ui.components.AegisBarItem
import io.github.triet4p.aegisf.ui.components.AegisBottomBar
import io.github.triet4p.aegisf.ui.components.AegisGlyph
import io.github.triet4p.aegisf.ui.text.LocalAegisStrings
import io.github.triet4p.aegisf.ui.text.ShellTab
import java.io.Serializable
import java.util.Locale
import java.util.TimeZone

/**
 * The secondary routes of the Home destination.
 *
 * Every entry is a destination that already exists: the reports view, the manual record form,
 * the category manager, the settings hub and its two configuration screens, and the record views
 * the reports drill into. Home itself owns only the choice of task.
 */
private sealed interface HomeRoute : Serializable {
    data object Analytics : HomeRoute
    data object ManualRecord : HomeRoute
    data object Categories : HomeRoute
    data object Settings : HomeRoute
    data object BankNotifications : HomeRoute
    data object HostedProviderSettings : HomeRoute

    /** One confirmed-records list behind an analytics figure, in that figure's own scope. */
    data class ScopedList(val listScope: DashboardListScope) : HomeRoute
    data class Detail(val transactionId: Long) : HomeRoute
    data class Edit(val transactionId: Long) : HomeRoute
}

/** The secondary routes of the Ledger destination. */
private sealed interface LedgerRoute : Serializable {
    data class Detail(val transactionId: Long) : LedgerRoute
    data class Edit(val transactionId: Long) : LedgerRoute
}

/** The secondary routes of the Notes destination: manual records, from the form or an event. */
private sealed interface NotesRoute : Serializable {
    data object ManualRecord : NotesRoute
    data class RecordEvent(val eventId: Long) : NotesRoute
}

/** The secondary routes of the AI Chat destination. */
private sealed interface ChatRoute : Serializable {
    data class RecordEvent(val eventId: Long) : ChatRoute
}

/** The secondary routes of the Review destination. */
private sealed interface ReviewRoute : Serializable {
    data class Draft(val draftId: Long) : ReviewRoute
}

/**
 * The application shell: five root destinations, one bottom bar, and one saveable route stack per
 * destination.
 *
 * The shell owns navigation only. A root tab shows a destination at its root; a focused child
 * route (a form, a receipt, a configuration screen) hides the bottom bar so the screen has one
 * task and one Back, and Back returns to the exact parent — same list position, same filters,
 * same half-written form — because state is kept per route and per destination.
 *
 * The AI Chat exchange is hoisted here, not owned by the tab subtree: switching tabs disposes the
 * destination's composition, and an in-flight run, a streamed answer or a pending clarification
 * must not disappear with it.
 */
@Composable
internal fun AegisAppShell(
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    notificationAccessStore: NotificationAccessStore,
    hostedProviderSettingsStore: HostedProviderSettingsStore,
    createHostedWorkflowHost: (WorkflowCallerScope.AgentSession, HostedProviderConfig) -> WorkflowRunHost,
    agentRunHistory: AgentRunHistory,
    language: AegisLanguage,
    onLanguageChange: (AegisLanguage) -> Unit,
    themeMode: AegisThemeMode,
    onThemeModeChange: (AegisThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAegisStrings.current
    var tab by rememberSaveable { mutableStateOf(ShellTab.HOME) }
    var homeStack by rememberSaveable { mutableStateOf(emptyList<HomeRoute>()) }
    var ledgerStack by rememberSaveable { mutableStateOf(emptyList<LedgerRoute>()) }
    var notesStack by rememberSaveable { mutableStateOf(emptyList<NotesRoute>()) }
    var chatStack by rememberSaveable { mutableStateOf(emptyList<ChatRoute>()) }
    var reviewStack by rememberSaveable { mutableStateOf(emptyList<ReviewRoute>()) }

    // The ledger's filters live above its own routes, so opening a record and coming back — or
    // visiting another destination and returning — keeps the list looking as it was left.
    var transactionFilters by rememberSaveable { mutableStateOf(TransactionFilterState()) }
    var transactionFiltersExpanded by rememberSaveable { mutableStateOf(false) }
    // The reports' scope is hoisted for the same reason: a drill-down and back must not lose the
    // chosen days or category, and a restart must not either.
    var dashboardScope by rememberSaveable {
        mutableStateOf(
            DashboardScopeState.currentMonth(System.currentTimeMillis(), TimeZone.getDefault()),
        )
    }

    val destinationState = rememberSaveableStateHolder()
    val routeState = rememberSaveableStateHolder()
    // Keys of routes the user has left. Their saved state is dropped after the disposal has
    // written it, so re-opening the same destination cannot resurrect a discarded form.
    var discardedKeys by remember { mutableStateOf(emptySet<String>()) }
    val chatRunState = remember { ChatAgentRunState() }
    val chatRunScope = rememberCoroutineScope()
    val waitingDrafts by remember(queryService) {
        queryService.observeDraftCandidateCount(IntakeReviewStatus.PENDING)
    }.collectAsState(initial = null)

    val currentStackSize = when (tab) {
        ShellTab.HOME -> homeStack.size
        ShellTab.LEDGER -> ledgerStack.size
        ShellTab.NOTES -> notesStack.size
        ShellTab.CHAT -> chatStack.size
        ShellTab.REVIEW -> reviewStack.size
    }
    val showBottomBar = currentStackSize == 0

    fun currentRouteKey(): String = tab.name + ":" + when (tab) {
        ShellTab.HOME -> homeStack.lastOrNull()?.homeKey() ?: "root"
        ShellTab.LEDGER -> ledgerStack.lastOrNull()?.ledgerKey() ?: "root"
        ShellTab.NOTES -> notesStack.lastOrNull()?.notesKey() ?: "root"
        ShellTab.CHAT -> chatStack.lastOrNull()?.chatKey() ?: "root"
        ShellTab.REVIEW -> reviewStack.lastOrNull()?.reviewKey() ?: "root"
    }

    fun currentStackKeys(): List<String> = when (tab) {
        ShellTab.HOME -> homeStack.map { "${tab.name}:${it.homeKey()}" }
        ShellTab.LEDGER -> ledgerStack.map { "${tab.name}:${it.ledgerKey()}" }
        ShellTab.NOTES -> notesStack.map { "${tab.name}:${it.notesKey()}" }
        ShellTab.CHAT -> chatStack.map { "${tab.name}:${it.chatKey()}" }
        ShellTab.REVIEW -> reviewStack.map { "${tab.name}:${it.reviewKey()}" }
    }

    fun selectTab(selected: ShellTab) {
        if (selected == tab) {
            // Tapping the current destination returns it to its own root. The routes that are
            // left behind are discarded: a form opened again must not restore a half-written
            // attempt the user already left.
            val removed = currentStackKeys()
            when (selected) {
                ShellTab.HOME -> homeStack = emptyList()
                ShellTab.LEDGER -> ledgerStack = emptyList()
                ShellTab.NOTES -> notesStack = emptyList()
                ShellTab.CHAT -> chatStack = emptyList()
                ShellTab.REVIEW -> reviewStack = emptyList()
            }
            discardedKeys = discardedKeys + removed
        } else {
            tab = selected
        }
    }

    fun popCurrentRoute() {
        // The route being left is disposed; its saved state is dropped after the disposal
        // (see the effect below), so re-opening the same destination starts clean.
        val removed = currentRouteKey()
        when (tab) {
            ShellTab.HOME -> homeStack = homeStack.dropLast(1)
            ShellTab.LEDGER -> ledgerStack = ledgerStack.dropLast(1)
            ShellTab.NOTES -> notesStack = notesStack.dropLast(1)
            ShellTab.CHAT -> chatStack = chatStack.dropLast(1)
            ShellTab.REVIEW -> reviewStack = reviewStack.dropLast(1)
        }
        discardedKeys = discardedKeys + removed
    }

    fun openHomeRoute(route: HomeRoute) {
        homeStack = homeStack + route
    }

    // System Back pops one route of the current destination; at a root that is not Home it
    // returns Home; at Home's root it leaves the app. A child screen's own handler runs first
    // when the child has something to protect.
    BackHandler(enabled = currentStackSize > 0 || tab != ShellTab.HOME) {
        if (currentStackSize > 0) {
            popCurrentRoute()
        } else {
            tab = ShellTab.HOME
        }
    }

    val barItems = ShellTab.entries.map { rootTab ->
        AegisBarItem(
            key = rootTab.name,
            label = strings.tabLabel(rootTab),
            description = strings.tabDescription(rootTab, waitingDrafts),
            glyph = rootTab.glyph(),
            badgeCount = if (rootTab == ShellTab.REVIEW) waitingDrafts else null,
        )
    }

    val routeKey = currentRouteKey()
    LaunchedEffect(discardedKeys, routeKey) {
        if (discardedKeys.isNotEmpty()) {
            discardedKeys.forEach(routeState::removeState)
            discardedKeys = emptySet()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
                AegisBottomBar(
                    items = barItems,
                    selectedKey = tab.name,
                    onSelect = { key -> selectTab(ShellTab.valueOf(key)) },
                )
            }
        },
        // Each destination's own Scaffold owns the system bars; the shell only adds the space
        // its bottom bar occupies, so no inset is applied twice.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            destinationState.SaveableStateProvider(tab.name) {
                routeState.SaveableStateProvider(routeKey) {
                    when (tab) {
                        ShellTab.HOME -> HomeDestination(
                            queryService = queryService,
                            commandService = commandService,
                            notificationAccessStore = notificationAccessStore,
                            hostedProviderSettingsStore = hostedProviderSettingsStore,
                            stack = homeStack,
                            scope = dashboardScope,
                            onScopeChange = { dashboardScope = it },
                            waitingDrafts = waitingDrafts,
                            language = language,
                            onLanguageChange = onLanguageChange,
                            themeMode = themeMode,
                            onThemeModeChange = onThemeModeChange,
                            onOpenRoute = ::openHomeRoute,
                            onPop = ::popCurrentRoute,
                            onSelectTab = ::selectTab,
                        )

                        ShellTab.LEDGER -> LedgerDestination(
                            queryService = queryService,
                            commandService = commandService,
                            stack = ledgerStack,
                            filters = transactionFilters,
                            onFiltersChange = { transactionFilters = it },
                            filtersExpanded = transactionFiltersExpanded,
                            onFiltersExpandedChange = { transactionFiltersExpanded = it },
                            onOpen = { route -> ledgerStack = ledgerStack + route },
                            onPop = ::popCurrentRoute,
                        )

                        ShellTab.NOTES -> NotesDestination(
                            queryService = queryService,
                            commandService = commandService,
                            stack = notesStack,
                            onOpenManualRecord = {
                                notesStack = notesStack + NotesRoute.ManualRecord
                            },
                            onOpenEvent = { eventId ->
                                notesStack = notesStack + NotesRoute.RecordEvent(eventId)
                            },
                            onPop = ::popCurrentRoute,
                        )

                        ShellTab.CHAT -> ChatDestination(
                            queryService = queryService,
                            commandService = commandService,
                            hostedProviderSettingsStore = hostedProviderSettingsStore,
                            createHostedWorkflowHost = createHostedWorkflowHost,
                            agentRunHistory = agentRunHistory,
                            agentRunState = chatRunState,
                            agentRunScope = chatRunScope,
                            stack = chatStack,
                            onRecordEvent = { eventId ->
                                chatStack = chatStack + ChatRoute.RecordEvent(eventId)
                            },
                            onPop = ::popCurrentRoute,
                        )

                        ShellTab.REVIEW -> ReviewDestination(
                            queryService = queryService,
                            commandService = commandService,
                            stack = reviewStack,
                            onOpenDraft = { draftId ->
                                reviewStack = reviewStack + ReviewRoute.Draft(draftId)
                            },
                            onPop = ::popCurrentRoute,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeDestination(
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    notificationAccessStore: NotificationAccessStore,
    hostedProviderSettingsStore: HostedProviderSettingsStore,
    stack: List<HomeRoute>,
    scope: DashboardScopeState,
    onScopeChange: (DashboardScopeState) -> Unit,
    waitingDrafts: Int?,
    language: AegisLanguage,
    onLanguageChange: (AegisLanguage) -> Unit,
    themeMode: AegisThemeMode,
    onThemeModeChange: (AegisThemeMode) -> Unit,
    onOpenRoute: (HomeRoute) -> Unit,
    onPop: () -> Unit,
    onSelectTab: (ShellTab) -> Unit,
) {
    val strings = LocalAegisStrings.current
    when (val route = stack.lastOrNull()) {
        null -> HomeLauncherScreen(
            queryService = queryService,
            reviewCount = waitingDrafts,
            onOpenLedger = { onSelectTab(ShellTab.LEDGER) },
            onOpenAnalytics = { onOpenRoute(HomeRoute.Analytics) },
            onOpenNotes = { onSelectTab(ShellTab.NOTES) },
            onOpenChat = { onSelectTab(ShellTab.CHAT) },
            onOpenReview = { onSelectTab(ShellTab.REVIEW) },
            onOpenManualRecord = { onOpenRoute(HomeRoute.ManualRecord) },
            onOpenCategories = { onOpenRoute(HomeRoute.Categories) },
            onOpenSettings = { onOpenRoute(HomeRoute.Settings) },
        )

        HomeRoute.Analytics -> DashboardScreen(
            queryService = queryService,
            scope = scope,
            onScopeChange = onScopeChange,
            onOpenList = { listScope -> onOpenRoute(HomeRoute.ScopedList(listScope)) },
            onOpenSettings = { onOpenRoute(HomeRoute.Settings) },
            onOpenReview = { onSelectTab(ShellTab.REVIEW) },
            title = strings.analyticsTitle.uppercase(Locale.ROOT),
        )

        HomeRoute.ManualRecord -> TransactionEditorScreen(
            commandService = commandService,
            queryService = queryService,
            transactionId = null,
            onNavigateUp = onPop,
        )

        HomeRoute.Categories -> CategoryManagerScreen(
            commandService = commandService,
            queryService = queryService,
            onNavigateUp = onPop,
        )

        HomeRoute.Settings -> SettingsHubScreen(
            language = language,
            onLanguageChange = onLanguageChange,
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            onOpenBankNotifications = { onOpenRoute(HomeRoute.BankNotifications) },
            onOpenHostedProvider = { onOpenRoute(HomeRoute.HostedProviderSettings) },
            onNavigateUp = onPop,
        )

        HomeRoute.BankNotifications -> BankNotificationSettingsScreen(
            accessStore = notificationAccessStore,
            onNavigateUp = onPop,
        )

        HomeRoute.HostedProviderSettings -> HostedProviderSettingsScreen(
            store = hostedProviderSettingsStore,
            onNavigateUp = onPop,
        )

        is HomeRoute.ScopedList -> DashboardScopedListScreen(
            queryService = queryService,
            scope = scope,
            listScope = route.listScope,
            onSelectRecord = { transactionId -> onOpenRoute(HomeRoute.Detail(transactionId)) },
            onNavigateUp = onPop,
        )

        is HomeRoute.Detail -> TransactionDetailScreen(
            commandService = commandService,
            queryService = queryService,
            transactionId = route.transactionId,
            onEdit = { onOpenRoute(HomeRoute.Edit(route.transactionId)) },
            onDeleted = onPop,
            onNavigateUp = onPop,
        )

        is HomeRoute.Edit -> TransactionEditorScreen(
            commandService = commandService,
            queryService = queryService,
            transactionId = route.transactionId,
            onNavigateUp = onPop,
        )
    }
}

@Composable
private fun LedgerDestination(
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    stack: List<LedgerRoute>,
    filters: TransactionFilterState,
    onFiltersChange: (TransactionFilterState) -> Unit,
    filtersExpanded: Boolean,
    onFiltersExpandedChange: (Boolean) -> Unit,
    onOpen: (LedgerRoute) -> Unit,
    onPop: () -> Unit,
) {
    when (val route = stack.lastOrNull()) {
        null -> TransactionListScreen(
            queryService = queryService,
            filters = filters,
            onFiltersChange = onFiltersChange,
            filtersExpanded = filtersExpanded,
            onFiltersExpandedChange = onFiltersExpandedChange,
            onSelect = { transactionId -> onOpen(LedgerRoute.Detail(transactionId)) },
            // The ledger is a root destination: it has no parent screen to go back to.
            onNavigateUp = {},
            showBack = false,
        )

        is LedgerRoute.Detail -> TransactionDetailScreen(
            commandService = commandService,
            queryService = queryService,
            transactionId = route.transactionId,
            onEdit = { onOpen(LedgerRoute.Edit(route.transactionId)) },
            onDeleted = onPop,
            onNavigateUp = onPop,
        )

        is LedgerRoute.Edit -> TransactionEditorScreen(
            commandService = commandService,
            queryService = queryService,
            transactionId = route.transactionId,
            onNavigateUp = onPop,
        )
    }
}

@Composable
private fun NotesDestination(
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    stack: List<NotesRoute>,
    onOpenManualRecord: () -> Unit,
    onOpenEvent: (Long) -> Unit,
    onPop: () -> Unit,
) {
    when (val route = stack.lastOrNull()) {
        null -> QuickCaptureScreen(
            queryService = queryService,
            commandService = commandService,
            onOpenManual = onOpenManualRecord,
            onRecordTransaction = onOpenManualRecord,
            onRecordEvent = onOpenEvent,
        )

        NotesRoute.ManualRecord -> TransactionEditorScreen(
            commandService = commandService,
            queryService = queryService,
            transactionId = null,
            onNavigateUp = onPop,
        )

        is NotesRoute.RecordEvent -> TransactionEditorScreen(
            commandService = commandService,
            queryService = queryService,
            transactionId = null,
            sourceEventId = route.eventId,
            onNavigateUp = onPop,
        )
    }
}

@Composable
private fun ChatDestination(
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    hostedProviderSettingsStore: HostedProviderSettingsStore,
    createHostedWorkflowHost: (WorkflowCallerScope.AgentSession, HostedProviderConfig) -> WorkflowRunHost,
    agentRunHistory: AgentRunHistory,
    agentRunState: ChatAgentRunState,
    agentRunScope: kotlinx.coroutines.CoroutineScope,
    stack: List<ChatRoute>,
    onRecordEvent: (Long) -> Unit,
    onPop: () -> Unit,
) {
    when (val route = stack.lastOrNull()) {
        null -> AiChatScreen(
            queryService = queryService,
            commandService = commandService,
            hostedProviderSettingsStore = hostedProviderSettingsStore,
            createHostedWorkflowHost = createHostedWorkflowHost,
            agentRunHistory = agentRunHistory,
            agentRunState = agentRunState,
            agentRunScope = agentRunScope,
            onRecordEvent = onRecordEvent,
        )

        is ChatRoute.RecordEvent -> TransactionEditorScreen(
            commandService = commandService,
            queryService = queryService,
            transactionId = null,
            sourceEventId = route.eventId.takeIf { it > 0L },
            onNavigateUp = onPop,
        )
    }
}

@Composable
private fun ReviewDestination(
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    stack: List<ReviewRoute>,
    onOpenDraft: (Long) -> Unit,
    onPop: () -> Unit,
) {
    when (val route = stack.lastOrNull()) {
        null -> BankDraftReviewScreen(
            queryService = queryService,
            commandService = commandService,
            draftId = null,
            onSelectDraft = onOpenDraft,
            onNavigateUp = {},
            // The inbox is this destination's root: there is no previous screen to go back
            // to, so it must not offer a Back control of its own.
            showBack = false,
        )

        is ReviewRoute.Draft -> BankDraftReviewScreen(
            queryService = queryService,
            commandService = commandService,
            draftId = route.draftId,
            onSelectDraft = onOpenDraft,
            onNavigateUp = onPop,
            showBack = true,
        )
    }
}

private fun HomeRoute.homeKey(): String = when (this) {
    HomeRoute.Analytics -> "analytics"
    HomeRoute.ManualRecord -> "manual-record"
    HomeRoute.Categories -> "categories"
    HomeRoute.Settings -> "settings"
    HomeRoute.BankNotifications -> "bank-notifications"
    HomeRoute.HostedProviderSettings -> "hosted-provider-settings"
    is HomeRoute.ScopedList -> "scoped-list-${listScope.name}"
    is HomeRoute.Detail -> "record-detail-$transactionId"
    is HomeRoute.Edit -> "record-edit-$transactionId"
}

private fun LedgerRoute.ledgerKey(): String = when (this) {
    is LedgerRoute.Detail -> "record-detail-$transactionId"
    is LedgerRoute.Edit -> "record-edit-$transactionId"
}

private fun NotesRoute.notesKey(): String = when (this) {
    NotesRoute.ManualRecord -> "manual-record"
    is NotesRoute.RecordEvent -> "record-event-$eventId"
}

private fun ChatRoute.chatKey(): String = when (this) {
    is ChatRoute.RecordEvent -> "record-event-$eventId"
}

private fun ReviewRoute.reviewKey(): String = when (this) {
    is ReviewRoute.Draft -> "draft-$draftId"
}

private fun ShellTab.glyph(): AegisGlyph = when (this) {
    ShellTab.HOME -> AegisGlyph.HOME
    ShellTab.LEDGER -> AegisGlyph.LEDGER
    ShellTab.NOTES -> AegisGlyph.NOTES
    ShellTab.CHAT -> AegisGlyph.CHAT
    ShellTab.REVIEW -> AegisGlyph.REVIEW
}
