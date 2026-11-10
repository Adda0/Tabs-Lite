package com.gbros.tabslite.view.tabsearchbar

import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.gbros.tabslite.LoadingState
import com.gbros.tabslite.R
import com.gbros.tabslite.data.tab.TabWithDataPlaylistEntry
import com.gbros.tabslite.utilities.LocalSearchResult
import com.gbros.tabslite.ui.theme.AppTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TabsSearchBar(
    modifier: Modifier = Modifier,
    leadingIcon: @Composable () -> Unit = { Icon(
        imageVector = ImageVector.vectorResource(id = R.drawable.ic_launcher_foreground),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )},
    viewState: ITabSearchBarViewState,
    onQueryChange: (newQuery: String) -> Unit,
    onSearch: (query: String) -> Unit,
    onNavigateToTabById: ((tabId: String) -> Unit)? = null,
    onNavigateToPlaylistEntryById: ((playlistEntryId: Int) -> Unit)? = null
) {
    val query = viewState.query.observeAsState("")
    // An animated full-screen surface can cover freshly loaded search results.
    val searchBarState = rememberSearchBarState(
        animationSpecForExpand = snap(),
        animationSpecForCollapse = snap(),
    )
    val coroutineScope = rememberCoroutineScope()
    val lazyColumnState = rememberLazyListState()
    val searchSuggestions = viewState.searchSuggestions.observeAsState(listOf())
    val suggestedTabs = viewState.tabSuggestions.observeAsState(listOf())
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    val onActiveChange: (Boolean) -> Unit = { expanded ->
        coroutineScope.launch {
            if (expanded) {
                searchBarState.animateToExpanded()
            } else {
                searchBarState.animateToCollapsed()
            }
        }
    }
    val submitSearch = { query: String ->
        if (query.isNotBlank()) {
            focusManager.clearFocus()
            coroutineScope.launch {
                searchBarState.animateToCollapsed()
                onSearch(query)
            }
        }
    }
    val inputField = @Composable {
        SearchBarDefaults.InputField(
            query = query.value,
            onQueryChange = onQueryChange,
            onSearch = submitSearch,
            expanded = searchBarState.targetValue == SearchBarValue.Expanded,
            onExpandedChange = onActiveChange,
            enabled = true,
            placeholder = {
                Text(text = stringResource(id = R.string.app_action_search))
            },
            leadingIcon = leadingIcon,
            trailingIcon = {
                IconButton(onClick = {
                    onQueryChange("")
                    if (searchBarState.targetValue == SearchBarValue.Collapsed) {
                        focusRequester.requestFocus()
                    }
                }) {
                    if (query.value.isNotEmpty()) {
                        Icon(Icons.Filled.Clear, stringResource(R.string.generic_action_clear))
                    } else {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = stringResource(id = R.string.app_action_description_search)
                        )
                    }
                }
            }
        )
    }
    SearchBar(
        state = searchBarState,
        inputField = inputField,
        modifier = modifier.focusRequester(focusRequester),
    )
    ExpandedFullScreenSearchBar(
        state = searchBarState,
        inputField = inputField,
        windowInsets = { WindowInsets.safeDrawing },
        content = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), state = lazyColumnState) {
                if (query.value.isNotBlank()) {
                    items(items = suggestedTabs.value) { result ->
                        val suggestedTab = result.tab
                        SuggestedTab(
                            modifier = Modifier
                                .fillMaxWidth(),
                            tab = suggestedTab,
                            snippet = result.snippet,
                            onClick = {
                                when {
                                    suggestedTab.entryId > 0 && suggestedTab.playlistId > 0 && onNavigateToPlaylistEntryById != null -> onNavigateToPlaylistEntryById(suggestedTab.entryId)
                                    onNavigateToTabById != null -> onNavigateToTabById(suggestedTab.tabId)
                                    else -> submitSearch(suggestedTab.songName)
                                }
                            }
                        )
                    }
                    items(items = searchSuggestions.value) { searchSuggestion ->
                        SearchSuggestion(
                            suggestionText = searchSuggestion,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (searchSuggestion.isNotBlank())
                                submitSearch(searchSuggestion)
                        }
                    }
                }
            }
        }
    )
}

@Composable @Preview
fun TabsSearchBarPreview() {
    class TabSearchBarViewStateForTest(
        override val query: LiveData<String>,
        override val searchSuggestions: LiveData<List<String>>,
        override val tabSuggestions: LiveData<List<LocalSearchResult>>,
        override val loadingState: LiveData<LoadingState>,
    ) : ITabSearchBarViewState

    AppTheme {
        TabsSearchBar(
            viewState = TabSearchBarViewStateForTest(
                query = MutableLiveData("Test query"),
                searchSuggestions = MutableLiveData(listOf("suggestion1", "suggestion 2")),
                tabSuggestions = MutableLiveData(listOf(LocalSearchResult(TabWithDataPlaylistEntry(0)))),
                loadingState = MutableLiveData()
            ),
            leadingIcon = { Icon(
                imageVector = ImageVector.vectorResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )},
            onQueryChange = {},
            onSearch = {},
            onNavigateToTabById = {},
            onNavigateToPlaylistEntryById = {}
        )
    }
}