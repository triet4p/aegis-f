package io.github.triet4p.aegisf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.ui.theme.LocalAegisSemanticColors

/**
 * One labelled feature launcher: a tinted squircle, a persistent label, and the badge that
 * belongs to that destination.
 *
 * The label is part of the launcher, never a tooltip or a mystery icon: the row announces
 * "Open Ledger" and shows "Ledger". The whole cell is one button with a minimum 48dp touch
 * target, and the label may wrap to two lines at larger text scales instead of being clipped.
 */
internal data class AegisLauncherItem(
    val glyph: AegisGlyph,
    val label: String,
    val description: String,
    val badgeCount: Int? = null,
    val featured: Boolean = false,
    val onClick: () -> Unit,
)

/** The eight labelled launchers, laid out four to a row directly on the screen surface. */
@Composable
internal fun AegisFeatureLauncherGrid(
    items: List<AegisLauncherItem>,
    modifier: Modifier = Modifier,
    columns: Int = 4,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.chunked(columns).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rowItems.forEach { item ->
                    AegisFeatureLauncher(item = item, modifier = Modifier.weight(1f))
                }
                repeat(columns - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AegisFeatureLauncher(item: AegisLauncherItem, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 80.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = item.onClick)
            .semantics {
                contentDescription = item.description
            }
            .padding(horizontal = 2.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            val accent = item.glyph.accent()
            val container: Brush = if (item.featured) {
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        LocalAegisSemanticColors.current.transfer,
                    ),
                )
            } else {
                Brush.linearGradient(listOf(accent.copy(alpha = 0.18f), accent.copy(alpha = 0.12f)))
            }
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(container),
                contentAlignment = Alignment.Center,
            ) {
                AegisIcon(
                    glyph = item.glyph,
                    tint = if (item.featured) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        accent
                    },
                    size = 28.dp,
                )
            }
            val count = item.badgeCount
            if (count != null && count > 0) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-6).dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.tertiary,
                ) {
                    Text(
                        text = count.toString(),
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiary,
                    )
                }
            }
        }
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The tint of one launcher: a stable, theme-aware accent per destination family. */
@Composable
private fun AegisGlyph.accent(): Color {
    val semantic = LocalAegisSemanticColors.current
    return when (this) {
        AegisGlyph.LEDGER -> MaterialTheme.colorScheme.primary
        AegisGlyph.ANALYTICS -> semantic.transfer
        AegisGlyph.NOTES -> MaterialTheme.colorScheme.secondary
        AegisGlyph.CHAT -> MaterialTheme.colorScheme.primary
        AegisGlyph.REVIEW -> MaterialTheme.colorScheme.tertiary
        AegisGlyph.MANUAL -> semantic.info
        AegisGlyph.CATEGORIES -> semantic.expense
        AegisGlyph.SETTINGS -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
}
