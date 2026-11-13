package io.github.triet4p.aegisf.data

import android.content.Context

/**
 * A durable generation of the stored business state.
 *
 * A complete-state replacement advances it. An agent session captures the value when it is
 * admitted and carries it on every command; the command boundary refuses a session whose
 * captured generation no longer matches, so hosted work prepared from the replaced rows can
 * never apply an effect — including an obsolete hosted category — to the restored state.
 *
 * The value lives in ordinary app-private preferences: it is not business data, is never
 * exported, and its only purpose is to make a replacement invalidate earlier sessions.
 */
interface WorkflowStateEpoch {
    /** The generation a new session must capture. */
    fun current(): Long

    /** Advances the generation after a replacement; returns the new value. */
    fun advance(): Long
}

/** [WorkflowStateEpoch] backed by app-private preferences, surviving process restarts. */
class RestoreEpochStore(context: Context) : WorkflowStateEpoch {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    override fun current(): Long = preferences.getLong(KEY_EPOCH, 0L)

    @Synchronized
    override fun advance(): Long {
        val next = current() + 1L
        check(preferences.edit().putLong(KEY_EPOCH, next).commit()) {
            "The replaced-state generation could not be saved."
        }
        return next
    }

    private companion object {
        const val PREFERENCES = "complete_state_generation"
        const val KEY_EPOCH = "epoch"
    }
}
