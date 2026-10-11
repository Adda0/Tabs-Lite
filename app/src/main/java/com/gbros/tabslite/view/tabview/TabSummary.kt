package com.gbros.tabslite.view.tabview

import android.icu.text.CompactDecimalFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.gbros.tabslite.R
import com.gbros.tabslite.data.tab.ITab
import com.gbros.tabslite.data.tab.Tab
import com.gbros.tabslite.ui.theme.AppTheme
import com.gbros.tabslite.view.taborigin.tabVersionLabel
import com.gbros.tabslite.view.ratingicon.ProportionallyFilledStar
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TabSummary(
    difficulty: String,
    tuning: String,
    capo: String,
    key: String,
    author: String,
    version: Int,
    tabId: String,
    songVersions: List<ITab>,
    onNavigateToTabById: (String) -> Unit,
    tempo: String = "",
    rhythm: String = "",
    tags: List<String> = emptyList(),
    /** Opens the song lists filtered by this tag */
    onTagClick: (String) -> Unit = {}) {
    var versionDropdownExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .windowInsetsPadding(
                WindowInsets(
                    left = WindowInsets.safeDrawing.asPaddingValues().calculateStartPadding(
                        LayoutDirection.Ltr
                    ),
                    right = WindowInsets.safeContent.asPaddingValues().calculateEndPadding(
                        LayoutDirection.Ltr
                    )
                )
            )
            .fillMaxWidth(),
    ) {
        // everything short on one wrapping line next to the versions dropdown
        val metaParts = buildList {
            if (difficulty.isNotBlank()) add(stringResource(id = R.string.tab_difficulty, difficulty))
            if (tuning.isNotBlank()) add(stringResource(id = R.string.tab_tuning, tuning))
            if (capo.isNotBlank()) add(stringResource(id = R.string.tab_capo, capo))
            if (key.isNotBlank()) add(stringResource(id = R.string.tab_key, key))
            if (tempo.isNotBlank()) add(stringResource(id = R.string.tab_tempo, tempo))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = metaParts.joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            // versions dropdown to switch versions of this song
            ExposedDropdownMenuBox(
                expanded = versionDropdownExpanded,
                onExpandedChange = { versionDropdownExpanded = !versionDropdownExpanded },
                modifier = Modifier
                    .width(170.dp)
                    .padding(start = 8.dp)
            ) {
                TextField(
                    value = songVersions.firstOrNull { it.tabId == tabId }?.let { tabVersionLabel(it) } ?: "Version $version",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = versionDropdownExpanded) },
                    colors = ExposedDropdownMenuDefaults.textFieldColors(),
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                )
                ExposedDropdownMenu(
                    expanded = versionDropdownExpanded,
                    onDismissRequest = { versionDropdownExpanded = false }
                ) {
                    songVersions.forEach { selectionOption ->
                        DropdownMenuItem(
                            text = {
                                Row (
                                    modifier = Modifier.height(24.dp)
                                ) {
                                    Text(tabVersionLabel(selectionOption))
                                    if (selectionOption.votes > 0) {
                                        Spacer(Modifier.weight(1f))
                                        val numStars = String.format(Locale.getDefault(), "%.1f", selectionOption.rating)
                                        Text(numStars, modifier = Modifier.padding(horizontal = 4.dp))
                                        ProportionallyFilledStar(fillPercentage = (selectionOption.rating / 5.0).toFloat().coerceIn(0f, 1f), modifier = Modifier.width(18.dp))
                                        Text("(${roundToThousands(selectionOption.votes)})", modifier = Modifier.padding(start = 4.dp))
                                    }
                                }
                            },
                            onClick = {
                                onNavigateToTabById(selectionOption.tabId)
                                versionDropdownExpanded = false
                            }
                        )
                    }
                }
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.Center
        ) {
            if (author.isNotBlank()) {
                Text(
                    text = stringResource(id = R.string.tab_author, author),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
            for (tag in tags) {
                AssistChip(onClick = { onTagClick(tag) }, label = { Text("#$tag") })
            }
        }
        if (rhythm.isNotBlank()) {
            // monospaced so the strums line up under the beat header
            Text(
                text = rhythm,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

fun roundToThousands(number: Int): String {
    val formatter = CompactDecimalFormat.getInstance(Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT)

    if (number < 1000) return formatter.format(number)
    return formatter.format((number / 1000) * 1000)
}

@Composable @Preview
private fun TabSummaryPreview() {
    val tab = Tab(
        tabId = "0",
        songName = "Three Little Birds and a lot lot more long title",
        artistName = "Bob Marley with a long artist name as well",
        version = 1
    )

    AppTheme {
        TabSummary(
            difficulty = "expert",
            tuning = "E A D G B E",
            capo = "2nd Fret",
            key = "C",
            author = "Joe Blow",
            version = 1,
            tabId = tab.tabId,
            songVersions = listOf(tab),
            tempo = "4/4",
            rhythm = "         | 1 & | 2 & | 3 & | 4 &\nverse: 8 | d - | d - | D - | d u\nr: 4     | D - | D U | D U | D U",
            onNavigateToTabById = {}
        )
    }
}