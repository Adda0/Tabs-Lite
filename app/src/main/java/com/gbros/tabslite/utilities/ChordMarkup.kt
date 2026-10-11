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
 * Matched against the token *after* [stripChordDecorations] removes any `()[]{}<>\` for the
 * purposes of recognizing it as chord-shaped - those are allowed anywhere in the raw token,
 * including nested/mid-chord (e.g. `Am(7)`, `(D)`, `{D}`, `<D>`, `(\Dm(7))`), since personal
 * notation like this is common (e.g. marking an optional chord change, a single strum instead
 * of the full chord, or grouping chords played over one repeated pattern).
 * `/` is kept as-is (not stripped) since it's meaningful on its own: between two roots it's the
 * bass note of a slash chord (`D/F#`), and opening a token it marks an alternative to the chord
 * before it (`D (/C)` - play D, or C instead), which transposes and is looked up as that chord.
 *
 * Recognizing a decorated token as a chord is one thing; keeping the decoration in what's actually
 * *displayed* is another - see [sanitizeChordNameForTag].
 *
 * A root followed by nothing but repeated m's is a hum (`Hmm...`), not a chord.
 */
private val CHORD_TOKEN_REGEX = Regex(
    "^(?!/?[A-H][#b]?(?i:m){2,}$)/?[A-H][#b]?(?:(?i:maj|min|dim|aug|sus|add|mi|m)?[0-9]{0,2}){0,2}(?:[#b][0-9]{1,2})?(?:/[A-H][#b]?)?$"
)

private const val CHORD_DECORATION_CHAR_CLASS = "[()\\[\\]{}<>\\\\]"
private val CHORD_DECORATION_REGEX = Regex(CHORD_DECORATION_CHAR_CLASS)
private val LEADING_CHORD_DECORATION_REGEX = Regex("^$CHORD_DECORATION_CHAR_CLASS+")
private val TAG_INCOMPATIBLE_CHARS_REGEX = Regex("[{}]")

/**
 * Removes any `()[]{}<>\` a chord token was written with, wherever they sit in it - `(D)` -> `D`,
 * `(\Dm(7))` -> `Dm7` - leaving the chord name itself. Used both to decide whether a token is
 * chord-shaped at all, and to build the name a chord is looked up by (see [Chord.lookupName]),
 * since decoration is the writer's own annotation and means nothing to the chord database.
 */
fun stripChordDecorations(token: String): String = CHORD_DECORATION_REGEX.replace(token, "")

/**
 * Splits a chord name into the decoration it opens with - e.g. the `(` of `(Am7)`, the `(\` of
 * `(\Dm(7))` - and the chord name proper that follows it. Transposition works off the start of the
 * chord name (the root note), so a name that opens with such personal notation has to be split
 * before it can be transposed, and put back together after; see [Chord.transposeChord]. Anything
 * further in (`Am(7)`, `(D)`'s closing paren) never gets in the root note's way, so only the
 * leading run is separated out here, and the chord keeps its notation exactly as typed.
 */
fun splitChordDecorationPrefix(chordName: String): Pair<String, String> {
    val decoration = LEADING_CHORD_DECORATION_REGEX.find(chordName)?.value ?: ""
    return decoration to chordName.substring(decoration.length)
}

/**
 * Keeps a chord token's own notation (parens, square/angle brackets, backslash) intact for display -
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
 * The plain text of stored tab content: chord tags (both formats) and `[tab]` markers removed, so a
 * word split by an inline chord (`h{ch:C}eard`) reads whole again.
 */
fun stripChordMarkup(content: String): String =
    APP_CHORD_TAG_REGEX.replace(UG_CHORD_TAG_REGEX.replace(content, ""), "")
        .replace(TAB_OPEN_TAG, "")
        .replace(TAB_CLOSE_TAG, "")

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
 * Converts any line made up of bare chord names (no tags at all - the standard way chord sheets
 * are hand-written, chords on their own line above the lyrics they apply to) into the app's
 * `{ch:C}` tagged format, preserving each token's column position so [mergeChordOnlyLines] can
 * merge it into the following lyric line exactly as it would a tagged chord-only line. Each
 * chord's own notation is kept as typed (see [sanitizeChordNameForTag]).
 *
 * Besides the chords themselves, a chord line may carry the notation chord sheets are written
 * with - bar lines, repeat brackets, repeat counts ([splitChordToken]) - and may end in a
 * parenthesized aside ([splitTrailingAnnotation]); all of that stays plain text, only the chord
 * names in it are tagged.
 *
 * A line starting with [LEADING_CHORD_LINE_MARKER] and/or ending with [TRAILING_CHORD_LINE_MARKER]
 * is always treated as a chord line (the marker(s) are stripped before conversion, and every token
 * on it is taken for a chord); otherwise a line is only treated as a chord line if it looks like
 * one, per [isBareChordLine].
 */
