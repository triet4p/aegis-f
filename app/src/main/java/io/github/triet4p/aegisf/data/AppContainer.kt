package io.github.triet4p.aegisf.data

import android.content.Context

/**
 * The application's dependency graph: one Room database and the repositories that read and
 * write it.
 *
 * This is the production entry point to the ledger, reached from application code with
 * `(application as AegisApplication).container.ledgerRepository`. Building the container
 * does not touch disk; Room opens the database file the first time a repository is used,
 * which keeps app start-up cheap.
 */
class AppContainer(context: Context) {

    private val database: LedgerDatabase by lazy { LedgerDatabase.build(context) }

    val ledgerRepository: LedgerRepository by lazy {
        LedgerRepository(
            categoryDao = database.categoryDao(),
            transactionDao = database.transactionDao(),
        )
    }
}
