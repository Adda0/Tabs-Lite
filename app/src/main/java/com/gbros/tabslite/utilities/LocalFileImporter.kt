package com.gbros.tabslite.utilities

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.gbros.tabslite.data.DataAccess
import com.gbros.tabslite.data.Preference
import com.chrynan.chords.model.ChordMarker
import com.chrynan.chords.model.Finger
import com.chrynan.chords.model.FretNumber
import com.chrynan.chords.model.StringNumber
import com.gbros.tabslite.data.chord.Chord
import com.gbros.tabslite.data.chord.ChordVariation
import com.gbros.tabslite.data.chord.Instrument
import com.gbros.tabslite.data.playlist.Playlist
import com.gbros.tabslite.data.tab.ITab
import com.gbros.tabslite.data.tab.TabDataType
import java.text.Normalizer
import java.util.UUID

/**
 * Imports songs from a user-chosen local directory tree into the app's database, recursively, using
 * the file format described in [parseLocalSongFile]. Subdirectories are purely for the user's own
 * filesystem organization - every song file found anywhere under the chosen folder is imported flat
 * into a single "Local Files" playlist.
 */
object LocalFileImporter {
    /**
     * Marks a tab as sourced from a local song file, so a later rescan can tell which tabs are its
     * own to update/remove, without touching tabs from Ultimate Guitar or the in-app create-tab flow.
     */
    const val LOCAL_FILE_ACCESS_TYPE = "local_file"

    private const val LOCAL_FILES_PLAYLIST_TITLE = "Local Files"
    /** Marks chord variations imported from the chords file; must match the `local-%` patterns in [DataAccess] */
    private const val LOCAL_CHORD_VARIATION_ID_PREFIX = "local-"
    private val SONG_FILE_EXTENSIONS = setOf("txt", "song")

