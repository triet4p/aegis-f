package io.github.triet4p.aegisf.data

import android.content.Context
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
        )
    }
    /** Draft corrections, dismissal, and their atomic ledger confirmation transition. */
    val bankDraftReviewRepository: BankDraftReviewRepository by lazy {
        BankDraftReviewRepository(
            draftDao = database.pendingBankDraftDao(),
            reviewDao = database.bankDraftReviewDao(),
        )
    }


    /** Pending bank-notification drafts: reviewable intake, never ledger records. */
    val intakeRepository: IntakeRepository by lazy {
        IntakeRepository(draftDao = database.pendingBankDraftDao())
    }

    /** The user's bank-notification preferences: access choices, not ledger data. */
    val notificationAccessStore: NotificationAccessStore by lazy {
        NotificationAccessStore(appContext)
    }
}
