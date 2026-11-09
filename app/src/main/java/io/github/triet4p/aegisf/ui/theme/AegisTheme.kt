package io.github.triet4p.aegisf.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import io.github.triet4p.aegisf.data.AegisLanguage
import io.github.triet4p.aegisf.data.AegisThemeMode
import io.github.triet4p.aegisf.ui.text.LocalAegisStrings
import io.github.triet4p.aegisf.ui.text.aegisStrings

/**
 * The one app theme: the shared color schemes, type scale, shapes, financial roles and shell
 * vocabulary, in the language and light/dark mode the user chose.
 *
 * [AegisThemeMode.SYSTEM] follows the device; [AegisThemeMode.LIGHT] and [AegisThemeMode.DARK]
 * override it for both previewing and reading a phone at night. The language is independent of
 * the device locale: switching it re-provides [LocalAegisStrings] and every shell label and
 * accessibility description follows.
 */
@Composable
fun AegisTheme(
    themeMode: AegisThemeMode = AegisThemeMode.SYSTEM,
    language: AegisLanguage = AegisLanguage.ENGLISH,
    content: @Composable () -> Unit,
) {
    val dark = resolveAegisDarkTheme(themeMode, isSystemInDarkTheme())
    val strings = remember(language) { aegisStrings(language) }
    CompositionLocalProvider(
        LocalAegisStrings provides strings,
        LocalAegisSemanticColors provides if (dark) {
            AegisDarkSemanticColors
        } else {
            AegisLightSemanticColors
        },
    ) {
        MaterialTheme(
            colorScheme = if (dark) AegisDarkColorScheme else AegisLightColorScheme,
            typography = AegisTypography,
            shapes = AegisShapes,
            content = content,
        )
    }
}

/** Whether [themeMode] selects the dark scheme on a device whose system theme is [systemDark]. */
fun resolveAegisDarkTheme(themeMode: AegisThemeMode, systemDark: Boolean): Boolean =
    when (themeMode) {
        AegisThemeMode.SYSTEM -> systemDark
        AegisThemeMode.LIGHT -> false
        AegisThemeMode.DARK -> true
    }