fun convertBareChordLines(content: String): String {
    return content.split("\n").joinToString("\n") { line ->
        val hasLeadingMarker = line.startsWith(LEADING_CHORD_LINE_MARKER)
        val afterLeadingMarker = if (hasLeadingMarker) line.removePrefix(LEADING_CHORD_LINE_MARKER) else line

        val trimmedEnd = afterLeadingMarker.trimEnd()
        val hasTrailingMarker = trimmedEnd.endsWith(TRAILING_CHORD_LINE_MARKER)
        val body = if (hasTrailingMarker) trimmedEnd.removeSuffix(TRAILING_CHORD_LINE_MARKER) else afterLeadingMarker

        val annotation = if (hasLeadingMarker || hasTrailingMarker) null else splitTrailingAnnotation(line)
        if (hasLeadingMarker || hasTrailingMarker) {
            Regex("\\S+").replace(body) { matchResult -> "{ch:${sanitizeChordNameForTag(matchResult.value)}}" }
        } else if (isBareChordLine(line)) {
            tagChordLine(line)
        } else if (annotation != null) {
            tagChordLine(annotation.first) + tagAnnotation(annotation.second)
        } else {
            line
        }
    }
}

/**
 * A section label opening a line, e.g. `I: ` (intro), `B: `, `O: `, `R2: `. A line of chords behind
 * such a label (`I: Em Em Em Em`) is a chord line too: the chords are tagged, the label is kept as text.
 */
private val SECTION_LABEL_REGEX = Regex("^\\s*[A-Z][0-9]*:\\s+")

/** How many times the run of chords it follows is played, e.g. `2x`, `3×`, `x4` */
private val REPEAT_COUNT_REGEX = Regex("[0-9]{1,2}[xX×]|[xX×][0-9]{1,2}")

/**
 * Bar lines and the brackets enclosing a repeated run of chords (`[: Em D :] 3x`), written either
 * on their own or against the chord they open/close (`[:Em`, `D:]`), and the ellipsis that carries
 * on the chords of the line above (`D ...`, `G...`). They name no chord, so they stay plain text -
 * tagging one would make a chord button out of a bar line.
 */
private val LEADING_CHORD_LINE_NOTATION = listOf("[:", "|:", "|")
private val TRAILING_CHORD_LINE_NOTATION = listOf(":]", ":|", "|", "...", "…")

/** A chord-line token split into the notation it's written against and the chord name between it */
private data class ChordLineToken(val prefix: String, val core: String, val suffix: String) {
    val isChord: Boolean get() = isChordName(core) || splitAlternative(core)?.let { (chord, alternative) ->
        isChordName(chord) && isChordName(alternative)
    } == true

    /** Nothing but notation: a bar line, a repeat bracket, a repeat count (`2x`, `(2x)`), an ellipsis */
    val isNotation: Boolean get() = core.isEmpty() || REPEAT_COUNT_REGEX.matches(stripChordDecorations(core))
}

private fun isChordName(name: String): Boolean = name.isNotEmpty() && CHORD_TOKEN_REGEX.matches(stripChordDecorations(name))

/**
 * Splits a token written as a chord and the alternative to it in one go - `G\E`, `Dm(\F)`,
 * `Dm(/F)` (play Dm, or F instead) - into the two chords, or null if it holds no such alternative.
 *
 * `\` and `/` mark an alternative alike; what differs is only that a `/` between two plain chord
 * names is the bass note of a slash chord (`D/F#`), so a `/` splits a token only when written
 * against decoration of its own (the `(` of `Dm(/F)`), while a `\`, which no chord name contains,
 * always does. A marker opening the token (`(\Em)`, `(/C)`, `\C`, `/C`) makes the whole token the
 * alternative, so there's nothing to split off it.
 */
