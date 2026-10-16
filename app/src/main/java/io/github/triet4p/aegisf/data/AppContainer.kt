package io.github.triet4p.aegisf.data

import android.content.Context

/**
 * The application's dependency graph: one Room database, the repository that reads and
 * writes it, and the bank-notification preferences.
 *
 * This is the production entry point to the ledger, reached from application code with
 * `(application as AegisApplication).container.ledgerRepository`. Building the container
 * does not touch disk; Room opens the database file the first time a repository is used,
 * which keeps app start-up cheap.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    private val database: LedgerDatabase by lazy { LedgerDatabase.build(appContext) }

    val ledgerRepository: LedgerRepository by lazy {
        LedgerRepository(
            categoryDao = database.categoryDao(),
            transactionDao = database.transactionDao(),
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
