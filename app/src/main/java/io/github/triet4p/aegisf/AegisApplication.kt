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
            container.workflowCommandService.resumePendingArrivalReconciliation()
        }
        if (isNotificationAccessGranted(this)) {
            BankNotificationListener.requestActiveReconciliation(this)
        }
    }

    /** Test/emulator lifecycle hook; production process teardown also reclaims native resources. */
    override fun onTerminate() {
        backgroundScope.cancel()
        if (::container.isInitialized) container.close()
        super.onTerminate()
    }
}
