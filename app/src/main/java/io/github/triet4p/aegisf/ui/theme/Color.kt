package io.github.triet4p.aegisf.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The design tokens of the mobile experience: one light and one dark Material color scheme, plus
 * the financial semantic roles the scheme itself cannot express.
 *
 * The Material roles carry the surfaces, text and controls; [AegisSemanticColors] carries what a
 * money screen has to name precisely — spending, income, an internal transfer, a warning, an
 * informational proposal. A status is never told by color alone: every status primitive pairs
 * these colors with a glyph and a label.
 */

internal val AegisLightColorScheme = lightColorScheme(
    primary = Color(0xFF0284C7),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0F172A),
    secondary = Color(0xFF059669),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF064E3B),
    tertiary = Color(0xFFD97706),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF78350F),
    error = Color(0xFFE11D48),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE4E6),
    onErrorContainer = Color(0xFF881337),
    background = Color(0xFFF4F6F9),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F4F8),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
)

internal val AegisDarkColorScheme = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF07131E),
    primaryContainer = Color(0xFF0C344E),
    onPrimaryContainer = Color(0xFFBAE6FD),
    secondary = Color(0xFF34D399),
    onSecondary = Color(0xFF052E23),
    secondaryContainer = Color(0xFF082F24),
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = Color(0xFFFBBF24),
    onTertiary = Color(0xFF291900),
    tertiaryContainer = Color(0xFF362106),
    onTertiaryContainer = Color(0xFFFDE68A),
    error = Color(0xFFFB7185),
    onError = Color(0xFF3B1219),
    errorContainer = Color(0xFF3B1219),
    onErrorContainer = Color(0xFFFECDD3),
    background = Color(0xFF0B0F17),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF131923),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF18202E),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF1E293B),
)

/**
 * The financial roles a money screen names: what was spent, what arrived, what only moved
 * between the user's own accounts, what needs attention, and what is an informational proposal.
 *
 * A container/on-container pair is used for chips and status pills; the plain role is used for
 * an amount or an accent line. These never replace the stored values: the colors describe the
 * same stored type and status the text does.
 */
@Immutable
data class AegisSemanticColors(
    val expense: Color,
    val expenseContainer: Color,
    val onExpenseContainer: Color,
    val income: Color,
    val incomeContainer: Color,
    val onIncomeContainer: Color,
    val transfer: Color,
    val transferContainer: Color,
    val onTransferContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
)

internal val AegisLightSemanticColors = AegisSemanticColors(
    expense = Color(0xFFE11D48),
    expenseContainer = Color(0xFFFFE4E6),
    onExpenseContainer = Color(0xFF881337),
    income = Color(0xFF059669),
    incomeContainer = Color(0xFFD1FAE5),
    onIncomeContainer = Color(0xFF064E3B),
    transfer = Color(0xFF6366F1),
    transferContainer = Color(0xFFEEF2FF),
    onTransferContainer = Color(0xFF312E81),
    warning = Color(0xFFB45309),
    warningContainer = Color(0xFFFEF3C7),
    onWarningContainer = Color(0xFF78350F),
    info = Color(0xFF0284C7),
    infoContainer = Color(0xFFE0F2FE),
    onInfoContainer = Color(0xFF0C4A6E),
)

internal val AegisDarkSemanticColors = AegisSemanticColors(
    expense = Color(0xFFFB7185),
    expenseContainer = Color(0xFF3B1219),
    onExpenseContainer = Color(0xFFFECDD3),
    income = Color(0xFF34D399),
    incomeContainer = Color(0xFF082F24),
    onIncomeContainer = Color(0xFFA7F3D0),
    transfer = Color(0xFF818CF8),
    transferContainer = Color(0xFF1E1B4B),
    onTransferContainer = Color(0xFFC7D2FE),
    warning = Color(0xFFFBBF24),
    warningContainer = Color(0xFF362106),
    onWarningContainer = Color(0xFFFDE68A),
    info = Color(0xFF38BDF8),
    infoContainer = Color(0xFF0C2B42),
    onInfoContainer = Color(0xFFBAE6FD),
)

internal val LocalAegisSemanticColors = staticCompositionLocalOf { AegisLightSemanticColors }
