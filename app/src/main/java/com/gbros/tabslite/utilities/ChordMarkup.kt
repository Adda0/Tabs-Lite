package com.gbros.tabslite.utilities

/**
 * Shared chord-markup conversion logic used both when translating Ultimate Guitar's raw tab
 * content into the app's internal format, and when parsing hand-written local song files.
 *
 * The app's internal/rendered format embeds each chord inline within its lyric line, e.g.
 * `I h{ch:C}eard...`, wrapped in `[tab]...[/tab]` so the renderer knows to float chords above the
 * text. Both Ultimate Guitar's raw API format (`[ch]C[/ch]` on its own line above the lyric line)
 * and a hand-written "chords above the lyrics" plaintext format (bare chord names, no tags at all,
 * on their own line, optionally marked with a leading `^` and/or trailing `$` to mark the line as a
 * chord line explicitly - see [LEADING_CHORD_LINE_MARKER]/[TRAILING_CHORD_LINE_MARKER]) need
 * converting into this shape.
 */

private const val TAB_OPEN_TAG = "[tab]"
private const val TAB_CLOSE_TAG = "[/tab]"
private val UG_CHORD_TAG_REGEX = Regex("\\[ch](.*?)\\[/ch]")
private val APP_CHORD_TAG_REGEX = Regex("\\{ch:([^}]*)\\}")

/**
 * A single bare chord token, e.g. "Em7", "G", "D/F#", "Hm"/"Hmi" (Czech/German notation, where H
 * is B natural and "mi" is an alternate minor suffix - not to be confused with English B, which
 * stays a valid root of its own too). Deliberately conservative: the quality/extension suffix must
 * be built from recognized chord vocabulary (m, mi, maj, min, dim, aug, sus, add, digits, altered
 * #/b extensions), so ordinary capitalized words don't get mistaken for chords when checked in
 * isolation - the "does the *entire* line consist of only such tokens" check in [isBareChordLine]
 * is what actually keeps false positives out of ordinary lyric lines.
 *
 * Matched against the token *after* [stripChordDecorations] removes any `()[]{}\` for the purposes
 * of recognizing it as chord-shaped - those are allowed anywhere in the raw token, including
 * nested/mid-chord (e.g. `Am(7)`, `(D)`, `{D}`, `(\Dm(7))`), since personal notation like this is
 * common (e.g. marking an optional chord change, or a single strum instead of the full chord).
 * `/` is kept as-is (not stripped) since it's meaningful on its own (the bass note of a slash chord).
 *
 * Recognizing a decorated token as a chord is one thing; keeping the decoration in what's actually
 * *displayed* is another - see [sanitizeChordNameForTag].
 */
private val CHORD_TOKEN_REGEX = Regex(
    "^[A-H][#b]?(?:(?i:maj|min|dim|aug|sus|add|mi|m)?[0-9]{0,2}){0,2}(?:[#b][0-9]{1,2})?(?:/[A-H][#b]?)?$"
)

private val CHORD_DECORATION_REGEX = Regex("[()\\[\\]{}\\\\]")
private val TAG_INCOMPATIBLE_CHARS_REGEX = Regex("[{}]")

private fun stripChordDecorations(token: String): String = CHORD_DECORATION_REGEX.replace(token, "")

/**
 * Keeps a chord token's own notation (parens, square brackets, backslash) intact for display -
 * e.g. "Am(7)" is shown as "Am(7)", not cleaned up to "Am7" - since that punctuation is often
 * meaningful to whoever wrote the file (an optional chord change, a single strum, etc.), not just
 * decorative noise. The one exception is a literal `{` or `}`: the app's `{ch:...}` tag format is
 * itself delimited by `}` (see [APP_CHORD_TAG_REGEX]), so a chord name containing one would be
 * indistinguishable from the tag's own closing brace - those two characters can't be preserved
 * without changing that shared delimiter scheme, so they're the only ones actually dropped.
 */
private fun sanitizeChordNameForTag(token: String): String = TAG_INCOMPATIBLE_CHARS_REGEX.replace(token, "")

/**
 * Converts `[ch]C[/ch]` tags (Ultimate Guitar's raw wire format) to the app's internal `{ch:C}` format.
 */
fun convertUgChordTags(content: String): String =
    UG_CHORD_TAG_REGEX.replace(content) { matchResult -> "{ch:${matchResult.groupValues[1]}}" }

/**
 * A line can be explicitly marked as a chord line by starting and/or ending it with one of these
 * characters (mirroring the regex start-/end-of-line anchors `^`/`$`), so the line doesn't have to
 * be shape-checked by [isBareChordLine] at all - useful for the odd chord line the shape heuristic
 * would otherwise get wrong (e.g. personal shorthand that doesn't look chord-shaped). Not required -
 * plain unmarked chord lines are still recognized by their shape - but supported for when it's
 * needed. [TRAILING_CHORD_LINE_MARKER] is the one to reach for when alignment matters, since chord
 * lines are sparse and rarely reach the line's full width, so appending it costs no column; a
 * leading [LEADING_CHORD_LINE_MARKER] always shifts every chord one column to the right of the
 * lyric line beneath it, once its own column is accounted for.
 */
private const val LEADING_CHORD_LINE_MARKER = "^"
private const val TRAILING_CHORD_LINE_MARKER = "$"

