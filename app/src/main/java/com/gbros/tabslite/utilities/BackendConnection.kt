package com.gbros.tabslite.utilities

import android.accounts.AuthenticatorException
import android.content.res.Resources.NotFoundException
import android.os.Build
import android.util.Log
import com.gbros.tabslite.data.DataAccess
import com.gbros.tabslite.data.SearchSuggestions
import com.gbros.tabslite.data.chord.ChordVariation
import com.gbros.tabslite.data.chord.Instrument
import com.gbros.tabslite.data.playlist.Playlist.Companion.TOP_TABS_PLAYLIST_ID
import com.gbros.tabslite.data.servertypes.SearchRequestType
import com.gbros.tabslite.data.servertypes.SearchSuggestionType
import com.gbros.tabslite.data.servertypes.ServerTimestampType
import com.gbros.tabslite.data.servertypes.SongRequestType
import com.gbros.tabslite.data.servertypes.TabRequestType
import com.gbros.tabslite.data.tab.TabDataType
import com.gbros.tabslite.utilities.BackendConnection.apiKey
import com.gbros.tabslite.utilities.BackendConnection.deviceId
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * The API interface handling all API-specific logic to get data from the server (or send to the server)
 */
object BackendConnection {
    //#region private data

    private val gson = Gson()

    private var apiKey: String? = null

    private val apiKeyFetchLock: Mutex = Mutex(locked = false)

    /**
     * Songs/artists created locally that don't have a tab backing them yet (a brand-new song has
     * nowhere local to live until its first tab is submitted). Entries are removed once a tab is
     * created for that song. Does not survive process death.
     */
    private val pendingSongs = ConcurrentHashMap<String, SongRequestType>()
    private val pendingArtists = ConcurrentHashMap<String, String>()

    //#endregion

    //#region public data

    private var storeDeviceId: String? = null
    private val deviceId: String
        get() = fetchDeviceId()

    //#endregion

    //#region public methods

    /**
     * Create a new song locally.
     *
     * @param [song] the song to create.
     * @param [dataAccess] the local database, used to look for an existing matching song to reuse its ID
     *
     * @return The ID of the created (or matching pre-existing) song
     */
    suspend fun createSong(song: SongRequestType, dataAccess: DataAccess): String {
        require(song.song_name.isNotBlank()) { "Song name cannot be blank" }
        require(song.artist_name.isNotBlank()) { "Artist name cannot be blank" }
        require(song.artist_id.isNotBlank()) { "Artist ID cannot be blank - ensure the artist has been created first." }
        require(song.song_genre.isNotBlank()) { "Song genre cannot be blank" }

        // reuse an existing song's ID if this exact song was already created locally
        val existingSongId = dataAccess.findSongId(song.song_name, song.artist_name, song.artist_id, song.song_genre)
        if (existingSongId != null) {
            return existingSongId
        }

        val newSongId = UUID.randomUUID().toString()
        song.song_id = newSongId
        song.status = "approved"
        pendingSongs[newSongId] = song
        Log.v(TAG, "Created song $newSongId")
        return newSongId
    }

    /**
     * Get the artist ID of the artist with the passed name.
     *
     * @param [artistName] the name of the artist
     * @param [dataAccess] the local database, searched for any existing tab by this artist
     *
     * @return The ID of the artist with the passed name
     *
     * @throws NotFoundException if the artist with the passed name is not found locally
     */
    suspend fun fetchArtistId(artistName: String, dataAccess: DataAccess): String {
        if (artistName.isBlank()) return ""
        val trimmedName = artistName.trim()

        pendingArtists[trimmedName.lowercase()]?.let { return it }

        return dataAccess.findArtistId(trimmedName)
            ?: throw NotFoundException("Artist $trimmedName not found in database")
    }

    suspend fun fetchSongDetails(songId: String, dataAccess: DataAccess): SongRequestType? {
        if (songId.isBlank()) return null

        pendingSongs[songId]?.let { return it }

        return dataAccess.findSongInfoBySongId(songId)?.let { info ->
            SongRequestType(song_id = songId, song_name = info.songName, artist_name = info.artistName, artist_id = info.artistId, song_genre = info.songGenre)
        }
    }

