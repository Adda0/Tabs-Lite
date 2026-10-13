package com.gbros.tabslite.view.tabview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gbros.tabslite.ui.theme.AppTheme
import com.gbros.tabslite.utilities.RhythmRow
import com.gbros.tabslite.utilities.parseRhythmRows

/**
 * The song's strumming patterns ([rhythm], as rendered by
 * [com.gbros.tabslite.utilities.formatRhythms]) drawn as arrows on the beat grid: big for strong
 * strums (`D`/`U`), small for light ones (`d`/`u`), a dot for a skipped one.
 */
@Composable
fun RhythmDiagram(rhythm: String, modifier: Modifier = Modifier) {
    val rows = parseRhythmRows(rhythm).filter { it.cells.isNotEmpty() }
    if (rows.none { it.name.isNotEmpty() }) return // just the beat header: no pattern known
    // names padded to one width in a monospaced font, so the grids start in the same column
    val nameWidth = rows.maxOf { it.name.length }
    Column(modifier = modifier.widthIn(max = 420.dp)) {
        for (row in rows) RhythmDiagramRow(row, nameWidth)
    }
}

@Composable
private fun RhythmDiagramRow(row: RhythmRow, nameWidth: Int) {
    val barColor = MaterialTheme.colorScheme.outline
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(if (row.name.isEmpty()) 16.dp else 26.dp)) {
        Text(
            text = row.name.padEnd(nameWidth) + " ",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
            softWrap = false
        )
        for (cell in row.cells) {
            cell.forEachIndexed { index, strum ->
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(if (row.name.isEmpty()) 16.dp else 26.dp)
                        .then(if (index == 0) Modifier.drawBehind { drawLine(barColor, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) } else Modifier)
                ) {
                    if (row.name.isEmpty()) BeatLabel(strum) else Strum(strum)
                }
            }
        }
    }
}

@Composable
private fun BeatLabel(beat: String) {
    val isOffBeat = beat == "&"
    Text(
        text = beat,
        fontSize = 11.sp,
        fontWeight = if (isOffBeat) FontWeight.Normal else FontWeight.Bold,
        color = if (isOffBeat) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onBackground
    )
}

@Composable
private fun Strum(strum: String) {
    val (symbol, strong) = when (strum) {
        "D" -> "↓" to true
        "U" -> "↑" to true
        "d" -> "↓" to false
        "u" -> "↑" to false
        "-" -> "·" to false
        else -> strum to false // e.g. vvv (a fan) as written
    }
    Text(
        text = symbol,
        fontSize = if (strong) 22.sp else if (symbol.length == 1) 15.sp else 11.sp,
        fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal,
        color = when {
            strong -> MaterialTheme.colorScheme.primary
            strum == "-" -> MaterialTheme.colorScheme.outline
            else -> MaterialTheme.colorScheme.onBackground
        },
        softWrap = false
    )
}

@Composable @Preview
private fun RhythmDiagramPreview() {
    AppTheme {
        RhythmDiagram(
            "         | 1 & | 2 & | 3 & | 4 &\n" +
            "verse: 8 | d - | d - | D - | d u\n" +
            "r: 3v    | D  vvv  U | - U | D U (klasický táborák)\n" +
            "b: 42"
        )
    }
}
