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
 * the same kind written out earlier: its lyrics and chords are shown there again. With a repeat
 * count the reference line stays and the copy follows with its label blanked out.
 */
fun expandSectionReferences(content: String): String {
    val lines = content.split("\n")
    val sections = findSections(lines)
    val written = mutableMapOf<String, Section>()
    val expansions = mutableMapOf<Int, List<String>>() // reference line index -> replacement lines
    for (section in sections) {
        val text = displayText(lines[section.start])
        val rest = text.replaceFirst(SECTION_LABEL, "")
        val source = written[section.key]
        if (source != null && section.end == section.start + 1 && REFERENCE_REST.matches(rest) && !CHORD_TAG.containsMatchIn(lines[section.start])) {
            val copy = lines.subList(source.start, source.end)
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
