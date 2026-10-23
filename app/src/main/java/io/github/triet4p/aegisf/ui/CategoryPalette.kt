package io.github.triet4p.aegisf.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Stable, presentation-only hues keyed by category identity rather than its editable name. */
internal object CategoryPalette {
    private val dark = listOf(
        Color(0xFFF59E0B), // Amber
        Color(0xFF0D9488), // Teal
        Color(0xFF4F46E5), // Indigo
        Color(0xFF9333EA), // Purple
        Color(0xFFE11D48), // Rose
        Color(0xFFEA580C), // Orange
        Color(0xFF16A34A), // Emerald
        Color(0xFF65A30D), // Lime
    )
    private val light = listOf(
        Color(0xFFD97706),
        Color(0xFF0F766E),
        Color(0xFF4338CA),
        Color(0xFF7E22CE),
        Color(0xFFBE123C),
        Color(0xFFC2410C),
        Color(0xFF15803D),
        Color(0xFF4D7C0F),
    )

    /** `floorMod` stays non-negative even when `Long.hashCode()` is Int.MIN_VALUE. */
    fun colorIndex(categoryId: Long): Int = Math.floorMod(categoryId.hashCode(), dark.size)

    fun color(
        categoryId: Long?,
        darkTheme: Boolean,
        archived: Boolean = false,
        directory: Boolean = false,
    ): Color {
        if (categoryId == null) {
            return if (darkTheme) Color(0xFF64748B) else Color(0xFF475569)
        }
        if (archived && !directory) {
            return if (darkTheme) Color(0xFF94A3B8) else Color(0xFF64748B)
        }
        val color = (if (darkTheme) dark else light)[colorIndex(categoryId)]
        return if (archived) color.copy(alpha = 0.4f) else color
    }
}

@Composable
internal fun CategorySwatch(
    categoryId: Long?,
    archived: Boolean = false,
    directory: Boolean = false,
    size: Dp = 12.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(
                color = CategoryPalette.color(
                    categoryId = categoryId,
                    darkTheme = isSystemInDarkTheme(),
                    archived = archived,
                    directory = directory,
                ),
                shape = RoundedCornerShape(2.dp),
            ),
    )
}

/** A visible, textual state badge; archived identity is never communicated by hue alone. */
@Composable
internal fun ArchivedCategoryBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(2.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(
            text = "ARCHIVED",
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
