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
 * Lays [blocks] out in two columns: the left column holds as many blocks as fit in the visible
 * height and stays put (it usually carries the first verse with its chords), everything else flows
 * into the right column, which scrolls via [rightColumnScrollState] - the state autoscroll drives.
 * Blocks are measured at the column width with the current text size, so changing the zoom
 * reflows the split. A first block taller than the screen still goes left; only then does the
 * left column need scrolling of its own.
 */
@Composable
fun TwoColumnTabContent(
    blocks: List<TabContentBlock>,
    fontSizeSp: Float,
    fontStyle: FontStyle,
    onChordClick: (chord: String) -> Unit,
    rightColumnScrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    val leftColumnScrollState = rememberScrollState()
    SubcomposeLayout(modifier = modifier) { constraints ->
        val leftColumnWidth = constraints.maxWidth / 2
        val rightColumnWidth = constraints.maxWidth - leftColumnWidth
        val columnHeight = constraints.maxHeight

        // greedily fill the left column up to the visible height
        var splitIndex = 0
        var leftHeight = 0
        for ((index, block) in blocks.withIndex()) {
            val height = subcompose("measure-$index") {
                TabContentBlockView(block = block, fontSizeSp = fontSizeSp, fontStyle = fontStyle, onChordClick = {})
            }.first().measure(Constraints(maxWidth = leftColumnWidth)).height
            if (splitIndex > 0 && leftHeight + height > columnHeight) break
            leftHeight += height
            splitIndex = index + 1
        }

        val leftPlaceable = subcompose("left") {
            Column(modifier = Modifier.verticalScroll(leftColumnScrollState)) {
                blocks.subList(0, splitIndex).forEach {
                    TabContentBlockView(block = it, fontSizeSp = fontSizeSp, fontStyle = fontStyle, onChordClick = onChordClick)
                }
            }
        }.first().measure(Constraints.fixed(leftColumnWidth, columnHeight))
        val rightPlaceable = subcompose("right") {
            Column(modifier = Modifier.verticalScroll(rightColumnScrollState)) {
                blocks.subList(splitIndex, blocks.size).forEach {
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
