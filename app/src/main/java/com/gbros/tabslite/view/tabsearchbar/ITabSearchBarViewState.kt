package com.gbros.tabslite.view.tabsearchbar

import androidx.lifecycle.LiveData
import com.gbros.tabslite.LoadingState
import com.gbros.tabslite.data.tab.ITab
import com.gbros.tabslite.utilities.LocalSearchResult

interface ITabSearchBarViewState {
    /**
     * The current query to be displayed in the search bar
     */
    val query: LiveData<String>

    /**
     * Songs already stored on the device matching the query by title, artist or content
     */
    val tabSuggestions: LiveData<List<LocalSearchResult>>

    /**
     * The current search suggestions to be displayed
     */
    val searchSuggestions: LiveData<List<String>>
    val loadingState: LiveData<LoadingState>
}