private fun splitAlternative(core: String): Pair<String, String>? {
    for (marker in 1 until core.length) {
        if (core[marker] !in ALTERNATIVE_CHORD_MARKERS) continue
        // decoration opening the alternative (the `(` of `Dm(\F)`) belongs to it, not to the chord
        val cut = if (core[marker - 1] in ALTERNATIVE_DECORATION_OPENERS) marker - 1 else marker
        if (cut == 0) continue
        if (cut == marker && core[marker] != '\\') continue // a bass note, not an alternative
        return core.substring(0, cut) to core.substring(cut)
    }
    return null
}

/** `\` and `/`: what follows one is an alternative to the chord, not part of its name */
private const val ALTERNATIVE_CHORD_MARKERS = "\\/"
private const val ALTERNATIVE_DECORATION_OPENERS = "([{<"

/**
 * Splits [token] into the bar lines/repeat brackets it's written against and the chord name
 * between them, e.g. `[:Em` -> `[:` + `Em`. With [peelUnbalancedParens], parens that can't be the
 * chord's own decoration because the token never closes them are peeled off too: in `([Dm C] Dm))`
 * they group the annotation the chords sit in, so only `[Dm`, `C]` and `Dm` are chords.
 */
private fun splitChordToken(token: String, peelUnbalancedParens: Boolean = false): ChordLineToken {
    var start = 0
    var end = token.length
    var peeled = true
    while (peeled && start < end) {
        peeled = false
        LEADING_CHORD_LINE_NOTATION.firstOrNull { token.startsWith(it, start) }?.let { start += it.length; peeled = true }
        if (start >= end) break
        TRAILING_CHORD_LINE_NOTATION.firstOrNull { token.substring(start, end).endsWith(it) }?.let { end -= it.length; peeled = true }
        if (peelUnbalancedParens && start < end) {
            val core = token.substring(start, end)
            val unclosed = core.count { it == '(' } - core.count { it == ')' }
            if (unclosed > 0 && core.startsWith("(")) { start++; peeled = true }
            else if (unclosed < 0 && core.endsWith(")")) { end--; peeled = true }
        }
    }
    return ChordLineToken(token.substring(0, start), token.substring(start, end), token.substring(end))
}

/**
 * [token] with its chord name tagged, keeping the notation around it as text; unchanged if it
 * names no chord. A chord and the alternative written against it (`Dm(\F)`) are tagged as the two
 * chords they are, so both can be looked up and transposed.
 */
private fun tagChordToken(token: String, peelUnbalancedParens: Boolean = false): String {
    val split = splitChordToken(token, peelUnbalancedParens)
    if (!split.isChord) return token
    val names = splitAlternative(split.core)?.takeIf { isChordName(it.first) && isChordName(it.second) }
        ?.toList() ?: listOf(split.core)
    return split.prefix + names.joinToString("") { "{ch:${sanitizeChordNameForTag(it)}}" } + split.suffix
}

/** [line] with every chord name on it tagged, keeping its section label and notation as text */
private fun tagChordLine(line: String): String {
    val label = SECTION_LABEL_REGEX.find(line)?.value.orEmpty()
    return label + Regex("\\S+").replace(line.substring(label.length)) { tagChordToken(it.value) }
}

/** [annotation] with every chord name in it tagged, keeping the prose and the grouping parens as text */
private fun tagAnnotation(annotation: String): String =
    Regex("\\S+").replace(annotation) { tagChordToken(it.value, peelUnbalancedParens = true) }

/**
 * Whether [line] is a line of chords: every token on it is either a chord or the notation chord
 * lines are written with ([splitChordToken]), and at least one of them is a chord - a line of bar
 * lines and repeat counts alone (`R: 2x`) says nothing about which chords to play.
 */
private fun isBareChordLine(line: String): Boolean {
    val tokens = line.replaceFirst(SECTION_LABEL_REGEX, "").trim().split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .map { splitChordToken(it) }
    return tokens.any { it.isChord } && tokens.all { it.isChord || it.isNotation }
}

/**
 * Splits a chord line that ends in an aside addressed to another section - `G  D  A  D   (R: + D)`
 * - into its chords and that aside, or null if [line] isn't one. The aside is the writer's own note
 * about the line (here: sung as the refrain, an extra D is played at this spot) rather than a chord
 * above a syllable, so it stays text and is moved behind the lyrics when the chords are merged
 * into them (see [mergeAnnotatedChordLines]); the chords named in it are tagged like any other,
 * since they have to transpose with the song.
 *
 * It has to open a token of its own with a section label and close at the end of the line, so
 * neither a chord's own parens (`Am(7)`, `(D)`) nor an alternative chord (`D (/C)`) is mistaken
 * for one.
 */
