package com.gbros.tabslite.viewmodel

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.map
import com.gbros.tabslite.R
import com.gbros.tabslite.data.DataAccess
import com.gbros.tabslite.data.playlist.IDataPlaylistEntry
import com.gbros.tabslite.data.playlist.Playlist
import com.gbros.tabslite.data.tab.Tab
import com.gbros.tabslite.data.tab.TabWithDataPlaylistEntry
import com.gbros.tabslite.utilities.LocalFileImporter
import com.gbros.tabslite.utilities.TAG
import com.gbros.tabslite.view.playlists.IPlaylistViewState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel(assistedFactory = PlaylistViewModel.PlaylistViewModelFactory::class)
class PlaylistViewModel
@AssistedInject constructor(
    @Assisted private val playlistId: Int,
    @Assisted private val dataAccess: DataAccess
) : ViewModel(), IPlaylistViewState {

    //#region dependency injection factory

    @AssistedFactory
    interface PlaylistViewModelFactory {
        fun create(playlistId: Int, dataAccess: DataAccess): PlaylistViewModel
    }

    //#endregion

    //#region private data

    private val playlist: LiveData<Playlist> = dataAccess.getLivePlaylist(playlistId)

    //#endregion

    //#region view state

    /**
     * The title of the playlist to display
     */
    override val title: LiveData<String> = playlist.map { p -> p.title }

    /**
     * The description of the playlist to display
     */
    override val description: LiveData<String> = playlist.map { p -> p.description }

    /**
     * The ordered list of songs in the playlist
     */
    override val songs: LiveData<List<TabWithDataPlaylistEntry>> = dataAccess.getSortedPlaylistTabs(playlistId)

    //#endregion

    //#region public methods

    /**
     * Rearrange playlist entries
     */
    fun reorderPlaylistEntry(fromIndex: Int, toIndex: Int) {
        val currentSongs = songs.value
        if (currentSongs != null) {
            CoroutineScope(Dispatchers.IO).launch {
                Log.d(TAG, "Moving ${currentSongs[fromIndex].songName} to ${if (toIndex > fromIndex) "after" else "before"} ${currentSongs[toIndex].songName}")
                entryMoved(currentSongs[fromIndex], currentSongs[toIndex], toIndex > fromIndex)
            }

        } else {
            Log.e(TAG, "Attempting to reorder songs in an uninitialized song list")
        }
    }

    /**
     * Rearrange playlist entries
     */
    private suspend fun entryMoved(src: IDataPlaylistEntry, dest: IDataPlaylistEntry, moveAfter: Boolean) {
        if (moveAfter) {
            dataAccess.moveEntryAfter(src, dest)
        } else {
            dataAccess.moveEntryBefore(src, dest)
        }
    }

    /**
     * Remove an entry from the playlist
     */
    fun entryRemoved(entry: IDataPlaylistEntry) {
        CoroutineScope(Dispatchers.IO).launch {
            dataAccess.removeEntryFromPlaylist(entry)
        }
    }

    /**
     * Delete the playlist and all playlist entries for this playlist from the database
     */
    fun playlistDeleted() {
        CoroutineScope(Dispatchers.IO).launch {
            dataAccess.deletePlaylist(playlistId)
        }
    }

    /**
     * Update playlist description
     */
    fun descriptionChanged(newDescription: String) {
        CoroutineScope(Dispatchers.IO).launch {
            dataAccess.updateDescription(playlistId = playlistId, newDescription = newDescription)
        }
    }

    /**
     * Update playlist title
     */
    fun titleChanged(newTitle: String) {
        CoroutineScope(Dispatchers.IO).launch {
            dataAccess.updateTitle(playlistId = playlistId, newTitle = newTitle)
        }
    }


    //#endregion

    //#region save all as local files

    val saveAllAsLocalFilesInProgress: LiveData<Boolean> = MutableLiveData(false)

    fun onSaveAllAsLocalFilesClick(context: Context) {
        val currentSongs = songs.value ?: return
        if (currentSongs.isEmpty()) return
        (saveAllAsLocalFilesInProgress as MutableLiveData).value = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Tab.fetchAllEmptyPlaylistTabsFromInternet(dataAccess, playlistId) { }
                // the list was read before fetching: take the content just downloaded
                val songsWithContent = currentSongs.onEach { song ->
                    if (song.content.isBlank()) song.content = runCatching { dataAccess.getTabInstance(song.tabId).content }.getOrDefault("")
                }
                val result = LocalFileImporter.saveAllAsSongFiles(context.applicationContext, dataAccess, songsWithContent)
                val message = when {
                    result == null -> context.getString(R.string.message_save_as_local_file_no_folder)
                    result.failed > 0 && result.saved == 0 && result.skipped == 0 -> context.getString(R.string.message_save_all_as_local_failed, result.failed)
                    else -> context.getString(R.string.message_save_all_as_local_files, result.saved, result.skipped, result.failed)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
                    (saveAllAsLocalFilesInProgress as MutableLiveData).value = false
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Couldn't save all playlist songs as local files: ${ex.message}", ex)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context.applicationContext, context.getString(R.string.message_save_as_local_file_failed), Toast.LENGTH_LONG).show()
                    (saveAllAsLocalFilesInProgress as MutableLiveData).value = false
                }
            }
        }
    }

    //#endregion
}