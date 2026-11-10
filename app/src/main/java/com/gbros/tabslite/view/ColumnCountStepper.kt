package com.gbros.tabslite.view

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.ui.res.stringResource
import com.gbros.tabslite.R

@Composable
fun ColumnCountStepper(
    columnCount: Int,
    onColumnCountChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { onColumnCountChange(columnCount - 1) },
            enabled = columnCount > 1,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.column_count_decrease_accessibility_text))
        }
        val countDescription = stringResource(R.string.column_count_value_accessibility_text, columnCount)
        Text(
            text = columnCount.toString(),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(min = 24.dp)
                .semantics { contentDescription = countDescription }
        )
        IconButton(onClick = { onColumnCountChange(columnCount + 1) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.column_count_increase_accessibility_text))
        }
    }
}
