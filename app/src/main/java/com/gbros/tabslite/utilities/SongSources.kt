package com.gbros.tabslite.utilities

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Czech songbook sites songs can be found on and downloaded from besides Ultimate Guitar. None
 * of them has a public API, so each is read the way its own web pages read it.
 */
enum class SongSource(val prefix: String, val siteName: String) {
    PLNY_ZPEVNIK("plnyzpevnik", "plnyzpevnik.cz"),
    VELKY_ZPEVNIK("velkyzpevnik", "velkyzpevnik.cz"),
    KYTARISTKA("kytaristka", "kytaristka.cz"),
    SUPERMUSIC("supermusic", "supermusic.cz"),
    ZPEVNIK_WZ("zpevnikwz", "zpevnik.wz.cz"),
}

/**
 * A song on a [SongSource]: the page at [path] (without leading `/`), and which of the versions
 * that page holds ([version], counted from 0; only plnyzpevnik.cz puts several on one page).
 */
data class SongbookSong(val source: SongSource, val path: String, val version: Int = 0) {
    /**
     * The tab id the song is stored under, so it can always be downloaded again from the id alone:
     * `<prefix>:<path>` with `/` replaced by `:`, and `@<version>` after it for any version but the
     * page's first (`velkyzpevnik:kabat:colorado`, `plnyzpevnik:kabat:colorado@2`)
     */
    val tabId: String get() = "${source.prefix}:${path.replace('/', ':')}" + if (version > 0) "@$version" else ""

    val pageUrl: String get() = when (source) {
        SongSource.PLNY_ZPEVNIK -> "https://plnyzpevnik.cz/zpevnik/$path"
        SongSource.VELKY_ZPEVNIK -> "https://www.velkyzpevnik.cz/$path"
        SongSource.KYTARISTKA -> "https://kytaristka.cz/zpevnik/$path"
        SongSource.SUPERMUSIC -> "https://www.supermusic.cz/piesen.php?idpiesne=$path"
        SongSource.ZPEVNIK_WZ -> "https://www.zpevnik.wz.cz/index.php?id=$path"
    }
}

/** A song found on a [SongSource] */
data class SongSearchHit(val song: SongbookSong, val songName: String, val artistName: String)

/**
 * A version of a song downloaded from a [SongSource], [content] in the app's internal format.
 * [versionCount] is how many versions the song's page holds.
 */
data class DownloadedSong(
    val song: SongbookSong,
    val versionCount: Int,
    val songName: String,
    val artistName: String,
    val content: String,
    val key: String = "",
    val capo: Int = 0,
    val tempo: String = "",
)

/** The page a song was found on can't be read: it has no song on it, or its layout has changed */
class SongPageException(message: String) : IOException(message)

object SongSources {
    // the sites serve their pages to browsers only (Cloudflare rejects other clients outright)
    private const val BROWSER_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    private const val MAX_SEARCH_RESULTS = 20

    private val TAB_ID_VERSION_REGEX = Regex("@(\\d+)$")

    /** The songbook song stored under [tabId] (see [SongbookSong.tabId]), or null for an Ultimate Guitar tab */
    fun sourceOf(tabId: String): SongbookSong? {
        val source = SongSource.entries.firstOrNull { tabId.startsWith("${it.prefix}:") } ?: return null
        val rest = tabId.removePrefix("${source.prefix}:")
        val version = TAB_ID_VERSION_REGEX.find(rest)
        return SongbookSong(source, rest.removeSuffix(version?.value.orEmpty()).replace(':', '/'), version?.groupValues?.get(1)?.toInt() ?: 0)
    }

    /** The web page of the song stored under [tabId], or null for an Ultimate Guitar tab */
    fun pageUrl(tabId: String): String? = sourceOf(tabId)?.pageUrl

