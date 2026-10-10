package com.gbros.tabslite.utilities

/**
 * Shared tables kept as plain text files next to the songs in the local song folder:
 * [RHYTHMS_FILE_NAME] (strumming patterns, numbered per time signature) and [CHORDS_FILE_NAME]
 * (chord fingerings). Both are excluded from the song import itself.
 */
const val RHYTHMS_FILE_NAME = "rhythms.txt"
const val CHORDS_FILE_NAME = "chords.txt"

/** One strumming pattern, e.g. id `3`, pattern `| D - | D U | - U | D U (klasický táborák)` */
data class Rhythm(val id: String, val pattern: String)

/** All rhythms of one time signature, with the beat-count header row (`| 1 & | 2 & | 3 & | 4 &`) */
data class RhythmGroup(val beats: String, val rhythms: Map<String, Rhythm>)

private val TIME_SIGNATURE_REGEX = Regex("\\b(\\d+/\\d+)\\b")
private val TIME_SIGNATURE_LINE_REGEX = Regex("^\\s*(\\d+/\\d+)\\s*$")
private val BEATS_LINE_REGEX = Regex("^\\s*(\\|.*)$")
private val RHYTHM_LINE_REGEX = Regex("^\\s*([0-9]+[A-Za-z]*)\\s*(\\|.*)$")

/**
 * Parses a rhythms file: a time signature on a line of its own (`4/4`) starts its group; a line
 * starting with `|` is that group's beat header; `<id> | <cells>` lines are its rhythms, ids being
 * a number optionally followed by letters (`3`, `3v`, `9d`). Anything after the cells (a note in
 * parentheses) is kept as part of the pattern. Other lines (titles, `#` comments, blanks) are ignored.
 *
 * @return rhythm groups keyed by time signature
 */
fun parseRhythms(text: String): Map<String, RhythmGroup> {
    val beats = linkedMapOf<String, String>()
    val rhythms = linkedMapOf<String, LinkedHashMap<String, Rhythm>>()
    var current: String? = null
    for (rawLine in text.lines()) {
        val line = rawLine.trimEnd()
        if (line.trimStart().startsWith("#")) continue
        val signature = TIME_SIGNATURE_LINE_REGEX.matchEntire(line)?.groupValues?.get(1)
        if (signature != null) {
            current = signature
            rhythms.getOrPut(signature) { linkedMapOf() }
            continue
        }
        val group = current ?: continue
        val rhythm = RHYTHM_LINE_REGEX.matchEntire(line)
        val beatHeader = BEATS_LINE_REGEX.matchEntire(line)
        if (rhythm != null) {
            val id = rhythm.groupValues[1]
            rhythms.getValue(group)[id] = Rhythm(id, rhythm.groupValues[2].trim())
        } else if (beatHeader != null) {
            beats[group] = beatHeader.groupValues[1].trim()
        }
    }
    return rhythms.mapValues { (signature, groupRhythms) -> RhythmGroup(beats[signature].orEmpty(), groupRhythms) }
}

/** The time signature named in a `tempo` header value, e.g. `4/4` from `4/4 (~68 BPM)` */
fun timeSignatureOf(tempo: String): String? = TIME_SIGNATURE_REGEX.find(tempo)?.groupValues?.get(1)

/** One `label: id` entry of a `rhythm` header, e.g. `verse: 8`; the label is blank for a bare id */
data class RhythmReference(val label: String, val id: String)

/** Parses a `rhythm` header value: comma-separated `label: id` pairs (`verse: 8, r: 4`) or a bare id (`3`) */
fun parseRhythmReferences(value: String): List<RhythmReference> = value.split(",").mapNotNull { part ->
    val trimmed = part.trim()
    if (trimmed.isEmpty()) return@mapNotNull null
    val colon = trimmed.lastIndexOf(':')
    if (colon == -1) RhythmReference("", trimmed)
    else RhythmReference(trimmed.substring(0, colon).trim(), trimmed.substring(colon + 1).trim())
}

/**
 * Renders [references] with their patterns from [rhythms] for the song's [tempo], one per line,
 * patterns aligned under the beat header:
 * ```
 *          | 1 & | 2 & | 3 & | 4 &
 * verse: 8 | d - | d - | D - | d u
 * r: 4     | D - | D U | D U | D U
 * ```
 * A reference without a known pattern (no tempo, unknown time signature or id) is still listed.
 */
fun formatRhythms(references: List<RhythmReference>, tempo: String, rhythms: Map<String, RhythmGroup>): String {
    if (references.isEmpty()) return ""
    val group = timeSignatureOf(tempo)?.let { rhythms[it] }
    val names = references.map { if (it.label.isEmpty()) it.id else "${it.label}: ${it.id}" }
    val width = names.maxOf { it.length }
    val lines = mutableListOf<String>()
    if (group != null && group.beats.isNotEmpty()) lines.add(" ".repeat(width + 1) + group.beats)
    references.forEachIndexed { index, reference ->
        val pattern = group?.rhythms?.get(reference.id)?.pattern
        lines.add(if (pattern != null) names[index].padEnd(width) + " " + pattern else names[index])
    }
    return lines.joinToString("\n")
}

/**
 * One fingering from the chords file. [frets] runs from the low E string to the high e string;
 * `null` is a muted string, 0 an open one.
 */
data class ChordFingering(val name: String, val frets: List<Int?>)

private val CHORD_LINE_REGEX = Regex("^\\s*(\\S+)\\s*\\|((?:\\s*[0-9xX]+\\s*\\|){6})")

/**
 * Parses a chords file: rows of `<name> |<E>|<A>|<D>|<G>|<B>|<e>|` with a fret number or `x` per
 * string (`C     |3|3|2|0|1|0|`). The header row and separator are skipped because their cells
 * aren't frets. A name listed again adds another fingering of the same chord.
 */
fun parseChordFingerings(text: String): List<ChordFingering> = text.lines().mapNotNull { line ->
    val match = CHORD_LINE_REGEX.find(line) ?: return@mapNotNull null
    val frets = match.groupValues[2].split("|").map { it.trim() }.filter { it.isNotEmpty() }
        .map { if (it.equals("x", ignoreCase = true)) null else it.toInt() }
    ChordFingering(match.groupValues[1], frets)
}
