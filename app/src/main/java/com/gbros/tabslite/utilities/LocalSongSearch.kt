package com.gbros.tabslite.utilities

import com.gbros.tabslite.data.tab.TabWithDataPlaylistEntry
import java.text.Normalizer

/** Most songs listed for one local search */
private const val MAX_RESULTS = 20

/** A snippet whose first match is further in than this starts this far before it instead, so the match isn't cut off */
private const val SNIPPET_LEAD = 24

/**
 * A line of a song's lyrics that matched a search, with the matched parts of [text] to highlight.
 */
data class SearchSnippet(val text: String, val highlights: List<IntRange>)

/**
 * A downloaded or local song matching a [LocalSongSearchIndex] search. [snippet] is the best
 * matching line of its content, when the song matched by its content rather than its title/artist.
 */
data class LocalSearchResult(val tab: TabWithDataPlaylistEntry, val snippet: SearchSnippet? = null)

/**
 * Full text search over the songs stored on the device, ignoring case, diacritics (`prisel`
 * finds `přišel`), punctuation and chord markup. Filter specifiers (`#tag`, `@artist`,
 * `=song name`, `lang:cz`) gate which songs are searched at all - each takes a run of non-space
 * characters, or `"anything with spaces"` (`_` for a space in an unquoted value, e.g. `#na_tabor`)
 * - and the rest of the query matches fuzzily: the song name first, then the artist, then the
 * content (the query as a whole phrase beats single words, whose letters found in order in any
 * word beat nothing). A result of an earlier group outranks any of a later one, a better match
 * within a group outranking a worse one. Only the first version of each song is listed.
 */
class LocalSongSearchIndex(tabs: List<TabWithDataPlaylistEntry>) {
    private class Entry(val tab: TabWithDataPlaylistEntry, val name: String, val artist: String, val heading: String, val text: String)

    /** A match of [entry]: [group] 0 by song name, 1 by artist, 2 by content (lower outranks), [score] within the group */
    private class Ranked(val group: Int, val score: Double, val entry: Entry, val snippet: SearchSnippet?)

    private val entries = tabs.map { tab ->
        val name = foldForSearch(tab.songName)
        val artist = foldForSearch(tab.artistName)
        Entry(tab, name, artist, "$name $artist", foldForSearch(stripChordMarkup(tab.content)))
    }

    fun search(query: String): List<LocalSearchResult> {
        val tags = mutableListOf<String>()
        val nameFilters = mutableListOf<String>()
        val artistFilters = mutableListOf<String>()
        val languageFilters = mutableListOf<String>()
        for (match in FILTER_TOKEN.findAll(query)) {
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
            when (match.groupValues[1]) {
                "#" -> tagKey(value).takeUnless { it.isEmpty() }?.let(tags::add)
                "@" -> foldForSearch(value).takeUnless { it.isEmpty() }?.let(artistFilters::add)
                "=" -> foldForSearch(value).takeUnless { it.isEmpty() }?.let(nameFilters::add)
                "lang:" -> value.trim().lowercase().takeUnless { it.isEmpty() }?.let(languageFilters::add)
            }
        }
        val text = FILTER_TOKEN.replace(query, " ")
        val phrase = foldForSearch(text)
        if (phrase.isEmpty() && tags.isEmpty() && nameFilters.isEmpty() && artistFilters.isEmpty() && languageFilters.isEmpty()) return listOf()
        val words = phrase.split(' ').distinct()
        val id = text.trim()

        return entries.mapNotNull { entry ->
            if (!hasAllTags(entry.tab.tags, tags)) return@mapNotNull null
            if (nameFilters.any { !filterMatches(entry.name, it) }) return@mapNotNull null
            if (artistFilters.any { !filterMatches(entry.artist, it) }) return@mapNotNull null
            if (languageFilters.any { it != entry.tab.language }) return@mapNotNull null
            if (phrase.isEmpty()) return@mapNotNull Ranked(0, 0.0, entry, null)
            if (entry.tab.tabId == id) return@mapNotNull Ranked(0, 2.0, entry, null)
            val nameScore = matchScore(entry.name, words, phrase)
            if (nameScore > 0.0) return@mapNotNull Ranked(0, nameScore, entry, null)
            val artistScore = matchScore(entry.artist, words, phrase)
            if (artistScore > 0.0) return@mapNotNull Ranked(1, artistScore, entry, null)
            if (words.all { entry.heading.contains(it) }) return@mapNotNull Ranked(1, matchScore(entry.heading, words, phrase), entry, null)
            val textScore = matchScore(entry.text, words, phrase)
            if (textScore > 0.0) return@mapNotNull Ranked(2, textScore, entry, findSnippet(entry.tab.content, phrase, words))
            null
        }
            .sortedWith(compareBy<Ranked> { it.group }.thenByDescending { it.score })  // stable: keeps the given order within a score
            .distinctBy { it.entry.tab.songId }
            .take(MAX_RESULTS)
            .map { LocalSearchResult(it.entry.tab, it.snippet) }
    }
}

