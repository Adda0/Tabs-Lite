package com.gbros.tabslite.utilities

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.gbros.tabslite.data.DataAccess
import com.gbros.tabslite.data.Preference
import com.gbros.tabslite.data.chord.Chord
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
                        favoriteTranspose = parsed.favoriteTranspose,
                        // a song never played yet opens in its favourite key
                        defaultTranspose = parsed.favoriteTranspose ?: 0
                    )
                    // preserve this tab's remembered local settings (two-column view, text size,
                    // last played transpose, and an in-app favourite when the file names none)
                    // across a rescan of the same file, since re-importing overwrites the whole row
                    try {
                        val existing = dataAccess.getTabInstance(tabId)
                        tab.twoColumnView = existing.twoColumnView
                        tab.textSizeSp = existing.textSizeSp
                        tab.defaultTranspose = existing.defaultTranspose
                        if (parsed.favoriteTranspose == null) {
                            tab.favoriteTranspose = existing.favoriteTranspose
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
     * Writes [favoriteTranspose] into the header of the local song file behind [tabId] (or removes
     * the favourite header when null), so the file stays the source of truth across rescans: as
     * `favorite_key` when the file declares its `key`, else as `favorite_transpose`. No-op for tabs
     * that didn't come from the local song folder.
     *
     * @return whether the file was written
     */
    suspend fun writeFavoriteToFile(context: Context, dataAccess: DataAccess, tabId: String, favoriteTranspose: Int?, useFlats: Boolean): Boolean {
        val treeUri = dataAccess.getPreferenceValue(Preference.LOCAL_IMPORT_FOLDER_URI)?.let { Uri.parse(it) } ?: return false
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
        val files = mutableListOf<Pair<String, DocumentFile>>()
        collectSongFiles(root, "", files)
        val file = files.firstOrNull { (relativePath, _) -> tabIdFor(relativePath) == tabId }?.second ?: return false

        val resolver = context.contentResolver
        val rawText = resolver.openInputStream(file.uri)?.use { it.reader().readText() } ?: return false
        val writtenKey = parseLocalSongFile(rawText)?.key.orEmpty()
        val favorite = favoriteTranspose?.let { transpose ->
            if (writtenKey.isNotBlank()) "favorite_key" to Chord.transposeChord(writtenKey, transpose, useFlats)
            else "favorite_transpose" to (if (transpose > 0) "+$transpose" else transpose.toString())
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
            } else if (SONG_FILE_EXTENSIONS.any { ext -> child.name?.endsWith(".$ext", ignoreCase = true) == true }) {
                into.add(childRelativePath to child)
            }
        }
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
