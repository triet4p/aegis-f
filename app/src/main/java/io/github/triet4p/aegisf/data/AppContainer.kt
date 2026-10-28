package io.github.triet4p.aegisf.data

import android.content.Context
import io.github.triet4p.aegisf.data.laya.LayaClassificationEngine
import io.github.triet4p.aegisf.data.laya.LayaInferenceLifecycle
import io.github.triet4p.aegisf.provider.HostedProviderSettingsStore
import io.github.triet4p.aegisf.agent.AgentRunHistory
import io.github.triet4p.aegisf.agent.AgentToolDeclarations
import io.github.triet4p.aegisf.agent.WorkflowOperationKeySource
import io.github.triet4p.aegisf.agent.WorkflowRunHost
import io.github.triet4p.aegisf.agent.WorkflowSkillCatalog
import io.github.triet4p.aegisf.privacy.MaskedHostedGateway
import io.github.triet4p.aegisf.privacy.MaskedPromptBoundary
import io.github.triet4p.aegisf.privacy.SensitiveVault
import io.github.triet4p.aegisf.provider.HostedAdapters
import io.github.triet4p.aegisf.provider.HostedProviderConfig
import io.github.triet4p.aegisf.provider.UrlConnectionHttpTransport
import java.util.UUID
import java.io.File

