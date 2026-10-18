package com.gbros.tabslite.utilities

/**
 * The parsed contents of a locally-imported song file: a small `key: value` metadata header,
 * a blank line, then the tab body. The body accepts three chord notations, which all get
 * converted to the app's internal format (`{ch:C}` embedded inline, `[tab]...[/tab]` wrapping):
 * - the app's own internal format directly (`{ch:C}` inline)
 * - Ultimate Guitar's raw wire format (`[ch]C[/ch]` on its own line above the lyric line)
 * - a hand-written chord sheet, chords on their own line above the lyrics (e.g. `Em7   G   Dsus4`
 *   above `Today is gonna be the day...`), the most natural way to type one by hand. A chord line
 *   is normally recognized by shape (every token on it is a chord, or the notation chord sheets
 *   are written with: bar lines, repeat brackets and counts like `[: Em D :] 3x`, and a closing
 *   aside addressed to another section like `(R: + D)`), with no annotation needed at all, but it
 *   can also be marked explicitly with a leading `^` and/or trailing `$` (mirroring the regex line
 *   anchors) to force a line the shape heuristic would otherwise miss - `$` is the one to prefer
 *   when alignment matters, since it costs no column, unlike `^`
 */
data class LocalSongFile(
    val songName: String,
    val artistName: String,
    val capo: Int,
    val tuning: String,
    val difficulty: String,
    val songGenre: String,
    val versionDescription: String,
    val content: String,
    /** The key the content is written in, or blank if not given */
    val key: String = "",
    /** Chord shape transposition that, with the written [capo], sounds in the original recording's key; null if not given */
    val originalTranspose: Int? = null,
    /** Favourite ways to play it (sounding key + capo), in file order */
    val favorites: List<FavoriteKey> = emptyList(),
    /** Time signature and/or speed as written, e.g. `4/4` or `4/4 (~68 BPM)` */
    val tempo: String = "",
    /** `label: id` references into the rhythms file, see [parseRhythmReferences] */
    val rhythms: List<RhythmReference> = emptyList(),
    /** Autoscroll speed slider position (0..1) from file header, or null if not given */
    val autoscroll: Float? = null,
    /** Tags from the `tags` (or `tag`) header, see [parseTags] */
    val tags: List<String> = emptyList(),
    /** The language of the song's words from the `language` header (`cz`, `en`), or blank if not given */
    val language: String = "",
)

private val HEADER_LINE_REGEX = Regex("^\\s*([A-Za-z_-]+)\\s*:\\s*(.*)$")

/**
 * Parses a local song file's raw text into its metadata header and content body.
 *
 * Recognized header keys (case-insensitive, `-` and `_` interchangeable, `favourite` accepted for
 * `favorite`): `song_name` and `artist_name` (required), `capo`, `tuning`, `difficulty`,
 * `song_genre`, `version_description`, `key`, `original_key`, `favorite_key`, `favorite_transpose`,
 * `tempo`, `rhythm`, `autoscroll`, `tags`, `language` (all optional). `key` is the key the chords are written in and `capo` the capo
 * they're written for; together they sound in key + capo, the original key unless `original_key`
 * (the sounding key) says otherwise. `favorite_key` is a comma-separated list of chord shapes, each
 * optionally `@` a capo fret, a leading `*` marking the one that suits the singer's voice best:
 * `favorite_key: D@1, *D@2, E` (D shapes at capo 2 sound E; no `@` means no capo).
 * `favorite_key` and `original_key` need `key`; without it, `favorite_transpose` takes half steps
 * from the written chords instead of chord names (`+2@2`). `tempo` holds the time
 * signature (`4/4`, optionally with a speed: `4/4 (~68 BPM)`), which selects the group of the
 * rhythms file that `rhythm` (`verse: 8, r: 4`) numbers refer to. `autoscroll` is the autoscroll
 * slider position (0..1); `tags` a comma-separated list (`táborák, vánoční`); `language` the language
 * of the song's words (`cz` or `en`; anything else is read as unset). Header parsing stops at the
 * first line that isn't a `key: value` pair; everything from there on (after skipping a single
 * separating blank line, if present) is the tab content body.
 *
 * @return the parsed [LocalSongFile], or null if the required `song_name`/`artist_name` header
 * fields are missing.
 */