    /**
     * Songs on [source] matching [query], each as the first version on its page. Blocking.
     *
     * @throws IOException if the site can't be reached or answers with something unexpected
     */
    fun search(source: SongSource, query: String): List<SongSearchHit> = when (source) {
        SongSource.PLNY_ZPEVNIK -> searchPlnyZpevnik(query)
        SongSource.VELKY_ZPEVNIK -> searchVelkyZpevnik(query)
        SongSource.KYTARISTKA -> searchKytaristka(query)
        SongSource.SUPERMUSIC -> searchSuperMusic(query)
        SongSource.ZPEVNIK_WZ -> searchZpevnikWz(query)
    }.take(MAX_SEARCH_RESULTS)

    /**
     * Downloads every version of a song on the page of the one stored under [tabId], that one
     * among them. Blocking.
     *
     * @throws IllegalArgumentException if [tabId] isn't one of a [SongSource]
     * @throws IOException if the site can't be reached or the page has no song on it
     */
    fun download(tabId: String): List<DownloadedSong> {
        val song = requireNotNull(sourceOf(tabId)) { "$tabId is not a song from a songbook site" }
        val page = SongbookSong(song.source, song.path)
        return when (song.source) {
            SongSource.PLNY_ZPEVNIK -> downloadPlnyZpevnik(page)
            SongSource.VELKY_ZPEVNIK -> listOf(downloadVelkyZpevnik(page))
            SongSource.KYTARISTKA -> listOf(downloadKytaristka(page))
            SongSource.SUPERMUSIC -> listOf(downloadSuperMusic(page))
            SongSource.ZPEVNIK_WZ -> listOf(downloadZpevnikWz(page))
        }
    }

    //#region plnyzpevnik.cz

    private fun searchPlnyZpevnik(query: String): List<SongSearchHit> {
        val response = JsonParser.parseString(get("https://plnyzpevnik.cz/api/search?q=${encode(query)}")).asJsonObject
        return response.getAsJsonArray("results").map { it.asJsonObject }
            .filter { it.string("type") == "song" }
            .map { SongSearchHit(SongbookSong(SongSource.PLNY_ZPEVNIK, "${it.string("artistSlug")}/${it.string("songSlug")}"), it.string("title"), it.string("artist")) }
    }

    /**
     * The page is a Next.js app: the song, with its `variants` (each a version with its own
     * ChordPro), is part of the server component payload streamed into it
     */
    private fun downloadPlnyZpevnik(page: SongbookSong): List<DownloadedSong> {
        val html = get(page.pageUrl)
        val payload = quotedLiteralsAfter(html, "self.__next_f.push([1,")
            .joinToString("") { JsonParser.parseString(it).asString }
        val rows = parseFlightRows(payload)
        val variants = rows.values.asSequence()
            .filter { it.contains("\"variants\":") }
            .mapNotNull { row -> runCatching { JsonParser.parseString(row) }.getOrNull()?.let(::findVariants) }
            .firstOrNull()
            ?: throw SongPageException("No song found on ${page.pageUrl}")
        val chordPros = variants
            .sortedBy { it.get("order")?.takeIf { order -> order.isJsonPrimitive }?.asInt ?: 0 }
            .mapNotNull { variant -> variant.string("chordpro").let { if (it.startsWith("$")) rows[it.substring(1)] else it } }
        val recording = musicRecording(html)
        return chordPros.mapIndexed { version, chordPro ->
            songFromChordPro(page.copy(version = version), chordPros.size, chordPro, recording)
        }
    }

    /** The first `variants` array, of objects with a `chordpro` each, anywhere in [element] */
    private fun findVariants(element: JsonElement): List<JsonObject>? = when {
        element.isJsonArray -> element.asJsonArray.firstNotNullOfOrNull(::findVariants)
        element.isJsonObject -> {
            val variants = element.asJsonObject.get("variants")
            if (variants != null && variants.isJsonArray && variants.asJsonArray.any { it.isJsonObject && it.asJsonObject.has("chordpro") }) {
                variants.asJsonArray.filter { it.isJsonObject }.map { it.asJsonObject }
            } else {
                element.asJsonObject.entrySet().firstNotNullOfOrNull { findVariants(it.value) }
            }
        }
        else -> null
    }

