package io.github.triet4p.aegisf.data

import android.content.Context
import io.github.triet4p.aegisf.data.laya.LayaClassificationEngine
import io.github.triet4p.aegisf.data.laya.LayaInferenceLifecycle
import java.io.File

/**
 * The application's dependency graph: one Room database for ledger data and explicit
 * remembered category rules, plus the bank-notification preferences.
 *
 * This is the production entry point to the ledger, reached from application code with
 * `(application as AegisApplication).container.ledgerRepository`. Building the container
 * does not touch disk; Room opens the database file the first time a repository is used,
 * which keeps app start-up cheap.
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

    val ledgerRepository: LedgerRepository by lazy {
        LedgerRepository(
            categoryDao = database.categoryDao(),
            transactionDao = database.transactionDao(),
            rememberedCategoryRuleDao = database.rememberedCategoryRuleDao(),
            captureMessageDao = database.captureMessageDao(),
        )
    }
    /** Draft corrections, dismissal, and their atomic ledger confirmation transition. */
    val bankDraftReviewRepository: BankDraftReviewRepository by lazy {
        BankDraftReviewRepository(
            draftDao = database.pendingBankDraftDao(),
            reviewDao = database.bankDraftReviewDao(),
        )
    }

    /**
     * The app's one write surface for the reversible preparation commands.
     *
     * Every saved capture, every reading, every link decision, and every classification
     * request goes through this service: it owns the caller's scope, the idempotency of a
     * repeated request, the expected revisions, and the structured outcome. The local model
     * is reached through the same process-owned lifecycle the screens use, and it is asked
     * only for a request the boundary accepted.
     *
     * The repositories that store the captures, the readings, the links, and the
     * predictions are internal to the app module: the command boundary is their only
     * public way in, so no caller can write around its rules.
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
