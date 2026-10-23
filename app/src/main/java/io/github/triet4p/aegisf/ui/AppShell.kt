package io.github.triet4p.aegisf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.NotificationAccessStore
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.data.laya.LayaInferenceLifecycle
import java.io.Serializable
import java.util.TimeZone

/**
 * The four primary destinations of the daily workflow. They are the only surfaces the
 * bottom navigation offers; everything else is a route opened from one of them.
 *
 * "Chat" is the short label for "Chat / Quick Capture": it is a persistent journal, not a
 * simulated conversation. "Review" carries the live count of bank drafts still waiting.
 */
private enum class PrimaryDestination(val label: String, val spokenName: String) {
    DASHBOARD("Dashboard", "Dashboard"),
    CHAT("Chat", "Chat / Quick Capture"),
    REVIEW("Review", "Review notifications"),
    MANUAL("Manual", "Manual entry"),
    ;

    /** What a screen reader announces for this destination, including the review count. */
    fun spokenLabel(reviewCount: Int?): String =
        if (this == REVIEW && reviewCount != null && reviewCount > 0) {
            "$spokenName, $reviewCount waiting"
        } else {
            spokenName
        }
}

/**
 * One secondary route per destination, each behind its own stack. The four primary
 * destinations stay mounted in the same order, and their stacks are saveable, so a draft
 * typed in Manual survives a visit to Dashboard and back, and the record a process restart
 * was in the middle of composing is still on screen when the process comes back.
 */
private sealed interface DashboardRoute : Serializable {
    data object Settings : DashboardRoute
    data object BankNotifications : DashboardRoute
    data object ModelSetup : DashboardRoute

    /** The records behind one Dashboard figure, in the scope the figure shows. */
    data class ScopedList(val listScope: DashboardListScope) : DashboardRoute

    /** One stored record, opened from a Dashboard list. */
    data class RecordDetail(val transactionId: Long) : DashboardRoute

    /** The correction form of one stored record, opened from its detail view. */
    data class RecordEdit(val transactionId: Long) : DashboardRoute
}

private sealed interface ReviewRoute : Serializable {
    data class Draft(val draftId: Long) : ReviewRoute
}

private sealed interface ManualRoute : Serializable {
    data object Categories : ManualRoute
    data object TransactionList : ManualRoute
    data class NewTransaction(val sourceEventId: Long? = null) : ManualRoute
    data class Detail(val transactionId: Long) : ManualRoute
    data class Edit(val transactionId: Long) : ManualRoute
}

/**
 * The application shell: four primary destinations, one bottom navigation bar, and each
 * destination's own secondary stack.
 *
 * State ownership is deliberate. Every scroll position, filter, page and unsaved form field
 * inside a destination is ordinary saveable Compose state; [rememberSaveableStateHolder]
 * keeps it while another destination is showing, so switching destinations is not a way to
 * lose a half-written record or a chosen filter. Back pops the current destination's route,
 * then returns a non-Dashboard destination to Dashboard, and finally leaves the app from
 * Dashboard — the same pop order the screens themselves already implement with their own
 * BackHandlers.
 */