    /**
     * Splits a React server component ("flight") payload into its rows by id: `id:T<hex length>,<text>`
     * rows hold that many UTF-8 bytes of raw text (the long strings other rows point to as `"$id"`),
     * any other row runs to the end of its line.
     */
    private fun parseFlightRows(payload: String): Map<String, String> {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val rows = mutableMapOf<String, String>()
        var position = 0
        while (position < bytes.size) {
            val colon = bytes.indexOf(':'.code.toByte(), position).takeIf { it >= 0 } ?: break
            val id = String(bytes, position, colon - position, Charsets.UTF_8)
            position = colon + 1
            if (position < bytes.size && bytes[position] == 'T'.code.toByte()) {
                val comma = bytes.indexOf(','.code.toByte(), position).takeIf { it >= 0 } ?: break
                val length = String(bytes, position + 1, comma - position - 1, Charsets.US_ASCII).toIntOrNull(16) ?: break
                val end = minOf(comma + 1 + length, bytes.size)
                rows[id] = String(bytes, comma + 1, end - comma - 1, Charsets.UTF_8)
                position = end
            } else {
                val end = bytes.indexOf('\n'.code.toByte(), position).takeIf { it >= 0 } ?: bytes.size
                rows[id] = String(bytes, position, end - position, Charsets.UTF_8)
                position = end + 1
            }
        }
        return rows
    }

    //#endregion

    //#region velkyzpevnik.cz

    /** The `res` cookie the site's browser check sets, once solved; reused until the site asks again */
    @Volatile private var velkyZpevnikCookie: String? = null

    private fun searchVelkyZpevnik(query: String): List<SongSearchHit> =
        JsonParser.parseString(getVelkyZpevnik("https://www.velkyzpevnik.cz/?q=${encode(query)}&do=typeaheadSongInterpret")).asJsonArray
            .map { it.asJsonObject }
            .filter { it.string("type") == "song" }
            .map { SongSearchHit(SongbookSong(SongSource.VELKY_ZPEVNIK, it.string("url").trim('/')), it.string("name"), it.string("interpret")) }

