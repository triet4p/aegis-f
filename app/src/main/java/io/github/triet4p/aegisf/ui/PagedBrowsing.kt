package io.github.triet4p.aegisf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The page controls of one bounded list.
 *
 * A bounded read shows one page of its rows, and these controls are how the rest of the
 * rows are reached: "Next" asks the same read for the page after this one, and "Previous"
 * asks for the page before it. The list never grows by appending, so the rows a page
 * holds are the rows that page holds however many pages the user has walked through.
 *
 * The controls say which page is shown, so a user who has walked to the last page of a
 * long list knows that the list ends there and not that the app stopped reading.
 *
 * "Next" is only offered when the read says more rows match than this page holds, and
 * "Previous" only when there is a page before this one, so neither button ever asks for
 * a page that cannot exist.
 */
@Composable
fun PageControls(
    pageIndex: Int,
    hasMore: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onPrevious, enabled = pageIndex > 0) {
            Text("Previous")
        }
        Text(
            text = "Page ${pageIndex + 1}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onNext, enabled = hasMore) {
            Text("Next")
        }
    }
}

/**
 * Keeps a bounded list on a page that holds rows.
 *
 * A live read can lose rows while a later page is shown — a record is deleted, a filter
 * narrows — and the page the user is on can then hold nothing at all. The list steps back
 * to the nearest page that still holds rows instead of showing an empty page that looks
 * like the end of the data.
 *
 * [hasRows] is null while the page is still being read: nothing is decided then, because
 * an empty page that has not arrived yet is not an empty page.
 */
@Composable
fun ClampPageToRows(
    pageIndex: Int,
    hasRows: Boolean?,
    onClamp: (Int) -> Unit,
) {
    LaunchedEffect(pageIndex, hasRows) {
        if (pageIndex > 0 && hasRows == false) {
            onClamp(pageIndex - 1)
        }
    }
}
