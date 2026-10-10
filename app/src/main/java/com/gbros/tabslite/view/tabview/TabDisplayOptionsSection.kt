package com.gbros.tabslite.view.tabview

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gbros.tabslite.R
import com.gbros.tabslite.ui.theme.AppTheme

/**
 * Two-column view toggle and text size controls, displayed alongside [TabTransposeSection]
 */
@Composable
fun TabDisplayOptionsSection(
    twoColumnView: Boolean,
    onTwoColumnViewToggled: (Boolean) -> Unit,
    onTextSizeDecreaseClick: () -> Unit,
    onTextSizeIncreaseClick: () -> Unit
) {
    val decreaseTextSizeDescription = stringResource(id = R.string.tab_text_size_decrease_accessibility_text)
    val increaseTextSizeDescription = stringResource(id = R.string.tab_text_size_increase_accessibility_text)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets(
                left = WindowInsets.safeDrawing.asPaddingValues().calculateStartPadding(LocalLayoutDirection.current),
                right = WindowInsets.safeDrawing.asPaddingValues().calculateEndPadding(LocalLayoutDirection.current)
            ))
    ) {
        // labelled chip: the bare two-bar icon this replaced read as a pause button
        FilterChip(
            selected = twoColumnView,
            onClick = { onTwoColumnViewToggled(!twoColumnView) },
            label = { Text(stringResource(id = R.string.tab_two_column_view_accessibility_text)) },
            leadingIcon = if (twoColumnView) {
                { Icon(imageVector = Icons.Filled.Check, contentDescription = null) }
            } else null,
            modifier = Modifier.padding(start = 4.dp)
        )
        Spacer(modifier = Modifier.weight(1f))
        Button(
            onClick = onTextSizeDecreaseClick,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .semantics { contentDescription = decreaseTextSizeDescription }
        ) {
            Text(text = "A", fontSize = 14.sp)
        }
        Button(
            onClick = onTextSizeIncreaseClick,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .semantics { contentDescription = increaseTextSizeDescription }
        ) {
            Text(text = "A", fontSize = 22.sp)
        }
    }
}

@Composable @Preview(showBackground = true)
private fun TabDisplayOptionsSectionPreview() {
    AppTheme {
        TabDisplayOptionsSection(
            twoColumnView = false,
            onTwoColumnViewToggled = { },
            onTextSizeDecreaseClick = { },
            onTextSizeIncreaseClick = { }
        )
    }
}
