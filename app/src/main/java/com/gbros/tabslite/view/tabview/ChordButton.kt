package com.gbros.tabslite.view.tabview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import com.gbros.tabslite.ui.theme.AppTheme

@Composable
fun ChordButton(
    modifier: Modifier = Modifier,
    text: String,
    fontSizeSp: Float,
    fontFamily: FontFamily? = null,
    /** only a hint, copied from another section: its position over the lyrics is approximate */
    hint: Boolean = false,
    onClick: () -> Unit
){
    // same font, size and weight as the lyrics, and no horizontal padding, so a chord starts exactly
    // above the character it belongs to and spans the same columns; color alone sets it apart, and
    // a hint apart from a chord written where it's played
    BasicText(
        text = text,
        modifier = modifier.clickable(onClick = onClick),
        style = TextStyle(
            color = if (hint) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            fontSize = TextUnit(fontSizeSp, TextUnitType.Sp),
            fontFamily = fontFamily
        )
    )
}

@Preview
@Composable
private fun ChordButtonPreview() {
    AppTheme {
        ChordButton(text = "Am", fontSizeSp = 14f, fontFamily = null, onClick = {})
    }
}