@Composable
internal fun AegisAppShell(
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    layaInferenceLifecycle: LayaInferenceLifecycle,
    notificationAccessStore: NotificationAccessStore,
    modifier: Modifier = Modifier,
) {
    var destination by rememberSaveable { mutableStateOf(PrimaryDestination.DASHBOARD) }
    var dashboardStack by rememberSaveable { mutableStateOf(emptyList<DashboardRoute>()) }
    var reviewStack by rememberSaveable { mutableStateOf(emptyList<ReviewRoute>()) }
    var manualStack by rememberSaveable { mutableStateOf(emptyList<ManualRoute>()) }
    // The browsing filters belong above the list screen, so opening a record and coming back,
    // or visiting another destination and returning, keeps the list looking as it was left.
    var transactionFilters by rememberSaveable { mutableStateOf(TransactionFilterState()) }
    var transactionFiltersExpanded by rememberSaveable { mutableStateOf(false) }
    // The Dashboard's scope — the chosen calendar days and the chosen category — belongs
    // above the Dashboard's own routes, so it survives switching to another destination
    // and back, opening a record and coming back, and the process being restarted.
    var dashboardScope by rememberSaveable {
        mutableStateOf(
            DashboardScopeState.currentMonth(System.currentTimeMillis(), TimeZone.getDefault()),
        )
    }
    val destinationState = rememberSaveableStateHolder()
    // One saveable state per Dashboard route, so the list behind a figure keeps its page
    // when a record is opened from it and the user comes back.
    val dashboardRouteState = rememberSaveableStateHolder()
    val waitingDrafts by remember(queryService) {
        queryService.observeDraftCandidateCount(IntakeReviewStatus.PENDING)
    }.collectAsState(initial = null)

    fun selectDestination(selected: PrimaryDestination) {
        if (selected == destination) {
            // Tapping the current destination is the conventional way back to its root.
            when (selected) {
                PrimaryDestination.DASHBOARD -> dashboardStack = emptyList()
                PrimaryDestination.CHAT -> Unit
                PrimaryDestination.REVIEW -> reviewStack = emptyList()
                PrimaryDestination.MANUAL -> manualStack = emptyList()
            }
        } else {
            destination = selected
        }
    }

    fun openTransactionEditor(sourceEventId: Long? = null) {
        val route = ManualRoute.NewTransaction(sourceEventId)
        destination = PrimaryDestination.MANUAL
        if (manualStack.lastOrNull() != route) {
            manualStack = manualStack + route
        }
    }

    val backEnabled = when (destination) {
        PrimaryDestination.DASHBOARD -> dashboardStack.isNotEmpty()
        PrimaryDestination.CHAT -> true
        PrimaryDestination.REVIEW -> true
        PrimaryDestination.MANUAL -> true
    }
    BackHandler(enabled = backEnabled) {
        when (destination) {
            PrimaryDestination.DASHBOARD -> dashboardStack = dashboardStack.dropLast(1)
            PrimaryDestination.CHAT -> destination = PrimaryDestination.DASHBOARD
            PrimaryDestination.REVIEW -> {
                if (reviewStack.isNotEmpty()) {
                    reviewStack = reviewStack.dropLast(1)
                } else {
                    destination = PrimaryDestination.DASHBOARD
                }
            }
            PrimaryDestination.MANUAL -> {
                if (manualStack.isNotEmpty()) {
                    manualStack = manualStack.dropLast(1)
                } else {
                    destination = PrimaryDestination.DASHBOARD
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            PrimaryNavigationBar(
                selected = destination,
                reviewCount = waitingDrafts,
                onSelect = ::selectDestination,
            )
        },
        // The bottom bar owns the system navigation-bar inset; each destination's own
        // Scaffold keeps owning the status bar through its top app bar. Zeroing the outer
        // content insets avoids applying the system bars twice.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            destinationState.SaveableStateProvider(destination.name) {
                when (destination) {
                    PrimaryDestination.DASHBOARD -> dashboardRouteState.SaveableStateProvider(
                        dashboardStack.lastOrNull()?.routeKey() ?: "root",
                    ) {
                        DashboardDestination(
                            queryService = queryService,
                            commandService = commandService,
                            layaInferenceLifecycle = layaInferenceLifecycle,
                            scope = dashboardScope,
                            onScopeChange = { dashboardScope = it },
                            notificationAccessStore = notificationAccessStore,
                            stack = dashboardStack,
                            onOpenSettings = {
                                dashboardStack = dashboardStack + DashboardRoute.Settings
                            },
                            onOpenBankNotifications = {
                                dashboardStack = dashboardStack + DashboardRoute.BankNotifications
                            },
                            onOpenModelSetup = {
                                dashboardStack = dashboardStack + DashboardRoute.ModelSetup
                            },
                            onOpenList = { listScope ->
                                dashboardStack = dashboardStack +
                                    DashboardRoute.ScopedList(listScope)
                            },
                            onOpenRecord = { transactionId ->
                                dashboardStack = dashboardStack +
                                    DashboardRoute.RecordDetail(transactionId)
                            },
                            onOpenRecordEdit = { transactionId ->
                                dashboardStack = dashboardStack +
                                    DashboardRoute.RecordEdit(transactionId)
                            },
                            onOpenReview = { destination = PrimaryDestination.REVIEW },
                            onPop = { dashboardStack = dashboardStack.dropLast(1) },
                        )
                    }

                    PrimaryDestination.CHAT -> ChatDestination(
                        queryService = queryService,
                        commandService = commandService,
                        onOpenManual = { destination = PrimaryDestination.MANUAL },
                        onRecordTransaction = { openTransactionEditor() },
                        onRecordEvent = { eventId -> openTransactionEditor(eventId) },
                    )

                    PrimaryDestination.REVIEW -> ReviewDestination(
                        queryService = queryService,
                        commandService = commandService,
                        stack = reviewStack,
                        onOpenDraft = { draftId ->
                            reviewStack = reviewStack + ReviewRoute.Draft(draftId)
                        },
                        onPop = { reviewStack = reviewStack.dropLast(1) },
                    )

                    PrimaryDestination.MANUAL -> ManualDestination(
                        queryService = queryService,
                        commandService = commandService,
                        layaInferenceLifecycle = layaInferenceLifecycle,
                        stack = manualStack,
                        transactionFilters = transactionFilters,
                        onTransactionFiltersChange = { transactionFilters = it },
                        transactionFiltersExpanded = transactionFiltersExpanded,
                        onTransactionFiltersExpandedChange = { transactionFiltersExpanded = it },
                        onRecordTransaction = {
                            manualStack = manualStack + ManualRoute.NewTransaction()
                        },
                        onBrowseTransactions = { manualStack = manualStack + ManualRoute.TransactionList },
                        onManageCategories = { manualStack = manualStack + ManualRoute.Categories },
                        onOpenTransaction = { transactionId ->
                            manualStack = manualStack + ManualRoute.Detail(transactionId)
                        },
                        onEditTransaction = { transactionId ->
                            manualStack = manualStack + ManualRoute.Edit(transactionId)
                        },
                        onPop = { manualStack = manualStack.dropLast(1) },
                    )
                }
            }
        }
    }
}