fun parseLocalSongFile(rawText: String): LocalSongFile? {
    val lines = rawText.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    val header = mutableMapOf<String, String>()

    var bodyStartIndex = lines.size
    for ((index, line) in lines.withIndex()) {
        val match = HEADER_LINE_REGEX.matchEntire(line)
        if (match == null) {
            bodyStartIndex = index
            break
        }
        val headerKey = normalizeHeaderKey(match.groupValues[1])
        header[headerKey] = match.groupValues[2].trim()
    }

    val songName = header["song_name"]?.trim().orEmpty()
    val artistName = header["artist_name"]?.trim().orEmpty()
    if (songName.isBlank() || artistName.isBlank()) {
        return null
    }

    // skip a single leading blank line separating the header from the body
    if (bodyStartIndex < lines.size && lines[bodyStartIndex].isBlank()) {
        bodyStartIndex++
    }

    val rawBody = lines.drop(bodyStartIndex).joinToString("\n")
    val convertedBody = convertChordSheet(rawBody)
    val writtenKey = header["key"].orEmpty()
    val writtenCapo = header["capo"]?.toIntOrNull() ?: 0
    // half steps from the written key to a key, or a signed number of them
    fun offsetTo(value: String): Int? =
        value.toIntOrNull() ?: writtenKey.takeIf { it.isNotBlank() }?.let { transposeBetweenKeys(it, value) }
    val favorites = listOfNotNull(header["favorite_key"], header["favorite_transpose"])
        .flatMap { it.split(',') }
        .mapNotNull { entry -> FAVORITE_ENTRY_REGEX.matchEntire(entry.trim()) }
        .mapNotNull { match ->
            val transpose = offsetTo(match.groupValues[2]) ?: return@mapNotNull null
            FavoriteKey(transpose, capo = match.groupValues[3].toIntOrNull() ?: 0, best = match.groupValues[1].isNotEmpty())
        }
    val originalTranspose = header["original_key"]?.takeIf { it.isNotBlank() }?.let { offsetTo(it) }?.minus(writtenCapo)

    return LocalSongFile(
        songName = songName,
        artistName = artistName,
        capo = writtenCapo,
        tuning = header["tuning"]?.takeIf { it.isNotBlank() } ?: "E A D G B E",
        difficulty = header["difficulty"]?.takeIf { it.isNotBlank() } ?: "novice",
        songGenre = header["song_genre"].orEmpty(),
        versionDescription = header["version_description"].orEmpty(),
        content = convertedBody,
        key = writtenKey,
        originalTranspose = originalTranspose,
        favorites = favorites,
        tempo = header["tempo"].orEmpty(),
        autoscroll = header["autoscroll"]?.toFloatOrNull()?.coerceIn(0f, 1f),
        rhythms = parseRhythmReferences((header["rhythm"] ?: header["rhythms"]).orEmpty()),
        tags = parseTags((header["tags"] ?: header["tag"]).orEmpty()),
        language = parseLanguage(header["language"].orEmpty())
    )
}

// `*D@2`: best marker, chord shapes (or half steps), optional `@` capo
private val FAVORITE_ENTRY_REGEX = Regex("^(\\*?)\\s*([^\\s@*]+)\\s*(?:@\\s*(\\d+))?$")

/** Formats [favorite] for a `favorite_key` header (`*D@2`), or `favorite_transpose` (`*+2@2`) when [writtenKey] is blank */
fun formatFavoriteEntry(favorite: FavoriteKey, writtenKey: String, useFlats: Boolean, transposeChord: (String, Int, Boolean) -> String): String {
    val shapes = if (writtenKey.isNotBlank()) transposeChord(writtenKey, favorite.transpose, useFlats)
        else if (favorite.transpose > 0) "+${favorite.transpose}" else favorite.transpose.toString()
    val capo = if (favorite.capo > 0) "@${favorite.capo}" else ""
    return (if (favorite.best) "*" else "") + shapes + capo
}

private fun normalizeHeaderKey(key: String) = key.lowercase().replace('-', '_').replace("favourite", "favorite")

private val FAVORITE_HEADER_KEYS = setOf("favorite_key", "favorite_transpose")

/**
 * Returns [rawText] with its favourite header line set to `headerKey: value` from [favorite]
 * (`favorite_key` or `favorite_transpose`), or removed when [favorite] is null. An existing
 * favourite line, in any spelling, is replaced in place; otherwise the new line ends the header.
 * Everything else, including line endings, is left untouched.
 */
fun withFavoriteHeader(rawText: String, favorite: Pair<String, String>?): String =
    withHeaderLine(rawText, FAVORITE_HEADER_KEYS, favorite?.let { (key, value) -> "$key: $value" })

/** Returns [rawText] with its `autoscroll` header set to slider position [value] (0..1), or removed when null; see [withFavoriteHeader] */
fun withAutoscrollHeader(rawText: String, value: Float?): String =
    withHeaderLine(rawText, setOf("autoscroll"), value?.let { "autoscroll: " + String.format(java.util.Locale.ROOT, "%.2f", it) })

/** Returns [rawText] with its `tags` (or `tag`) header set to [tags], or removed when there are none; see [withFavoriteHeader] */
fun withTagsHeader(rawText: String, tags: List<String>): String =
    withHeaderLine(rawText, setOf("tags", "tag"), tags.takeIf { it.isNotEmpty() }?.let { "tags: " + it.joinToString(", ") })

private fun withHeaderLine(rawText: String, keys: Set<String>, newLine: String?): String {
    val lineEnding = if (rawText.contains("\r\n")) "\r\n" else "\n"
    val lines = rawText.split(lineEnding).toMutableList()
    val headerEnd = lines.indexOfFirst { HEADER_LINE_REGEX.matchEntire(it) == null }.let { if (it == -1) lines.size else it }
    val matchingLineIndices = (0 until headerEnd).filter { index ->
        normalizeHeaderKey(HEADER_LINE_REGEX.matchEntire(lines[index])!!.groupValues[1]) in keys
    }

    val insertAt = matchingLineIndices.firstOrNull() ?: headerEnd
    matchingLineIndices.asReversed().forEach { lines.removeAt(it) }
    if (newLine != null) {
        lines.add(insertAt, newLine)
    }
    return lines.joinToString(lineEnding)
}
