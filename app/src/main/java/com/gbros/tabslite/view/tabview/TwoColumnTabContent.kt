package com.gbros.tabslite.view.tabview
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import com.gbros.tabslite.data.FontStyle
import com.gbros.tabslite.data.tab.TabContentBlock
/**
 * Lays [blocks] out like a printed songbook: the left column is filled with as many blocks as fit
 * in the visible height, the rest continues at the top of the right column, and whatever still
 * doesn't fit starts a further two-column page below, reached by scrolling [scrollState] one
 * screen down. Blocks are measured at the column width with the current text size, so changing
 * the zoom reflows the pages. A single block taller than the screen gets a column of its own.
 */
@Composable
fun TwoColumnTabContent(
    blocks: List<TabContentBlock>,
    fontSizeSp: Float,
    fontStyle: FontStyle,
    onChordClick: (chord: String) -> Unit,
    scrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    SubcomposeLayout(modifier = modifier) { constraints ->
        val columnWidth = constraints.maxWidth / 2
        val pageHeight = constraints.maxHeight
        val heights = blocks.mapIndexed { index, block ->
            subcompose("measure-$index") {
                TabContentBlockView(block = block, fontSizeSp = fontSizeSp, fontStyle = fontStyle, onChordClick = {})
            }.first().measure(Constraints(maxWidth = columnWidth)).height
        }

        // greedily fill columns up to the visible height
        val columns = mutableListOf<List<TabContentBlock>>()
        var current = mutableListOf<TabContentBlock>()
        var currentHeight = 0
        blocks.forEachIndexed { index, block ->
            if (current.isNotEmpty() && currentHeight + heights[index] > pageHeight) {
                columns.add(current)
                current = mutableListOf()
                currentHeight = 0
            }
            current.add(block)
            currentHeight += heights[index]
        }
        if (current.isNotEmpty()) columns.add(current)
        val pages = columns.chunked(2)

        val columnWidthDp = columnWidth.toDp()
        val pageHeightDp = pageHeight.toDp()
        val content = subcompose("pages") {
            Column(modifier = Modifier.verticalScroll(scrollState)) {
                pages.forEachIndexed { pageIndex, page ->
                    // every page but the last spans a full screen, so scrolling one screen lands on the next page
                    val rowModifier = if (pageIndex < pages.lastIndex) Modifier.heightIn(min = pageHeightDp) else Modifier
                    Row(modifier = rowModifier) {
                        page.forEach { columnBlocks ->
                            Column(modifier = Modifier.width(columnWidthDp)) {
                                columnBlocks.forEach {
                                    TabContentBlockView(block = it, fontSizeSp = fontSizeSp, fontStyle = fontStyle, onChordClick = onChordClick)
                                }
                            }
                        }
                    }
                }
            }
        }.first().measure(Constraints.fixed(constraints.maxWidth, pageHeight))

        layout(constraints.maxWidth, pageHeight) {
            content.placeRelative(0, 0)
        }
    }
}
