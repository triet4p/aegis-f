package io.github.triet4p.aegisf.data

import android.content.Context
import io.github.triet4p.aegisf.data.laya.LayaClassificationEngine
import io.github.triet4p.aegisf.data.laya.LayaInferenceLifecycle
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
        if (layaInferenceDelegate.isInitialized()) layaInferenceDelegate.value.close()
        if (databaseDelegate.isInitialized()) databaseDelegate.value.close()
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
        )
    }

    /** Pending bank-notification drafts: reviewable intake, never ledger records. */
    val intakeRepository: IntakeRepository by lazy {
        IntakeRepository(database = database)
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
}
