package com.gbros.tabslite.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "preferences"
)

/**
 * Store user preferences by name in the local database.
 */
data class Preference(
    /**
     * The name of the preference.  Usually stored in the Constants class.
     */
    @PrimaryKey @ColumnInfo(name = "name") var name: String,

    /**
     * The preference value (e.g. "true" or "a-z")
     */
    @ColumnInfo(name = "value") var value: String = "",
) {

    companion object {
        /**
         * The preference name for the user preference of which order the favorites playlist should
         * be ordered in
         */
        const val FAVORITES_SORT: String = "FAVORITES_SORT"

        /**
         * The preference name for which order the popular tabs playlist should be ordered in
         */
        const val POPULAR_SORT: String = "POPULAR_SORT"

        /**
         * The preference name for which order the local files playlist should be ordered in
         */
        const val LOCAL_FILES_SORT: String = "LOCAL_FILES_SORT"

        /**
         * The preference name for which order the user-created playlists should be sorted in
         */
        const val PLAYLIST_SORT: String = "PLAYLIST_SORT"

        /**
         * The preference name for the delay in ms between 1px scrolls during autoscroll
         */
        const val AUTOSCROLL_DELAY: String = "AUTOSCROLL_DELAY"

        /**
         * The preference name for which instrument to display chords for
         */
        const val INSTRUMENT: String = "INSTRUMENT"

        /**
         * The preference name for whether to use the flats forms of chords vs sharps
         */
        const val USE_FLATS: String = "USE_FLATS"

        /**
         * The preference name for the [ThemeSelection] to use
         */
        const val APP_THEME: String = "APP_THEME"

        /**
         * The preference name for whether to display chord fingerings pinned to the top of the tab screen
         */
        const val PIN_CHORDS: String = "PIN_CHORDS"

        /**
         * The preference name for the font style to use
         */
        const val FONT_STYLE: String = "FONT_STYLE"

        /**
         * The preference name for the content:// tree URI of the user's chosen local song folder
         */
        const val LOCAL_IMPORT_FOLDER_URI: String = "LOCAL_IMPORT_FOLDER_URI"

        /**
         * The preference name for whether the local song folder is rescanned every time the app
         * starts, so edits made to the song files outside the app show up without a manual rescan
         */
        const val RESCAN_LOCAL_SONGS_ON_START: String = "RESCAN_LOCAL_SONGS_ON_START"

        /**
         * The preference name for whether the tab screen hides the song details and transpose
         * controls above the content, leaving more room while playing
         */
        const val HIDE_TAB_DETAILS: String = "HIDE_TAB_DETAILS"

        /**
         * The preference name for whether a later bare section label (`R:`, `R: 2x`) shows that
         * section's lyrics and chords again; on unless set
         */
        const val EXPAND_SECTION_REFERENCES: String = "EXPAND_SECTION_REFERENCES"

    }
}