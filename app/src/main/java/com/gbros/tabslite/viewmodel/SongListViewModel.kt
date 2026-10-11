package com.gbros.tabslite.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import com.gbros.tabslite.data.DataAccess
import com.gbros.tabslite.data.Preference
import com.gbros.tabslite.data.tab.TabWithDataPlaylistEntry
import com.gbros.tabslite.utilities.allTags
import com.gbros.tabslite.utilities.combine
import com.gbros.tabslite.utilities.hasAllTags
import com.gbros.tabslite.view.songlist.ISongListViewState
import com.gbros.tabslite.view.songlist.SortBy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The view model to handle business logic for [com.gbros.tabslite.view.songlist.SongListView]. Meant
 * to be used as a sub-view under another view model
 */
class SongListViewModel(
    tabs: LiveData<List<TabWithDataPlaylistEntry>>,
    defaultSortBy: SortBy,
    private val sortPreferenceName: String? = null,
    private val dataAccess: DataAccess
): ISongListViewState {

    //#region private data

    private val backupSortBy: MutableLiveData<SortBy> = MutableLiveData(defaultSortBy)

    //#endregion

    //#region view state

    /**
     * How these tabs are currently sorted
     */
    override val sortBy: LiveData<SortBy> = sortPreferenceName?.let { notNullSortPreferenceName ->
        dataAccess.getLivePreference(notNullSortPreferenceName).map { sortByPreference ->
            sortByPreference?.let { SortBy.valueOf(sortByPreference.value) } ?: SortBy.Name
        }
    } ?: backupSortBy

    override val lastPlayed: LiveData<Map<String, Long>> = dataAccess.getLastPlayed().map { plays -> plays.associate { it.tabId to it.lastPlayed } }

    override val tags: LiveData<List<String>> = tabs.map { list -> allTags(list.map { it.tags }) }

    override val selectedTags: LiveData<Set<String>> = dataAccess.getLivePreference(Preference.TAG_FILTER).map { p ->
        p?.value.orEmpty().split(',').filter { it.isNotEmpty() }.toSet()
    }

    private val filteredTabs: LiveData<List<TabWithDataPlaylistEntry>> = tabs.combine(selectedTags) { list, selected ->
        list.orEmpty().filter { hasAllTags(it.tags, selected.orEmpty()) }
    }

    /**
     * The tabs to display in this song list
     */
    override val songs: LiveData<List<TabWithDataPlaylistEntry>> = filteredTabs.combine(sortBy, lastPlayed) { playlistTabs, currentSortBy, plays ->
        val lastPlayed = plays.orEmpty()
        when(currentSortBy) {
            SortBy.Name -> playlistTabs?.sortedBy { it.songName } ?: listOf()
            SortBy.Popularity -> playlistTabs?.sortedByDescending { it.votes } ?: listOf()
            SortBy.ArtistName -> playlistTabs?.sortedBy { it.artistName } ?: listOf()
            SortBy.DateAdded -> playlistTabs?.sortedByDescending { it.dateAdded } ?: listOf()
            SortBy.LastPlayed -> playlistTabs?.sortedByDescending { lastPlayed[it.tabId] ?: 0 } ?: listOf()
            SortBy.NotPlayedRecently -> playlistTabs?.sortedBy { lastPlayed[it.tabId] ?: 0 } ?: listOf()
            null -> playlistTabs ?: listOf()
        }
    }

    //#endregion

    //#region public methods

    fun onSortSelectionChange(sortSelection: SortBy) {
        backupSortBy.postValue(sortSelection)

        if (sortPreferenceName != null) {
            CoroutineScope(Dispatchers.IO).launch {
                dataAccess.upsert(Preference(sortPreferenceName, sortSelection.name))
            }
        }
    }

    //#endregion
}