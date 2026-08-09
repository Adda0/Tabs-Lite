package com.gbros.tabslite.utilities

/**
 * The parsed contents of a locally-imported song file: a small `key: value` metadata header,
 * a blank line, then the tab body. The body accepts three chord notations, which all get
 * converted to the app's internal format (`{ch:C}` embedded inline, `[tab]...[/tab]` wrapping):
 * - the app's own internal format directly (`{ch:C}` inline)
 * - Ultimate Guitar's raw wire format (`[ch]C[/ch]` on its own line above the lyric line)
 * - a hand-written chord sheet, chords on their own line above the lyrics (e.g. `Em7   G   Dsus4`
 *   above `Today is gonna be the day...`), the most natural way to type one by hand
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
)

private val HEADER_LINE_REGEX = Regex("^\\s*([A-Za-z_]+)\\s*:\\s*(.*)$")

/**
 * Parses a local song file's raw text into its metadata header and content body.
 *
 * Recognized header keys (case-insensitive): `song_name` and `artist_name` (required), `capo`,
 * `tuning`, `difficulty`, `song_genre`, `version_description` (all optional). Header parsing stops
 * at the first line that isn't a `key: value` pair; everything from there on (after skipping a
 * single separating blank line, if present) is the tab content body.
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
        header[match.groupValues[1].lowercase()] = match.groupValues[2].trim()
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

    return LocalSongFile(
        songName = songName,
        artistName = artistName,
        capo = header["capo"]?.toIntOrNull() ?: 0,
        tuning = header["tuning"]?.takeIf { it.isNotBlank() } ?: "E A D G B E",
        difficulty = header["difficulty"]?.takeIf { it.isNotBlank() } ?: "novice",
        songGenre = header["song_genre"].orEmpty(),
        versionDescription = header["version_description"].orEmpty(),
        content = convertedBody
    )
}
