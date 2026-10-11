package com.gbros.tabslite.data.tab

import com.gbros.tabslite.utilities.LocalFileImporter
import com.gbros.tabslite.utilities.SongSource
import com.gbros.tabslite.utilities.SongSources

/** Where a tab comes from */
sealed interface TabOrigin {
    data object LocalFile : TabOrigin
    data object UltimateGuitar : TabOrigin
    data class Songbook(val source: SongSource) : TabOrigin
}

val ITab.origin: TabOrigin
    get() = when {
        tabAccessType == LocalFileImporter.LOCAL_FILE_ACCESS_TYPE -> TabOrigin.LocalFile
        else -> SongSources.sourceOf(tabId)?.let { TabOrigin.Songbook(it.source) } ?: TabOrigin.UltimateGuitar
    }

/** Versions of a song in the order they're listed: local files, Ultimate Guitar, then each songbook site, each by version number */
fun List<ITab>.sortedAsVersions(): List<ITab> = sortedWith(compareBy({ tab ->
    when (val origin = tab.origin) {
        TabOrigin.LocalFile -> 0
        TabOrigin.UltimateGuitar -> 1
        is TabOrigin.Songbook -> 2 + origin.source.ordinal
    }
}, { it.version }))

/**
 * A search result row standing for every result of the same song name and artist, whatever
 * [origins] they come from; opening it lists all of their versions (see `DataAccess.getSongVersions`)
 */
data class SongSearchResult(private val song: ITab, val origins: List<TabOrigin>, val versionCount: Int) : ITab by song {
    companion object {
        /** [results] with the rows of the same song name and artist merged into one, in order of first appearance */
        fun merge(results: List<ITab>): List<SongSearchResult> = results
            .groupBy { it.songName.trim().lowercase() to it.artistName.trim().lowercase() }
            .values
            .map { rows ->
                SongSearchResult(
                    song = rows.first(),
                    origins = rows.map { it.origin }.distinct(),
                    // a song entry counts each of its versions twice (as Ultimate Guitar does), whichever of them is listed
                    versionCount = rows.groupBy { it.songId }.values.sumOf { song -> maxOf(1, song.maxOf { it.versionsCount } / 2) },
                )
            }
    }
}
