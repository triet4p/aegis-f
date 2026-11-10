package io.github.triet4p.aegisf.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.ui.components.AegisGlyph
import io.github.triet4p.aegisf.ui.components.AegisIcon
import io.github.triet4p.aegisf.ui.components.AegisMoneyText
import io.github.triet4p.aegisf.ui.components.AegisStatus
import io.github.triet4p.aegisf.ui.components.AegisStatusChip
import io.github.triet4p.aegisf.ui.theme.LocalAegisSemanticColors

/**
 * The digital receipt of one bank draft: who stated it, its own amount and moment, and the
 * stored review state.
 *
 * The amount keeps the bank's direction as a sign on the evidence surface, and the direction is
 * also named in words elsewhere. A debit is the bank's vocabulary, never a ledger type, so this
 * card alone never tells the review form what to record.
 */
@Composable
internal fun DraftReceiptCard(
    draft: PendingBankDraftEntity,
    strings: ReviewStrings,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalAegisSemanticColors.current
    val amountColor = when (draft.direction) {
        io.github.triet4p.aegisf.data.IntakeDirection.CREDIT -> semantic.income
        io.github.triet4p.aegisf.data.IntakeDirection.DEBIT -> semantic.expense
        io.github.triet4p.aegisf.data.IntakeDirection.UNKNOWN -> MaterialTheme.colorScheme.onSurface
    }
    val status = when (draft.reviewStatus) {
        IntakeReviewStatus.PENDING -> AegisStatus.NEEDS_REVIEW
        IntakeReviewStatus.CONFIRMED -> AegisStatus.CONFIRMED
        IntakeReviewStatus.DISMISSED -> AegisStatus.DISMISSED
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(modifier = Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    AegisIcon(
                        glyph = AegisGlyph.LEDGER,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        size = 26.dp,
                    )
                }
            }
            Text(
                text = reviewBankName(draft.bank),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            val amount = draft.amountVnd
            if (amount != null) {
                AegisMoneyText(
                    amountVnd = amount,
                    prefix = strings.directionPrefix(draft.direction),
                    style = MaterialTheme.typography.displaySmall,
                    color = amountColor,
                )
            } else {
                Text(
                    text = strings.amount(draft),
                    style = MaterialTheme.typography.titleMedium,
                    color = semantic.expense,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = strings.statedMoment(draft),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (draft.reviewStatus == IntakeReviewStatus.PENDING) {
                Text(
                    text = strings.status(draft.reviewStatus),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AegisStatusChip(
                        status = status,
                        label = strings.status(draft.reviewStatus),
                    )
                }
            }
        }
    }
}

/**
 * The possible-duplicate notice: the pair is kept, the evidence is not proof, and the other
 * candidate is one explicit tap away.
 */
@Composable
internal fun DuplicateWarningCard(
    body: String,
    actionLabel: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = LocalAegisSemanticColors.current.warningContainer,
        contentColor = LocalAegisSemanticColors.current.onWarningContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AegisIcon(
                    glyph = AegisGlyph.WARNING,
                    tint = LocalAegisSemanticColors.current.onWarningContainer,
                    size = 18.dp,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(onClick = onOpen) { Text(actionLabel) }
        }
    }
}