    /**
     * Create a new artist locally.
     *
     * @param [artistName] the name of the artist
     *
     * @return The ID of the created artist
     */
    fun createNewArist(artistName: String): String {
        val newArtistId = UUID.randomUUID().toString()
        pendingArtists[artistName.trim().lowercase()] = newArtistId
        return newArtistId
    }

    /**
     * Create a new tab locally. Fetches song info to insert into tab.
     *
     * @param [tab] the tab to create. `id`, `artist_id`, `artist_name`, `song_genre`, and `song_name` fields not respected.
     * @param [dataAccess] the local database, used to fetch song info and to know the created tab's song is now backed by a real tab
     *
     * @return The ID of the created tab
     */
    suspend fun createTab(tab: TabRequestType, dataAccess: DataAccess): TabDataType {
        // fetch song info to insert into tab
        val songDetails = fetchSongDetails(tab.song_id, dataAccess)
            ?: throw NotFoundException("Song ${tab.song_id} not found in database")

        tab.artist_id = songDetails.artist_id
        tab.artist_name = songDetails.artist_name
        tab.song_genre = songDetails.song_genre
        tab.song_name = songDetails.song_name

        // set the date to now as an int
        tab.date = System.currentTimeMillis()
        tab.status = "approved" // created locally; no moderation queue

        tab.id = UUID.randomUUID().toString()
        pendingSongs.remove(tab.song_id) // song is now backed by a real local tab row
        Log.v(TAG, "Created tab ${tab.id}")
        return tab.getTabFull()
    }

    /**
     * Get search suggestions for the given query.  Stores search suggestions to the local database,
     * overwriting any previous search suggestions for the specified query
     *
     * @param [q]: The query to fetch search suggestions for
     *
     * @return A string list of suggested searches, or an empty list if no suggestions could be found.
     */
    suspend fun searchSuggest(q: String, dataAccess: DataAccess) = withContext(Dispatchers.IO) {
        // fetch search suggestions from the internet
        try {
            var query = q
            if (q.length > 5) { // ug api only allows a max of 5 chars for search suggestion requests.  rest of processing is done in app
                query = q.slice(0 until 5)
            }

            val connection = URL("https://api.ultimate-guitar.com/api/v1/tab/suggestion?q=$query").openConnection() as HttpURLConnection
            val suggestions = connection.inputStream.use {inputStream ->
                val jsonReader = JsonReader(inputStream.reader())
                val searchSuggestionTypeToken = object : TypeToken<SearchSuggestionType>() {}.type
                gson.fromJson<SearchSuggestionType>(jsonReader, searchSuggestionTypeToken).suggestions
            }

            if (suggestions.isNotEmpty()) {
                dataAccess.upsert(SearchSuggestions(query = query, suggestions))
            }
            return@withContext
        } catch (_: FileNotFoundException) {
            // no search suggestions for this query
            return@withContext
        } catch (ex: UnknownHostException) {
            // no internet access
            throw NoInternetException("No internet access to fetch search suggestions for query $q", ex)
        } catch (ex: Exception) {
            val message = "SearchSuggest ${ex.javaClass.canonicalName} while finding search suggestions. Probably no internet; no search suggestions added"
            Log.e(TAG, message, ex)
            throw SearchException(message, ex)
        }
    }