    /**
     * Recursively scans [treeUri] for song files, imports/updates them in the local database, adds
     * them all to a "Local Files" playlist, and removes any previously-imported local songs whose
     * source file is no longer present.
     *
     * @return the number of song files successfully imported
     */
    suspend fun importFromFolder(
        treeUri: Uri,
        context: Context,
        dataAccess: DataAccess,
        onProgressChange: (Float) -> Unit = {}
    ): Int {
        onProgressChange(0.02f)
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("Couldn't open local song folder $treeUri")

        val files = mutableListOf<Pair<String, DocumentFile>>() // relative path -> file
        val tables = mutableMapOf<String, Pair<Int, DocumentFile>>() // table file name -> (depth, file)
        collectSongFiles(root, "", files, tables)

        // the shared tables may sit in any folder (the shallowest copy wins); a broken table
        // mustn't stop the songs from importing
        fun readTable(name: String): String? = try {
            tables[name]?.second?.let { file -> context.contentResolver.openInputStream(file.uri)?.use { it.reader().readText() } }
        } catch (ex: Exception) {
            Log.e(TAG, "Couldn't read $name: ${ex.message}", ex)
            null
        }
        val rhythms = try {
            readTable(RHYTHMS_FILE_NAME)?.let { parseRhythms(it) }.orEmpty()
        } catch (ex: Exception) {
            Log.e(TAG, "Couldn't parse $RHYTHMS_FILE_NAME: ${ex.message}", ex)
            emptyMap()
        }
        try {
            importChordFingerings(readTable(CHORDS_FILE_NAME), dataAccess)
        } catch (ex: Exception) {
            Log.e(TAG, "Couldn't import $CHORDS_FILE_NAME: ${ex.message}", ex)
        }

        val playlistId = getOrCreateLocalFilesPlaylistId(dataAccess)
        dataAccess.clearPlaylist(playlistId)

        val importedTabIds = mutableSetOf<String>()
        var imported = 0
        for ((index, entry) in files.withIndex()) {
            val (relativePath, file) = entry
            try {
                val rawText = context.contentResolver.openInputStream(file.uri)?.use { it.reader().readText() }
                val parsed = rawText?.let { parseLocalSongFile(it) }
                if (parsed == null) {
                    Log.w(TAG, "Skipping local song file '$relativePath': missing song_name/artist_name header, or unreadable.")
                } else {
                    // deterministic ids so re-importing the same file path updates it in place, rather than duplicating it
                    val songId = UUID.nameUUIDFromBytes("song:$relativePath".toByteArray()).toString()
                    val tabId = tabIdFor(relativePath)

                    val startFavorite = parsed.favorites.firstOrNull { it.best } ?: parsed.favorites.firstOrNull()
                    val tab = TabDataType(
                        tabId = tabId,
                        songId = songId,
                        songName = parsed.songName,
                        artistName = parsed.artistName,
                        songGenre = parsed.songGenre,
                        type = "Chords",
                        status = "approved",
                        tabAccessType = LOCAL_FILE_ACCESS_TYPE,
                        versionDescription = parsed.versionDescription,
                        difficulty = parsed.difficulty,
                        tuning = parsed.tuning,
                        capo = parsed.capo,
                        content = parsed.content,
                        tonalityName = parsed.key,
                        originalTranspose = parsed.originalTranspose ?: 0,
                        favoriteTranspose = parsed.favorites.firstOrNull { it.best }?.transpose,
                        favoriteTransposes = formatFavoriteKeys(parsed.favorites),
                        // a song never played yet opens in its best favourite key
                        defaultTranspose = startFavorite?.transpose ?: 0,
                        currentCapo = startFavorite?.capo,
                        tempo = parsed.tempo,
                        autoscrollSlider = parsed.autoscroll,
                        rhythm = formatRhythms(parsed.rhythms, parsed.tempo, rhythms),
                        tags = parsed.tags.joinToString(", ")
                    )
                    // preserve this tab's remembered local settings (two-column view, text size,
                    // last played transpose, and an in-app favourite when the file names none)
                    // across a rescan of the same file, since re-importing overwrites the whole row
                    try {
                        val existing = dataAccess.getTabInstance(tabId)
                        tab.twoColumnView = existing.twoColumnView
                        tab.textSizeSp = existing.textSizeSp
                        tab.defaultTranspose = existing.defaultTranspose
                        tab.currentCapo = existing.currentCapo
                        tab.autoscrollSlider = existing.autoscrollSlider ?: parsed.autoscroll
                        if (parsed.favorites.isEmpty()) {
                            tab.favoriteTranspose = existing.favoriteTranspose
                            tab.favoriteTransposes = existing.favoriteTransposes
                        }
                    } catch (ex: Exception) {
                        // no existing row for this tab id - nothing to preserve
                    }
                    dataAccess.upsert(tab)
                    dataAccess.appendToPlaylist(playlistId, tabId, transpose = tab.defaultTranspose)
                    importedTabIds.add(tabId)
                    imported++
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Error importing local song file '$relativePath': ${ex.message}", ex)
            }

            onProgressChange(0.05f + (0.9f * (index + 1) / files.size.coerceAtLeast(1)))
        }

        // remove any previously-imported local songs whose source file is no longer present
        val staleTabIds = dataAccess.getTabIdsByAccessType(LOCAL_FILE_ACCESS_TYPE) - importedTabIds
        for (staleId in staleTabIds) {
            dataAccess.deleteAllPlaylistEntriesForTab(staleId)
            dataAccess.deleteTab(staleId)
        }

        Log.i(TAG, "Imported $imported of ${files.size} local song files from $treeUri")
        onProgressChange(1f)
        return imported
    }

    /**
     * Writes [favorites] into the header of the local song file behind [tabId] (or removes the
     * favourite header when empty), so the file stays the source of truth across rescans: as
     * `favorite_key: D@1, *D@2` (chord shapes @ capo) when the file declares its `key`, else as
     * `favorite_transpose: 0@1, *0@2`. No-op for tabs that didn't come from the
     * local song folder.
     *
     * @return whether the file was written
     */
    suspend fun writeFavoritesToFile(context: Context, dataAccess: DataAccess, tabId: String, favorites: List<FavoriteKey>, useFlats: Boolean): Boolean =
        rewriteSongFile(context, dataAccess, tabId) { rawText ->
            val writtenKey = parseLocalSongFile(rawText)?.key.orEmpty()
            val favorite = favorites.takeIf { it.isNotEmpty() }?.let { list ->
                val entries = list.joinToString(", ") { formatFavoriteEntry(it, writtenKey, useFlats) { chord, steps, flats -> Chord.transposeChord(chord, steps, flats) } }
                (if (writtenKey.isNotBlank()) "favorite_key" else "favorite_transpose") to entries
            }
            withFavoriteHeader(rawText, favorite)
        }

    /** Writes the autoscroll slider position [value] (0..1) into the `autoscroll` header of the local song file behind [tabId] */
    suspend fun writeAutoscrollToFile(context: Context, dataAccess: DataAccess, tabId: String, value: Float): Boolean =
        rewriteSongFile(context, dataAccess, tabId) { rawText -> withAutoscrollHeader(rawText, value) }

    /** Replaces the text of the local song file behind [tabId] with [edit] of it; false if there's no such file */
    private suspend fun rewriteSongFile(context: Context, dataAccess: DataAccess, tabId: String, edit: (String) -> String): Boolean {
        val treeUri = dataAccess.getPreferenceValue(Preference.LOCAL_IMPORT_FOLDER_URI)?.let { Uri.parse(it) } ?: return false
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
        val files = mutableListOf<Pair<String, DocumentFile>>()
        collectSongFiles(root, "", files)
        val file = files.firstOrNull { (relativePath, _) -> tabIdFor(relativePath) == tabId }?.second ?: return false

        val resolver = context.contentResolver
        val rawText = resolver.openInputStream(file.uri)?.use { it.reader().readText() } ?: return false
        val newText = edit(rawText)
        if (newText == rawText) return true
        resolver.openOutputStream(file.uri, "wt")?.use { it.writer().use { writer -> writer.write(newText) } } ?: return false
        return true
    }

    /**
     * Writes [tab] as a new song file (header, then its chords above the lyrics) into the local
     * song folder, named `song_name-artist.song` like the others (`-2`, `-3`... if taken), and
     * re-imports the folder so it shows up as a local version.
     *
     * @return the new file's name, or null if no local song folder was chosen yet
     */
    suspend fun saveAsSongFile(context: Context, dataAccess: DataAccess, tab: ITab): String? {
        val treeUri = dataAccess.getPreferenceValue(Preference.LOCAL_IMPORT_FOLDER_URI)?.let { Uri.parse(it) } ?: return null
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val fileName = writeSongFile(context, root, tab, skipIfExists = false)!!
        importFromFolder(treeUri, context, dataAccess) { }
        return fileName
    }

    data class SaveAllResult(val saved: Int, val skipped: Int, val failed: Int)

    /**
     * Writes each of [tabs] that isn't a local file yet as a song file like [saveAsSongFile] does,
     * skipping songs whose `song_name-artist.song` already exists instead of numbering a copy,
     * then re-imports the folder once.
     *
     * @return the counts, or null if no local song folder was chosen yet
     */
    suspend fun saveAllAsSongFiles(context: Context, dataAccess: DataAccess, tabs: List<ITab>): SaveAllResult? {
        val treeUri = dataAccess.getPreferenceValue(Preference.LOCAL_IMPORT_FOLDER_URI)?.let { Uri.parse(it) } ?: return null
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        var saved = 0; var skipped = 0; var failed = 0
        for (tab in tabs.filter { it.tabAccessType != LOCAL_FILE_ACCESS_TYPE }) {
            try {
                if (tab.content.isBlank()) failed++
                else if (writeSongFile(context, root, tab, skipIfExists = true) == null) skipped++
                else saved++
            } catch (ex: Exception) {
                Log.e(TAG, "Couldn't save tab ${tab.tabId} as a local song file: ${ex.message}", ex)
                failed++
            }
        }
        if (saved > 0) importFromFolder(treeUri, context, dataAccess) { }
        return SaveAllResult(saved, skipped, failed)
    }

    /**
     * Writes [tab] into [root]; when its name is taken, numbers it (`-2`, `-3`...) or, with
     * [skipIfExists], returns null instead.
     */
    private fun writeSongFile(context: Context, root: DocumentFile, tab: ITab, skipIfExists: Boolean): String? {
        val baseName = "${fileNameSlug(tab.songName)}-${fileNameSlug(tab.artistName)}"
        if (skipIfExists && root.findFile("$baseName.song") != null) return null
        val fileName = generateSequence(1) { it + 1 }
            .map { n -> if (n == 1) "$baseName.song" else "$baseName-$n.song" }
            .first { root.findFile(it) == null }
        val header = listOfNotNull(
            "song_name: ${tab.songName}",
            "artist_name: ${tab.artistName}",
            tab.tonalityName.takeIf { it.isNotBlank() }?.let { "key: $it" },
            tab.capo.takeIf { it > 0 }?.let { "capo: $it" },
            tab.tempo.takeIf { it.isNotBlank() }?.let { "tempo: $it" },
            "version_description: " + (SongSources.pageUrl(tab.tabId) ?: "ultimate-guitar.com tab ${tab.tabId}, version ${tab.version}"),
        )
        val text = header.joinToString("\n") + "\n\n" + toChordSheet(tab.content).trim('\n') + "\n"
        // octet-stream: with text/plain, document providers append .txt to the name
        val file = root.createFile("application/octet-stream", fileName)
            ?: throw IllegalStateException("Couldn't create $fileName in the local song folder")
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.writer().use { writer -> writer.write(text) } }
            ?: throw IllegalStateException("Couldn't write $fileName")
        return file.name ?: fileName
    }

