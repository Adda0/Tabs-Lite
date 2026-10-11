package com.gbros.tabslite.utilities

/*
 * Song sections in the app's content format: a section opens with a label at the start of a line
 * (`1.`, `R:`, `R2:`, `Ref:`, `B:`, …; chord tags and a `[tab]` wrapper may sit around it) and runs
 * to the next blank line or the next labelled line.
 */

private const val TAB_OPEN = "[tab]"
private const val TAB_CLOSE = "[/tab]"
private val CHORD_TAG = Regex("\\{ch:[^}]*\\}")
private val SECTION_LABEL = Regex("^\\s*(\\d{1,2}\\.|(?:Ref|Rec|Coda|[RBIOMC])\\d?:)", RegexOption.IGNORE_CASE)
/** What may follow a label that only points back at an earlier section: `R:`, `R: 2x`, `R: (3×)` */
private val REFERENCE_REST = Regex("^\\s*\\(?\\s*(?:\\d+\\s*[x×]|[x×]\\s*\\d+)?\\s*\\)?\\s*$", RegexOption.IGNORE_CASE)
private val LETTER = Regex("\\p{L}")
private val REPEAT_COUNT = Regex("(?<!\\p{L})(?:\\d+\\s*[x×]|[x×]\\s*\\d+)(?!\\p{L})", RegexOption.IGNORE_CASE)

/** [line] as displayed: without its `[tab]` wrapper and chord tags */
private fun displayText(line: String): String = CHORD_TAG.replace(line.removePrefix(TAB_OPEN).removeSuffix(TAB_CLOSE), "")

private fun labelOf(line: String): String? = SECTION_LABEL.find(displayText(line))?.groupValues?.get(1)

/** Sections of one kind share a key: verses all `#`, `Ref:`/`R:` both `R`, `R2:` its own `R2` */
private fun sectionKey(label: String): String {
    val name = label.trimEnd(':', '.').uppercase()
    return when {
        name.all { it.isDigit() } -> "#"
        name == "REF" -> "R"
        else -> name
    }
}

private class Section(val key: String, val start: Int, val end: Int) // lines [start, end)

private fun findSections(lines: List<String>): List<Section> {
    val sections = mutableListOf<Section>()
    var i = 0
    while (i < lines.size) {
        val label = labelOf(lines[i])
        if (label == null) { i++; continue }
        var end = i + 1
        while (end < lines.size && displayText(lines[end]).isNotBlank() && labelOf(lines[end]) == null) end++
        sections.add(Section(sectionKey(label), i, end))
        i = end
    }
    return sections
}

private fun hasLyrics(line: String) = LETTER.containsMatchIn(REPEAT_COUNT.replace(displayText(line).replaceFirst(SECTION_LABEL, ""), ""))

/**
 * A later label with nothing but a repeat count after it (`R:`, `R: 2x`) stands for the section of
 * the same kind written out earlier: its lyrics are shown there again, with its chords only when
 * [includeChords] is set (chord-only lines are dropped without them). With a repeat count the
 * reference line stays and the copy follows with its label blanked out.
 */
fun expandSectionReferences(content: String, includeChords: Boolean = true): String {
    val lines = content.split("\n")
    val sections = findSections(lines)
    val written = mutableMapOf<String, Section>()
    val expansions = mutableMapOf<Int, List<String>>() // reference line index -> replacement lines
    for (section in sections) {
        val text = displayText(lines[section.start])
        val rest = text.replaceFirst(SECTION_LABEL, "")
        val source = written[section.key]
        if (source != null && section.end == section.start + 1 && REFERENCE_REST.matches(rest) && !CHORD_TAG.containsMatchIn(lines[section.start])) {
            val copy = lines.subList(source.start, source.end).let { linesSublist ->
                if (includeChords) linesSublist else linesSublist.map { CHORD_TAG.replace(it, "") }
                    .filter { displayText(it).isNotBlank() } // written sections always keep their lyrics
            }
            expansions[section.start] = if (rest.isBlank()) copy else listOf(lines[section.start]) + blankLabel(copy.first()) + copy.drop(1)
        } else if (source == null && (section.start until section.end).any { hasLyrics(lines[it]) }) {
            written[section.key] = section
        }
    }
    if (expansions.isEmpty()) return content
    return lines.flatMapIndexed { i, line -> expansions[i] ?: listOf(line) }.joinToString("\n")
}

/** [line] with its section label replaced by spaces, so the lyrics keep their columns */
private fun blankLabel(line: String): String {
    val prefix = if (line.startsWith(TAB_OPEN)) TAB_OPEN else ""
    val body = line.removePrefix(prefix)
    val match = SECTION_LABEL.find(body) ?: return line // label behind a chord tag: leave as written
    return prefix + body.replaceRange(match.range, " ".repeat(match.value.length))
}

