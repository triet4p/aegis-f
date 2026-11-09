package io.github.triet4p.aegisf.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.VndMoney
import io.github.triet4p.aegisf.ui.text.LocalAegisStrings
import io.github.triet4p.aegisf.ui.theme.LocalAegisSemanticColors
import kotlinx.coroutines.launch

/** The stored states the shared status primitive can present. */
internal enum class AegisStatus {
    CONFIRMED,
    NEEDS_REVIEW,
    DISMISSED,
    PROPOSED_LINK,
    ATTACHED,
    REJECTED,
    UNLINKED,
}

/**
 * One status, said three ways: a tint, a glyph and a word.
 *
 * A reader who cannot tell rose from amber still reads the triangle and the label, so a
 * financial state is never carried by color alone. The stored status itself stays authoritative;
 * this primitive only renders it.
 */
@Composable
internal fun AegisStatusChip(
    status: AegisStatus,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val strings = LocalAegisStrings.current
    val semantic = LocalAegisSemanticColors.current
    val resolvedLabel = label ?: when (status) {
        AegisStatus.CONFIRMED -> strings.statusConfirmed
        AegisStatus.NEEDS_REVIEW -> strings.statusNeedsReview
        AegisStatus.DISMISSED -> strings.statusDismissed
        AegisStatus.PROPOSED_LINK -> strings.statusProposedLink
        AegisStatus.ATTACHED -> strings.statusAttached
        AegisStatus.REJECTED -> strings.statusRejected
        AegisStatus.UNLINKED -> strings.statusUnlinked
    }
    val container: Color
    val onContainer: Color
    val glyph: AegisGlyph
    when (status) {
        AegisStatus.CONFIRMED,
        AegisStatus.ATTACHED,
        -> {
            container = semantic.incomeContainer
            onContainer = semantic.onIncomeContainer
            glyph = AegisGlyph.CHECK
        }

        AegisStatus.NEEDS_REVIEW -> {
            container = semantic.warningContainer
            onContainer = semantic.onWarningContainer
            glyph = AegisGlyph.WARNING
        }

        AegisStatus.PROPOSED_LINK -> {
            container = semantic.infoContainer
            onContainer = semantic.onInfoContainer
            glyph = AegisGlyph.LINK
        }

        AegisStatus.REJECTED -> {
            container = MaterialTheme.colorScheme.errorContainer
            onContainer = MaterialTheme.colorScheme.onErrorContainer
            glyph = AegisGlyph.CROSS
        }

        AegisStatus.DISMISSED,
        AegisStatus.UNLINKED,
        -> {
            container = MaterialTheme.colorScheme.surfaceVariant
            onContainer = MaterialTheme.colorScheme.onSurfaceVariant
            glyph = AegisGlyph.CROSS
        }
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = onContainer,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .semantics(mergeDescendants = true) { contentDescription = resolvedLabel },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AegisIcon(glyph = glyph, tint = onContainer, size = 12.dp)
            Text(
                text = resolvedLabel,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The amount mask the design system uses wherever a figure is hidden on purpose. */
internal const val AEGIS_MASKED_AMOUNT = "••• ••• ₫"

/**
 * An exact whole-dong amount, with the currency suffix and tabular figures.
 *
 * The digits come from [VndMoney.formatAmount], the one exact formatter: a displayed total and
 * the stored total are the same integer. `overflowed` says the sum was not exact and is a lower
 * bound, so the extra words are part of the value rather than a footnote. A masked amount keeps
 * its place on the line without pretending to be a figure.
 */
@Composable
internal fun AegisMoneyText(
    amountVnd: Long,
    modifier: Modifier = Modifier,
    masked: Boolean = false,
    overflowed: Boolean = false,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    prefix: String = "",
) {
    val strings = LocalAegisStrings.current
    val text = when {
        masked -> AEGIS_MASKED_AMOUNT
        overflowed -> prefix + VndMoney.formatAmount(amountVnd, " ₫") + " " + strings.orMore
        else -> prefix + VndMoney.formatAmount(amountVnd, " ₫")
    }
    Text(
        text = text,
        modifier = modifier.then(
            if (masked) {
                Modifier.semantics { contentDescription = strings.amountsHidden }
            } else {
                Modifier
            },
        ),
        style = style,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * One navigational choice row: a title, the reason it matters, the current value, and a chevron.
 *
 * It is a setting rather than a form field: tapping it opens a focused sheet or a child screen,
 * so a long configuration page never becomes one wall of inputs.
 */
@Composable
internal fun AegisSettingRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    value: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AegisIcon(
            glyph = AegisGlyph.CHEVRON_RIGHT,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 18.dp,
        )
    }
}

/** One option of a [AegisChoiceSheet]. */
internal data class AegisChoice<T>(val value: T, val label: String)

/**
 * A small bottom sheet that asks the user to choose one value (a language, a theme).
 *
 * It is a sheet rather than a new screen because the choice is short, reversible, and belongs to
 * the row that opened it. The sheet owns the keyboard/navigation insets, so its last row is never
 * under the gesture bar. Dismissing it changes nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> AegisChoiceSheet(
    title: String,
    options: List<AegisChoice<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .selectable(
                            selected = option.value == selected,
                            role = Role.RadioButton,
                            onClick = {
                                onSelect(option.value)
                                scope.launch { sheetState.hide() }.invokeOnCompletion {
                                    onDismiss()
                                }
                            },
                        )
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(
                        selected = option.value == selected,
                        onClick = null,
                    )
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/**
 * The unsaved-changes guard: leaving a form with edits asks first, and only an explicit choice
 * discards anything.
 *
 * The shell and the shared form primitives use this one dialog, so "keep editing" and "discard"
 * mean the same thing everywhere. Dismissing the dialog keeps the edits.
 */
@Composable
internal fun AegisDiscardChangesDialog(
    onKeepEditing: () -> Unit,
    onDiscard: () -> Unit,
) {
    val strings = LocalAegisStrings.current
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(strings.discardTitle) },
        confirmButton = {
            TextButton(onClick = onDiscard) { Text(strings.discardConfirm) }
        },
        dismissButton = {
            TextButton(onClick = onKeepEditing) { Text(strings.discardKeepEditing) }
        },
    )
}