/** A filter word: `#tag`, `@artist`, `=song name` or `lang:cz`, its value `\S+` or `"with spaces"` */
private val FILTER_TOKEN = Regex("""(?<!\S)([@#=]|lang:)\s*(?:"([^"]*)"|(\S+))""")

/** A specifier's value matches a folded [text] when its whole phrase or all its words occur in it */
private fun filterMatches(text: String, filter: String): Boolean =
    text.contains(filter) || filter.split(' ').all { text.contains(it) }

/** Every character of [word] occurs in some single word of folded [text], in order, from the same first letter (`tday` in `today`, not `man` in `komendianti`) */
private fun fuzzyWordMatch(text: String, word: String): Boolean =
    word.length > 1 && text.split(' ').any { candidate ->
        if (candidate.first() != word.first()) return@any false
        var i = 0
        for (c in candidate) {
            if (i == word.length) break
            if (c == word[i]) i++
        }
        i == word.length
    }

/** How well a folded [text] matches the folded [phrase]/[words]: the whole phrase found beats (2.0) single words (their matched share), none matches nothing (0.0) */
private fun matchScore(text: String, words: List<String>, phrase: String): Double {
    if (text.isBlank()) return 0.0
    if (text.contains(phrase)) return 2.0
    val matched = words.count { text.contains(it) || fuzzyWordMatch(text, it) }
    return matched.toDouble() / words.size
}

/**
 * The content line matching most of [words] (a line containing the whole [phrase] beats any
 * other), trimmed to start near its first match.
 */
internal fun findSnippet(content: String, phrase: String, words: List<String>): SearchSnippet? {
    val best = stripChordMarkup(content).lines()
        .map { line ->
            val folded = foldForSearch(line)
            val score = words.count { folded.contains(it) } + if (folded.contains(phrase)) words.size else 0
            line to score
        }
        .filter { (_, score) -> score > 0 }
        .maxByOrNull { (_, score) -> score }  // first of the best lines
        ?.first ?: return null

    // foldChars keeps every character in place, so ranges found in it apply to the line as is
    val folded = foldChars(best)
    val highlights = words.flatMap { word ->
        generateSequence(folded.indexOf(word).takeIf { it >= 0 }) { previous ->
            folded.indexOf(word, previous + 1).takeIf { it >= 0 }
        }.map { start -> start until start + word.length }
    }.sortedBy { it.first }

    var start = best.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
    val firstMatch = highlights.firstOrNull()?.first ?: start
    var prefix = ""
    if (firstMatch - start > SNIPPET_LEAD) {
        // start at a word boundary shortly before the match
        val wordStart = best.indexOf(' ', firstMatch - SNIPPET_LEAD) + 1
        start = if (wordStart in 1..firstMatch) wordStart else firstMatch
        prefix = "…"
    }
    val shift = prefix.length - start
    return SearchSnippet(
        text = prefix + best.substring(start).trimEnd(),
        highlights = highlights.filter { it.first >= start }.map { (it.first + shift)..(it.last + shift) }
    )
}

/**
 * [text] lowercased without diacritics, with each run of characters other than letters and digits
 * collapsed into one space (apostrophes dropped: `don't` matches `dont`), and trimmed.
 */
internal fun foldForSearch(text: String): String {
    val folded = StringBuilder(text.length)
    var pendingSpace = false
    for (c in text) {
        if (c == '\'' || c == '’') continue
        val f = foldChar(c)
        if (f.isLetterOrDigit()) {
            if (pendingSpace && folded.isNotEmpty()) folded.append(' ')
            pendingSpace = false
            folded.append(f)
        } else {
            pendingSpace = true
        }
    }
    return folded.toString()
}

/** [text] lowercased without diacritics, one character for each of the original */
private fun foldChars(text: String): String = buildString(text.length) { text.forEach { append(foldChar(it)) } }

/** Folded form of each character met so far; `\u0000` until computed. Concurrent writes store the same value. */
private val foldedChars = CharArray(Char.MAX_VALUE.code + 1)

private fun foldChar(c: Char): Char {
    if (c.code < 128) return c.lowercaseChar()
    val cached = foldedChars[c.code]
    if (cached != '\u0000') return cached
    // the base letter comes first in the canonical decomposition: č -> c + combining caron
    val folded = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0].lowercaseChar()
    foldedChars[c.code] = folded
    return folded
}