/**
 * The application's dependency graph: one Room database for ledger data and explicit
 * remembered category rules, plus the bank-notification preferences.
 *
 * This is the production entry point to the ledger. The public surfaces it hands out are
 * the app's one write surface — the command boundary — and its one read surface — the
 * bounded queries. Building the container does not touch disk; Room opens the database
 * file the first time one of them is used, which keeps app start-up cheap.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    private val layaInferenceDelegate = lazy {
        LayaInferenceLifecycle(File(appContext.filesDir, "laya/model"))
    }

    /** Process-owned, bounded Laya inference work; notification intake never retains it. */
    val layaInferenceLifecycle: LayaInferenceLifecycle
        get() = layaInferenceDelegate.value

    /** Releases process-owned inference resources when the application is terminating. */
    fun close() {
        if (classificationQueueDelegate.isInitialized()) {
            classificationQueueDelegate.value.close()
        }
        if (layaInferenceDelegate.isInitialized()) layaInferenceDelegate.value.close()
        if (databaseDelegate.isInitialized()) databaseDelegate.value.close()
    }

    /**
     * The process's durable classification work for incoming bank drafts.
     *
     * It is the one path that turns a durably persisted draft into a review-only Laya
     * prediction without the user opening the draft. The jobs live in the database
     * (`classification_work`), so the queue holds no work of its own: a process that ends
     * mid-job resumes it on the next start, and a burst of notifications is coalesced per
     * draft instead of piling up in memory.
     */
    private val classificationQueueDelegate = lazy {
        BankDraftClassificationQueue(
            database = database,
            commands = workflowCommandService,
        )
    }

    /** The durable, process-owned work that classifies stored drafts as they arrive. */
    val bankDraftClassificationQueue: BankDraftClassificationQueue
        get() = classificationQueueDelegate.value

    /**
     * Starts the classification worker at process start.
     *
     * The worker resumes the jobs an earlier process left queued or running, so a
     * draft's suggestion survives a restart even when no new notification arrives and no
     * screen is opened. Accessing the queue is what starts it; the queue opens the
     * database on its own worker, so this adds no disk work to the main thread.
     */
    fun startClassificationWork() {
        classificationQueueDelegate.value
    }

    private val databaseDelegate = lazy { LedgerDatabase.build(appContext) }
    private val database: LedgerDatabase by databaseDelegate

    /**
     * The ledger's own storage layer.
     *
     * It is private to this container on purpose: the only public way to change a
     * category, a saved rule, or a ledger record is the command boundary below, which
     * owns the caller's authority, the idempotency of a repeated request, and the
     * revisions the caller read.
     */
    private val ledgerRepository: LedgerRepository by lazy {
        LedgerRepository(
            categoryDao = database.categoryDao(),
            transactionDao = database.transactionDao(),
            rememberedCategoryRuleDao = database.rememberedCategoryRuleDao(),
            captureMessageDao = database.captureMessageDao(),
        )
    }

    /**
     * The app's one write surface: the preparation commands and the financial commands.
     *
     * Every saved capture, every reading, every link decision, every classification
     * request, every manual record, every correction, every removal, and every bank-draft
     * confirmation or dismissal goes through this service: it owns the caller's scope, the
     * idempotency of a repeated request, the expected revisions, and the structured
     * outcome. The local model is reached through the same process-owned lifecycle the
     * screens use, and it is asked only for a request the boundary accepted.
     *
     * The financial commands are the user's own decisions, so they are available to the
     * user's scope alone: another caller is refused before anything is read or written.
     *
     * The repositories that store the captures, the readings, the links, the predictions,
     * and the ledger records are internal to the app module: the command boundary is
     * their only public way in, so no caller can write around its rules.
     */
    val workflowCommandService: WorkflowCommandService by lazy {
        WorkflowCommandService(
            database = database,
            engine = LayaClassificationEngine(layaInferenceLifecycle),
            findRememberedCategory = ledgerRepository::findRememberedCategoryForDescription,
            // The queue is resolved when a command is applied, never while either object
            // is being constructed: the queue needs the command boundary to ask the model,
            // and the boundary needs the queue to reconsider derived work.
            onAcceptedChange = { change -> bankDraftClassificationQueue.onAcceptedChange(change) },
        )
    }

    /**
     * Pending bank-notification drafts: reviewable intake, never ledger records.
     *
     * The stored draft is reported to [bankDraftClassificationQueue] after its transaction
     * commits, so a supported notification records one durable classification job without
     * making intake wait for the model. A re-read of the same delivery re-reports the same
     * draft, which is the app's recovery signal for work a process did not finish.
     */
    val intakeRepository: IntakeRepository by lazy {
        IntakeRepository(
            database = database,
            onDraftStored = bankDraftClassificationQueue::admit,
        )
    }

    /**
     * The app's one read surface: the typed, bounded queries the screens and the later
     * tools read through.
     *
     * The service reads the same database file as the repositories and never writes, so
     * giving it to a screen adds a read path and no other.
     */
    val workflowQueryService: WorkflowQueryService by lazy {
        WorkflowQueryService(database = database)
    }

    /** The user's bank-notification preferences: access choices, not ledger data. */
    val notificationAccessStore: NotificationAccessStore by lazy {
        NotificationAccessStore(appContext)
    }

    /** Device-local encrypted provider profiles; credentials never enter app logs or history. */
    val hostedProviderSettingsStore: HostedProviderSettingsStore by lazy {
        HostedProviderSettingsStore(appContext)
    }

    /** Durable hosted-run metadata remains separate from conversations and business data. */
    val agentRunHistory: AgentRunHistory by lazy { AgentRunHistory(database) }

    /**
     * Builds a run from the selected, already validated configuration. Each call creates a new
     * vault and adapter, so provider changes affect only later runs and never reuse a conversation.
     */
    fun createHostedWorkflowHost(
        scope: WorkflowCallerScope.AgentSession,
        config: HostedProviderConfig,
    ): WorkflowRunHost {
        val boundary = MaskedPromptBoundary(SensitiveVault(scope.scopeId))
        val adapter = HostedAdapters.create(config, UrlConnectionHttpTransport())
        return WorkflowRunHost(
            scope = scope,
            boundary = boundary,
            gateway = MaskedHostedGateway(adapter, boundary),
            declarations = AgentToolDeclarations.load(appContext.assets::open),
            skills = WorkflowSkillCatalog.load(appContext.assets::open),
            commands = workflowCommandService,
            queries = workflowQueryService,
            operationKeys = WorkflowOperationKeySource { UUID.randomUUID().toString() },
        )
    }
}
