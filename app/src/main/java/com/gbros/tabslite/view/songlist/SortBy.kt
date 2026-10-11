package com.gbros.tabslite.view.songlist

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gbros.tabslite.R

/**
 * The ways a song list can be sorted, and their string representations
 */
enum class SortBy {
    DateAdded,
    Name,
    ArtistName,
    Popularity,
    LastPlayed,
    NotPlayedRecently,
    MostPlayed;

    companion object {
        @Composable
        fun getString(entry: SortBy): String {
            return when(entry) {
                DateAdded -> stringResource(id = R.string.sort_by_date_added)
                Popularity -> stringResource(id = R.string.sort_by_popularity)
                ArtistName -> stringResource(id = R.string.sort_by_artist_name)
                Name -> stringResource(id = R.string.sort_by_title)
                LastPlayed -> stringResource(id = R.string.sort_by_last_played)
                NotPlayedRecently -> stringResource(id = R.string.sort_by_not_played_recently)
                MostPlayed -> stringResource(id = R.string.sort_by_most_played)
            }
        }
    }
}
