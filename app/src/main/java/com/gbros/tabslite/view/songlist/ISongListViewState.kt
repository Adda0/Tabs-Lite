package com.gbros.tabslite.view.songlist

import androidx.lifecycle.LiveData
import com.gbros.tabslite.data.tab.TabWithDataPlaylistEntry

interface ISongListViewState {
    /**
     * The tabs to display in this song list
     */
    val songs: LiveData<List<TabWithDataPlaylistEntry>>

    /**
     * How these tabs are currently sorted
     */
    val sortBy: LiveData<SortBy>

    /**
     * When each played song was last played (epoch ms), by tab ID
     */
    val lastPlayed: LiveData<Map<String, Long>>

    /**
     * The tags of the songs in this list, before filtering
     */
    val tags: LiveData<List<String>>

    /**
     * The keys of the tags the list is filtered by (see [com.gbros.tabslite.utilities.tagKey]); a song needs them all
     */
    val selectedTags: LiveData<Set<String>>
}
