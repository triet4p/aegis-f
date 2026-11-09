package io.github.triet4p.aegisf.data

import android.content.Context
import android.content.SharedPreferences

/**
 * The interface language the app presents, in addition to whatever the device locale is.
 *
 * English is the baseline every string is authored in; Vietnamese is a full translation of the
 * shell and the shared design-system vocabulary. The stored value is a stable token, never the
 * display name, so a translation of the name can never change what is stored.
 */
enum class AegisLanguage(val storedValue: String) {
    ENGLISH("en"),
    VIETNAMESE("vi"),
    ;

    companion object {
        fun fromStored(value: String?): AegisLanguage =
            entries.firstOrNull { it.storedValue == value } ?: ENGLISH
    }
}

/** How the app chooses between its light and dark color schemes. */
enum class AegisThemeMode(val storedValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun fromStored(value: String?): AegisThemeMode =
            entries.firstOrNull { it.storedValue == value } ?: SYSTEM
    }
}

/**
 * The user's app-wide presentation choices: interface language and theme mode.
 *
 * These are presentation preferences, not ledger data and not credentials: they live in a small
 * key-value file beside the ledger. Reads are direct `SharedPreferences` reads, so they are safe
 * on the UI thread; writes use `apply()` and are visible to every later read in the process, and
 * survive the process being recreated.
 */
class AegisUiPreferencesStore(context: Context) {

    private val preferences: SharedPreferences by lazy {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    }

    /** The stored interface language; English when nothing is stored yet. Never null. */
    fun loadLanguage(): AegisLanguage =
        AegisLanguage.fromStored(preferences.getString(KEY_LANGUAGE, null))

    fun saveLanguage(language: AegisLanguage) {
        preferences.edit().putString(KEY_LANGUAGE, language.storedValue).apply()
    }

    /** The stored theme mode; follow the system theme when nothing is stored yet. Never null. */
    fun loadThemeMode(): AegisThemeMode =
        AegisThemeMode.fromStored(preferences.getString(KEY_THEME_MODE, null))

    fun saveThemeMode(mode: AegisThemeMode) {
        preferences.edit().putString(KEY_THEME_MODE, mode.storedValue).apply()
    }

    companion object {
        /** Preference file holding the presentation choices. */
        const val FILE_NAME = "aegis_ui_preferences"

        private const val KEY_LANGUAGE = "language"
        private const val KEY_THEME_MODE = "theme_mode"
    }
}
