package com.gbros.tabslite.viewmodel

import android.content.ContentResolver
import android.content.Context
import android.widget.Toast
import android.content.res.Resources.NotFoundException
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Log
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.map
import androidx.lifecycle.switchMap
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import com.gbros.tabslite.LoadingState
import com.gbros.tabslite.R
import com.gbros.tabslite.data.DataAccess
import com.gbros.tabslite.data.FontStyle
import com.gbros.tabslite.data.PlayHistory
import com.gbros.tabslite.data.Preference
import com.gbros.tabslite.data.chord.Chord
import com.gbros.tabslite.data.chord.ChordVariation
import com.gbros.tabslite.data.chord.Instrument
import com.gbros.tabslite.data.playlist.Playlist
import com.gbros.tabslite.data.tab.ITab
import com.gbros.tabslite.data.tab.Tab
import com.gbros.tabslite.data.tab.TabContentBlock
import com.gbros.tabslite.data.tab.TabWithDataPlaylistEntry
import com.gbros.tabslite.data.tab.sortedAsVersions
import com.gbros.tabslite.utilities.BackendConnection
import com.gbros.tabslite.utilities.TAG
import com.gbros.tabslite.utilities.LocalFileImporter
import com.gbros.tabslite.utilities.autoscrollDelayForTempo
import com.gbros.tabslite.utilities.sliderForDelay
import com.gbros.tabslite.utilities.combine
import com.gbros.tabslite.utilities.isSameTransposition
import com.gbros.tabslite.utilities.FavoriteKey
import com.gbros.tabslite.utilities.MAX_CAPO
import com.gbros.tabslite.utilities.keyOrFirstChord
import com.gbros.tabslite.utilities.expandSectionReferences
import com.gbros.tabslite.utilities.addChordHints
import com.gbros.tabslite.utilities.parseTags
import com.gbros.tabslite.utilities.tagKey
import com.gbros.tabslite.utilities.TabViewMode
import com.gbros.tabslite.utilities.parseFavoriteKeys
import com.gbros.tabslite.utilities.formatFavoriteKeys
import com.gbros.tabslite.utilities.CapoSuggestion
import com.gbros.tabslite.utilities.countChords
import com.gbros.tabslite.utilities.suggestCapos
import com.gbros.tabslite.utilities.SongSources
import com.gbros.tabslite.view.tabview.ITabViewState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// font size constraints, measured in sp
private const val MIN_FONT_SIZE_SP = 6f
private const val MAX_FONT_SIZE_SP = 42f
private const val TEXT_SIZE_STEP_SP = 2f

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = TabViewModel.TabViewModelFactory::class)
class TabViewModel
@AssistedInject constructor(
    @Assisted private val providedTabId: String? = null,
    @Assisted private val entryId: Int? = null,
    @Assisted defaultFontSize: Float,
    @Assisted private val dataAccess: DataAccess,
    @Assisted private val urlHandler: UriHandler,
    @Assisted private val onNavigateToPlaylistEntry: (Int) -> Unit
) : ViewModel(), ITabViewState {

    //#region dependency injection factory

    @AssistedFactory
    interface TabViewModelFactory {
        fun create(tabId: String? = null, entryId: Int? = null, defaultFontSize: Float, dataAccess: DataAccess, urlHandler: UriHandler, navigateToPlaylistEntryById: (Int) -> Unit): TabViewModel
    }

    //#endregion

    //#region private methods

    /**
     * Handle transposition by a set number of steps. Transposes the tab content and remembers the
     * new value as this song's last played transposition, wherever it's opened from next (every
     * playlist entry of the tab, favorites included, plus the tab's own fallback value).
     */
    private fun transpose(numHalfSteps: Int) {
        val currentTab = tab.value
        val currentTranspose = transpose.value
        if (currentTab != null && currentTranspose != null) {

            val newTranspose = (currentTranspose + numHalfSteps) % 12  // update tab.transpose variable

            CoroutineScope(Dispatchers.IO).launch {
                dataAccess.updateAllEntriesTransposition(currentTab.tabId, newTranspose)
                dataAccess.updateDefaultTranspose(currentTab.tabId, newTranspose)
            }
        } else {
            Log.e(TAG, "Transpose button clicked while tab was null.")
        }
    }

    /** Sets the capo the user plays with, remembered per song */
    private fun setCapo(capo: Int) {
        val currentTab = tab.value ?: return
        CoroutineScope(Dispatchers.IO).launch {
            dataAccess.updateCurrentCapo(currentTab.tabId, capo)
        }
    }

    /** Moves to the given chord shape transposition and capo */
    private fun playAs(transposition: Int, capo: Int) {
        transpose(transposition - (transpose.value ?: 0))
        setCapo(capo)
    }

    //#region autoscroll

    /**
     * Autoscroll slider midpoint (default starting speed). Should be between [minDelay] and [maxDelay]
     */
    private val middleDelay: Float = 15f
    /**
     * Autoscroll shortest delay between 1px scrolls (fastest speed)
     */
    private val minDelay: Float = 1f  // fastest speed
    /**
     * Autoscroll longest delay between 1px scrolls (slowest speed)
     */
    private val maxDelay: Float = 75f // slowest speed
    /**
     * Maps the autoscroll slider value to the delay between 1px scrolls for autoscroll
     */
    private val mapAutoscrollSliderToScrollDelay = getValueMapperFunction(minOutput = minDelay, middleOutput = maxDelay - middleDelay, maxOutput = maxDelay)

    /**
     * Creates a quadratic function that maps 0f..1f to [minOutput]..[maxOutput] where 0.5f maps to [middleOutput]
     */
    private fun getValueMapperFunction(minOutput: Float, middleOutput: Float, maxOutput: Float): (x: Float) -> Float {
        val coefficients = findQuadraticCoefficients(y1 = minOutput, y2 = middleOutput, y3 = maxOutput)

        val (a, b, c) = coefficients
        return {
                x: Float ->
            val returnVal = (a * (x * x)) + (b * x) + c
            (maxOutput - returnVal).coerceIn(minimumValue = minOutput, maximumValue = maxOutput)
        }
    }
    private fun findQuadraticCoefficients(y1: Float, y2: Float, y3: Float): Triple<Float, Float, Float> {
        val b = 4 * (y2 - y1) - y3
        val a = (2*y3) - (4 * (y2 - y1)) - (2*y1)
        val c = y1

        return Triple(a, b, c)
    }

    //#endregion autoscroll

    private fun load(forceReload: Boolean = false) {
        _state.postValue(LoadingState.Loading)
        val reloadJob = CoroutineScope(Dispatchers.IO).async {
            var currentTab = tab.value
            if (!tab.isInitialized || currentTab == null) {
                // tab hasn't loaded yet. try to load the tab via the passed ID
                if (entryId != null) {
                    val tabId = dataAccess.getEntryById(entryId)?.tabId
                    if (tabId == null) {
                        Log.e(TAG, "Couldn't get tab from playlist entry ${this@TabViewModel.providedTabId}")
                        _state.postValue(LoadingState.Error(R.string.message_tab_load_from_playlist_unexpected_error))
                    } else {
                        currentTab = Tab(tabId)
                    }
                }
                else if (providedTabId != null)
                    currentTab = Tab(providedTabId)
                } else {
                    Log.e(TAG, "Couldn't load tab in TabViewModel. Both tabId and entryId were null.")
                    _state.postValue(LoadingState.Error(R.string.message_tab_load_unexpected_error, "tabId and entryId both null"))
                }

            currentTab?.load(dataAccess = dataAccess, forceInternetFetch = forceReload)
        }
        reloadJob.invokeOnCompletion { ex ->
            when (ex) {
                null -> {
                    // success
                    _state.postValue(LoadingState.Success)
                }
                is BackendConnection.NoInternetException -> {
                    Log.i(TAG, "No internet while fetching tab $providedTabId / playlist entry $entryId", ex)
                    _state.postValue(LoadingState.Error(R.string.message_tab_load_no_internet))
                }
                is BackendConnection.UnavailableForLegalReasonsException -> {
                    Log.i(TAG, "Tab ${tab.value?.songName} (${tab.value?.tabId}) unavailable for legal reasons.")
                    _state.postValue(LoadingState.Error(R.string.message_tab_unavailable_for_legal_reasons))
                }
                is NotFoundException -> {
                    // this shouldn't happen. We only get to this page through the app; it's strange to have a tab ID somewhere else, but not found here.
                    Log.e(TAG, "Tab $providedTabId / playlist entry $entryId not found.", ex)
                    if (entryId != null) {
                        _state.postValue(LoadingState.Error(R.string.message_tab_playlist_entry_not_found))
                    } else {
                        _state.postValue(LoadingState.Error(R.string.message_tab_not_found))
                    }
                }
                else -> {
                    Log.e(TAG, "Unexpected error loading tab $providedTabId / playlist entry $entryId: ${ex.message}", ex)
                    _state.postValue(LoadingState.Error(R.string.message_tab_load_unexpected_error, ex.message.toString()))
                }
            }
        }
    }

    /**
     * Create a PDF document from the current tab
     */
    private fun createPdf(): PdfDocument {
        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 page size

        // Margins
        val leftMargin = 40f
        val topMargin = 50f
        val bottomMargin = 50f

        var currentPageNumber = 1

        fun startNewPage(pageNumber: Int): PdfDocument.Page {
            val newPageInfo = PdfDocument.PageInfo.Builder(pageInfo.pageWidth, pageInfo.pageHeight, pageNumber).create()
            return doc.startPage(newPageInfo)
        }

        var page = startNewPage(currentPageNumber)
        var canvas = page.canvas
        var currentY = topMargin

        // draw title
        val titlePaint = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 18f
            textAlign = Paint.Align.CENTER
            color = android.graphics.Color.BLACK // Explicitly set text color
        }
        val titleX = pageInfo.pageWidth / 2f // center the title on the page
        val title = "${songName.value} - ${artist.value}"
        canvas.drawText(title, titleX, currentY, titlePaint)
        currentY += titlePaint.fontSpacing * 2 // Add some vertical space after the title

        // setup paints for content
        val contentPaint = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textSize = 12f
            color = android.graphics.Color.BLACK
        }
        val chordTextPaint = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 12f
            color = android.graphics.Color.BLACK
        }

        val annotatedContent: AnnotatedString = TabContent(urlHandler::openUri, shownContentString.value ?: "").contentBlocks
            .map { tabContent -> tabContent.content }
            .reduce { acc, annotatedString -> acc + annotatedString }
        val lineRegex = Regex(".*\\R?") // Regex to match a full line including its newline characters

        lineRegex.findAll(annotatedContent).forEach { lineMatchResult ->
            val line = lineMatchResult.value.trimEnd() // The actual line content without trailing newline
            val lineStartOffset = lineMatchResult.range.first

            // Check if there's enough space for both the lyrics and the chords above them, otherwise start a new page
            val requiredSpace = contentPaint.fontSpacing + chordTextPaint.fontSpacing
            if (currentY + requiredSpace > pageInfo.pageHeight - bottomMargin) {
                // Finish current page and start a new one
                doc.finishPage(page)
                page = startNewPage(++currentPageNumber)
                canvas = page.canvas
                currentY = topMargin
            }

            var currentX = leftMargin
            val lineEndOffset = lineStartOffset + line.length

            // Get all chord annotations for the current line
            val lineAnnotations = annotatedContent.getStringAnnotations("chord", lineStartOffset, lineEndOffset)

            var lastCharIndexInLine = 0
            for (annotation in lineAnnotations) {
                val annotationStartInLine = annotation.start - lineStartOffset
                val annotationEndInLine = annotation.end - lineStartOffset

                // Draw text before this annotation
                if (annotationStartInLine > lastCharIndexInLine) {
                    val textBefore = line.substring(lastCharIndexInLine, annotationStartInLine)
                    canvas.drawText(textBefore, currentX, currentY, contentPaint)
                    currentX += contentPaint.measureText(textBefore)
                }

                // The character in the main text that the chord is attached to
                val annotatedChar = line.substring(annotationStartInLine, annotationEndInLine)
                val annotatedCharWidth = contentPaint.measureText(annotatedChar)

                // Extract chord name and check if it's an inline chord
                var chordText = annotation.item
                val isInline = chordText.startsWith("{il}")
                if (isInline) {
                    chordText = chordText.substring(4)
                }

                // Draw the chord text above the current position
                if (!isInline) {
                    val chordY = currentY - contentPaint.textSize // Position above the main text line
                    canvas.drawText(chordText, currentX, chordY, chordTextPaint)

                    // Draw the annotated character(s) itself
                    canvas.drawText(annotatedChar, currentX, currentY, contentPaint)
                    currentX += annotatedCharWidth // Advance currentX
                } else {
                    // If it's an inline chord, just draw the chord, not the annotated character
                    canvas.drawText(chordText, currentX, currentY, chordTextPaint)
                    currentX += chordTextPaint.measureText(chordText)
                }

                lastCharIndexInLine = annotationEndInLine
            }

            // Draw any remaining text after the last annotation
            if (lastCharIndexInLine < line.length) {
                val remainingText = line.substring(lastCharIndexInLine)
                canvas.drawText(remainingText, currentX, currentY, contentPaint)
            }

            // Move to the next line position
            currentY += contentPaint.fontSpacing * 2f // Add extra spacing for lines with chords
        }

        doc.finishPage(page)
        return doc
    }

    //#endregion

    //#region private data

    private val tab: LiveData<out ITab?> = if (entryId != null) dataAccess.getTabFromPlaylistEntryId(entryId) else if (providedTabId != null) dataAccess.getTab(providedTabId) else MutableLiveData(null)

    /**
     * The chord name to look up in the database and display
     */
    private var currentChordToDisplay: MutableLiveData<String> = MutableLiveData("")

    override val chordInstrument: LiveData<Instrument> = dataAccess.getLivePreference(Preference.INSTRUMENT).map { p -> if (p != null) Instrument.valueOf(p.value) else Instrument.Guitar }

    private val currentChordInstrumentCombo: LiveData<Pair<String?, Instrument?>> =
        currentChordToDisplay.combine(chordInstrument) { chord, instrument ->
            Pair(chord, instrument)
        } as MutableLiveData<Pair<String?, Instrument?>>

    /**
     * The user's in-session override of the font size, if they've zoomed or clicked the text size
     * buttons since this tab was opened. Null until then, in which case [fontSizeSp] tracks the
     * song's remembered [ITab.textSizeSp] from the database instead
     */
    private val userAdjustedFontSize: MutableLiveData<Float?> = MutableLiveData(null)

    override val allPlaylists: LiveData<List<Playlist>> = dataAccess.getLivePlaylists()

    private val _addToPlaylistDialogSelectedPlaylist: MutableLiveData<Playlist> = MutableLiveData()
    private var addToPlaylistDialogSelectedPlaylist: LiveData<Playlist?> = _addToPlaylistDialogSelectedPlaylist.combine(allPlaylists) { currentSelection, playlistList ->
        currentSelection  // use the current selection if there is one
            ?: if (!playlistList.isNullOrEmpty()) {
                playlistList.first()  // default to the first element in the list of playlists if the list of playlists is populated
            } else {
                null  // fallback to a null selection to let the UI handle the nothing-is-selected case
            }
    }

    //#endregion

    //#region view state

    override val tabId: LiveData<String> = tab.map { t -> t?.tabId ?: "" }

    override val songId: LiveData<String> = tab.map { t -> t?.songId ?: "" }

    override val artistId: LiveData<String?> = tab.map { t -> t?.artistId }

    override val useFlats: LiveData<Boolean> = dataAccess.getLivePreference(Preference.USE_FLATS).map { p -> p?.value?.toBoolean() == true }

    override val chordsPinned: LiveData<Boolean> = dataAccess.getLivePreference(Preference.PIN_CHORDS).map { p -> p?.value?.toBoolean() == true }

    override val detailsHidden: LiveData<Boolean> = dataAccess.getLivePreference(Preference.HIDE_TAB_DETAILS).map { p -> p?.value?.toBoolean() == true }

    override val sectionReferencesExpanded: LiveData<Boolean> = dataAccess.getLivePreference(Preference.EXPAND_SECTION_REFERENCES).map { p -> p?.value?.toBoolean() ?: true }

    override val chordHintsShown: LiveData<Boolean> = dataAccess.getLivePreference(Preference.CHORD_HINTS).map { p -> p?.value?.toBoolean() == true }

    override val headerDiagramsShown: LiveData<Boolean> = dataAccess.getLivePreference(Preference.HEADER_DIAGRAMS).map { p -> p?.value?.toBoolean() == true }

    override val blankLinesHidden: LiveData<Boolean> = dataAccess.getLivePreference(Preference.HIDE_BLANK_LINES).map { p -> p?.value?.toBoolean() == true }

    override val tabViewMode: LiveData<TabViewMode> = dataAccess.getLivePreference(Preference.TAB_VIEW_MODE).map { p ->
        TabViewMode.entries.firstOrNull { it.name == p?.value } ?: TabViewMode.Normal
    }

    // tab content as displayed, before transposition: repeated sections written out where referenced
    // (with their chords only when chord hints are on), chord hints on sections written without chords
    private val displayedContentString: LiveData<String> = tab.combine(sectionReferencesExpanded, chordHintsShown) { t, expand, hints ->
        var content = t?.content ?: ""
        if (expand != false) content = expandSectionReferences(content, includeChords = hints == true)
        if (hints == true) content = addChordHints(content)
        content
    }

    override val songName: LiveData<String> = tab.map { t -> t?.songName ?: "" }

    override val version: LiveData<Int> = tab.map { t -> t?.version ?: 0 }

    override val songVersions: LiveData<List<ITab>> = tab.switchMap { t -> if(t == null) MutableLiveData(listOf()) else dataAccess.getSongVersions(t.songId).map { versions -> versions.sortedAsVersions() } }

    override val isFavorite: LiveData<Boolean> = if (entryId != null) dataAccess.playlistEntryExistsInFavorites(entryId) else if (providedTabId != null) dataAccess.tabExistsInFavoritesLive(providedTabId) else MutableLiveData(false)

    /**
     * Whether to display the playlist navigation bar
     */
    override val isPlaylistEntry: LiveData<Boolean> = tab.map { t -> t is TabWithDataPlaylistEntry && t.playlistId > 0 }

    override val canSaveAsLocalFile: LiveData<Boolean> = tab.map { t -> t != null && t.tabAccessType != LocalFileImporter.LOCAL_FILE_ACCESS_TYPE }

    override val playlistTitle: LiveData<String> = tab.map { t ->
        if (t is TabWithDataPlaylistEntry && t.playlistTitle != null) t.playlistTitle!! else ""
    }

    override val playlistNextSongButtonEnabled = tab.map { t -> t is TabWithDataPlaylistEntry && t.nextEntryId != null }

    override val playlistPreviousSongButtonEnabled = tab.map { t -> t is TabWithDataPlaylistEntry && t.prevEntryId != null }

    override val difficulty: LiveData<String> = tab.map { t -> t?.difficulty ?: "" }

    override val tuning: LiveData<String> = tab.map { t -> t?.tuning ?: "" }

    /** The capo the user plays with, followed by the key the song then sounds in when the key is known */
    override fun getCapoText(context: Context): LiveData<String> = tab.combine(transpose, useFlats) { t, tr, f ->
        if (t == null) return@combine ""
        val capo = t.currentCapo ?: t.capo
        val capoText = t.getCapoText(context, capo)
        val writtenKey = keyOrFirstChord(t.tonalityName, t.content)
        if (writtenKey.isBlank()) return@combine capoText
        val soundingKey = Chord.transposeChord(writtenKey, (tr ?: 0) + capo, f == true)
        context.getString(R.string.tab_capo_sounds_in, capoText, soundingKey)
    }

    override val capo: LiveData<Int> = tab.map { t -> t?.currentCapo ?: t?.capo ?: 0 }

    override val writtenCapo: LiveData<Int> = tab.map { t -> t?.capo ?: 0 }

    // the written key, or the first chord's when the song doesn't name it, so keys can always be shown
    override val key: LiveData<String> = tab.map { t -> if (t == null) "" else keyOrFirstChord(t.tonalityName, t.content) }

    override val tempo: LiveData<String> = tab.map { t -> t?.tempo ?: "" }

    override val rhythm: LiveData<String> = tab.map { t -> t?.rhythm ?: "" }

    override val tags: LiveData<List<String>> = tab.map { t -> parseTags(t?.tags.orEmpty()) }
    
    override val author: LiveData<String> = tab.map { t -> t?.contributorUserName ?: "" }

    override val artist: LiveData<String> = tab.map { t -> t?.artistName ?: "" }

    override val fontStylePreference: LiveData<FontStyle> = dataAccess.getLivePreference(Preference.FONT_STYLE).map { p ->
        if (p != null) FontStyle.valueOf(p.value) else FontStyle.Modern
    }


    override val transpose: LiveData<Int> = tab.map { t -> t?.transpose ?: t?.defaultTranspose ?: 0 }

    override val favoriteKeys: LiveData<List<FavoriteKey>> = tab.map { t -> parseFavoriteKeys(t?.favoriteTransposes.orEmpty(), t?.capo ?: 0) }

    override val capoSuggestions: LiveData<List<CapoSuggestion>> = tab.combine(transpose, capo) { t, tr, cp ->
        if (t == null) return@combine emptyList()
        val writtenChords = countChords(t.content)
        if (writtenChords.isEmpty()) return@combine emptyList()
        val soundingSteps = (tr ?: 0) + (cp ?: 0)
        suggestCapos(writtenChords, soundingSteps, { chord, steps -> Chord.transposeChord(chord, steps, false) }, Chord::lookupName)
    }

    override val originalTranspose: LiveData<Int> = tab.map { t -> t?.originalTranspose ?: 0 }

    override val fontSizeSp: LiveData<Float> = tab.combine(userAdjustedFontSize) { t, override ->
        override ?: t?.textSizeSp ?: defaultFontSize
    }

    override val twoColumnView: LiveData<Boolean> = tab.map { t ->
        t?.twoColumnView?.takeIf { t.twoColumnViewSet }
    }.combine(dataAccess.getLivePreference(Preference.TWO_COLUMN_VIEW_DEFAULT).map { it?.value == "true" }) { perSong, default ->
        perSong ?: default ?: false
    }

    // tab content after transposition and useFlats
    private val transposedContentString: LiveData<String> = displayedContentString.combine(transpose, useFlats) { c, tr, f ->
        val currentDbContent = c ?: ""
        val currentTranspose = tr ?: 0
        val useFlats = f == true

        val chordPattern = Regex("\\{ch:([^}]+?)\\}")
        val transposedContent = chordPattern.replace(currentDbContent) {
            val chord = it.groupValues[1]
            "{ch:" + Chord.transposeChord(chord, currentTranspose, useFlats) + "}"
        }

        return@combine transposedContent
    }

    // the transposed content as the chosen view mode shows it, without empty lines when they're hidden
    private val shownContentString: LiveData<String> = transposedContentString.combine(tabViewMode, blankLinesHidden) { c, mode, hideBlank ->
        val shown = (mode ?: TabViewMode.Normal).apply(c ?: "")
        if (hideBlank == true) shown.lines().filterNot { it.isBlank() }.joinToString("\n") else shown
    }

    private val tabContent: LiveData<TabContent> = shownContentString.map { shown -> TabContent(urlHandler::openUri, shown) }
    // transposed content converted to a list of TabContentBlock (tags are stripped and chords are annotations not text)
    override val content: LiveData<List<TabContentBlock>> = tabContent.map { tc -> tc.contentBlocks }

    override val pinnedChordVariations: LiveData<List<ChordVariation>> = tabContent
        .map{c -> c.chords.map { chord -> Chord.lookupName(chord) }.filter { it.isNotEmpty() }.distinct() }
        .combine(chordInstrument) { chords, instrument ->
            if (chords != null && instrument != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    // anytime chords or instrument are updated, download the new chords
                    Chord.ensureAllChordsDownloaded(chords, instrument, dataAccess)
                }
            }
            Pair(chords, instrument)
        }
        .switchMap { (chords, instrument) ->
            if (chords == null || instrument == null) null
            else dataAccess.getFirstChordVariations(chords, instrument)
        }
    
    private val _state: MutableLiveData<LoadingState> = MutableLiveData(LoadingState.Loading)
    override val state: LiveData<LoadingState> = transposedContentString.combine(_state) { c, _ ->
        // check for an update in status if we're still in Loading (or Failure) state before returning
        if (c != null) {
            if (_state.value != LoadingState.Success && c.isNotEmpty()) {
                _state.postValue(LoadingState.Success)  // content successfully loaded and formatted
            }
        }
        _state.value ?: LoadingState.Loading
    }

    /**
     * Whether we're currently autoscrolling
     */
    private val _autoscrollPaused: MutableLiveData<Boolean> = MutableLiveData(true)
    override val autoscrollPaused: LiveData<Boolean> = _autoscrollPaused

    private var _autoscrollSpeedSliderPosition: MutableLiveData<Float> = MutableLiveData(.5f)
    override val autoScrollSpeedSliderPosition: LiveData<Float> = _autoscrollSpeedSliderPosition

    /**
     * Whether to display the chord fingerings for the current chord
     */
    private val _chordDetailsActive: MutableLiveData<Boolean> = MutableLiveData(false)
    override val chordDetailsActive: LiveData<Boolean> = _chordDetailsActive

    /**
     * The title for the chord details section (usually the name of the active chord being displayed)
     */
    override val chordDetailsTitle: LiveData<String> = currentChordToDisplay

    /**
     * A list of chord fingerings to be displayed in the chord details section
     */
    override val chordDetailsVariations: LiveData<List<ChordVariation>> = currentChordInstrumentCombo.switchMap { (chord, instrument) ->
        if (chord == null || instrument == null) {
            MutableLiveData(listOf())
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                // double check that the chord is downloaded
                Chord.getChord(chord, instrument, dataAccess)
            }
            dataAccess.chordVariations(Chord.lookupName(chord), instrument)
        }
    }

    /**
     * The state of the chord details section (loading until the details have been fetched successfully)
     */
    private val _chordDetailsState: MutableLiveData<LoadingState> = MutableLiveData(LoadingState.Loading)
    override val chordDetailsState: LiveData<LoadingState> = chordDetailsVariations.switchMap { c ->
        // check for an update in status if we're still in Loading (or Failure) state before returning
        if (c != null) {
            _chordDetailsState.postValue(LoadingState.Success)  // chords successfully loaded
        }
        _chordDetailsState
    }

    override val autoscrollDelay: LiveData<Float> = autoScrollSpeedSliderPosition.map { sliderPosition -> mapAutoscrollSliderToScrollDelay(sliderPosition) }

    override val shareUrl: LiveData<String> = tab.map { t -> t?.tabId?.let { SongSources.pageUrl(it) } ?: "https://tabslite.com/tab/${t?.tabId}" }

    override fun getShareTitle(context: Context): LiveData<String> = tab.map { t -> t?.let { String.format(format = context.getString(R.string.tab_title), t.songName, it.artistName) } ?: "" }

    override val addToPlaylistDialogSelectedPlaylistTitle: LiveData<String?> = addToPlaylistDialogSelectedPlaylist.map { p -> p?.title }

    override val addToPlaylistDialogConfirmButtonEnabled: LiveData<Boolean> = addToPlaylistDialogSelectedPlaylist.map { p -> p != null }

    //#endregion

    //#region event handling

    fun onExportToPdfClick(exportFile: Uri, contentResolver: ContentResolver) {
        val exportJob = CoroutineScope(Dispatchers.IO).async {
            val pdfDoc = createPdf()
            contentResolver.openOutputStream(exportFile).use { outputStream ->
                pdfDoc.writeTo(outputStream)
                pdfDoc.close()
                outputStream?.flush()
            }
        }

        exportJob.invokeOnCompletion { ex ->
            if (ex != null) {
                Log.e(TAG, "Unexpected error during playlist export: ${ex.message}")
            }
        }
    }

    /** Saves the song as a new file in the local song folder, telling the user where it went */
    fun onSaveAsLocalFileClick(context: Context) {
        val currentTab = tab.value ?: return
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val message = try {
                LocalFileImporter.saveAsSongFile(appContext, dataAccess, currentTab)
                    ?.let { fileName -> appContext.getString(R.string.message_saved_as_local_file, fileName) }
                    ?: appContext.getString(R.string.message_save_as_local_file_no_folder)
            } catch (ex: Exception) {  // e.g. SecurityException when the folder was granted read-only
                Log.e(TAG, "Couldn't save tab ${currentTab.tabId} as a local song file: ${ex.message}", ex)
                appContext.getString(R.string.message_save_as_local_file_failed)
            }
            withContext(Dispatchers.Main) { Toast.makeText(appContext, message, Toast.LENGTH_LONG).show() }
        }
    }

    fun onPlaylistNextSongClick() {
        val currentTab = tab.value
        if (currentTab != null && currentTab is TabWithDataPlaylistEntry) {
            val entryIdToNavigateTo = currentTab.nextEntryId
            if (entryIdToNavigateTo != null) {
                onNavigateToPlaylistEntry(entryIdToNavigateTo)
            } else {
                Log.w(TAG, "Playlist next song click event triggered while next entry id is null")
            }
        } else {
            Log.w(TAG, "Playlist next song clicked while tab (id: $providedTabId, playlistEntryId: $entryId) is null or not playlist entry: ${tab.value?.toString()}")
        }
    }

    fun onPlaylistPreviousSongClick() {
        val currentTab = tab.value
        if (currentTab != null && currentTab is TabWithDataPlaylistEntry) {
            val entryIdToNavigateTo = currentTab.prevEntryId
            if (entryIdToNavigateTo != null) {
                onNavigateToPlaylistEntry(entryIdToNavigateTo)
            } else {
                Log.w(TAG, "Playlist previous song click event triggered while previous entry id is null")
            }
        } else {
            Log.w(TAG, "Playlist previous song clicked while tab (tabId: $providedTabId, playlistEntryId: $entryId) is null or not playlist entry: ${tab.value?.toString()}")
        }
    }

    /** Back to the chords and capo as written */
    fun onTransposeResetClick() {
        playAs(0, tab.value?.capo ?: 0)
    }

    /** Key up: chord shapes up, same capo, so the song sounds a half step higher */
    fun onTransposeUpClick() {
        transpose(1)
    }

    fun onTransposeDownClick() {
        transpose(-1)
    }

    /** Capo up a fret, chord shapes unchanged: sounds a half step higher */
    fun onCapoUpClick() {
        val current = capo.value ?: 0
        if (current < MAX_CAPO) setCapo(current + 1)
    }

    fun onCapoDownClick() {
        val current = capo.value ?: 0
        if (current > 0) setCapo(current - 1)
    }

    /** Capo up a fret, chord shapes down a half step: same sounding key */
    fun onCapoKeepKeyUpClick() {
        val current = capo.value ?: 0
        if (current >= MAX_CAPO) return
        setCapo(current + 1)
        transpose(-1)
    }

    fun onCapoKeepKeyDownClick() {
        val current = capo.value ?: 0
        if (current <= 0) return
        setCapo(current - 1)
        transpose(1)
    }

    /**
     * Plays as [favorite]; if that's already how it's played, toggles it as the favourite that
     * suits the singer's voice best.
     */
    fun onTransposeToFavoriteClick(context: Context, favorite: FavoriteKey) {
        if (!favorite.sameAs(transpose.value ?: 0, capo.value ?: 0)) {
            playAs(favorite.transpose, favorite.capo)
            return
        }
        val makeBest = !favorite.best
        saveFavorites(context, favoriteKeys.value.orEmpty().map { it.copy(best = makeBest && it == favorite) })
    }

    /** The original key, with the capo as written */
    fun onTransposeToOriginalClick() {
        playAs(originalTranspose.value ?: 0, tab.value?.capo ?: 0)
    }
    /** Applies a capo suggestion (plays as the suggested shapes and capo) */
    fun onCapoSuggestionClick(suggestion: CapoSuggestion) {
        playAs(suggestion.transpose, suggestion.capo)
    }

    /**
     * Adds the current chord shapes and capo to this song's favourites, or removes them if they
     * already are one.
     */
    fun onFavoriteTransposeToggle(context: Context) {
        val currentTranspose = transpose.value ?: 0
        val currentCapo = capo.value ?: 0
        val favorites = favoriteKeys.value.orEmpty()
        if (favorites.any { it.sameAs(currentTranspose, currentCapo) }) {
            saveFavorites(context, favorites.filterNot { it.sameAs(currentTranspose, currentCapo) })
        } else {
            saveFavorites(context, favorites + FavoriteKey(currentTranspose, currentCapo))
        }
    }

    /**
     * Stores the favourites; for a song imported from the local song folder, also writes them
     * into the file's header.
     */
    private fun saveFavorites(context: Context, favorites: List<FavoriteKey>) {
        val currentTab = tab.value ?: return
        val flats = useFlats.value == true
        CoroutineScope(Dispatchers.IO).launch {
            dataAccess.updateFavoriteTransposes(currentTab.tabId, formatFavoriteKeys(favorites), favorites.firstOrNull { it.best }?.transpose)
            if (currentTab.tabAccessType == LocalFileImporter.LOCAL_FILE_ACCESS_TYPE) {
                try {
                    if (!LocalFileImporter.writeFavoritesToFile(context.applicationContext, dataAccess, currentTab.tabId, favorites, flats)) {
                        Log.w(TAG, "Couldn't find the local song file for tab ${currentTab.tabId} to save its favorite keys")
                    }
                } catch (ex: Exception) {  // e.g. SecurityException when the folder was granted read-only
                    Log.e(TAG, "Couldn't save favorite keys to local song file: ${ex.message}", ex)
                }
            }
        }
    }

    /**
     * Callback to be called when the user triggers a tab refresh. Tries to retrieve a tab ID if the
     * initial load failed, and re-fetches the tab from the internet
     */
    fun onReload() {
        load(true)
    }

    /**
     * Callback for when a chord is clicked, to display the chord fingering diagram
     */
    @OptIn(ExperimentalTextApi::class)
    fun onChordClick(chord: String) {
        _chordDetailsState.value = LoadingState.Loading
        _chordDetailsActive.postValue(true)
        currentChordToDisplay.postValue(chord)

        // todo: handle link clicks
//        content.value?.getUrlAnnotations(clickLocation, clickLocation)?.firstOrNull()?.item?.let {
//                urlAnnotation ->
//            try {
//                uriHandler.openUri(urlAnnotation.url.trim())
//            } catch (ex: ActivityNotFoundException) {
//                Log.i(TAG, "Couldn't launch URL, copying to clipboard instead")
//                clipboardManager.nativeClipboard.setPrimaryClip(ClipData.newPlainText(urlAnnotation.url.trim(), urlAnnotation.url.trim()))
//            }
//        }
    }

    fun onChordDetailsDismiss() {
        _chordDetailsActive.postValue(false)
    }

    fun onAutoscrollSliderValueChange(newValue: Float) {
         _autoscrollSpeedSliderPosition.postValue(newValue)
    }

    /**
     * Callback for when the user lifts their finger after adjusting the autoscroll speed. Save the new
     * autoscroll slider position to the database here rather than in [onAutoscrollSliderValueChange]
     * to prevent too many database calls
     */
    fun onAutoscrollSliderValueChangeFinished(context: android.content.Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val sliderValue = autoScrollSpeedSliderPosition.value ?: 0.5f
            // save to global preference (the last used value)
            dataAccess.upsert(Preference(Preference.AUTOSCROLL_DELAY, sliderValue.toString()))
            // save to per-song column if a song is loaded
            val currentTab = tab.value
            if (currentTab != null) {
                dataAccess.updateAutoscrollSlider(currentTab.tabId, sliderValue)
                // write back to local song file if it's a local tab
                if (currentTab.tabAccessType == LocalFileImporter.LOCAL_FILE_ACCESS_TYPE) {
                    try {
                        LocalFileImporter.writeAutoscrollToFile(context, dataAccess, currentTab.tabId, sliderValue)
                    } catch (ex: Exception) {
                        Log.w(TAG, "Couldn't write autoscroll to local file for tab ${currentTab.tabId}: ${ex.message}")
                    }
                }
            }
        }
    }

    /**
     * Callback for when the Play/Pause button is pressed, to enabled or disable autoscroll
     */
    /** Stops autoscroll; the screen scrolls itself back to the top */
    fun onAutoscrollResetClick() {
        _autoscrollPaused.postValue(true)
    }

    fun onAutoscrollButtonClick() {
        if (autoscrollPaused.value != false) recordPlay()
        _autoscrollPaused.postValue(autoscrollPaused.value == false)  // toggle the pause state of autoscroll
    }

    private var playRecorded = false

    /** Counts this opening of the song as played, once */
    private fun recordPlay() {
        val tabId = tab.value?.tabId ?: return
        if (playRecorded) return
        playRecorded = true
        CoroutineScope(Dispatchers.IO).launch {
            dataAccess.insert(PlayHistory(tabId = tabId, playedAt = System.currentTimeMillis()))
        }
    }

    fun onFavoriteButtonClick() {
        CoroutineScope(Dispatchers.IO).launch {
            if(isFavorite.value == true) {
                tab.value?.let { dataAccess.deleteTabFromFavorites(it.tabId) }
            } else {
                val transpose = transpose.value ?: 0
                tab.value?.let { dataAccess.insertToFavorites(it.tabId, transpose) }
            }
        }
    }

    fun onAddPlaylistDialogPlaylistSelected(selection: Playlist) {
        _addToPlaylistDialogSelectedPlaylist.postValue(selection)
    }

    /**
     * Callback for when the AddToPlaylist dialog Confirm button is pressed. Add the current tab
     * to the selected playlist
     */
    fun onAddToPlaylist() {
        val selectedPlaylist = addToPlaylistDialogSelectedPlaylist.value
        val currentTab = tab.value
        val currentTranspose = transpose.value

        if (selectedPlaylist != null && currentTab != null && currentTranspose != null) {
            CoroutineScope(Dispatchers.IO).launch {
                dataAccess.appendToPlaylist(
                    selectedPlaylist.playlistId,
                    currentTab.tabId,
                    currentTranspose
                )
            }
        }
    }

    fun onCreatePlaylist(title: String, description: String) {
        val playlistToSave = Playlist(playlistId = 0, userCreated = true, title = title, description = description, dateCreated = System.currentTimeMillis(), dateModified = System.currentTimeMillis())
        CoroutineScope(Dispatchers.IO).launch {
            val newPlaylistId = dataAccess.upsert(playlistToSave)
            val newPlaylist = dataAccess.getPlaylist(newPlaylistId.toInt())
            _addToPlaylistDialogSelectedPlaylist.postValue(newPlaylist)
        }
    }

    /**
     * Handle user zooming in and out
     */
    fun onZoom(zoomFactor: Float) {
        val currentFontSize = fontSizeSp.value
        if (currentFontSize != null) {
            userAdjustedFontSize.value =
                currentFontSize.times(zoomFactor).coerceIn(  // Add checks for maximum and minimum font size
                    MIN_FONT_SIZE_SP,
                    MAX_FONT_SIZE_SP
                )
        }
    }

    fun onTextSizeIncreaseClick() = adjustTextSize(TEXT_SIZE_STEP_SP)

    fun onTextSizeDecreaseClick() = adjustTextSize(-TEXT_SIZE_STEP_SP)

    /**
     * Adjusts the text size by [deltaSp] and remembers the new size for this song, so it's restored
     * next time this song is opened
     */
    private fun adjustTextSize(deltaSp: Float) {
        val currentFontSize = fontSizeSp.value ?: return
        val newFontSize = (currentFontSize + deltaSp).coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
        userAdjustedFontSize.value = newFontSize

        val tabId = tab.value?.tabId
        if (tabId != null) {
            CoroutineScope(Dispatchers.IO).launch {
                dataAccess.updateTextSize(tabId, newFontSize)
            }
        }
    }

    /**
     * Handle user toggling two-column view for this song's content, remembering the choice for
     * next time this song is opened
     */
    fun onTwoColumnViewToggled(enabled: Boolean) {
        val tabId = tab.value?.tabId ?: return
        CoroutineScope(Dispatchers.IO).launch {
            dataAccess.updateTwoColumnView(tabId, enabled)
        }
    }

    /**
     * Handle user selecting a different instrument to display tabs for
     */
    fun onInstrumentSelected(instrument: Instrument) {
        _chordDetailsState.value = LoadingState.Loading
        CoroutineScope(Dispatchers.IO).launch {
            dataAccess.upsert(Preference(Preference.INSTRUMENT, instrument.name))
            _chordDetailsState.postValue(LoadingState.Success)
        }
    }

    /**
     * Handle user toggling between flats and sharps by converting all chords to use flats or sharps
     * depending on the passed parameter
     */
    fun onUseFlatsToggled(useFlats: Boolean) {
        _chordDetailsState.value = LoadingState.Loading
        // use the transpose function to force the correct flat/sharp
        currentChordToDisplay.value = Chord.transposeChord(currentChordToDisplay.value.toString(), 0, useFlats)

        CoroutineScope(Dispatchers.IO).launch {
            dataAccess.upsert(Preference(Preference.USE_FLATS, useFlats.toString()))
        }
    }

    /**
     * Handle user toggling the pinned chords display
     */
    fun onChordsPinnedToggled(pinChords: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            dataAccess.upsert(Preference(Preference.PIN_CHORDS, pinChords.toString()))
        }
    }

    /**
     * Handle user hiding or showing the song details and transpose controls
     */
    fun onDetailsHiddenToggled(hidden: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            dataAccess.upsert(Preference(Preference.HIDE_TAB_DETAILS, hidden.toString()))
        }
    }

    fun onSectionReferencesExpandedToggled(expanded: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            dataAccess.upsert(Preference(Preference.EXPAND_SECTION_REFERENCES, expanded.toString()))
        }
    }

    fun onChordHintsToggled(shown: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            dataAccess.upsert(Preference(Preference.CHORD_HINTS, shown.toString()))
        }
    }

    fun onHeaderDiagramsToggled(shown: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            dataAccess.upsert(Preference(Preference.HEADER_DIAGRAMS, shown.toString()))
        }
    }

    fun onBlankLinesHiddenToggled(hidden: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            dataAccess.upsert(Preference(Preference.HIDE_BLANK_LINES, hidden.toString()))
        }
    }

    /** Filters the home song lists by just [tag] */
    fun onTagClick(tag: String) {
        CoroutineScope(Dispatchers.IO).launch {
            dataAccess.upsert(Preference(Preference.TAG_FILTER, tagKey(tag)))
        }
    }

    fun onTabViewModeSelected(mode: TabViewMode) {
        CoroutineScope(Dispatchers.IO).launch {
            dataAccess.upsert(Preference(Preference.TAB_VIEW_MODE, mode.name))
        }
    }

    //#endregion

    //#region init

    init {
        // load the tab content from the database (or the internet if no cached database value)
        load()

        // initial autoscroll speed: the song's own, else one estimated from its tempo, else the last used one
        viewModelScope.launch {
            val lastUsed = withContext(Dispatchers.IO) {
                try {
                    dataAccess.getPreferenceValue(Preference.AUTOSCROLL_DELAY)?.toFloat()
                } catch (ex: Exception) {
                    Log.e(TAG, "Couldn't load autoscroll user preference: ${ex.message}", ex)
                    null
                }
            }
            _autoscrollSpeedSliderPosition.value = lastUsed ?: 0.5f
            val loadedTab = tab.asFlow().filterNotNull().first()
            val system = android.content.res.Resources.getSystem()
            // a lyric line with its chords above it is about two text lines high
            val linePx = 2.4f * (loadedTab.textSizeSp ?: defaultFontSize) * system.displayMetrics.density * system.configuration.fontScale
            val songSpeed = loadedTab.autoscrollSlider
                ?: autoscrollDelayForTempo(loadedTab.tempo, linePx)?.let { sliderForDelay(it, mapAutoscrollSliderToScrollDelay) }
            if (songSpeed != null) _autoscrollSpeedSliderPosition.value = songSpeed
        }

        // a song open this long was played, even without autoscroll
        viewModelScope.launch {
            tab.asFlow().filterNotNull().first()
            delay(PLAYED_AFTER_MS)
            recordPlay()
        }
    }

    //#endregion
}

/** How long a song has to be open to count as played */
private const val PLAYED_AFTER_MS = 30_000L
