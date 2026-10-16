package io.github.triet4p.aegisf.data

import android.content.Context
import android.content.SharedPreferences

/**
 * The user's bank package name, as stored in the notification allowlist.
 *
 * A stored value is the exact Android application id of a bank app the user chose — never a
 * display name, never a guess. Validation happens here, before anything is persisted, so a
 * refused value stores nothing.
 */
const val MAX_BANK_PACKAGE_NAME_LENGTH = 255

private val BANK_PACKAGE_NAME_PATTERN =
    Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

/**
 * Checks a user-typed bank package name and returns it trimmed.
 *
 * The grammar is the application-id shape (at least two dot-separated parts, each starting
 * with a letter), which rejects display names, blank input, and single words while accepting
 * every real package name. Matching later is exact and case-sensitive: what is stored here
 * is what the listener compares against.
 */
fun requireBankPackageName(input: String): String {
    val name = input.trim()
    if (name.isEmpty()) {
        throw LedgerValidationException("A bank app package name is required.")
    }
    if (name.length > MAX_BANK_PACKAGE_NAME_LENGTH) {
        throw LedgerValidationException(
            "A bank app package name is at most $MAX_BANK_PACKAGE_NAME_LENGTH characters.",
        )
    }
    if (!BANK_PACKAGE_NAME_PATTERN.matches(name)) {
        throw LedgerValidationException(
            "A package name looks like two or more dot-separated parts starting " +
                "with a letter, without spaces.",
        )
    }
    return name
}

/**
 * The persisted bank-notification preferences: which bank packages may become draft sources.
 *
 * This is a small key-value file, not ledger data: the allowlist is a user choice, and it
 * lives beside the ledger rather than in it. Reads are direct SharedPreferences reads, so
 * they are safe to call from the UI thread and from the listener's refresh path; writes
 * use `apply()` and are visible to every later read in the process.
 *
 * An empty allowlist is the default and means nothing may be processed. Removing a package
 * only narrows future intake; it never touches stored ledger records.
 */
class NotificationAccessStore(context: Context) {

    private val preferences: SharedPreferences by lazy {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    }

    /** The stored allowlist, in a stable sorted order for display. Never null. */
    fun loadAllowedPackages(): Set<String> =
        preferences.getStringSet(KEY_ALLOWED_PACKAGES, emptySet())?.toSortedSet()
            ?: sortedSetOf()

    /**
     * Adds a user-typed package name and returns the stored value.
     *
     * Adding a name that is already stored keeps it once. A refused name throws
     * [LedgerValidationException] and stores nothing.
     */
    fun addPackage(rawInput: String): String {
        val name = requireBankPackageName(rawInput)
        saveAllowedPackages(loadAllowedPackages() + name)
        return name
    }

    /**
     * Removes a package name. Returns false when it was not stored; the allowlist is
     * unchanged in that case.
     */
    fun removePackage(packageName: String): Boolean {
        val current = loadAllowedPackages()
        if (!current.contains(packageName)) {
            return false
        }
        saveAllowedPackages(current - packageName)
        return true
    }

    private fun saveAllowedPackages(packages: Set<String>) {
        preferences.edit().putStringSet(KEY_ALLOWED_PACKAGES, packages.toSet()).apply()
    }

    companion object {
        /** Preference file holding the bank-notification choices. */
        const val FILE_NAME = "bank_notification_settings"

        private const val KEY_ALLOWED_PACKAGES = "allowed_bank_packages"
    }
}
