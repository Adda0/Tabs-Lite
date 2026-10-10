package com.gbros.tabslite.view.tabview
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import com.gbros.tabslite.data.FontStyle
import com.gbros.tabslite.data.tab.TabContentBlock

/**
 * Two-column autoscroll bookkeeping shared between [TwoColumnTabContent] and the autoscroll loop.
 * Plain fields on purpose: written during measurement and read by the loop, they must never
 * trigger recomposition.
 */
class TwoColumnAutoscroll {
    /** Height in px of the content in the fixed left column, as last laid out */
    var leftColumnHeight = 0

    /** How far (px) autoscroll has "read" through the left column; the right column moves once this reaches [leftColumnHeight] */
    var leftColumnProgress = 0f
}

/**
 * Lays [blocks] out in two columns: the left column is filled with as much content as fits in the
 * visible height - down to single lines, so a block too tall for the remaining space is split
 * rather than leaving a gap - and stays put; everything else flows into the right column, which
 * scrolls via [rightColumnScrollState]. Content that fits entirely in the left column stays there.
 * Blocks are measured at the column width with the current text size, so changing the zoom
 * reflows the split.
 */
@Composable
fun TwoColumnTabContent(
    blocks: List<TabContentBlock>,
    fontSizeSp: Float,
    fontStyle: FontStyle,
    onChordClick: (chord: String) -> Unit,
    rightColumnScrollState: ScrollState,
    autoscroll: TwoColumnAutoscroll,
    modifier: Modifier = Modifier
) {
    // only needed when a single line is taller than the screen
    val leftColumnScrollState = rememberScrollState()
    SubcomposeLayout(modifier = modifier) { constraints ->
        val leftColumnWidth = constraints.maxWidth / 2
        val rightColumnWidth = constraints.maxWidth - leftColumnWidth
        val columnHeight = constraints.maxHeight

        var measureCount = 0
        fun heightOf(block: TabContentBlock): Int = subcompose("measure-${measureCount++}") {
            TabContentBlockView(block = block, fontSizeSp = fontSizeSp, fontStyle = fontStyle, onChordClick = {})
        }.first().measure(Constraints(maxWidth = leftColumnWidth)).height

        val leftBlocks = mutableListOf<TabContentBlock>()
        val rightBlocks = mutableListOf<TabContentBlock>()
        var leftHeight = 0
        for ((index, block) in blocks.withIndex()) {
            val height = heightOf(block)
            if (leftBlocks.isEmpty() || leftHeight + height <= columnHeight) {
                leftBlocks.add(block)
                leftHeight += height
                continue
            }
            // doesn't fit whole: move as many of its leading lines as fit into the left column
            val lineEnds = block.content.text.indices.filter { block.content.text[it] == '\n' }
            val split = lineEnds.asReversed().firstNotNullOfOrNull { end ->
                val head = TabContentBlock(block.content.subSequence(0, end), block.tab)
                val headHeight = heightOf(head)
                if (leftHeight + headHeight <= columnHeight) Triple(head, headHeight, end) else null
            }
            if (split != null) {
                val (head, headHeight, end) = split
                leftBlocks.add(head)
                leftHeight += headHeight
                rightBlocks.add(TabContentBlock(block.content.subSequence(end + 1, block.content.length), block.tab))
            } else {
                rightBlocks.add(block)
            }
            rightBlocks.addAll(blocks.subList(index + 1, blocks.size))
            break
        }
        autoscroll.leftColumnHeight = minOf(leftHeight, columnHeight)

        val leftPlaceable = subcompose("left") {
            Column(modifier = Modifier.verticalScroll(leftColumnScrollState)) {
                leftBlocks.forEach {
                    TabContentBlockView(block = it, fontSizeSp = fontSizeSp, fontStyle = fontStyle, onChordClick = onChordClick)
                }
            }
        }.first().measure(Constraints.fixed(leftColumnWidth, columnHeight))
        val rightPlaceable = subcompose("right") {
            Column(modifier = Modifier.verticalScroll(rightColumnScrollState)) {
                rightBlocks.forEach {
                    TabContentBlockView(block = it, fontSizeSp = fontSizeSp, fontStyle = fontStyle, onChordClick = onChordClick)
                }
            }
        }.first().measure(Constraints.fixed(rightColumnWidth, columnHeight))

        layout(constraints.maxWidth, columnHeight) {
            leftPlaceable.placeRelative(0, 0)
            rightPlaceable.placeRelative(leftColumnWidth, 0)
        }
    }
}
