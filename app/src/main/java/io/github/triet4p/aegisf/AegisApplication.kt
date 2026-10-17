package io.github.triet4p.aegisf

import android.app.Application
import io.github.triet4p.aegisf.notification.BankNotificationListener
import io.github.triet4p.aegisf.notification.isNotificationAccessGranted
import io.github.triet4p.aegisf.data.AppContainer

/**
 * Owns the ledger's dependencies for the lifetime of the process.
 *
 * Application code reads the ledger through [container]; nothing else constructs a
 * database, so the schema and repositories are shared by every screen.
 */
class AegisApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        if (isNotificationAccessGranted(this)) {
            BankNotificationListener.requestActiveReconciliation(this)
        }
    }

    /** Test/emulator lifecycle hook; production process teardown also reclaims native resources. */
    override fun onTerminate() {
        if (::container.isInitialized) container.close()
        super.onTerminate()
    }
}
