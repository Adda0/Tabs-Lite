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
 * Full text search over the songs stored on the device: title, artist and content, ignoring case,
 * diacritics (`prisel` finds `přišel`), punctuation and chord markup. Every query word has to occur
 * somewhere in the song, as a part of any word. Title/artist matches are listed first, then songs
 * containing the query as a phrase, then songs containing all its words; within each group, the
 * order of [tabs] is kept and only the first version of each song is listed.
 */
class LocalSongSearchIndex(tabs: List<TabWithDataPlaylistEntry>) {
    private class Entry(val tab: TabWithDataPlaylistEntry, val heading: String, val text: String)

    private val entries = tabs.map { tab ->
        Entry(tab, foldForSearch("${tab.songName} ${tab.artistName}"), foldForSearch(stripChordMarkup(tab.content)))
    }

    fun search(query: String): List<LocalSearchResult> {
        val phrase = foldForSearch(query)
        if (phrase.isEmpty()) return listOf()
        val words = phrase.split(' ').distinct()
        val id = query.trim()

        return entries.mapNotNull { entry ->
            val rank = when {
                entry.tab.tabId == id || words.all { entry.heading.contains(it) } -> 0
                entry.text.contains(phrase) -> 1
                words.all { entry.heading.contains(it) || entry.text.contains(it) } -> 2
                else -> return@mapNotNull null
            }
            rank to entry
        }
            .sortedBy { (rank, _) -> rank }  // stable: keeps the given order within a rank
            .distinctBy { (_, entry) -> entry.tab.songId }
            .take(MAX_RESULTS)
            .map { (rank, entry) ->
                LocalSearchResult(entry.tab, if (rank == 0) null else findSnippet(entry.tab.content, phrase, words))
            }
    }
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