    /** [text] as file names in the song folder spell it: ASCII lowercase, words joined by `_` */
    private fun fileNameSlug(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
            .replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "song" }

    // deterministic per file path, so re-importing the same file updates its tab in place
    private fun tabIdFor(relativePath: String) = UUID.nameUUIDFromBytes("tab:$relativePath".toByteArray()).toString()

    private fun collectSongFiles(
        dir: DocumentFile,
        relativePath: String,
        into: MutableList<Pair<String, DocumentFile>>,
        tables: MutableMap<String, Pair<Int, DocumentFile>> = mutableMapOf(),
        depth: Int = 0
    ) {
        for (child in dir.listFiles()) {
            val childRelativePath = if (relativePath.isEmpty()) child.name.orEmpty() else "$relativePath/${child.name}"
            val name = child.name.orEmpty().lowercase()
            if (child.isDirectory) {
                collectSongFiles(child, childRelativePath, into, tables, depth + 1)
            } else if (name in setOf(RHYTHMS_FILE_NAME, CHORDS_FILE_NAME)) {
                if ((tables[name]?.first ?: Int.MAX_VALUE) > depth) tables[name] = depth to child
            } else if (SONG_FILE_EXTENSIONS.any { ext -> child.name?.endsWith(".$ext", ignoreCase = true) == true }) {
                into.add(childRelativePath to child)
            }
        }
    }

