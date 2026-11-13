package io.github.triet4p.aegisf

import android.app.Application
import io.github.triet4p.aegisf.data.AppContainer
import io.github.triet4p.aegisf.notification.BankNotificationListener
import io.github.triet4p.aegisf.notification.isNotificationAccessGranted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns the ledger's dependencies for the lifetime of the process.
 *
 * Application code reads the ledger through [container]; nothing else constructs a
 * database, so the schema and repositories are shared by every screen.
 */
class AegisApplication : Application() {
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        backgroundScope.launch {
            // A cutover interrupted by process death is settled before any reconciliation or
            // intake work starts. The command boundary and hosted runs stay closed while its
            // journal is pending, so nothing user-visible can run against a half-applied cutover.
            // A start-up failure must not crash the process: the journal stays, the gate stays
            // closed, and the next start (or the privacy screen) retries it.
            runCatching { container.completeStateRestorer.completeInterruptedRestore() }
            runCatching { container.workflowCommandService.resumePendingArrivalReconciliation() }
            if (isNotificationAccessGranted(this@AegisApplication)) {
                runCatching {
                    BankNotificationListener.requestActiveReconciliation(this@AegisApplication)
                }
            }
        }
    }

    /** Test/emulator lifecycle hook; production process teardown also reclaims native resources. */
    override fun onTerminate() {
        backgroundScope.cancel()
        if (::container.isInitialized) container.close()
        super.onTerminate()
    }
}
