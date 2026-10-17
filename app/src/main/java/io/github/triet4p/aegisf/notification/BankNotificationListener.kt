package io.github.triet4p.aegisf.notification

import android.content.Context
import android.util.Log
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.triet4p.aegisf.data.NotificationAccessStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The system-bound bank-notification listener.
 *
 * The manifest binds this service to the system with
 * `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE`, so only the system can deliver
 * notifications to it, and only after the user grants notification access in the system
 * settings. It declares no SMS, accessibility, or notification-posting permission, and it
 * requests none at runtime.
 *
 * Scope (S3.1 setup, S3.2 filtering, S3.3 parsing, S3.4 persistence, S3.5 dedup, S3.7
 * recovery): the service reports its binding state, refreshes the allowlist snapshot, and
 * hands each posted notification to [decideNotificationIntake], which drops anything that
 * is not from an allowlisted package *before* reading a single content field, then rejects
 * one-time passwords, verification and security messages, group summaries, and payloads
 * with no text or with redaction only. A permitted candidate is deterministically parsed
 * and persisted as a pending bank draft through
 * [io.github.triet4p.aegisf.data.IntakeRepository], with every field the bank did not state
 * left explicitly unresolved. A persisted draft is reviewable evidence, never a ledger
 * record: it enters no summary and is never auto-confirmed.
 *
 * After a (re)connect the service also reconciles what is still active on the system side:
 * once the allowlist snapshot is loaded and [connection] is `Connected`, it reads the
 * current active notifications ([activeNotificationsForTest] in production delegates to the
 * system's `getActiveNotifications()`), keeps only that current set through
 * [reconcileActivePosts] — the same allowlist/security/group filter a live callback
 * passes — and stores each survivor through the same persisted delivery/evidence identity,
 * so a re-read post writes nothing, a later equal-evidence post becomes its own flagged
 * candidate, and a confirmed or dismissed draft is never reopened. Anything the system
 * already removed is not claimed or reconstructed: reconciliation is current-active only,
 * never removed-notification history and never a bank statement.
 */
class BankNotificationListener : NotificationListenerService() {
    /**
     * Background dispatcher for the allowlist load and the intake persistence write.
     *
     * Production code keeps the default `Dispatchers.IO`; tests replace it with a
     * controllable dispatcher to pin the delayed-initialization ordering without
     * touching the Android main thread.
     */
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    /**
     * The currently active notifications the connect load reconciles, in system order.
     *
     * Null in production, when the connect load reads the system's
     * `getActiveNotifications()` for this binding; a test supplies the posts its fake
     * system side still shows, or answers null when that side cannot answer at all —
     * the unavailability the platform's own read reports before it has a binding, which
     * [reconcileCurrentActive] handles without reading or claiming anything. A removed
     * notification is simply absent from an answer that was given, and is never
     * reconstructed from one.
     */
    var activeNotificationsForTest: (() -> Array<StatusBarNotification>?)? = null

    private val serviceScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        Log.i(TAG, "onCreate: listener service created")
    }

    override fun onListenerConnected() {
        activeInstance = this
        val appContext = applicationContext
        val activeAnswer = activeNotificationsForTest
        val repository = intakeRepository
        Log.i(TAG, "onListenerConnected: listener connected, loading allowlist snapshot")
        serviceScope.launch(ioDispatcher) {
            val loaded = NotificationAccessStore(appContext).loadAllowedPackages()
            if (!isActive) {
                return@launch
            }
            allowedPackages = loaded
            connectionState.value = ListenerConnection.Connected(System.currentTimeMillis())
            Log.i(TAG, "onListenerConnected: allowlist ready (${loaded.size} packages), draining queue and reconciling")
            drainQueuedIntake()
            reconcileCurrentActive(appContext, activeAnswer, repository)
        }
    }

    override fun onListenerDisconnected() {
        if (activeInstance === this) {
            activeInstance = null
        }
        queuedIntake.clear()
        serviceScope.coroutineContext.cancelChildren()
        connectionState.value = ListenerConnection.Disconnected
        Log.i(TAG, "onListenerDisconnected: listener disconnected")
        val appContext = applicationContext
        if (isNotificationAccessGranted(appContext)) {
            Log.i(TAG, "onListenerDisconnected: notification access remains granted; requesting rebind")
            requestListenerRebind(appContext, accessGranted = true)
        }
    }

    override fun onNotificationPosted(notification: StatusBarNotification?) {
        val posted = notification ?: return
        Log.d(TAG, "onNotificationPosted: package=${posted.packageName}")
        if (connectionState.value !is ListenerConnection.Connected) {
            queuedIntake.add(posted)
            return
        }
        persistPosted(posted)
    }

    override fun onNotificationRemoved(notification: StatusBarNotification?) {
        // A removal deletes nothing: the persisted drafts are stored evidence the user
        // reviews, and reconciliation only ever reads the currently active set — it never
        // reconstructs a removed notification from history.
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        queuedIntake.clear()
        serviceScope.cancel()
        Log.i(TAG, "onDestroy: listener service destroyed")
        super.onDestroy()
    }

    fun reconcileNow(appContext: Context) {
        val activeAnswer = activeNotificationsForTest
        val repository = intakeRepository
        Log.i(TAG, "reconcileNow: active reconciliation requested")
        serviceScope.launch(ioDispatcher) {
            reconcileCurrentActive(appContext, activeAnswer, repository)
        }
    }

    /**
     * Applies the privacy boundary to one posted notification and persists a permitted
     * candidate as a pending draft, off the main thread.
     *
     * The main-thread callback reads nothing and writes nothing: it hands [posted] to
     * this background step, which runs [decideNotificationIntake] (package metadata
     * first, supported content second) and stores only a permitted candidate through
     * [IntakeRepository.storePermitted]. A rejected OTP or security body is never
     * retained, let alone parsed, in any form — the decision carries the reason only,
     * and this step returns without a write.
     */
    private fun persistPosted(posted: StatusBarNotification) {
        serviceScope.launch(ioDispatcher) {
            persistOne(posted, allowedPackages)
        }
    }

    /**
     * Applies the privacy boundary to one posted notification and persists a permitted
     * candidate as a pending draft.
     *
     * The [snapshot] is captured by the caller: the main-thread callback hands the post
     * to the background step with the current snapshot, while the connect load drains
     * the init-window queue in order under the freshly loaded one. Every persisted post
     * — queued, live, or reconciled — passes the same `Connected` guard first, so a
     * revocation that lands before the write still stops it.
     */
    private suspend fun persistOne(posted: StatusBarNotification, snapshot: Set<String>) {
        currentCoroutineContext().ensureActive()
        if (connectionState.value !is ListenerConnection.Connected) {
            return
        }
        val appContext = applicationContext
        val packageName = posted.packageName
        val key = posted.key
        val postTime = posted.postTime
        val decision = decideNotificationIntake(posted, snapshot)
        when (decision) {
            is NotificationIntakeDecision.Permitted -> {
                Log.i(TAG, "persistOne: permitted notification from $packageName (postTime=$postTime); storing draft")
                intakeRepositoryFor(appContext).storePermitted(
                    sourcePackage = packageName,
                    notificationKey = key,
                    postedAtEpochMillis = postTime,
                    content = decision.content,
                )
            }
            is NotificationIntakeDecision.NotPermitted -> {
                Log.d(TAG, "persistOne: dropped notification from $packageName (not in allowlist)")
            }
            is NotificationIntakeDecision.Rejected -> {
                Log.d(TAG, "persistOne: rejected notification from $packageName (reason=${decision.reason})")
            }
        }
    }

    /**
     * Processes init-window posts in arrival order once the allowlist snapshot is ready.
     *
     * Runs on the background dispatcher as the tail of the connect load, so the queued
     * posts are judged against the loaded snapshot in the order they arrived — with
     * current access implied by the binding that delivered them and rechecked by the
     * same connected-state guard every persisted post passes.
     */
    private suspend fun drainQueuedIntake() {
        val queued = ArrayList(queuedIntake)
        queuedIntake.clear()
        val snapshot = allowedPackages
        for (posted in queued) {
            persistOne(posted, snapshot)
        }
    }

    /**
     * Stores the notifications that are still active on the system side after a (re)connect.
     *
     * Runs on the background dispatcher as the tail of the connect load, after the
     * init-window queue: [connectionState] is already `Connected` and [allowedPackages]
     * is the freshly loaded snapshot. Each active post passes [reconcileActivePosts] —
     * the same allowlist/security/group filter a live callback passes — and each survivor
     * is stored through the same persisted delivery/evidence identity, so a re-read post
     * writes nothing, a later equal-evidence post becomes its own flagged candidate, and a
     * confirmed or dismissed draft is never reopened. A disconnect that lands
     * mid-reconciliation stops further writes through the same connected-state guard
     * every persisted post passes; drafts already persisted stay in the database.
     * Anything the system already removed is simply absent from the active set and is
     * never claimed or reconstructed, and an answer the system could not give at all —
     * the platform's null from before it has a binding, or a refusal — reconciles
     * nothing instead of being read as an empty or removed history.
     */
    private suspend fun reconcileCurrentActive(
        appContext: android.content.Context,
        activeAnswer: (() -> Array<StatusBarNotification>?)?,
        repository: io.github.triet4p.aegisf.data.IntakeRepository?,
    ) {
        currentCoroutineContext().ensureActive()
        if (connectionState.value !is ListenerConnection.Connected) {
            return
        }
        val snapshot = allowedPackages
        val candidates = try {
            // The system's own answer for this binding: the test's fake system side when it
            // supplied one, `getActiveNotifications()` otherwise.
            val active: Array<StatusBarNotification>? =
                if (activeAnswer != null) activeAnswer() else activeNotifications
            if (active == null) {
                Log.d(TAG, "reconcileCurrentActive: activeNotifications returned null")
                return
            }
            val filtered = reconcileActivePosts(
                active = active.toList(),
                accessGranted = true,
                allowedPackages = snapshot,
            )
            Log.i(
                TAG,
                "reconcileCurrentActive: active=${active.size}, permitted candidates=${filtered.size}",
            )
            filtered
        } catch (_: SecurityException) {
            Log.w(TAG, "reconcileCurrentActive: SecurityException from activeNotifications read")
            return
        }
        val store = repository ?: intakeRepositoryFor(appContext)
        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()
            if (connectionState.value !is ListenerConnection.Connected) {
                return
            }
            try {
                store.storePermitted(
                    sourcePackage = candidate.sourcePackage,
                    notificationKey = candidate.notificationKey,
                    postedAtEpochMillis = candidate.postedAtEpochMillis,
                    content = candidate.content,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // One unreadable or un-storable active post must not stop the rest; the
                // surviving drafts stay reviewable and the failure stays a miss, not a claim.
                continue
            }
        }
    }

    companion object {
        private const val TAG = "BankNotificationListener"

        @Volatile
        private var activeInstance: BankNotificationListener? = null

        /**
         * Triggers active-notification reconciliation through the currently bound listener,
         * or requests a system rebind if the listener is disconnected while access is granted.
         */
        fun requestActiveReconciliation(context: Context) {
            val listener = activeInstance
            if (listener != null && connectionState.value is ListenerConnection.Connected) {
                listener.reconcileNow(context.applicationContext)
            } else if (isNotificationAccessGranted(context)) {
                Log.i(TAG, "requestActiveReconciliation: listener not connected, requesting rebind")
                requestListenerRebind(context, accessGranted = true)
            }
        }

        private val connectionState =
            MutableStateFlow<ListenerConnection>(ListenerConnection.Disconnected)

        /** The live binding state for the settings screen. */
        val connection: StateFlow<ListenerConnection> = connectionState.asStateFlow()

        /**
         * The allowlist snapshot the callback consults.
         *
         * A snapshot — not a live store read — keeps the system callback free of storage
         * I/O. It is refreshed off the main thread when the system binds the service and
         * pushed in-memory whenever the settings screen saves a change. `Connected`
         * implies the snapshot has been refreshed for this binding.
         */
        @Volatile
        var allowedPackages: Set<String> = emptySet()
            private set

        /** Replaces the callback snapshot after the settings screen saves a change. */
        fun updateAllowedPackages(snapshot: Set<String>) {
            allowedPackages = snapshot.toSet()
        }

        /**
         * Posts that arrived before the allowlist snapshot was ready, in arrival order.
         *
         * The main-thread callback holds only the posted object — never a content field
         * and never a decision made on the transient empty state. The connect load drains
         * this queue once the snapshot is ready; revocation and destroy clear it so no
         * post-revoke intake can be written after the unbind. Already persisted drafts
         * are unaffected: they live in the database, not here.
         */
        private val queuedIntake: MutableList<StatusBarNotification> =
            java.util.Collections.synchronizedList(mutableListOf())

        /**
         * The intake repository the background persistence step writes through.
         *
         * Production code builds the real Room-backed repository on first use; tests
         * replace it with an in-memory recorder to assert the callback-to-persistence
         * wiring without touching disk from the main thread.
         */
        @Volatile
        var intakeRepository: io.github.triet4p.aegisf.data.IntakeRepository? = null
            private set

        /**
         * Replaces the intake repository (tests only). Passing null restores the lazy
         * production repository built from the given context.
         */
        fun updateIntakeRepository(repository: io.github.triet4p.aegisf.data.IntakeRepository?) {
            intakeRepository = repository
        }

        private fun intakeRepositoryFor(context: android.content.Context) =
            intakeRepository ?: io.github.triet4p.aegisf.data.IntakeRepository(
                io.github.triet4p.aegisf.data.LedgerDatabase.build(context.applicationContext)
                    .pendingBankDraftDao(),
            )
    }
}
