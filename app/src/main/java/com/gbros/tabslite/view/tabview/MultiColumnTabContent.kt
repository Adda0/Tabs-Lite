package com.gbros.tabslite.view.tabview

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import com.gbros.tabslite.data.FontStyle
import com.gbros.tabslite.data.tab.TabContentBlock

/**
 * Multi-column autoscroll bookkeeping shared between [MultiColumnTabContent] and the autoscroll loop.
 * Plain fields on purpose: written during measurement and read by the loop, they must never
 * trigger recomposition.
 */
class MultiColumnAutoscroll {
    /** Combined height in px of the fixed columns, as last laid out */
    var fixedColumnsHeight = 0

    /** How far (px) autoscroll has "read" through the fixed columns */
    var fixedColumnsProgress = 0f
}

/**
 * Lays [blocks] out in [columnCount] columns: each fixed column is filled with as much content as
 * fits in the visible height - down to single lines, so a block too tall for the remaining space is
 * split rather than leaving a gap. Everything else flows into the last column, which scrolls via
 * [lastColumnScrollState]. Content that fits entirely in the fixed columns stays there. Blocks are
 * measured at the column width with the current text size, so changing the zoom reflows the split.
 */
@Composable
fun MultiColumnTabContent(
    blocks: List<TabContentBlock>,
    columnCount: Int,
    fontSizeSp: Float,
    fontStyle: FontStyle,
    onChordClick: (chord: String) -> Unit,
    lastColumnScrollState: ScrollState,
    autoscroll: MultiColumnAutoscroll,
    modifier: Modifier = Modifier
) {
    require(columnCount >= 2)
    // Oversized lines in fixed columns remain scrollable, like the final column
    val fixedColumnScrollStates = remember(columnCount) { List(columnCount - 1) { ScrollState(0) } }
    SubcomposeLayout(modifier = modifier) { constraints ->
        val fixedColumnWidth = constraints.maxWidth / columnCount
        val columnHeight = constraints.maxHeight

        var measureCount = 0
        fun heightOf(block: TabContentBlock): Int = subcompose("measure-${measureCount++}") {
            TabContentBlockView(block = block, fontSizeSp = fontSizeSp, fontStyle = fontStyle, onChordClick = {})
        }.first().measure(Constraints(maxWidth = fixedColumnWidth)).height

        val partition = partitionMultiColumn(
            blocks = blocks,
            columnCount = columnCount,
            columnHeight = columnHeight,
            heightOf = { heightOf(it) },
            lineEnds = { block -> block.content.text.indices.filter { block.content.text[it] == '\n' } },
            splitAt = { block, end ->
                block.copy(content = block.content.subSequence(0, end)) to
                        TabContentBlock(block.content.subSequence(end + 1, block.content.length), block.tab)
            }
        )
        val columns = partition.columns
        autoscroll.fixedColumnsHeight = partition.fixedColumnsHeight

        val placeables = columns.mapIndexed { columnIndex, columnBlocks ->
            val width = if (columnIndex == columnCount - 1) {
                constraints.maxWidth - fixedColumnWidth * (columnCount - 1)
            } else {
                fixedColumnWidth
            }
            val scrollState = if (columnIndex == columnCount - 1) lastColumnScrollState else fixedColumnScrollStates[columnIndex]
            subcompose("column-$columnIndex") {
                Column(modifier = Modifier.verticalScroll(scrollState)) {
                    columnBlocks.forEach {
                        TabContentBlockView(block = it, fontSizeSp = fontSizeSp, fontStyle = fontStyle, onChordClick = onChordClick)
                    }
                }
            }.first().measure(Constraints.fixed(width, columnHeight))
        }

        layout(constraints.maxWidth, columnHeight) {
            var x = 0
            placeables.forEach { placeable ->
                placeable.placeRelative(x, 0)
                x += placeable.width
            }
        }
    }
}
