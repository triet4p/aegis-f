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
import io.github.triet4p.aegisf.ui.BankNotificationSettingsScreen
import io.github.triet4p.aegisf.ui.CategoryManagerScreen
import io.github.triet4p.aegisf.ui.AnalyticsScreen
import io.github.triet4p.aegisf.ui.ChatAgentRunState
import io.github.triet4p.aegisf.ui.ChatDraftContext
import io.github.triet4p.aegisf.ui.ChatNoteContext
import io.github.triet4p.aegisf.ui.DashboardListScope
import io.github.triet4p.aegisf.ui.DashboardScopeState
import io.github.triet4p.aegisf.ui.HostedProviderSettingsScreen
import io.github.triet4p.aegisf.ui.LedgerScreen
import io.github.triet4p.aegisf.ui.NoteDetailScreen
import io.github.triet4p.aegisf.ui.NoteDraftChooserScreen
import io.github.triet4p.aegisf.ui.NoteEditorScreen
import io.github.triet4p.aegisf.ui.NoteEventScreen
import io.github.triet4p.aegisf.ui.QuickCaptureScreen
import io.github.triet4p.aegisf.ui.SavedNotesScreen
import io.github.triet4p.aegisf.ui.ScopedLedgerScreen
import io.github.triet4p.aegisf.ui.TransactionDetailScreen
import io.github.triet4p.aegisf.ui.TransactionEditorScreen
import io.github.triet4p.aegisf.ui.TransactionFilterState
import io.github.triet4p.aegisf.ui.chat.AiChatScreen
import io.github.triet4p.aegisf.ui.review.DraftDetailScreen
import io.github.triet4p.aegisf.ui.review.ReviewInboxScreen
import io.github.triet4p.aegisf.ui.components.AegisBarItem
import io.github.triet4p.aegisf.ui.components.AegisBottomBar
import io.github.triet4p.aegisf.ui.components.AegisGlyph
import io.github.triet4p.aegisf.ui.text.LocalAegisStrings
import io.github.triet4p.aegisf.ui.text.ShellTab
import java.io.Serializable
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

    /** One confirmed-records list behind an analytics figure, with that figure's exact scope. */
    data class ScopedList(
        val listScope: DashboardListScope,
        val scope: DashboardScopeState,
    ) : HomeRoute
    data class Detail(val transactionId: Long) : HomeRoute
    data class Edit(val transactionId: Long) : HomeRoute
}

/** The secondary routes of the Ledger destination. */
private sealed interface LedgerRoute : Serializable {
    data object ManualRecord : LedgerRoute
    data class Detail(val transactionId: Long) : LedgerRoute
    data class Edit(val transactionId: Long) : LedgerRoute
}

/** The secondary routes of the Notes destination: one saved note and everything it owns. */
private sealed interface NotesRoute : Serializable {
    data object ManualRecord : NotesRoute

    /** The focused browse of every note saved on this device. */
    data object Browse : NotesRoute

    /** One saved note, read from storage when the route is shown. */
    data class Detail(val captureId: Long) : NotesRoute

    /** The focused text editor for one saved note. */
    data class Edit(val captureId: Long) : NotesRoute

    /** One detail read from a note; a null event id starts a new detail. */
    data class EventDetail(val captureId: Long, val eventId: Long?) : NotesRoute

    /** The bounded chooser of pending bank notifications for one detail. */
    data class ChooseDraft(val captureId: Long, val eventId: Long) : NotesRoute

    /** The existing manual form, optionally filled from one detail. */
    data class RecordEvent(val eventId: Long) : NotesRoute
}

/** The secondary routes of the AI Chat destination. */
private sealed interface ChatRoute : Serializable {
    data class RecordEvent(val eventId: Long) : ChatRoute

