package com.gbros.tabslite.data.tab

import android.content.Context
import com.gbros.tabslite.R
import com.gbros.tabslite.data.DataAccess

private const val LOG_NAME = "tabslite.ITab          "

interface ITab {
    val tabId: String
    val type: String
    val part: String
    val version: Int
    val votes: Int
    val rating: Double
    val date: Long
    val status: String
    val tabAccessType: String

    /**
     * The key of the song (e.g. key of 'Am')
     */
    var tonalityName: String
    val versionDescription: String

    val songId: String
    val songName: String
    val songGenre: String

    /**
     * The author of the original song (not the person who wrote up these chords, that's [contributorUserName])
     */
    val artistName: String
    val artistId: String
    val isVerified: Boolean
    val versionsCount: Int
    val isTabMl: Boolean

    var recommended: ArrayList<String>
    var difficulty: String
    var tuning: String
    var capo: Int
    var contributorUserId: String

    /**
     * The author of the chord sheet (not the author of the song - that's [artistName])
     */
    var contributorUserName: String
    var content: String

    val transpose: Int?

    /** Whether this song's content should render in two reflowing columns instead of one, remembered per-song */
    var twoColumnView: Boolean
    /** Whether [twoColumnView] was chosen for this song itself; otherwise the app-wide default applies */
    var twoColumnViewSet: Boolean
    /** This song's remembered text size in sp, or null to use the app-wide default, remembered per-song */
    var textSizeSp: Float?
    /**
     * Fallback transpose value for when this tab isn't in a playlist or favorited, in which case
     * [transpose] is sourced from the playlist entry instead and this is unused
     */
    var defaultTranspose: Int
    /** Chord shape transposition of the favourite that suits the user's voice best; see [favoriteTransposes] */
    var favoriteTranspose: Int?
    /** The user's favourite ways to play this song (see [com.gbros.tabslite.utilities.parseFavoriteKeys]) */
    var favoriteTransposes: String
    /** The capo the user plays with, or null for the written [capo]; sounds in key + transpose + capo */
    var currentCapo: Int?
    /** Chord shape transposition that, with the written [capo], sounds in the original recording's key */
    var originalTranspose: Int
    /** Time signature and/or speed from a local song file's `tempo` header, blank if none */
    var tempo: String
    /** The song's strumming patterns, rendered from its `rhythm` header (see [com.gbros.tabslite.utilities.formatRhythms]), blank if none */
    var rhythm: String
    /** This song's remembered autoscroll speed slider position (0..1), or null to use a default */
    var autoscrollSlider: Float?
    /** Tags from a local song file's `tags` header, comma-separated (see [com.gbros.tabslite.utilities.parseTags]), blank if none */
    var tags: String
    /** The language of the song's words from a local song file's `language` header (`cz`, `en`), blank if none */
    var language: String

    /**
     * Get the human-readable capo number (ordinal numbers, i.e. 2nd Fret), of this tab's capo or [capo]
     */
    fun getCapoText(context: Context, capo: Int = this.capo): String {
        return when {
            capo == 0 -> "None"
            capo == 11 -> String.format(context.getString(R.string.capo_11), capo.toString()) // 11th, 12th, 13th are exceptions
            capo == 12 -> String.format(context.getString(R.string.capo_12), capo.toString()) // 11th, 12th, 13th are exceptions
            capo == 13 -> String.format(context.getString(R.string.capo_13), capo.toString()) // 11th, 12th, 13th are exceptions
            capo % 10 == 1 -> String.format(context.getString(R.string.capo_number_ending_in_1), capo.toString())
            capo % 10 == 2 -> String.format(context.getString(R.string.capo_number_ending_in_2), capo.toString())
            capo % 10 == 3 -> String.format(context.getString(R.string.capo_number_ending_in_3), capo.toString())
            else -> String.format(context.getString(R.string.capo_generic), capo.toString())
        }
    }

    /**
     * Get all the chords used in this tab.  Can be used to download all the chords.
     */
    fun getAllChordNames(): List<String> {
        val chordPattern = Regex("\\{ch:(.*?)\\}")
        val allMatches = chordPattern.findAll(content)
        val allChords = allMatches.map { matchResult -> matchResult.groupValues[1] }
        val uniqueChords = allChords.distinct()
        return uniqueChords.toList()
    }

    /**
     * Ensures that the full tab (not just the partial tab loaded in the search results) is stored
     * in the local database.  Checks if [content] is empty, and if so triggers an API call to download
     * the tab content from the internet and load it into the database.
     *
     * @param dataAccess: The database to load the updated tab into
     * @param forceInternetFetch: If true, load from the internet regardless of whether we already have the tab.  If false, load only if [content] is empty
     *
     * @return The resulting ITab, either from the local database or from the internet
     */
    suspend fun load(dataAccess: DataAccess, forceInternetFetch: Boolean = false): ITab
}