private fun splitTrailingAnnotation(line: String): Pair<String, String>? =
    splitAtAnnotation(line) { isBareChordLine(it) }

/** [splitTrailingAnnotation] for a line whose chords are already tagged, e.g. `{ch:G} {ch:D}  (R: + {ch:D})` */
private fun splitTaggedAnnotation(line: String): Pair<String, String>? =
    splitAtAnnotation(line) { isChordOnlyLine(it) }

private inline fun splitAtAnnotation(line: String, chordsBefore: (String) -> Boolean): Pair<String, String>? {
    if (!line.trimEnd().endsWith(")")) return null
    for (index in line.indices) {
        if (line[index] != '(' || (index > 0 && !line[index - 1].isWhitespace())) continue
        if (!SECTION_LABEL_REGEX.containsMatchIn(line.substring(index + 1))) continue
        val chords = line.substring(0, index)
        if (chordsBefore(chords)) return chords to line.substring(index)
    }
    return null
}

/**
 * A line with no lyrics of its own: chord tags, at most a section label, and the notation chord
 * lines carry (`I: {ch:Em} 8x`, `{ch:G} | {ch:D} :]`). There's nothing for its chords to float
 * above, so they render inline, on the line itself.
 */
private fun isChordNotationOnlyLine(line: String): Boolean {
    val body = line.removePrefix(TAB_OPEN_TAG).replaceFirst(SECTION_LABEL_REGEX, "")
    if (!APP_CHORD_TAG_REGEX.containsMatchIn(body)) return false
    return APP_CHORD_TAG_REGEX.replace(body, " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        .all { splitChordToken(it).isNotation }
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
 * Merges a chord line that ends in a parenthesized aside - `{ch:G}  {ch:D}   (R: + {ch:D})` - into
 * the lyric line below it the way [mergeChordOnlyLines] merges a plain one, with the aside moved
 * behind the lyrics: it's a note about the whole line, not a chord belonging above one syllable,
 * and inserting its text at its own column would break the lyrics apart. It keeps the column it
 * was written at where the lyrics end before it, so it stays where the writer put it.
 */
fun mergeAnnotatedChordLines(content: String): String {
    val lines = content.split("\n")
    val result = mutableListOf<String>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val split = splitTaggedAnnotation(line)
        val nextLine = lines.getOrNull(i + 1)
        val nextLineBody = nextLine?.removeSuffix(TAB_CLOSE_TAG)
        if (split != null && nextLineBody != null && APP_CHORD_TAG_REGEX.replace(nextLineBody, "").isNotBlank()) {
            val (chords, annotation) = split
            result.add(appendAnnotation(mergeChordLineIntoLyricLine(chords, nextLine), annotation, writtenColumn = displayWidth(chords)))
            i += 2
        } else {
            result.add(line)
            i++
        }
    }
    return result.joinToString("\n")
}

/** The width [content] takes on screen: chord tags count as the bare chord name they wrap */
private fun displayWidth(content: String): Int = APP_CHORD_TAG_REGEX.replace(content) { it.groupValues[1] }.length

/** Appends [annotation] behind the lyrics of [mergedLine], at [writtenColumn] if they end before it */
private fun appendAnnotation(mergedLine: String, annotation: String, writtenColumn: Int): String {
    val tabSuffix = if (mergedLine.endsWith(TAB_CLOSE_TAG)) TAB_CLOSE_TAG else ""
    val body = mergedLine.removeSuffix(TAB_CLOSE_TAG).trimEnd()
    val lyricWidth = APP_CHORD_TAG_REGEX.replace(body, "").length
    val gap = if (writtenColumn > lyricWidth) " ".repeat(writtenColumn - lyricWidth) else "  "
    return body + gap + annotation.trim() + tabSuffix
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
 * A line with no lyrics of its own (`I: Em Em`, `[: Em D :] 3x`) has nothing to float above, so it
 * stays unwrapped and its chords render inline, on the line itself.
 */
fun wrapUnwrappedChordLines(content: String): String {
    return content.split("\n").joinToString("\n") { line ->
        if (!line.startsWith(TAB_OPEN_TAG) && APP_CHORD_TAG_REGEX.containsMatchIn(line) && !isChordNotationOnlyLine(line)) {
            "$TAB_OPEN_TAG$line$TAB_CLOSE_TAG"
        } else {
            line
        }
    }
}