    /** Provider configuration is a focused child, never a panel stacked inside the chat. */
    data object ProviderSettings : ChatRoute
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
    // Feedback about a completed note action must outlive the route change that follows it:
    // the route state holder disposes the destination subtree when the route key changes.
    var notesNotice by rememberSaveable { mutableStateOf<String?>(null) }

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
                            notice = notesNotice,
                            onNoticeChange = { notesNotice = it },
                            onOpen = { route -> notesStack = notesStack + route },
                            onOpenChatWithNote = { captureId, text ->
                                chatRunState.noteContext = ChatNoteContext(captureId, text)
                                chatStack = emptyList()
                                tab = ShellTab.CHAT
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
                            onOpenProviderSettings = {
                                chatStack = chatStack + ChatRoute.ProviderSettings
                            },
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
                            onDiscussDraft = { draftId ->
                                chatRunState.draftContext = ChatDraftContext(draftId)
                                chatStack = emptyList()
                                tab = ShellTab.CHAT
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
    when (val route = stack.lastOrNull()) {
        null -> HomeScreen(
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

        HomeRoute.Analytics -> AnalyticsScreen(
            queryService = queryService,
            scope = scope,
            onScopeChange = onScopeChange,
            onOpenRecords = { listScope, listScopeState ->
                onOpenRoute(HomeRoute.ScopedList(listScope, listScopeState))
            },
            onAddRecord = { onOpenRoute(HomeRoute.ManualRecord) },
            onNavigateUp = onPop,
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

        is HomeRoute.ScopedList -> ScopedLedgerScreen(
            queryService = queryService,
            scope = route.scope,
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
        null -> LedgerScreen(
            queryService = queryService,
            filters = filters,
            onFiltersChange = onFiltersChange,
            filtersExpanded = filtersExpanded,
            onFiltersExpandedChange = onFiltersExpandedChange,
            onSelect = { transactionId -> onOpen(LedgerRoute.Detail(transactionId)) },
            onCreate = { onOpen(LedgerRoute.ManualRecord) },
            // The ledger is a root destination: it has no parent screen to go back to.
            onNavigateUp = {},
            showBack = false,
        )

        LedgerRoute.ManualRecord -> TransactionEditorScreen(
            commandService = commandService,
            queryService = queryService,
            transactionId = null,
            onNavigateUp = onPop,
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
    notice: String?,
    onNoticeChange: (String?) -> Unit,
    onOpen: (NotesRoute) -> Unit,
    onOpenChatWithNote: (Long, String) -> Unit,
    onPop: () -> Unit,
) {
    val strings = LocalAegisStrings.current

    when (val route = stack.lastOrNull()) {
        null -> QuickCaptureScreen(
            queryService = queryService,
            commandService = commandService,
            notice = notice,
            onDismissNotice = { onNoticeChange(null) },
            onOpenSavedNotes = { onOpen(NotesRoute.Browse) },
        )

        NotesRoute.Browse -> SavedNotesScreen(
            queryService = queryService,
            notice = notice,
            onDismissNotice = { onNoticeChange(null) },
            onOpenNote = { captureId -> onOpen(NotesRoute.Detail(captureId)) },
            onNavigateUp = onPop,
        )

        is NotesRoute.Detail -> NoteDetailScreen(
            queryService = queryService,
            commandService = commandService,
            captureId = route.captureId,
            notice = notice,
            onDismissNotice = { onNoticeChange(null) },
            onEdit = { captureId -> onOpen(NotesRoute.Edit(captureId)) },
            onAddDetail = { captureId ->
                onOpen(NotesRoute.EventDetail(captureId, eventId = null))
            },
            onOpenDetail = { captureId, eventId ->
                onOpen(NotesRoute.EventDetail(captureId, eventId))
            },
            onRecordManually = { onOpen(NotesRoute.ManualRecord) },
            onDiscussWithAi = onOpenChatWithNote,
            onDeleted = {
                onNoticeChange(strings.noteDeleted)
                onPop()
            },
            onNavigateUp = onPop,
        )

        is NotesRoute.Edit -> NoteEditorScreen(
            queryService = queryService,
            commandService = commandService,
            captureId = route.captureId,
            onSaved = {
                onNoticeChange(strings.noteChangesSaved)
                onPop()
            },
            onNavigateUp = onPop,
        )

        is NotesRoute.EventDetail -> NoteEventScreen(
            queryService = queryService,
            commandService = commandService,
            captureId = route.captureId,
            eventId = route.eventId,
            onSaved = {
                onNoticeChange(strings.noteDetailSaved)
                onPop()
            },
            onChooseDraft = { eventId ->
                onOpen(NotesRoute.ChooseDraft(route.captureId, eventId))
            },
            onRecordManually = { eventId -> onOpen(NotesRoute.RecordEvent(eventId)) },
            onAddFromCurrent = { captureId ->
                onOpen(NotesRoute.EventDetail(captureId, eventId = null))
            },
            onNavigateUp = onPop,
        )

        is NotesRoute.ChooseDraft -> NoteDraftChooserScreen(
            queryService = queryService,
            commandService = commandService,
            captureId = route.captureId,
            eventId = route.eventId,
            onRecordManually = { eventId -> onOpen(NotesRoute.RecordEvent(eventId)) },
            onNavigateUp = onPop,
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
    onOpenProviderSettings: () -> Unit,
    onRecordEvent: (Long) -> Unit,
    onPop: () -> Unit,
) {
    val networkAvailable = io.github.triet4p.aegisf.ui.chat.rememberNetworkAvailable()
    when (val route = stack.lastOrNull()) {
        null -> AiChatScreen(
            queryService = queryService,
            commandService = commandService,
            activeProvider = hostedProviderSettingsStore::active,
            networkAvailable = networkAvailable,
            createHostedWorkflowHost = createHostedWorkflowHost,
            agentRunHistory = agentRunHistory,
            agentRunState = agentRunState,
            agentRunScope = agentRunScope,
            onOpenProviderSettings = onOpenProviderSettings,
            onRecordEvent = onRecordEvent,
        )

        ChatRoute.ProviderSettings -> HostedProviderSettingsScreen(
            store = hostedProviderSettingsStore,
            onNavigateUp = onPop,
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
    onDiscussDraft: (Long) -> Unit,
    onPop: () -> Unit,
) {
    when (val route = stack.lastOrNull()) {
        null -> ReviewInboxScreen(
            queryService = queryService,
            onOpenDraft = onOpenDraft,
        )

        is ReviewRoute.Draft -> DraftDetailScreen(
            queryService = queryService,
            commandService = commandService,
            draftId = route.draftId,
            onOpenDraft = onOpenDraft,
            onDiscussDraft = onDiscussDraft,
            onNavigateUp = onPop,
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
    is HomeRoute.ScopedList -> "scoped-list-${listScope.name}-${scope.fromText}-" +
        "${scope.throughText}-${scope.category}"
    is HomeRoute.Detail -> "record-detail-$transactionId"
    is HomeRoute.Edit -> "record-edit-$transactionId"
}

private fun LedgerRoute.ledgerKey(): String = when (this) {
    LedgerRoute.ManualRecord -> "manual-record"
    is LedgerRoute.Detail -> "record-detail-$transactionId"
    is LedgerRoute.Edit -> "record-edit-$transactionId"
}

private fun NotesRoute.notesKey(): String = when (this) {
    NotesRoute.ManualRecord -> "manual-record"
    NotesRoute.Browse -> "saved-notes"
    is NotesRoute.Detail -> "note-$captureId"
    is NotesRoute.Edit -> "note-edit-$captureId"
    is NotesRoute.EventDetail -> "note-event-$captureId-${eventId ?: "new"}"
    is NotesRoute.ChooseDraft -> "note-draft-$captureId-$eventId"
    is NotesRoute.RecordEvent -> "record-event-$eventId"
}

private fun ChatRoute.chatKey(): String = when (this) {
    ChatRoute.ProviderSettings -> "provider-settings"
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
