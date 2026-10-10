package com.gbros.tabslite.view.tabview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import com.gbros.tabslite.utilities.FavoriteKey
import com.gbros.tabslite.utilities.MAX_CAPO
import com.gbros.tabslite.utilities.isSameTransposition
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TabTransposeSection(
    /** Chord shape transposition from the written key */
    currentTransposition: Int,
    /** The capo the user plays with */
    currentCapo: Int,
    /** The capo the song is written for */
    writtenCapo: Int,
    favorites: List<FavoriteKey>,
    /** Chord shape transposition that, with [writtenCapo], sounds in the original key */
    originalTransposition: Int,
    /** The key the chords are written in, blank if unknown */
    key: String,
    useFlats: Boolean,
    onTransposeResetClick: () -> Unit,
    /** Key down: chord shapes down, same capo */
    onTransposeDownClick: () -> Unit,
    onTransposeUpClick: () -> Unit,
    /** Capo up/down a fret, chord shapes the other way: same sounding key */
    onCapoUpClick: () -> Unit,
    onCapoDownClick: () -> Unit,
    onFavoriteTransposeToggle: () -> Unit,
    /** Plays as a favourite; on the current one, toggles it as the best */
    onTransposeToFavoriteClick: (FavoriteKey) -> Unit,
    onTransposeToOriginalClick: () -> Unit
) {
    // a key [steps] half steps above the written one ("E"), or "+4" when the song's key is unknown
    fun keyName(steps: Int): String =
        if (key.isBlank()) (if (steps > 0) "+$steps" else steps.toString()) else Chord.transposeChord(key, steps, useFlats)
    // "E (capo 2)": the key it sounds in, then the capo
    fun describe(transposition: Int, capo: Int): String =
        keyName(transposition + capo) + if (capo > 0) " (capo $capo)" else ""
    val isFavorite = favorites.any { it.sameAs(currentTransposition, currentCapo) }

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
        Row {  // the key it sounds in
            Text(
                text = stringResource(id = R.string.tab_transpose, keyName(currentTransposition + currentCapo)),
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
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
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
        Row {  // the capo, and the chord shapes that go with it
            Text(
                text = stringResource(id = R.string.tab_capo_chord_shapes, currentCapo, keyName(currentTransposition)),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = onCapoDownClick,
                enabled = currentCapo > 0,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                Icon(imageVector = ImageVector.vectorResource(id = R.drawable.ic_remove),
                    contentDescription = stringResource(R.string.tab_capo_down_accessibility_text))
            }
            Button(
                onClick = onCapoUpClick,
                enabled = currentCapo < MAX_CAPO,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = stringResource(R.string.tab_capo_up_accessibility_text))
            }
        }
        // jump to the original/a favourite transposition; the chip matching the current one is
        // selected, and tapping it again marks it as the best favourite (star)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = isSameTransposition(originalTransposition, currentTransposition) && writtenCapo == currentCapo,
                onClick = onTransposeToOriginalClick,
                label = { Text(stringResource(R.string.tab_transpose_original, describe(originalTransposition, writtenCapo))) }
            )
            for (favorite in favorites) {
                FilterChip(
                    selected = favorite.sameAs(currentTransposition, currentCapo),
                    onClick = { onTransposeToFavoriteClick(favorite) },
                    label = { Text(describe(favorite.transpose, favorite.capo)) },
                    leadingIcon = { Icon(imageVector = if (favorite.best) Icons.Filled.Star else Icons.Filled.Favorite, contentDescription = null) }
                )
            }
        }
    }
}

@Composable @Preview
private fun TabTransposeSectionPreview() {
    AppTheme {
        TabTransposeSection(currentTransposition = 2, currentCapo = 2, writtenCapo = 0,
            favorites = listOf(FavoriteKey(2, 2, best = true), FavoriteKey(4, 0)),
            originalTransposition = 1, key = "C", useFlats = false, {}, {}, {}, {}, {}, {}, {}, {})
    }
}
