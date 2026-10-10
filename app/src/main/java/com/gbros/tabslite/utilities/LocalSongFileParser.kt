package com.gbros.tabslite.utilities

/**
 * The parsed contents of a locally-imported song file: a small `key: value` metadata header,
 * a blank line, then the tab body. The body accepts three chord notations, which all get
 * converted to the app's internal format (`{ch:C}` embedded inline, `[tab]...[/tab]` wrapping):
 * - the app's own internal format directly (`{ch:C}` inline)
 * - Ultimate Guitar's raw wire format (`[ch]C[/ch]` on its own line above the lyric line)
 * - a hand-written chord sheet, chords on their own line above the lyrics (e.g. `Em7   G   Dsus4`
 *   above `Today is gonna be the day...`), the most natural way to type one by hand. A chord line
 *   is normally recognized by shape (every token on it looks chord-like), with no annotation needed
 *   at all, but it can also be marked explicitly with a leading `^` and/or trailing `$` (mirroring
 *   the regex line anchors) to force a line the shape heuristic would otherwise miss - `$` is the
 *   one to prefer when alignment matters, since it costs no column, unlike `^`
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
    /** Half steps from [key] to the original recording's key, or null if not given */
    val originalTranspose: Int? = null,
    /** Half steps from [key] to the favourite key, or null if not given */
    val favoriteTranspose: Int? = null,
    /** Time signature and/or speed as written, e.g. `4/4` or `4/4 (~68 BPM)` */
    val tempo: String = "",
    /** `label: id` references into the rhythms file, see [parseRhythmReferences] */
    val rhythms: List<RhythmReference> = emptyList(),
)

private val HEADER_LINE_REGEX = Regex("^\\s*([A-Za-z_-]+)\\s*:\\s*(.*)$")

/**
 * Parses a local song file's raw text into its metadata header and content body.
 *
 * Recognized header keys (case-insensitive, `-` and `_` interchangeable, `favourite` accepted for
 * `favorite`): `song_name` and `artist_name` (required), `capo`, `tuning`, `difficulty`,
 * `song_genre`, `version_description`, `key`, `original_key`, `favorite_key`, `favorite_transpose`,
 * `tempo`, `rhythm` (all optional). `key` is the key the content is written in; `original_key` and
 * `favorite_key` only take effect alongside it, since they're stored as transpositions relative to
 * it; `favorite_transpose` (half steps, e.g. `+2`) is the keyless fallback. `tempo` holds the time
 * signature (`4/4`, optionally with a speed: `4/4 (~68 BPM)`), which selects the group of the
 * rhythms file that `rhythm` (`verse: 8, r: 4`) numbers refer to. Header parsing stops at the
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
    val convertedBody = wrapUnwrappedChordLines(mergeChordOnlyLines(convertBareChordLines(convertUgChordTags(rawBody))))
    val writtenKey = header["key"].orEmpty()
    fun transposeTo(headerKey: String): Int? =
        header[headerKey]?.takeIf { it.isNotBlank() && writtenKey.isNotBlank() }?.let { transposeBetweenKeys(writtenKey, it) }

    return LocalSongFile(
        songName = songName,
        artistName = artistName,
        capo = header["capo"]?.toIntOrNull() ?: 0,
        tuning = header["tuning"]?.takeIf { it.isNotBlank() } ?: "E A D G B E",
        difficulty = header["difficulty"]?.takeIf { it.isNotBlank() } ?: "novice",
        songGenre = header["song_genre"].orEmpty(),
        versionDescription = header["version_description"].orEmpty(),
        content = convertedBody,
        key = writtenKey,
        originalTranspose = transposeTo("original_key"),
        favoriteTranspose = transposeTo("favorite_key") ?: header["favorite_transpose"]?.toIntOrNull(),
        tempo = header["tempo"].orEmpty(),
        rhythms = parseRhythmReferences((header["rhythm"] ?: header["rhythms"]).orEmpty())
    )
}

private fun normalizeHeaderKey(key: String) = key.lowercase().replace('-', '_').replace("favourite", "favorite")

private val FAVORITE_HEADER_KEYS = setOf("favorite_key", "favorite_transpose")

/**
 * Returns [rawText] with its favourite header line set to `headerKey: value` from [favorite]
 * (`favorite_key` or `favorite_transpose`), or removed when [favorite] is null. An existing
 * favourite line, in any spelling, is replaced in place; otherwise the new line ends the header.
 * Everything else, including line endings, is left untouched.
 */
fun withFavoriteHeader(rawText: String, favorite: Pair<String, String>?): String {
    val lineEnding = if (rawText.contains("\r\n")) "\r\n" else "\n"
    val lines = rawText.split(lineEnding).toMutableList()
    val headerEnd = lines.indexOfFirst { HEADER_LINE_REGEX.matchEntire(it) == null }.let { if (it == -1) lines.size else it }
    val favoriteLineIndices = (0 until headerEnd).filter { index ->
        normalizeHeaderKey(HEADER_LINE_REGEX.matchEntire(lines[index])!!.groupValues[1]) in FAVORITE_HEADER_KEYS
    }

    val newLine = favorite?.let { (key, value) -> "$key: $value" }
    val insertAt = favoriteLineIndices.firstOrNull() ?: headerEnd
    favoriteLineIndices.asReversed().forEach { lines.removeAt(it) }
    if (newLine != null) {
        lines.add(insertAt, newLine)
    }
    return lines.joinToString(lineEnding)
}
