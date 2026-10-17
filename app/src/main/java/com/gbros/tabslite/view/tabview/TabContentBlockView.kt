package com.gbros.tabslite.view.tabview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.toFontFamily
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.withAnnotation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.gbros.tabslite.R
import com.gbros.tabslite.data.FontStyle
import com.gbros.tabslite.data.tab.TabContentBlock
import com.gbros.tabslite.ui.theme.AppTheme

/**
 * Displays a single TabContentBlock with chords positioned above the text
 */
@Composable
fun TabContentBlockView(
    modifier: Modifier = Modifier,
    block: TabContentBlock,
    fontSizeSp: Float,
    fontStyle: FontStyle,
    onChordClick: (chord: String) -> Unit
) {
    val textLayoutResult = remember { mutableStateOf<TextLayoutResult?>(null) }
    val chordAnnotations = remember(block.content) {
        block.content.getStringAnnotations("chord", 0, block.content.length)
    }

    // only Android 8+ supports variable weight fonts
    val selectedContentFontFamily = if (fontStyle == FontStyle.Mono) Font(R.font.roboto_mono_regular).toFontFamily() else null

    Column(modifier = modifier) {
        // consecutive chord lines are separate blocks: no spacer, so a chord row sits right under the lyrics above
        if (block.gapAbove) Spacer(modifier = Modifier.height(24.dp))

        Layout(
            modifier = modifier,
            content = {
                Text(
                    text = block.content,
                    onTextLayout = {
                        textLayoutResult.value = it
                    },
                    style = TextStyle(
                        fontSize = TextUnit(fontSizeSp, TextUnitType.Sp),
                        fontFamily = selectedContentFontFamily,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = if (block.tab) 2.5.em else 1.5.em, // room for the chord row above each lyric line in tabs
                        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Bottom, LineHeightStyle.Trim.None),
                        baselineShift = if (block.tab) BaselineShift(.15f) else BaselineShift(0f)
                    )
                )

                for (annotation in chordAnnotations) {
                    val chord = if (annotation.item.startsWith("{il}")) annotation.item.substring(4) else annotation.item
                    ChordButton(
                        text = chord,
                        fontFamily = selectedContentFontFamily,
                        fontSizeSp = fontSizeSp,
                    ) {
                        onChordClick(chord)
                    }
                }
            }
        ) { measurables, constraints ->
            placeChordButtons(chordAnnotations, textLayoutResult, measurables, constraints)
        }
    }
}

private fun MeasureScope.placeChordButtons(
    chordAnnotations: List<AnnotatedString.Range<String>>,
    textLayoutResult: MutableState<TextLayoutResult?>,
    measurables: List<Measurable>,
    constraints: Constraints
): MeasureResult {
    val basicTextPlaceable = measurables[0].measure(constraints)

    return layout(basicTextPlaceable.width, basicTextPlaceable.height) {
        basicTextPlaceable.placeRelative(0, 0)

        textLayoutResult.value?.let { layoutResult ->
            var lastButtonRightEdge = 0
            var lastButtonLine = -1

            for (i in chordAnnotations.indices) {
                val chordButton = measurables[i + 1].measure(Constraints()) // Unconstrained button size

                // the annotation tells us which chord goes above this place in the text
                val boundingBox = layoutResult.getBoundingBox(chordAnnotations[i].start)
                val currentButtonLine = layoutResult.getLineForOffset(chordAnnotations[i].start)

                // If we're on a new line, reset the tracking of the last button's edge
                if (currentButtonLine != lastButtonLine) {
                    lastButtonRightEdge = 0
                    lastButtonLine = currentButtonLine
                }

                val inline = chordAnnotations[i].item.startsWith("{il}")

                // Chords that fit at their column stay there, even touching the previous one (`D][G`);
                // only one that would overlap it is pushed right, with some space so the two read apart
                val left = boundingBox.left.toInt()
                val x = if (left >= lastButtonRightEdge) left else lastButtonRightEdge + 16
                val y = if (inline) boundingBox.bottom.toInt() - chordButton.height
                        else boundingBox.top.toInt()
                chordButton.placeRelative(x, y)

                lastButtonRightEdge = x + chordButton.width
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TabContentBlockViewTabPreview() {
    AppTheme {
        val builder = AnnotatedString.Builder()
        builder.append("I ")
        builder.withAnnotation("chord", "C") {
            append("h")
        }
        builder.append("eard there was a ")
        builder.withAnnotation("chord", "Am") {
            append("s")
        }
        builder.append("ecret chord")

        TabContentBlockView(
            block = TabContentBlock(builder.toAnnotatedString(), true),
            fontSizeSp = 14f,
            fontStyle = FontStyle.Modern,
            onChordClick = {}
        )
    }
}
@Preview(showBackground = true)
@Composable
private fun TabContentBlockViewNonTabPreview() {
    AppTheme {
        val builder = AnnotatedString.Builder("[Intro]\n")
        builder.withAnnotation("chord", "C", block = {append(" ")})
        builder.append(" ")
        builder.withAnnotation("chord", "Am", block = {append(" ")})
        builder.withAnnotation("chord", "C", block = {append(" ")})
        builder.withAnnotation("chord", "Am", block = {append(" ")})
        builder.append("\n")


        Box(modifier = Modifier.fillMaxWidth()) {
            TabContentBlockView(
                block = TabContentBlock(builder.toAnnotatedString(), false),
                fontSizeSp = 14f,
                fontStyle = FontStyle.Mono,
                onChordClick = {}
            )
        }
    }
}