/**
 * Converts any line made up entirely of bare chord names (no tags at all - the standard way chord
 * sheets are hand-written, chords on their own line above the lyrics they apply to) into the app's
 * `{ch:C}` tagged format, preserving each token's column position so [mergeChordOnlyLines] can
 * merge it into the following lyric line exactly as it would a tagged chord-only line. Each
 * chord's own notation is kept as typed (see [sanitizeChordNameForTag]).
 *
 * A line starting with [LEADING_CHORD_LINE_MARKER] and/or ending with [TRAILING_CHORD_LINE_MARKER]
 * is always treated as a chord line (the marker(s) are stripped before conversion); otherwise a line
 * is only treated as a chord line if every token on it is chord-shaped, per [isBareChordLine].
 */
fun convertBareChordLines(content: String): String {
    return content.split("\n").joinToString("\n") { line ->
        val hasLeadingMarker = line.startsWith(LEADING_CHORD_LINE_MARKER)
        val afterLeadingMarker = if (hasLeadingMarker) line.removePrefix(LEADING_CHORD_LINE_MARKER) else line

        val trimmedEnd = afterLeadingMarker.trimEnd()
        val hasTrailingMarker = trimmedEnd.endsWith(TRAILING_CHORD_LINE_MARKER)
        val body = if (hasTrailingMarker) trimmedEnd.removeSuffix(TRAILING_CHORD_LINE_MARKER) else afterLeadingMarker

        if (hasLeadingMarker || hasTrailingMarker) {
            Regex("\\S+").replace(body) { matchResult -> "{ch:${sanitizeChordNameForTag(matchResult.value)}}" }
        } else if (isBareChordLine(line)) {
            Regex("\\S+").replace(line) { matchResult -> "{ch:${sanitizeChordNameForTag(matchResult.value)}}" }
        } else {
            line
        }
    }
}

private fun isBareChordLine(line: String): Boolean {
    val tokens = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return tokens.isNotEmpty() && tokens.all { CHORD_TOKEN_REGEX.matches(stripChordDecorations(it)) }
}

private fun isChordOnlyLine(line: String): Boolean {
    val withoutTabOpen = line.removePrefix(TAB_OPEN_TAG)
    return APP_CHORD_TAG_REGEX.containsMatchIn(line) && APP_CHORD_TAG_REGEX.replace(withoutTabOpen, "").isBlank()
}

/**
 * Merges each chord-only line (a line made up entirely of `{ch:C}` tags) into the lyric line
 * immediately following it, at the column each chord would occupy if collapsed to its bare name -
 * so chords render inline, above the syllable they belong to, instead of as a separate line (which
 * would double the renderer's per-line chord headroom and read as a phantom blank line).
 */
fun mergeChordOnlyLines(content: String): String {
    val lines = content.split("\n")
    val result = mutableListOf<String>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val nextLine = lines.getOrNull(i + 1)
        val nextLineBody = nextLine?.removeSuffix(TAB_CLOSE_TAG)
        if (isChordOnlyLine(line) && nextLineBody != null && APP_CHORD_TAG_REGEX.replace(nextLineBody, "").isNotBlank()) {
            result.add(mergeChordLineIntoLyricLine(line, nextLine!!))
            i += 2
        } else {
            result.add(line)
            i++
        }
    }
    return result.joinToString("\n")
}

/**
 * Inserts each chord tag from [chordLine] into [lyricLine] at the column it would occupy if
 * the tags on [chordLine] were collapsed down to their bare chord names (undoing the extra
 * width `{ch:...}` markup adds over the plain chord name it wraps). Preserves a leading
 * `[tab]` on [chordLine] and/or a trailing `[/tab]` on [lyricLine] around the merged result.
 */
private fun mergeChordLineIntoLyricLine(chordLine: String, lyricLine: String): String {
    val tabPrefix = if (chordLine.startsWith(TAB_OPEN_TAG)) TAB_OPEN_TAG else ""
    val chordLineBody = chordLine.removePrefix(TAB_OPEN_TAG)

    val tabSuffix = if (lyricLine.endsWith(TAB_CLOSE_TAG)) TAB_CLOSE_TAG else ""
    val lyricLineBody = lyricLine.removeSuffix(TAB_CLOSE_TAG)

    var displayColumn = 0
    var lastRawEnd = 0
    val insertions = mutableListOf<Pair<Int, String>>()
    for (match in APP_CHORD_TAG_REGEX.findAll(chordLineBody)) {
        displayColumn += match.range.first - lastRawEnd // literal characters (e.g. spaces) before this chord
        insertions.add(displayColumn to match.value)
        displayColumn += match.groupValues[1].length // bare chord name width, e.g. "Am" -> 2
        lastRawEnd = match.range.last + 1
    }

    val builder = StringBuilder(lyricLineBody)
    var insertedSoFar = 0
    for ((column, tag) in insertions) {
        val insertPosition = (column + insertedSoFar).coerceIn(0, builder.length)
        builder.insert(insertPosition, tag)
        insertedSoFar += tag.length
    }
    return tabPrefix + builder.toString() + tabSuffix
}

/**
 * Wraps any line that has a `{ch:C}` chord annotation but isn't already inside a `[tab]...[/tab]`
 * block, in its own such block - so hand-written local song files render with the same "chords
 * floating above the lyrics" styling as fetched tabs, without the user needing to type [tab] tags.
 */
fun wrapUnwrappedChordLines(content: String): String {
    return content.split("\n").joinToString("\n") { line ->
        if (!line.startsWith(TAB_OPEN_TAG) && APP_CHORD_TAG_REGEX.containsMatchIn(line)) {
            "$TAB_OPEN_TAG$line$TAB_CLOSE_TAG"
        } else {
            line
        }
    }
}