    /**
     * Replaces the fingerings previously imported from the chords file with those in [text] (none
     * when the file is gone). They're stored as guitar chord variations under each chord's lookup
     * name (so `Hm` serves `Bm`), and are shown before any fetched from the internet.
     */
    private suspend fun importChordFingerings(text: String?, dataAccess: DataAccess) {
        dataAccess.deleteLocalChordVariations()
        val fingerings = text?.let { parseChordFingerings(it) }.orEmpty()
        val variations = fingerings.groupBy { Chord.lookupName(it.name) }.flatMap { (chordId, chordFingerings) ->
            chordFingerings.mapIndexed { index, fingering ->
                val notes = ArrayList<ChordMarker.Note>()
                val open = ArrayList<ChordMarker.Open>()
                val muted = ArrayList<ChordMarker.Muted>()
                fingering.frets.forEachIndexed { index, fret ->
                    val string = StringNumber(6 - index) // the file runs low E -> high e; string 1 is high e
                    when {
                        fret == null -> muted.add(ChordMarker.Muted(string))
                        fret == 0 -> open.add(ChordMarker.Open(string))
                        else -> notes.add(ChordMarker.Note(fret = FretNumber(fret), finger = Finger.UNKNOWN, string = string))
                    }
                }
                ChordVariation(
                    varId = "$LOCAL_CHORD_VARIATION_ID_PREFIX$chordId-$index", chordId = chordId,
                    noteChordMarkers = notes, openChordMarkers = open, mutedChordMarkers = muted,
                    barChordMarkers = arrayListOf(), instrument = Instrument.Guitar
                )
            }
        }
        dataAccess.insertAll(variations)
        Log.i(TAG, "Imported ${variations.size} chord fingerings from $CHORDS_FILE_NAME")
    }

    private suspend fun getOrCreateLocalFilesPlaylistId(dataAccess: DataAccess): Int {
        dataAccess.insert(
            Playlist(
                playlistId = Playlist.LOCAL_FILES_PLAYLIST_ID,
                userCreated = false,
                title = LOCAL_FILES_PLAYLIST_TITLE,
                dateCreated = System.currentTimeMillis(),
                dateModified = System.currentTimeMillis(),
                description = "Songs imported from a local folder"
            )
        )  // ignored if it already exists
        return Playlist.LOCAL_FILES_PLAYLIST_ID
    }
}