    /**
     * Perform a search for the given query, and get the tabs that match that search query.
     *
     * @param [title] The search term to find
     * @param [artistId] (Optional) The ID of the artist to filter search results to. Can be paired with the [title] set to an empty string to perform an artist song list. If null or 0, this parameter will be ignored
     * @param [page] The 1-indexed page of the search results to fetch
     *
     * @return A [SearchRequestType] with the search results, or an empty [SearchRequestType] if there are no search results on that page
     */
    suspend fun search(title: String, artistId: String? = null, page: Int): SearchRequestType = withContext(Dispatchers.IO) {
        var artistQuery = artistId
        if (artistQuery != null) {
            artistQuery = artistId.replace("7567-", "") // search can't handle prefixed ids
            artistQuery = URLEncoder.encode(artistQuery, "utf-8")
            artistQuery = "&artist_id=$artistQuery"
        }
        else {
            artistQuery = ""
        }

        val url =
            "https://api.ultimate-guitar.com/api/v1/tab/search?title=$title&page=$page$artistQuery&type[]=300&official[]=0"

        val inputStream: InputStream?
        try {
            inputStream = authenticatedStream(url)
        } catch (_: NotFoundException) {
            // end of search results
            return@withContext SearchRequestType()
        } catch (ex: NoInternetException) {
            throw ex  // pass through NoInternetExceptions
        } catch (ex: Exception) {
            Log.e(TAG, "Unexpected exception reading search results for page $page of query '$title': ${ex.message}", ex)
            throw SearchException("Couldn't fetch search results for page $page of query '$title': ${ex.message}", ex)
        }

        var result: SearchRequestType
        val jsonReader = JsonReader(inputStream.reader())

        try {
            val searchResultTypeToken = object : TypeToken<SearchRequestType>() {}.type
            result = gson.fromJson(jsonReader, searchResultTypeToken)
            Log.v(TAG, "Search for $title page $page success.")
        } catch (syntaxException: JsonSyntaxException) {
            // usually this block happens when the end of the exact query is reached and a 'did you mean' suggestion is available
            try {
                val stringTypeToken = object : TypeToken<String>() {}.type
                val suggestedSearch: String = gson.fromJson(jsonReader, stringTypeToken)

                result = SearchRequestType(suggestedSearch)
            } catch (_: IllegalStateException) {
                inputStream.close()
                val message = "Search illegal state exception!  Check SearchRequestType for consistency with data.  Query: $title, page $page"
                Log.e(TAG, message, syntaxException)
                throw SearchException(message, syntaxException)
            }
        } finally {
            inputStream.close()
        }

        return@withContext result
    }