/**
 * Chord hints: a later verse or chorus written without chords gets the chords of the first section
 * of its kind that has them - the n-th lyric line those of the n-th lyric line there, at the same
 * columns. Only a hint: the lyrics differ, so the chords land roughly where the original had them.
 */
fun addChordHints(content: String): String {
    val lines = content.split("\n").toMutableList()
    val sources = mutableMapOf<String, List<String>>() // section key -> its lyric lines with chords
    for (section in findSections(lines)) {
        val lyricLines = (section.start until section.end).filter { hasLyrics(lines[it]) }
        val withChords = (section.start until section.end).any { CHORD_TAG.containsMatchIn(lines[it]) }
        val source = sources[section.key]
        if (source == null) {
            if (withChords && lyricLines.any { CHORD_TAG.containsMatchIn(lines[it]) }) sources[section.key] = lyricLines.map { lines[it] }
        } else if (!withChords) {
            lyricLines.zip(source).forEach { (target, from) -> lines[target] = withChordsOf(from, lines[target]) }
        }
    }
    return lines.joinToString("\n")
}

/** [target] (a line without chords) with the chord tags of [source] at the columns they're displayed at there */
private fun withChordsOf(source: String, target: String): String {
    val sourceBody = source.removePrefix(TAB_OPEN).removeSuffix(TAB_CLOSE)
    val chords = mutableListOf<Pair<Int, String>>() // display column -> tag
    var removed = 0
    for (tag in CHORD_TAG.findAll(sourceBody)) {
        chords.add(tag.range.first - removed to tag.value)
        removed += tag.value.length
    }
    if (chords.isEmpty()) return target

    val body = StringBuilder(target.removePrefix(TAB_OPEN).removeSuffix(TAB_CLOSE).trimEnd())
    // insert from the right so earlier columns stay valid; chords past the end are padded out to their column
    for ((column, tag) in chords.asReversed()) {
        while (body.length < column) body.append(' ')
        body.insert(column, tag)
    }
    return if (source.startsWith(TAB_OPEN)) TAB_OPEN + body + TAB_CLOSE else body.toString()
}

/** How the tab screen shows a song */
enum class TabViewMode {
    /** Lyrics with their chords, as written */
    Normal,
    /** Lyrics and section labels only, for whoever sings */
    LyricsOnly,
    /** One line per section: its label and the chords it's played with */
    ChordMap;

    fun apply(content: String): String = when (this) {
        Normal -> content
        LyricsOnly -> lyricsOnly(content)
        ChordMap -> chordMap(content)
    }
}

/** The song without chords: chord tags taken out of the lyrics, lines of nothing but chords dropped */
fun lyricsOnly(content: String): String {
    val lines = content.split("\n").mapNotNull { line ->
        if (CHORD_TAG.containsMatchIn(line) && !hasLyrics(line)) null else displayText(line).trimEnd()
    }
    // a dropped chord line between two blank lines (or before the first lyrics) would leave a gap
    return lines.filterIndexed { i, line -> line.isNotBlank() || (i > 0 && lines[i - 1].isNotBlank()) }
        .dropWhile { it.isBlank() }.joinToString("\n")
}

/**
 * The song's chord progression: for each section (or paragraph, where there's no label) with
 * chords, its label and the chords of each of its lines, lines separated by `|`, a chord repeated
 * right after itself shown once. The chords stay tags, so they can be looked up.
 */
fun chordMap(content: String): String {
    val lines = content.split("\n")
    val map = mutableListOf<String>()
    var start = 0
    while (start < lines.size) {
        if (displayText(lines[start]).isBlank() && !CHORD_TAG.containsMatchIn(lines[start])) { start++; continue }
        var end = start + 1
        while (end < lines.size && (displayText(lines[end]).isNotBlank() || CHORD_TAG.containsMatchIn(lines[end])) && labelOf(lines[end]) == null) end++
        val progression = (start until end)
            .map { i -> CHORD_TAG.findAll(lines[i]).map { it.value }.toList().fold(listOf<String>()) { chords, chord -> if (chords.lastOrNull() == chord) chords else chords + chord } }
            .filter { it.isNotEmpty() }
            .joinToString(" | ") { it.joinToString(" ") }
        if (progression.isNotEmpty()) map.add(listOfNotNull(labelOf(lines[start]), progression).joinToString(" "))
        start = end
    }
    return map.joinToString("\n")
}
