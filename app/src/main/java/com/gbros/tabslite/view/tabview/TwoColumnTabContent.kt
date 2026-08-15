package com.gbros.tabslite.view.tabview

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import com.gbros.tabslite.data.FontStyle
import com.gbros.tabslite.data.tab.TabContentBlock

/**
 * Reflows [blocks] across two side-by-side columns, like a printed songbook page: [blocks] are
 * measured once at the target column width to find how much content fits in roughly half the
 * total measured height, that much goes in the left column, and the rest flows into the right
 * column. Each column scrolls independently via its own [ScrollState] - the caller drives both
 * during autoscroll, first exhausting [leftColumnScrollState] before advancing
 * [rightColumnScrollState] (see the autoscroll effect in [TabScreen]).
 */
@Composable
fun TwoColumnTabContent(
    blocks: List<TabContentBlock>,
    fontSizeSp: Float,
    fontStyle: FontStyle,
    onChordClick: (chord: String) -> Unit,
    leftColumnScrollState: ScrollState,
    rightColumnScrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    SubcomposeLayout(modifier = modifier) { constraints ->
        val leftColumnWidth = constraints.maxWidth / 2
        val rightColumnWidth = constraints.maxWidth - leftColumnWidth
        val columnHeight = constraints.maxHeight
        val measureConstraints = Constraints(maxWidth = leftColumnWidth)

        // measure each block once at the column's width to decide how much fits in the left column
        val heights = blocks.mapIndexed { index, block ->
            subcompose("measure-$index") {
                TabContentBlockView(block = block, fontSizeSp = fontSizeSp, fontStyle = fontStyle, onChordClick = {})
            }.first().measure(measureConstraints).height
        }
        val targetHeight = heights.sum() / 2

        var accumulatedHeight = 0
        var splitIndex = blocks.size
        for (i in heights.indices) {
            accumulatedHeight += heights[i]
            if (accumulatedHeight >= targetHeight) {
                splitIndex = i + 1
                break
            }
        }
        val leftBlocks = blocks.subList(0, splitIndex)
        val rightBlocks = blocks.subList(splitIndex, blocks.size)

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