    /** Chords above the lyrics in a `<pre>`, every chord marked by a `<span class='chord'>` */
    private fun downloadVelkyZpevnik(page: SongbookSong): DownloadedSong {
        val html = getVelkyZpevnik(page.pageUrl)
        val sheet = Regex("<pre[^>]*id=\"chordbox\"[^>]*>(.*?)</pre>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            ?: throw SongPageException("No song found on ${page.pageUrl}")
        val tagged = Regex("<span[^>]*class=['\"]chord['\"][^>]*>(.*?)</span>", RegexOption.DOT_MATCHES_ALL)
            .replace(sheet) { chord -> chordTag(htmlText(chord.groupValues[1]).trim()) }
        return DownloadedSong(
            song = page,
            versionCount = 1,
            songName = Regex("<h1[^>]*>(.*?)</h1>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.let { htmlText(it).trim() }.orEmpty(),
            artistName = Regex("title=\"Detail interpreta\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.let { htmlText(it).trim() }.orEmpty(),
            content = convertChordSheet(htmlText(tagged).trimEnd()),
            // the key the chords are shown in, as the print link passes it on
            key = Regex("[?&;]tune=([^&\"]+)&amp;do=print").find(html)?.groupValues?.get(1).orEmpty(),
        )
    }

    /** [url] from velkyzpevnik.cz, answering its browser check first when it asks for one */
    private fun getVelkyZpevnik(url: String): String {
        val first = request(url, cookie = velkyZpevnikCookie)
        if (first.code != 503 || !first.body.contains("const a0_")) return first.bodyOrThrow(url)
        velkyZpevnikCookie = solveVelkyZpevnikCheck(first.body)
        val second = request(url, cookie = velkyZpevnikCookie)
        return second.bodyOrThrow(url)
    }

    /**
     * The site's browser check is a proof of work: find the number `i` for which the SHA-1 of
     * `<challenge><i>` has two given bytes at an offset named by the challenge's first hex digit,
     * then send `res=<challenge><i>` as a cookie. Its script holds the challenge, cookie name and
     * bytes in an obfuscated but stable shape.
     */
    private fun solveVelkyZpevnikCheck(page: String): String {
        val strings = Regex("const a0_0x[0-9a-f]+=\\[([^\\]]*)]").find(page)?.groupValues?.get(1)
            ?.split(',')?.map { it.trim().trim('\'', '"') }
            ?: throw SongPageException("velkyzpevnik.cz browser check has changed")
        val challenge = strings.firstOrNull { it.matches(Regex("[0-9A-Fa-f]{40}")) }
        val cookieName = strings.firstOrNull { it.endsWith("=") }
        val expected = Regex("s\\[n1]===(0x[0-9a-f]+)&&s\\[n1\\+0x1]===(0x[0-9a-f]+)").find(page)
        if (challenge == null || cookieName == null || expected == null) throw SongPageException("velkyzpevnik.cz browser check has changed")
        val first = expected.groupValues[1].removePrefix("0x").toInt(16).toByte()
        val second = expected.groupValues[2].removePrefix("0x").toInt(16).toByte()
        val offset = challenge.substring(0, 1).toInt(16)
        val sha1 = MessageDigest.getInstance("SHA-1")
        for (i in 0 until 50_000_000) {
            val digest = sha1.digest("$challenge$i".toByteArray(Charsets.US_ASCII))
            if (digest[offset] == first && digest[offset + 1] == second) return "$cookieName$challenge$i"
        }
        throw SongPageException("velkyzpevnik.cz browser check could not be solved")
    }

    //#endregion

    //#region kytaristka.cz

    /** The search key kytaristka.cz's own pages search with, read from its script bundle */
    @Volatile private var kytaristkaSearchKey: String? = null

    private val KYTARISTKA_SEARCH_KEY_REGEX = Regex("host:\"https://meilisearch\\.mangozpevnik\\.cz\",apiKey:\"([0-9a-f]+)\"")

    /** kytaristka.cz searches a Meilisearch index it shares with other songbooks, filtered to its own songs */
    private fun searchKytaristka(query: String): List<SongSearchHit> {
        val body = JsonObject().apply {
            addProperty("q", query)
            addProperty("limit", MAX_SEARCH_RESULTS)
            add("filter", JsonArray().apply { add("source=kytaristka") })
            add("attributesToRetrieve", JsonArray().apply { add("title"); add("authorTitle"); add("sourceLink") })
        }.toString()
        var response = searchKytaristkaIndex(body, kytaristkaSearchKey ?: fetchKytaristkaSearchKey())
        if (response.code == 401 || response.code == 403) {
            // the key has been replaced; the site's current bundle holds the new one
            response = searchKytaristkaIndex(body, fetchKytaristkaSearchKey())
        }
        return JsonParser.parseString(response.bodyOrThrow("kytaristka.cz search")).asJsonObject
            .getAsJsonArray("hits").map { it.asJsonObject }
            .mapNotNull { hit ->
                val path = hit.string("sourceLink").substringAfter("kytaristka.cz/zpevnik/", "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                SongSearchHit(SongbookSong(SongSource.KYTARISTKA, path), hit.string("title"), hit.string("authorTitle"))
            }
    }

    private fun searchKytaristkaIndex(body: String, key: String): Response = request(
        "https://meilisearch.mangozpevnik.cz/indexes/songs/search",
        postJson = body,
        headers = mapOf("Authorization" to "Bearer $key", "Origin" to "https://kytaristka.cz"),
    )

    private fun fetchKytaristkaSearchKey(): String {
        val home = get("https://kytaristka.cz/")
        for (script in Regex("src=\"(/_nuxt/[^\"]+\\.js)\"").findAll(home).map { it.groupValues[1] }.distinct()) {
            KYTARISTKA_SEARCH_KEY_REGEX.find(get("https://kytaristka.cz$script"))?.let { match ->
                return match.groupValues[1].also { kytaristkaSearchKey = it }
            }
        }
        throw SongPageException("kytaristka.cz search key not found")
    }

    /** The page is a Nuxt app: the song's ChordPro is a string literal in the page state script */
    private fun downloadKytaristka(page: SongbookSong): DownloadedSong {
        val html = get(page.pageUrl)
        val chordPro = quotedLiteralsAfter(html, "chordpro:").firstOrNull()?.let { unescapeJavaScript(it.substring(1, it.length - 1)) }
            ?: throw SongPageException("No song found on ${page.pageUrl}")
        return songFromChordPro(page, 1, chordPro, musicRecording(html))
    }

    //#endregion

    //#region supermusic.cz

    /** The `_sm_verified` cookie the site's browser check sets, once solved; reused until the site asks again */
    @Volatile private var superMusicCookie: String? = null

    private val SUPERMUSIC_RESULT_REGEX = Regex(
        "<a href=\"piesen\\.php\\?id=(\\d+)\">(.*?)</a>.*?class=\"result-artist\">\\s*<a[^>]*>(.*?)</a>.*?class=\"result-song-type\">(.*?)</span>",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** The site's own search page, songs with chords only (it also lists lyrics, tabs and sheet music) */
    private fun searchSuperMusic(query: String): List<SongSearchHit> =
        htmlBlocks(getSuperMusic("https://www.supermusic.cz/najdi.php?hladane=${encode(query)}"), "class=\"result-item\"")
            .mapNotNull { SUPERMUSIC_RESULT_REGEX.find(it) }
            .filter { it.groupValues[4].contains("akord") }
            .map { SongSearchHit(SongbookSong(SongSource.SUPERMUSIC, it.groupValues[1]), htmlText(it.groupValues[2]).trim(), htmlText(it.groupValues[3]).trim()) }

    /** Lyrics with each chord inline at its syllable, in `<sup><a class="chord">` */
    private fun downloadSuperMusic(page: SongbookSong): DownloadedSong {
        val html = getSuperMusic(page.pageUrl)
        val text = Regex("<div class=\"chord-text\" id=\"chordText\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            ?: throw SongPageException("No song found on ${page.pageUrl}")
        val tagged = Regex("<sup>\\s*<a[^>]*class=\"chord\"[^>]*>(.*?)</a>\\s*</sup>", RegexOption.DOT_MATCHES_ALL)
            .replace(text) { chord -> chordTag(htmlText(chord.groupValues[1]).trim()) }
        val (songName, artistName) = musicRecording(html) ?: throw SongPageException("No song found on ${page.pageUrl}")
        return DownloadedSong(
            song = page,
            versionCount = 1,
            songName = songName,
            artistName = artistName,
            content = convertChordSheet(htmlText(tagged.replace(Regex("<br\\s*/?>"), "\n")).trimEnd()),
        )
    }

    /** [url] from supermusic.cz, answering its browser check first when it asks for one */
    private fun getSuperMusic(url: String): String {
        val first = request(url, cookie = superMusicCookie)
        val check = Regex("data-n=\"([^\"]+)\" data-l=\"(\\d+)\" data-t=\"([^\"]+)\" data-u=\"([^\"]+)\"").find(first.body)
        if (check == null || first.body.length > 4096) return first.bodyOrThrow(url)
        val (nonce, limit, token, target) = check.destructured
        val answer = solveSuperMusicCheck(nonce, limit.toLong())
        val solved = request("https://www.supermusic.cz${htmlText(target)}${if ('?' in target) '&' else '?'}_smc=$token.$answer")
        superMusicCookie = solved.cookies.joinToString("; ").ifEmpty { null }
        return solved.bodyOrThrow(url)
    }

    /** The site's browser check: the first `i` whose 32-bit FNV-1a hash of `<nonce>:<i>` is below [limit] */
    private fun solveSuperMusicCheck(nonce: String, limit: Long): Int {
        for (i in 0 until 100_000_000) {
            var hash = 0x811c9dc5L
            for (byte in "$nonce:$i".toByteArray(Charsets.UTF_8)) {
                hash = ((hash xor (byte.toLong() and 0xff)) * 16777619L) and 0xffffffffL
            }
            if (hash < limit) return i
        }
        throw SongPageException("supermusic.cz browser check could not be solved")
    }

    //#endregion

    //#region zpevnik.wz.cz

    private val ZPEVNIK_WZ_RESULT_REGEX = Regex("<li><a href=\"\\?id=(\\d+)\">(.*?)</a> \\(<a href=\"\\?id=\\d+\">(.*?)</a>\\)</li>")

    private fun searchZpevnikWz(query: String): List<SongSearchHit> =
        ZPEVNIK_WZ_RESULT_REGEX.findAll(get("https://www.zpevnik.wz.cz/index.php?pg=vysledky_vyhledavani&nazev=${encode(query)}&slova=&autor="))
            .map { SongSearchHit(SongbookSong(SongSource.ZPEVNIK_WZ, it.groupValues[1]), htmlText(it.groupValues[2]).trim(), zpevnikWzArtist(it.groupValues[3])) }
            .toList()

    /** The site files songs without a known artist under categories it marks with a dash: `- Ostatní` */
    private fun zpevnikWzArtist(html: String): String = htmlText(html).trim().removePrefix("-").trim()

    /** A plain chord sheet, chords on their own lines above the lyrics, in `<div class="song">` */
    private fun downloadZpevnikWz(page: SongbookSong): DownloadedSong {
        val html = get(page.pageUrl)
        val heading = Regex("<h1>(.*?)\\(<a href=\"\\?id_a=\\d+\">(.*?)</a>\\)</h1>", RegexOption.DOT_MATCHES_ALL).find(html)
        val sheet = Regex("<div class=\"song\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
        if (heading == null || sheet == null) throw SongPageException("No song found on ${page.pageUrl}")
        return DownloadedSong(
            song = page,
            versionCount = 1,
            songName = htmlText(heading.groupValues[1]).trim(),
            artistName = zpevnikWzArtist(heading.groupValues[2]),
            content = convertChordSheet(htmlText(sheet.replace("\r\n", "\n").replace(Regex("<br\\s*/?>"), "\n")).trimEnd()),
        )
    }

    /** The parts of [html] that each start with [marker], up to the next one */
    private fun htmlBlocks(html: String, marker: String): List<String> = html.split(marker).drop(1)

    //#endregion

    //#region shared parsing

    private fun songFromChordPro(song: SongbookSong, versionCount: Int, chordPro: String, recording: Pair<String, String>?): DownloadedSong {
        val converted = convertChordPro(chordPro)
        if (converted.content.isBlank()) throw SongPageException("Empty song on ${song.pageUrl}")
        return DownloadedSong(
            song = song,
            versionCount = versionCount,
            songName = recording?.first ?: converted.title,
            artistName = recording?.second ?: converted.artist,
            content = converted.content,
            key = converted.key,
            capo = converted.capo,
            tempo = converted.tempo,
        )
    }

    /**
     * Every double-quoted string literal (quotes included, escapes left as written) that directly
     * follows [marker] in [text]. Scanned by hand: a regex for it overflows the stack on the long
     * literals song pages hold.
     */
    private fun quotedLiteralsAfter(text: String, marker: String): List<String> {
        val literals = mutableListOf<String>()
        var from = 0
        while (true) {
            val start = text.indexOf(marker, from).takeIf { it >= 0 }?.plus(marker.length) ?: return literals
            from = start
            if (start >= text.length || text[start] != '"') continue
            var i = start + 1
            while (i < text.length && text[i] != '"') i += if (text[i] == '\\') 2 else 1
            if (i >= text.length) return literals
            literals.add(text.substring(start, i + 1))
            from = i + 1
        }
    }

    /**
     * Song name and artist from the page's schema.org description: its `MusicRecording`, or else
     * (not every song page has one) the last two steps of its `BreadcrumbList`: `… › artist › song`
     */
    private fun musicRecording(html: String): Pair<String, String>? {
        val descriptions = Regex("<script[^>]*type=\"application/ld\\+json\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).findAll(html)
            .mapNotNull { runCatching { JsonParser.parseString(it.groupValues[1]) }.getOrNull() }
            .flatMap { if (it.isJsonArray) it.asJsonArray.asSequence() else sequenceOf(it) }
            .filter { it.isJsonObject }
            .map { it.asJsonObject }
            .toList()
        descriptions.firstOrNull { it.string("@type") == "MusicRecording" }?.let { recording ->
            return recording.string("name") to (recording.getAsJsonObject("byArtist")?.string("name").orEmpty())
        }
        val steps = descriptions.firstOrNull { it.string("@type") == "BreadcrumbList" }?.getAsJsonArray("itemListElement")
            ?.map { it.asJsonObject }?.sortedBy { it.get("position")?.asInt ?: 0 }
            ?.map { step -> step.string("name").ifEmpty { step.get("item")?.takeIf { it.isJsonObject }?.asJsonObject?.string("name").orEmpty() } }
        return steps?.takeIf { it.size >= 4 }?.let { it.last() to it[it.size - 2] }
    }

    private fun JsonObject.string(member: String): String = get(member)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    /** The text of an HTML fragment: tags dropped, entities decoded */
    private fun htmlText(html: String): String = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(html.replace(Regex("<[^>]*>"), "")) { entity ->
        val name = entity.groupValues[1]
        when {
            name.startsWith("#x") -> name.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            name.startsWith("#") -> name.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0")[name]
        } ?: entity.value
    }

    /** The value of the body of a double-quoted JavaScript string literal */
    private fun unescapeJavaScript(literal: String): String {
        val result = StringBuilder(literal.length)
        var i = 0
        while (i < literal.length) {
            val c = literal[i]
            if (c != '\\' || i + 1 >= literal.length) { result.append(c); i++; continue }
            val escaped = literal[i + 1]
            i += 2
            when (escaped) {
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'b' -> result.append('\b')
                'f' -> result.append('\u000C')
                'u' -> literal.substring(i, minOf(i + 4, literal.length)).toIntOrNull(16)?.let { result.append(it.toChar()); i += 4 } ?: result.append(escaped)
                'x' -> literal.substring(i, minOf(i + 2, literal.length)).toIntOrNull(16)?.let { result.append(it.toChar()); i += 2 } ?: result.append(escaped)
                else -> result.append(escaped)
            }
        }
        return result.toString()
    }

    //#endregion

    //#region HTTP

    private class Response(val code: Int, val body: String, val headers: Map<String?, List<String>> = emptyMap()) {
        fun bodyOrThrow(what: String): String = when {
            code == 404 -> throw FileNotFoundException("$what: not found (404)")
            code !in 200..299 -> throw IOException("$what: HTTP $code")
            else -> body
        }

        /** The `name=value` of every cookie the response sets */
        val cookies: List<String> get() = headers.entries.filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value }.map { it.substringBefore(';') }
    }

    private fun get(url: String): String = request(url).bodyOrThrow(url)

    private fun request(url: String, cookie: String? = null, postJson: String? = null, headers: Map<String, String> = emptyMap()): Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 8000
            connection.setRequestProperty("User-Agent", BROWSER_USER_AGENT)
            connection.setRequestProperty("Accept-Language", "cs")
            cookie?.let { connection.setRequestProperty("Cookie", it) }
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (postJson != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(postJson.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            return Response(code, stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty(), connection.headerFields)
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(query: String): String = URLEncoder.encode(query, "UTF-8")

    private fun ByteArray.indexOf(byte: Byte, from: Int): Int {
        for (i in from until size) if (this[i] == byte) return i
        return -1
    }

    //#endregion
}