/** The one primary navigation bar: four labelled destinations and the review count. */
@Composable
private fun PrimaryNavigationBar(
    selected: PrimaryDestination,
    reviewCount: Int?,
    onSelect: (PrimaryDestination) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            androidx.compose.material3.HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PrimaryDestination.entries.forEach { destination ->
                    val isSelected = destination == selected
                    val count = if (destination == PrimaryDestination.REVIEW) reviewCount else null
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp)
                            .clickable(role = Role.Tab) { onSelect(destination) }
                            .semantics(mergeDescendants = true) {
                                this.selected = isSelected
                                contentDescription = destination.spokenLabel(count)
                            }
                            .padding(horizontal = 4.dp, vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                    ) {
                        PrimaryDestinationGlyph(
                            destination = destination,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = destination.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            if (count != null && count > 0) {
                                Surface(
                                    shape = RoundedCornerShape(2.dp),
                                    color = MaterialTheme.colorScheme.tertiary,
                                ) {
                                    Text(
                                        text = count.toString(),
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiary,
                                    )
                                }
                            }
                        }
                        // Weight and the underline both mark selection, not color alone.
                        Box(
                            modifier = Modifier
                                .padding(top = 1.dp)
                                .height(2.dp)
                                .width(24.dp)
                                .background(
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        Color.Transparent
                                    },
                                    shape = RoundedCornerShape(2.dp),
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrimaryDestinationGlyph(destination: PrimaryDestination, color: Color) {
    Canvas(modifier = Modifier.size(20.dp)) {
        val left = 3.dp.toPx()
        val right = size.width - left
        val top = 3.dp.toPx()
        val bottom = size.height - top
        val stroke = 1.8.dp.toPx()
        when (destination) {
            PrimaryDestination.DASHBOARD -> {
                drawLine(color, Offset(left, bottom), Offset(right, bottom), stroke)
                drawLine(color, Offset(6.dp.toPx(), bottom), Offset(6.dp.toPx(), top + 9.dp.toPx()), stroke)
                drawLine(color, Offset(10.dp.toPx(), bottom), Offset(10.dp.toPx(), top + 6.dp.toPx()), stroke)
                drawLine(color, Offset(14.dp.toPx(), bottom), Offset(14.dp.toPx(), top + 2.dp.toPx()), stroke)
            }
            PrimaryDestination.CHAT -> {
                drawLine(color, Offset(left, top), Offset(right, top), stroke)
                drawLine(color, Offset(right, top), Offset(right, bottom - 4.dp.toPx()), stroke)
                drawLine(color, Offset(right, bottom - 4.dp.toPx()), Offset(left + 6.dp.toPx(), bottom - 4.dp.toPx()), stroke)
                drawLine(color, Offset(left + 6.dp.toPx(), bottom - 4.dp.toPx()), Offset(left, bottom), stroke)
                drawLine(color, Offset(left, bottom), Offset(left, top), stroke)
            }
            PrimaryDestination.REVIEW -> {
                drawLine(color, Offset(left, top + 4.dp.toPx()), Offset(left, bottom), stroke)
                drawLine(color, Offset(left, bottom), Offset(right, bottom), stroke)
                drawLine(color, Offset(right, bottom), Offset(right, top + 4.dp.toPx()), stroke)
                drawLine(color, Offset(left, top + 4.dp.toPx()), Offset(left + 4.dp.toPx(), top + 4.dp.toPx()), stroke)
                drawLine(color, Offset(left + 4.dp.toPx(), top + 4.dp.toPx()), Offset(left + 5.dp.toPx(), top + 8.dp.toPx()), stroke)
                drawLine(color, Offset(left + 5.dp.toPx(), top + 8.dp.toPx()), Offset(right - 5.dp.toPx(), top + 8.dp.toPx()), stroke)
                drawLine(color, Offset(right - 5.dp.toPx(), top + 8.dp.toPx()), Offset(right - 4.dp.toPx(), top + 4.dp.toPx()), stroke)
                drawLine(color, Offset(right - 4.dp.toPx(), top + 4.dp.toPx()), Offset(right, top + 4.dp.toPx()), stroke)
            }
            PrimaryDestination.MANUAL -> {
                drawLine(color, Offset(left, top), Offset(right - 3.dp.toPx(), top), stroke)
                drawLine(color, Offset(right - 3.dp.toPx(), top), Offset(right - 3.dp.toPx(), bottom - 4.dp.toPx()), stroke)
                drawLine(color, Offset(right - 3.dp.toPx(), bottom - 4.dp.toPx()), Offset(left, bottom - 4.dp.toPx()), stroke)
                drawLine(color, Offset(left, bottom - 4.dp.toPx()), Offset(left, top), stroke)
                drawLine(color, Offset(left + 3.dp.toPx(), top + 4.dp.toPx()), Offset(right - 7.dp.toPx(), top + 4.dp.toPx()), stroke)
                drawLine(color, Offset(left + 3.dp.toPx(), top + 8.dp.toPx()), Offset(right - 7.dp.toPx(), top + 8.dp.toPx()), stroke)
                drawLine(color, Offset(right - 5.dp.toPx(), bottom - 7.dp.toPx()), Offset(right, bottom - 12.dp.toPx()), stroke)
                drawLine(color, Offset(right - 5.dp.toPx(), bottom - 7.dp.toPx()), Offset(right - 7.dp.toPx(), bottom - 12.dp.toPx()), stroke)
            }
        }
    }
}
@Composable
private fun DashboardDestination(
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    layaInferenceLifecycle: LayaInferenceLifecycle,
    scope: DashboardScopeState,
    onScopeChange: (DashboardScopeState) -> Unit,
    notificationAccessStore: NotificationAccessStore,
    stack: List<DashboardRoute>,
    onOpenSettings: () -> Unit,
    onOpenBankNotifications: () -> Unit,
    onOpenModelSetup: () -> Unit,
    onOpenList: (DashboardListScope) -> Unit,
    onOpenRecord: (Long) -> Unit,
    onOpenRecordEdit: (Long) -> Unit,
    onOpenReview: () -> Unit,
    onPop: () -> Unit,
) {
    when (val route = stack.lastOrNull()) {
        null -> DashboardScreen(
            queryService = queryService,
            scope = scope,
            onScopeChange = onScopeChange,
            onOpenList = onOpenList,
            onOpenSettings = onOpenSettings,
            onOpenReview = onOpenReview,
        )

        DashboardRoute.Settings -> SettingsRoot(
            onNavigateUp = onPop,
            onOpenBankNotifications = onOpenBankNotifications,
            onOpenModelSetup = onOpenModelSetup,
        )

        DashboardRoute.BankNotifications -> BankNotificationSettingsScreen(
            accessStore = notificationAccessStore,
            onNavigateUp = onPop,
        )

        DashboardRoute.ModelSetup -> LayaProvisioningScreen(onNavigateUp = onPop)

        is DashboardRoute.ScopedList -> DashboardScopedListScreen(
            queryService = queryService,
            scope = scope,
            listScope = route.listScope,
            onSelectRecord = onOpenRecord,
            onNavigateUp = onPop,
        )

        is DashboardRoute.RecordDetail -> TransactionDetailScreen(
            commandService = commandService,
            queryService = queryService,
            transactionId = route.transactionId,
            onEdit = { onOpenRecordEdit(route.transactionId) },
            // A deleted record has no detail view left to show; the list underneath is
            // the same list the record was opened from.
            onDeleted = onPop,
            onNavigateUp = onPop,
        )

        is DashboardRoute.RecordEdit -> TransactionEditorScreen(
            commandService = commandService,
            queryService = queryService,
            layaInferenceLifecycle = layaInferenceLifecycle,
            transactionId = route.transactionId,
            // A saved correction and a cancelled one both return to the detail view,
            // which re-reads the stored row.
            onNavigateUp = onPop,
        )
    }
}

/**
 * The saveable-state key of one Dashboard route.
 *
 * Each route keeps its own state, so opening a record from a list and coming back returns
 * to the same list at the same page, and the settings pages keep their own state as well.
 */
private fun DashboardRoute.routeKey(): String = when (this) {
    is DashboardRoute.Settings -> "settings"
    is DashboardRoute.BankNotifications -> "bank-notifications"
    is DashboardRoute.ModelSetup -> "model-setup"
    is DashboardRoute.ScopedList -> "scoped-list-${listScope.name}"
    is DashboardRoute.RecordDetail -> "record-detail-$transactionId"
    is DashboardRoute.RecordEdit -> "record-edit-$transactionId"
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

@Composable
private fun ManualDestination(
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    layaInferenceLifecycle: LayaInferenceLifecycle,
    stack: List<ManualRoute>,
    transactionFilters: TransactionFilterState,
    onTransactionFiltersChange: (TransactionFilterState) -> Unit,
    transactionFiltersExpanded: Boolean,
    onTransactionFiltersExpandedChange: (Boolean) -> Unit,
    onRecordTransaction: () -> Unit,
    onBrowseTransactions: () -> Unit,
    onManageCategories: () -> Unit,
    onOpenTransaction: (Long) -> Unit,
    onEditTransaction: (Long) -> Unit,
    onPop: () -> Unit,
) {
    when (val route = stack.lastOrNull()) {
        null -> ManualRoot(
            onRecordTransaction = onRecordTransaction,
            onBrowseTransactions = onBrowseTransactions,
            onManageCategories = onManageCategories,
        )

        ManualRoute.Categories -> CategoryManagerScreen(
            commandService = commandService,
            queryService = queryService,
            onNavigateUp = onPop,
        )

        ManualRoute.TransactionList -> TransactionListScreen(
            queryService = queryService,
            filters = transactionFilters,
            onFiltersChange = onTransactionFiltersChange,
            filtersExpanded = transactionFiltersExpanded,
            onFiltersExpandedChange = onTransactionFiltersExpandedChange,
            onSelect = onOpenTransaction,
            onNavigateUp = onPop,
        )

        is ManualRoute.NewTransaction -> TransactionEditorScreen(
            commandService = commandService,
            queryService = queryService,
            layaInferenceLifecycle = layaInferenceLifecycle,
            transactionId = null,
            sourceEventId = route.sourceEventId,
            onNavigateUp = onPop,
        )

        is ManualRoute.Detail -> TransactionDetailScreen(
            commandService = commandService,
            queryService = queryService,
            transactionId = route.transactionId,
            onEdit = { onEditTransaction(route.transactionId) },
            // A deleted record has no detail view left to show; the list underneath is
            // the same list the record was opened from.
            onDeleted = onPop,
            onNavigateUp = onPop,
        )

        is ManualRoute.Edit -> TransactionEditorScreen(
            commandService = commandService,
            queryService = queryService,
            layaInferenceLifecycle = layaInferenceLifecycle,
            transactionId = route.transactionId,
            // A saved correction and a cancelled one both return to the detail view,
            // which re-reads the stored row.
            onNavigateUp = onPop,
        )
    }
}

/** Manual root: the entry, history and category actions that already existed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualRoot(
    onRecordTransaction: () -> Unit,
    onBrowseTransactions: () -> Unit,
    onManageCategories: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "MANUAL",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                    )
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Record what you spend, earn, and move between your own accounts, in " +
                    "exact whole dong. Open a saved record to correct or delete it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "TRANSACTIONS",
                        style = MaterialTheme.typography.labelLarge,
                        letterSpacing = 1.2.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(shape = MaterialTheme.shapes.small, onClick = onRecordTransaction, modifier = Modifier.fillMaxWidth()) { Text("Record a transaction") }
                    OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onBrowseTransactions,
                    modifier = Modifier.fillMaxWidth(),) { Text("Saved transactions") }
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "CATEGORIES",
                        style = MaterialTheme.typography.labelLarge,
                        letterSpacing = 1.2.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Add, rename, and archive the categories your transactions are " +
                            "filed under.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onManageCategories,
                    modifier = Modifier.fillMaxWidth(),) { Text("Manage categories") }
                }
            }
        }
    }
}

/** The persistent note journal behind the Chat / Quick Capture destination. */
@Composable
private fun ChatDestination(
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    onOpenManual: () -> Unit,
    onRecordTransaction: () -> Unit,
    onRecordEvent: (Long) -> Unit,
) {
    QuickCaptureScreen(
        queryService = queryService,
        commandService = commandService,
        onOpenManual = onOpenManual,
        onRecordTransaction = onRecordTransaction,
        onRecordEvent = onRecordEvent,
    )
}

/**
 * The settings hub behind Dashboard's Settings action: the bank-notification choices and the
 * optional offline model, each on the screen that already owns it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsRoot(
    onNavigateUp: () -> Unit,
    onOpenBankNotifications: () -> Unit,
    onOpenModelSetup: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "SETTINGS",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onNavigateUp) { Text("Back") }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "BANK NOTIFICATIONS",
                        style = MaterialTheme.typography.labelLarge,
                        letterSpacing = 1.2.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Optionally let notifications from your bank apps become " +
                            "reviewable drafts. Manual recording works with or without it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onOpenBankNotifications,
                    modifier = Modifier.fillMaxWidth(),) { Text("Bank notification settings") }
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "OFFLINE MODEL",
                        style = MaterialTheme.typography.labelLarge,
                        letterSpacing = 1.2.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Check the pinned local model or import it from a folder. " +
                            "This is optional; manual transaction entry works either way.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onOpenModelSetup,
                    modifier = Modifier.fillMaxWidth(),) { Text("Model setup") }
                }
            }
        }
    }
}
