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
import com.gbros.tabslite.data.tab.TabDataType
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
        collectSongFiles(root, "", files)

        // the shared tables sit at the top of the folder and aren't songs themselves
        fun readTable(name: String): String? = root.findFile(name)?.let { file ->
            context.contentResolver.openInputStream(file.uri)?.use { it.reader().readText() }
        }
        val rhythms = readTable(RHYTHMS_FILE_NAME)?.let { parseRhythms(it) }.orEmpty()
        importChordFingerings(readTable(CHORDS_FILE_NAME), dataAccess)

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
                        rhythm = formatRhythms(parsed.rhythms, parsed.tempo, rhythms)
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
     * `favorite_key: D# (capo 1), E (capo 2)*` when the file declares its `key`, else as
     * `favorite_transpose: +1 (capo 1), +2 (capo 2)*`. No-op for tabs that didn't come from the
     * local song folder.
     *
     * @return whether the file was written
     */
    suspend fun writeFavoritesToFile(context: Context, dataAccess: DataAccess, tabId: String, favorites: List<FavoriteKey>, useFlats: Boolean): Boolean {
        val treeUri = dataAccess.getPreferenceValue(Preference.LOCAL_IMPORT_FOLDER_URI)?.let { Uri.parse(it) } ?: return false
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
        val files = mutableListOf<Pair<String, DocumentFile>>()
        collectSongFiles(root, "", files)
        val file = files.firstOrNull { (relativePath, _) -> tabIdFor(relativePath) == tabId }?.second ?: return false

        val resolver = context.contentResolver
        val rawText = resolver.openInputStream(file.uri)?.use { it.reader().readText() } ?: return false
        val writtenKey = parseLocalSongFile(rawText)?.key.orEmpty()
        val favorite = favorites.takeIf { it.isNotEmpty() }?.let { list ->
            val entries = list.joinToString(", ") { formatFavoriteEntry(it, writtenKey, useFlats) { chord, steps, flats -> Chord.transposeChord(chord, steps, flats) } }
            (if (writtenKey.isNotBlank()) "favorite_key" else "favorite_transpose") to entries
        }
        val newText = withFavoriteHeader(rawText, favorite)
        if (newText == rawText) return true
        resolver.openOutputStream(file.uri, "wt")?.use { it.writer().use { writer -> writer.write(newText) } } ?: return false
        return true
    }

    // deterministic per file path, so re-importing the same file updates its tab in place
    private fun tabIdFor(relativePath: String) = UUID.nameUUIDFromBytes("tab:$relativePath".toByteArray()).toString()

    private fun collectSongFiles(dir: DocumentFile, relativePath: String, into: MutableList<Pair<String, DocumentFile>>) {
        for (child in dir.listFiles()) {
            val childRelativePath = if (relativePath.isEmpty()) child.name.orEmpty() else "$relativePath/${child.name}"
            if (child.isDirectory) {
                collectSongFiles(child, childRelativePath, into)
            } else if (relativePath.isEmpty() && child.name.orEmpty().lowercase() in setOf(RHYTHMS_FILE_NAME, CHORDS_FILE_NAME)) {
                continue
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