    /**
     * Retrieves updated chord charts for the passed list of chords from the internet API, saves them
     * to the database, and returns a map from each chord name passed to the list of chord charts
     *
     * @param chordIds: List of chord names to fetch.  E.g. A#m7, Gsus, A
     * @param dataAccess: Database to save the updated chords to
     * @param instrument: The instrument to fetch chords for.
     *
     * @return Map from chord ID to the list of [ChordVariation] for that chord
     */
    suspend fun updateChordVariations(
        chordIds: List<CharSequence>,
        dataAccess: DataAccess,
        instrument: Instrument,
    ): Map<String, List<ChordVariation>> = withContext(Dispatchers.IO) {
        if (chordIds.isEmpty()) {
            return@withContext emptyMap()
        }

        val (instrumentPath, tuningPath) = when (instrument) {
            Instrument.Guitar -> "guitar" to "E A D G B E"
            Instrument.Ukulele -> "ukulele" to "G C E A"
        }

        val uInstrument = URLEncoder.encode(instrumentPath, "utf-8")
        val uTuning = URLEncoder.encode(tuningPath, "utf-8")
        val chordParam = chordIds.joinToString("") { chord -> "&chords[]=${URLEncoder.encode(chord.toString(), "utf-8")}" }
        val url = "https://api.ultimate-guitar.com/api/v1/tab/applicature?instrument=$uInstrument&tuning=$uTuning$chordParam"

        val resultMap = mutableMapOf<String, List<ChordVariation>>()

        try {
            val results: List<TabRequestType.ChordInfo> = authenticatedStream(url).use { inputStream ->
                val jsonReader = JsonReader(inputStream.reader())
                val chordRequestTypeToken = object : TypeToken<List<TabRequestType.ChordInfo>>() {}.type
                gson.fromJson(jsonReader, chordRequestTypeToken)
            }
            for (result in results) {
                val variations = result.getChordVariations(instrument)
                resultMap[result.chord] = variations
                dataAccess.insertAll(variations) // cache the results in the local DB
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Couldn't fetch chords $chordIds for instrument $instrument: ${ex.message}", ex)
        }

        if (resultMap.isEmpty() && chordIds.isNotEmpty()) {
            val message = "Couldn't fetch any of the requested chords: $chordIds"
            Log.w(TAG, message)
        }

        return@withContext resultMap
    }

    /**
     * Add today's most popular tabs to the database
     */
    suspend fun fetchTopTabs(dataAccess: DataAccess) = withContext(Dispatchers.IO) {
        // 'type[]=300' means just chords (all instruments? use 300, 400, 700, and 800)
        // 'order=hits_daily' means get top tabs today not overall.  For overall use 'hits'
        val topTabSearchResults = authenticatedStream("https://api.ultimate-guitar.com/api/v1/tab/explore?date=0&genre=0&level=0&order=hits_daily&page=1&type=0&official=0").use { inputStream ->
            val jsonReader = JsonReader(inputStream.reader())
            val typeToken = object : TypeToken<List<SearchRequestType.SearchResultTab>>() {}.type

             return@use (gson.fromJson(
                jsonReader,
                typeToken
            ) as List<SearchRequestType.SearchResultTab>)
        }
        val topTabs: List<TabDataType> = topTabSearchResults.map { t -> t.tabFull() }

        if (topTabs.isEmpty()) {
            // don't overwrite with an empty list
            throw NotFoundException("Top tabs result was empty: ${topTabSearchResults.size} results")
        }

        // clear top tabs playlist, then add all these to the top tabs playlist
        dataAccess.clearTopTabsPlaylist()
        for (tab in topTabs) {
            // add playlist entry
            dataAccess.appendToPlaylist(
                playlistId = TOP_TABS_PLAYLIST_ID,
                tabId = tab.tabId,
                transpose = 0
            )

            // add empty tab so it'll show up in the Popular list
            dataAccess.insert(tab)
        }
        return@withContext
    }

    /**
     * Gets tab based on tabId. Loads tab from internet and caches the result automatically in the
     * app database. A song from one of the [SongSource] songbook sites is downloaded from its site.
     *
     * @param tabId The ID of the tab to load
     * @param dataAccess The database instance to load a tab from (or into)
     */
    suspend fun fetchTabFromInternet(
        tabId: String,
        dataAccess: DataAccess,
        tabAccessType: String = "public"
    ): TabDataType = withContext(Dispatchers.IO) {
        Log.v(TAG, "Loading tab $tabId.")
        if (SongSources.sourceOf(tabId) != null) {
            return@withContext fetchSongbookTab(tabId, dataAccess)
        }
        val rawTabId = tabId.replace("7567-", "") // ultimate guitar's api can't handle prefixed ids
        val url = "https://api.ultimate-guitar.com/api/v1/tab/info?tab_id=$rawTabId&tab_access_type=$tabAccessType"

        val requestResponse: TabRequestType = authenticatedStream(url).use { inputStream ->
            val jsonReader = JsonReader(inputStream.reader())
            val tabRequestTypeToken = object : TypeToken<TabRequestType>() {}.type
            gson.fromJson(jsonReader, tabRequestTypeToken)
        }
        requestResponse.id = tabId // preserve the locally-used (possibly prefixed) id
        Log.v(TAG, "Parsed response for tab $tabId. Name: ${requestResponse.song_name}, capo ${requestResponse.capo}")

        val result = requestResponse.getTabFull()
        if (result.content.isNotBlank()) {
            storeFetchedTab(result, dataAccess)
        } else {
            val message = "Tab $tabId fetch completed successfully but had no content! This shouldn't happen. Might be a pro tab?"
            Log.e(TAG, message)
            throw TabFetchException(message)
        }
        result
    }

    /**
     * Downloads the song stored under [tabId] from its songbook site (see [SongSources]), storing
     * every other version of it on the same page along with it
     */
    private suspend fun fetchSongbookTab(tabId: String, dataAccess: DataAccess): TabDataType {
        val versions = try {
            SongSources.download(tabId)
        } catch (ex: FileNotFoundException) {
            throw NotFoundException("Song $tabId not found on its site: ${ex.message}")
        } catch (ex: SongPageException) {
            throw TabFetchException("Song $tabId could not be read from its site", ex)
        } catch (ex: IOException) {
            throw NoInternetException("Could not download song $tabId. ${ex.message}", ex)
        }
        val tabs = versions.map { version ->
            TabDataType(tabId = version.song.tabId, songId = version.song.copy(version = 0).tabId,
                songName = version.songName, artistName = version.artistName, type = "Chords",
                // numbered only among several versions on one page
                version = if (version.versionCount > 1) version.song.version + 1 else 0,
                versionsCount = 2 * version.versionCount, contributorUserName = version.song.source.siteName,
                tonalityName = version.key, capo = version.capo, tempo = version.tempo, content = version.content)
        }
        tabs.forEach { storeFetchedTab(it, dataAccess) }
        return tabs.firstOrNull { it.tabId == tabId } ?: throw NotFoundException("Song $tabId not found on its site: the page has ${tabs.size} versions")
    }

    /**
     * Stores a freshly fetched tab, keeping its remembered local settings (two-column view, text
     * size, fallback, favourite and original transpose) across the re-fetch, since they're
     * local-only and the source knows nothing of them
     */
    private suspend fun storeFetchedTab(result: TabDataType, dataAccess: DataAccess) {
        try {
            val existing = dataAccess.getTabInstance(result.tabId)
            result.twoColumnView = existing.twoColumnView
            result.textSizeSp = existing.textSizeSp
            result.defaultTranspose = existing.defaultTranspose
            result.favoriteTranspose = existing.favoriteTranspose
            result.favoriteTransposes = existing.favoriteTransposes
            result.currentCapo = existing.currentCapo
            result.originalTranspose = existing.originalTranspose
            result.autoscrollSlider = existing.autoscrollSlider
        } catch (ex: Exception) {
            // no existing row for this tab id - nothing to preserve
        }
        dataAccess.upsert(result)
        Log.v(TAG, "Successfully inserted tab ${result.songName} (${result.tabId})")
    }

    /**
     * Fetch multiple tabs from the internet, one at a time (Ultimate Guitar's API has no batch tab
     * lookup). A failure fetching one tab is logged and skipped rather than failing the whole batch.
     */
    suspend fun fetchTabsFromInternet(
        tabIds: List<String>,
        dataAccess: DataAccess,
    ): List<TabDataType> = withContext(Dispatchers.IO) {
        Log.v(TAG, "Loading tabs ${tabIds.joinToString()}.")

        val results = mutableListOf<TabDataType>()
        for (tabId in tabIds) {
            try {
                results.add(fetchTabFromInternet(tabId, dataAccess))
            } catch (ex: NoInternetException) {
                throw ex // no point continuing the batch if we're offline
            } catch (ex: Exception) {
                Log.e(TAG, "Error fetching tab $tabId from the internet.", ex)
            }
        }

        if (results.isEmpty() && tabIds.isNotEmpty()) {
            throw NotFoundException("Tabs (${tabIds.joinToString()}) not found in database")
        }

        return@withContext results
    }

    //#endregion

    //#region private methods

    /**
     * Gets an authenticated input stream for the passed API URL, updating the API key if needed
     *
     * @param url: The UG API url to start an authenticated InputStream with
     *
     * @return An [InputStream], authenticated with a valid API key
     *
     * @throws NoInternetException if no internet access
     * @throws Exception if an unknown error occurs (could still be an internet access issue)
     */
    private suspend fun authenticatedStream(url: String): InputStream = withContext(Dispatchers.IO) {
        Log.v(TAG, "Getting authenticated stream for url: $url.")
        try {
            apiKeyFetchLock.lock()

            if (apiKey == null) {
                updateApiKey()
            }
        } catch (ex: NoInternetException) {
            throw NoInternetException("Can't fetch $url. No internet access.", ex)
        } catch (ex: Exception) {
            throw Exception("Unexpected API Key initialization failure while fetching $url! Maybe an internet issue?", ex)
        } finally {
            apiKeyFetchLock.unlock()
        }

        // api key is not null
        Log.v(TAG, "Api key: $apiKey, device id: $deviceId.")

        var responseCode = 0
        try {
            var numTries = 0
            do {
                numTries++
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept-Charset", "utf-8")
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty(
                    "User-Agent",
                    "UGT_ANDROID/5.10.12 ("
                )  // actual value UGT_ANDROID/5.10.11 (ONEPLUS A3000; Android 10)
                conn.setRequestProperty(
                    "x-ug-client-id",
                    deviceId
                )             // stays constant over time; api key and client id are related to each other.
                conn.setRequestProperty(
                    "x-ug-api-key",
                    apiKey
                )                 // updates periodically.
                conn.connectTimeout = (5000)  // timeout of 5 seconds
                conn.readTimeout = 6000
                responseCode = conn.responseCode
                Log.v(TAG, "Retrieved URL with response code $responseCode.")

                if (responseCode == 498 && numTries == 1) {  // don't bother the second time through
                    Log.i(
                        TAG,
                        "498 response code for old api key $apiKey and device id $deviceId.  Refreshing api key"
                    )
                    conn.disconnect()

                    try {
                        updateApiKey()
                        Log.v(TAG, "Got new api key ($apiKey)")
                    } catch (ex: Exception) {
                        // we don't have an internet connection.  Strange, because we shouldn't have gotten a 498 error code if we had no internet.
                        val msg =
                            "498 response code, but api key update returned null! Generally this means we don't have an internet connection.  Strange, because we shouldn't have gotten a 498 error code if we had no internet.  Either precisely perfect timing or something's wrong."
                        throw Exception(msg, ex)
                    }
                } else {
                    Log.v(TAG, "Fetch attempt $numTries - valid token or max retries reached.")
                    Log.v(TAG, "Response code $responseCode on try $numTries for url $url (${conn.requestMethod}).")

                    if (responseCode == 498) {
                        // read response content if our api level includes the function
                        var content = ""
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            try {
                                content = conn.inputStream.readAllBytes().toString()
                            } catch (_: Exception) { }
                        }
                        throw AuthenticatorException("Couldn't fetch authenticated stream (498: bad token).  Response code: $responseCode, content: \n$content")
                    } else if (responseCode == 451) {
                        // read response content if our api level includes the function
                        var content = ""
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            try {
                                content = conn.inputStream.readAllBytes().toString()
                            } catch (_: Exception) { }
                        }

                        Log.i(TAG, "Url not available (451: unavailable for legal reasons). content: \n$content")
                        throw UnavailableForLegalReasonsException("Url '$url' unavailable for legal reasons: 451.\n$content")
                    } else {
                        return@withContext conn.inputStream
                    }
                }
            } while (true)

            throw Exception("Unreachable: Could not create authenticated stream.")  // shouldn't get here
        } catch (ex: UnavailableForLegalReasonsException) {
            throw ex  // pass through UnavailableForLegalReasonsExceptions
        }
        catch (ex: FileNotFoundException) {
            throw NotFoundException("NOT FOUND during fetch of url $url. Response code $responseCode.", ex)
        } catch (ex: ConnectException) {
            throw NoInternetException("Could not fetch $url. ConnectException (no internet access)", ex)
        } catch (ex: NoInternetException) {
            throw NoInternetException("Could not fetch $url. No internet.", ex)
        } catch (ex: SocketTimeoutException) {
            throw NoInternetException("Could not fetch $url. Socket timeout (no internet access).", ex)
        } catch (ex: IOException) {
            throw NoInternetException("Could not fetch $url. IOException (no internet access). ${ex.message}", ex)
        } catch (ex: Exception) {
            throw Exception("Unexpected exception during fetch of url $url with parameters apiKey: " +
                    "$apiKey and deviceId: $deviceId.  Response code $responseCode", ex)
        }
    }

    /**
     * Sets an updated [apiKey], based on the most recent server time.  This needs to be called
     * whenever we get a 498 response code
     *
     * @throws NoInternetException if no internet access
     * @throws Exception if api key could not be updated for an unknown reason
     */
    private suspend fun updateApiKey() {
        apiKey = null
        val simpleDateFormat = SimpleDateFormat("yyyy-MM-dd:H", Locale.US)
        simpleDateFormat.timeZone = TimeZone.getTimeZone("UTC")
        val stringBuilder = StringBuilder(deviceId)

        val serverTime = fetchServerTime()
        stringBuilder.append(serverTime)
        stringBuilder.append("createLog()")
        apiKey = getMd5(stringBuilder.toString())

        if (apiKey.isNullOrBlank()) {
            throw Exception("API key update completed without fetching API key.  Server time: $serverTime.  API key: $apiKey")
        }
    }

    /**
     * Gets the current server time, for use in API calls
     *
     * @return The current time according to the server
     *
     * @throws NoInternetException if not connected to the internet
     * @throws Exception if time fetch could not be completed for an unknown reason
     */
    private suspend fun fetchServerTime(): String = withContext(Dispatchers.IO) {
        val devId = deviceId
        val lastResult: ServerTimestampType
        val conn = URL("https://api.ultimate-guitar.com/api/v1/common/hello").openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "UGT_ANDROID/5.10.12 (")  // actual value "UGT_ANDROID/5.10.11 (ONEPLUS A3000; Android 10)". 5.10.11 is the app version.
        conn.setRequestProperty("x-ug-client-id", devId)                // stays constant over time; api key and client id are related to each other.

        val serverTimestamp = try {
            conn.inputStream.use {inputStream ->
                val jsonReader = JsonReader(inputStream.reader())
                val serverTimestampTypeToken = object : TypeToken<ServerTimestampType>() {}.type
                lastResult = Gson().fromJson(jsonReader, serverTimestampTypeToken)
                lastResult
            }
        } catch (ex: IllegalStateException) {
            throw IllegalStateException("Error converting types while performing hello handshake. Check proguard rules.", ex)
        } catch (ex: UnknownHostException) {
            throw NoInternetException("Unknown host while performing hello handshake. Probably not connected to the internet.", ex)
        } catch (ex: Exception) {
            throw Exception( "Unexpected error getting hello handshake (server time). We may not be connected to the internet.", ex)
        }

        // read server time into our date type of choice
        val simpleDateFormat = SimpleDateFormat("yyyy-MM-dd:H", Locale.US)
        simpleDateFormat.timeZone = TimeZone.getTimeZone("UTC")
        val formattedDateString = simpleDateFormat.format(serverTimestamp.getServerTime().time)

        Log.i(TAG, "Fetched server time: $formattedDateString}")
        return@withContext formattedDateString
    }

    /**
     * Hash a string using the MD5 algorithm
     *
     * @param [stringToHash]: The string to hash using the MD5 algorithm
     *
     * @return The MD5-hashed version of [stringToHash]
     *
     * @throws NoSuchAlgorithmException if the MD5 algorithm doesn't exist on this device
     */
    private fun getMd5(stringToHash: String): String {
        var ret = stringToHash

        ret = BigInteger(1, MessageDigest.getInstance("MD5").digest(ret.toByteArray())).toString(16)
        while (ret.length < 32) {
            val stringBuilder = java.lang.StringBuilder()
            stringBuilder.append("0")
            stringBuilder.append(ret)
            ret = stringBuilder.toString()
        }
        return ret
    }

    /**
     * Ensures that we have a current deviceId stored.  Creates new ID if needed.  Shouldn't be called
     * directly; use [deviceId] instead.
     *
     * @return The current deviceId (setting it if need be)
     */
    private fun fetchDeviceId(): String {
        val copyOfCurrentDeviceId = storeDeviceId
        return if (copyOfCurrentDeviceId != null) {
            copyOfCurrentDeviceId
        } else {
            // generate a new device id
            var newId = ""
            val charList = charArrayOf('1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')
            while(newId.length < 16) {
                newId += charList[Random.nextInt(0, 15)]
            }
            storeDeviceId = newId
            newId
        }
    }

    //#endregion

    //#region Custom exceptions

    class NoInternetException : Exception {
        constructor() : super()
        constructor(message: String) : super(message)
        constructor(message: String, cause: Throwable) : super(message, cause)
        constructor(cause: Throwable) : super(cause)
    }

    class SearchException : Exception {
        constructor(message: String, cause: Throwable) : super(message, cause)
    }

    open class TabFetchException : Exception {
        constructor(message: String) : super(message)
        constructor(message: String, cause: Throwable) : super(message, cause)
    }

    class UnavailableForLegalReasonsException : NotFoundException {
        constructor(message: String) : super(message)
    }

    //#endregion
}
