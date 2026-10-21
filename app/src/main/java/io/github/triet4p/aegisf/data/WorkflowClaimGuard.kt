package io.github.triet4p.aegisf.data

import java.util.concurrent.ConcurrentHashMap

/**
 * The in-process proof that an attempt that holds an idempotency key is still alive.
 *
 * The durable claim of a request is a row with a lease, and the lease is extended while
 * the work runs. That extension is a write, and a write can fail or arrive after the
 * lease has ended. When it does, the lease says nothing about whether the attempt that
 * holds the key is still working, and a second attempt may take the key over and ask
 * the model a second time for one request.
 *
 * This guard is the other half of the claim. It lives in the process, so a database
 * that cannot be written does not lose it, and it dies with the process, so a request
 * whose attempt was interrupted — a crash, a device that was switched off — can still
 * be taken over after a restart. The app runs in one process, so the guard covers every
 * attempt that can compete for a key.
 *
 * The guard says that an attempt is alive; it says nothing about what that attempt is
 * doing, and it never leaves the process.
 *
 * Three rules make the guard safe to use as the proof:
 *
 * - **A key is never taken away from the attempt that holds it.** An attempt that asks
 *   for a key that is held is told that the key is not its own, and it does no work.
 *   A claim that was taken over once must not be taken over again by an attempt that
 *   comes back later: the model must not be asked a second time for one request.
 * - **The key is taken before the durable claim is written.** The row and the guard
 *   must never name two different owners, and there must be no moment in which the row
 *   exists while the guard names nobody: a competitor that finds such a row would take
 *   it over while its owner is still alive.
 * - **The reservation says what the attempt that holds the key is running.** The
 *   durable claim is written after the key is taken, so there is a window in which the
 *   claim of a running request does not exist yet. A competitor that arrives in that
 *   window has no record to compare its request with, and without the reservation it
 *   would be answered as running whatever it asked for — including for a request or a
 *   caller that the durable claim would have refused as a conflict. The reservation
 *   carries the two values the durable claim compares, so both windows answer alike.
 */
internal object WorkflowClaimGuard {
    /**
     * What one held key says about the attempt that holds it.
     *
     * The token is the attempt's own, the scope is the caller it acts for, and the
     * digest is the request it is running. The scope and the digest are the very two
     * values the durable claim of the same request compares, so a competitor that
     * arrives before that claim is written is answered the way the claim itself would
     * answer it.
     *
     * Neither value names a protected row: the scope is the caller's own identity, and
     * the digest is a one-way digest of the request — the same value the durable claim
     * already stores. A competitor learns only whether the key names another request,
     * never who holds it, what that request was, or whether it was ever applied.
     */
    private data class Reservation(
        val ownerToken: String,
        val scopeId: String,
        val payloadDigest: String,
    )

    /** What an attempt that asks for a key is told. */
    sealed interface Acquisition {
        /** The key is this attempt's to work on. */
        data object Acquired : Acquisition

        /**
         * Another attempt holds the key.
         *
         * The reservation of that attempt is carried along, so that the contender can
         * be answered the way the durable claim of the same request would answer it.
         */
        data class Held(val scopeId: String, val payloadDigest: String) : Acquisition
    }

    private val held = ConcurrentHashMap<String, Reservation>()

    /**
     * The points of a claim's life at which an attempt may be held.
     *
     * A claim is taken in two steps — the key is taken in the process, and the durable
     * row is written — and the model is asked in a third. The host tests hold an
     * attempt at exactly those points, so that a competing attempt runs against an
     * attempt that is paused there. The app never holds an attempt.
     */
    enum class Point {
        /** The attempt holds the key in the process; the durable claim is not written yet. */
        KEY_TAKEN,

        /** The durable claim names this attempt; the model is not asked yet. */
        CLAIM_WRITTEN,
    }

    /**
     * The hook that holds an attempt at a point of its claim's life.
     *
     * It is null in the app: nothing outside the host tests ever sets it, and the
     * command boundary behaves the same whether it is set or not.
     */
    @Volatile
    var pointHook: (suspend (String, Point) -> Unit)? = null

    /**
     * Takes a key for one attempt, or reports that another attempt holds it.
     *
     * The key is taken only when no attempt holds it, so a live owner is never
     * overwritten: an attempt that comes back after its claim was taken over finds the
     * key held by the attempt that took it, and it does not do the work. The attempt
     * that already holds the key keeps it, so taking a key twice is not an error.
     *
     * The reservation of the attempt that takes the key says which caller it acts for
     * and which request it is running, so that a competitor that arrives before the
     * durable claim of that request is written can be answered the way the claim would
     * answer it.
     *
     * @return whether the key is this attempt's to work on, and who holds it if not.
     */
    fun acquire(
        key: String,
        ownerToken: String,
        scopeId: String,
        payloadDigest: String,
    ): Acquisition {
        val current = held.putIfAbsent(
            key,
            Reservation(
                ownerToken = ownerToken,
                scopeId = scopeId,
                payloadDigest = payloadDigest,
            ),
        )
        return when {
            current == null -> Acquisition.Acquired
            current.ownerToken == ownerToken -> Acquisition.Acquired
            else -> Acquisition.Held(
                scopeId = current.scopeId,
                payloadDigest = current.payloadDigest,
            )
        }
    }

    /**
     * Gives a key back, when this attempt is the one that holds it.
     *
     * The token is part of the release, so an attempt that lost its claim never takes
     * the key away from the attempt that took it over.
     */
    fun release(key: String, ownerToken: String) {
        held.computeIfPresent(key) { _, reservation ->
            if (reservation.ownerToken == ownerToken) null else reservation
        }
    }

    /** Whether a live attempt in this process holds the key. */
    fun isHeld(key: String): Boolean = held.containsKey(key)

    /**
     * Holds this attempt at a point of its claim's life, when the tests asked for it.
     *
     * The hook is a suspending one, so a test can hold an attempt without blocking a
     * thread of the dispatcher the attempt runs on.
     */
    suspend fun holdAt(key: String, point: Point) {
        pointHook?.invoke(key, point)
    }

    /**
     * Forgets every held key and every hook.
     *
     * The guard lives in the process and outlives the database it belongs to, so the
     * host tests clear it between their cases: a case that leaves a key held would
     * otherwise keep the next case from taking it, and the next case would fail for a
     * reason that has nothing to do with what it is testing.
     */
    fun reset() {
        held.clear()
        pointHook = null
    }
}
