package com.gbros.tabslite.view.tabview

import android.content.Context
import androidx.lifecycle.LiveData
import com.gbros.tabslite.LoadingState
import com.gbros.tabslite.data.FontStyle
import com.gbros.tabslite.data.chord.ChordVariation
import com.gbros.tabslite.data.chord.Instrument
import com.gbros.tabslite.data.playlist.Playlist
import com.gbros.tabslite.data.tab.ITab
import com.gbros.tabslite.data.tab.TabContentBlock

/**
 * The view state for [TabScreen] to control the state of each UI element in the view
 */
interface ITabViewState {

    /**
     * The name of the song being displayed
     */
    val songName: LiveData<String>

    val isFavorite: LiveData<Boolean>

    /**
     * Whether to display the playlist navigation bar
     */
    val isPlaylistEntry: LiveData<Boolean>

    /** Whether the song can be saved as a new local song file: it isn't one already */
    val canSaveAsLocalFile: LiveData<Boolean>

    val playlistTitle: LiveData<String>

    val playlistNextSongButtonEnabled: LiveData<Boolean>

    val playlistPreviousSongButtonEnabled: LiveData<Boolean>

    val difficulty: LiveData<String>

    val tuning: LiveData<String>

    fun getCapoText(context: Context): LiveData<String>

    /** The capo the user plays with */
    val capo: LiveData<Int>

    /** The capo the song is written for */
    val writtenCapo: LiveData<Int>

    val key: LiveData<String>

    /** Time signature and/or speed from a local song file's header, blank if none */
    val tempo: LiveData<String>

    /** The song's strumming patterns, one per line with a beat header, blank if none */
    val rhythm: LiveData<String>

    /** Tags from a local song file's header */
    val tags: LiveData<List<String>>

    /** How the song is shown: as written, lyrics only, or as a chord map */
    val tabViewMode: LiveData<com.gbros.tabslite.utilities.TabViewMode>

    /**
     * The author of the tab not the song
     */
    val author: LiveData<String>

    /**
     * The author of the song, not the tab
     */
    val artist: LiveData<String>

    /**
     * The ID of the song author
     */
    val artistId: LiveData<String?>

    val tabId: LiveData<String>

    val songId: LiveData<String>

    val version: LiveData<Int>

    val songVersions: LiveData<List<ITab>>

    /**
     * How many steps up or down this tab's content is transposed
     */
    val transpose: LiveData<Int>

    /** The song's favourite ways to play it (chord shapes + capo), one possibly marked best */
    val favoriteKeys: LiveData<List<com.gbros.tabslite.utilities.FavoriteKey>>
    /** Ranked ways to play the song in its current sounding key, easiest first */
    val capoSuggestions: LiveData<List<com.gbros.tabslite.utilities.CapoSuggestion>>

    /** The transposition matching the original recording's key */
    val originalTranspose: LiveData<Int>

    /**
     * The wrapped, transposed tab content to display
     */
    val content: LiveData<List<TabContentBlock>>

    /**
     * The font size that should be used to support the custom wrapping for [content]. Remembered
     * per-song, persisted across app restarts.
     */
    val fontSizeSp: LiveData<Float>

    /**
     * Whether this song's content should render in two reflowing columns instead of one.
     * Remembered per-song, persisted across app restarts.
     */
    val twoColumnView: LiveData<Boolean>

    /**
     * The current status of this tab's load process
     */
    val state: LiveData<LoadingState>

    /**
     * Whether we're currently autoscrolling (the Play button has been pressed)
     */
    val autoscrollPaused: LiveData<Boolean>

    val autoScrollSpeedSliderPosition: LiveData<Float>

    /**
     * The delay between 1px scrolls during autoscroll if not [autoscrollPaused]
     */
    val autoscrollDelay: LiveData<Float>

    /**
     * Whether to display the chord fingerings for the current chord
     */
    val chordDetailsActive: LiveData<Boolean>

    /**
     * The title for the chord details section (usually the name of the active chord being displayed)
     */
    val chordDetailsTitle: LiveData<String>

    /**
     * The state of the chord details section (loading until the details have been fetched successfully)
     */
    val chordDetailsState: LiveData<LoadingState>

    /**
     * A list of chord fingerings to be displayed in the chord details section
     */
    val chordDetailsVariations: LiveData<List<ChordVariation>>

    val shareUrl: LiveData<String>

    fun getShareTitle(context: Context): LiveData<String>

    val allPlaylists: LiveData<List<Playlist>>

    val addToPlaylistDialogSelectedPlaylistTitle: LiveData<String?>

    val addToPlaylistDialogConfirmButtonEnabled: LiveData<Boolean>

    /**
     * The selected instrument to display chords for
     */
    val chordInstrument: LiveData<Instrument>

    /**
     * Whether to display chords as flats or sharps
     */
    val useFlats: LiveData<Boolean>

    /**
     * Whether to pin all chords at the top of the screen
     */
    val chordsPinned: LiveData<Boolean>

    /**
     * Whether the song details and transpose controls above the content are hidden, for room while playing
     */
    val detailsHidden: LiveData<Boolean>

    /** Whether a later bare section label (`R:`) shows that section's lyrics and chords again */
    val sectionReferencesExpanded: LiveData<Boolean>

    /** Whether verses and choruses written without chords show those of the first one of their kind */
    val chordHintsShown: LiveData<Boolean>

    /** Whether the song header shows the rhythm and every chord used as diagrams */
    val headerDiagramsShown: LiveData<Boolean>

    /** Whether the empty lines between verses and sections are hidden */
    val blankLinesHidden: LiveData<Boolean>

    /**
     * Map of chord names to their first chord variation for pinned display
     */
    val pinnedChordVariations: LiveData<List<ChordVariation>>

    /**
     * The user's selected font style preference for viewing tabs
     */
    val fontStylePreference: LiveData<FontStyle>
}