package com.gbros.tabslite.view.tabview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.gbros.tabslite.R
import com.gbros.tabslite.data.chord.Chord
import com.gbros.tabslite.ui.theme.AppTheme
import com.gbros.tabslite.utilities.isSameTransposition

@Composable
fun TabTransposeSection(
    currentTransposition: Int,
    favoriteTransposition: Int?,
    originalTransposition: Int,
    key: String,
    useFlats: Boolean,
    onTransposeResetClick: () -> Unit,
    onTransposeDownClick: () -> Unit,
    onTransposeUpClick: () -> Unit,
    onFavoriteTransposeToggle: () -> Unit,
    onTransposeToFavoriteClick: () -> Unit,
    onTransposeToOriginalClick: () -> Unit
) {
    // "D (+2)" when the song's key is known, else just "+2"
    fun describe(transposition: Int): String {
        val signed = if (transposition > 0) "+$transposition" else transposition.toString()
        return if (key.isBlank()) signed else "${Chord.transposeChord(key, transposition, useFlats)} ($signed)"
    }
    val isFavorite = isSameTransposition(favoriteTransposition, currentTransposition)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets(
                left = WindowInsets.safeDrawing.asPaddingValues().calculateStartPadding(
                    LayoutDirection.Ltr),
                right = WindowInsets.safeDrawing.asPaddingValues().calculateEndPadding(
                    LayoutDirection.Ltr)
            ))
    ) {
        Row {
            Text(
                text = stringResource(id = R.string.tab_transpose, describe(currentTransposition)),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            IconButton(  // reset transpose
                onClick = onTransposeResetClick,
                modifier = Modifier
            ) {
                Icon(imageVector = Icons.Default.Clear, contentDescription = "Reset Transposition",
                    tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onFavoriteTransposeToggle) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = stringResource(
                        if (isFavorite) R.string.tab_transpose_favorite_remove_accessibility_text
                        else R.string.tab_transpose_favorite_set_accessibility_text
                    ),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(  // transpose down
                onClick = onTransposeDownClick,
                Modifier.padding(horizontal = 8.dp)
            ) {
                Icon(imageVector = ImageVector.vectorResource(id = R.drawable.ic_remove),
                    contentDescription = "Transpose down")
            }
            Button(  // transpose up
                onClick = onTransposeUpClick,
                Modifier.padding(horizontal = 8.dp)
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Transpose up")
            }
        }
        // jump to the original/favourite transposition; the chip matching the current one is selected
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = isSameTransposition(originalTransposition, currentTransposition),
                onClick = onTransposeToOriginalClick,
                label = { Text(stringResource(R.string.tab_transpose_original, describe(originalTransposition))) }
            )
            if (favoriteTransposition != null) {
                FilterChip(
                    selected = isFavorite,
                    onClick = onTransposeToFavoriteClick,
                    label = { Text(stringResource(R.string.tab_transpose_favorite, describe(favoriteTransposition))) },
                    leadingIcon = { Icon(imageVector = Icons.Filled.Star, contentDescription = null) }
                )
            }
        }
    }
}

@Composable @Preview
private fun TabTransposeSectionPreview() {
    AppTheme {
        TabTransposeSection(currentTransposition = 2, favoriteTransposition = 2, originalTransposition = 1,
            key = "C", useFlats = false, {}, {}, {}, {}, {}, {})
    }
}
