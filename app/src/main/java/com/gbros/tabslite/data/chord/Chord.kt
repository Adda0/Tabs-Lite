package com.gbros.tabslite.data.chord

import android.util.Log
import com.gbros.tabslite.data.DataAccess
import com.gbros.tabslite.data.chord.Chord.useFlats
import com.gbros.tabslite.utilities.TAG
import com.gbros.tabslite.utilities.stripChordDecorations
import com.gbros.tabslite.utilities.splitChordDecorationPrefix
import com.gbros.tabslite.utilities.BackendConnection
import kotlin.math.abs

object Chord {
    // region public methods

    /**
     * The name a chord is stored and fetched under: the chord name with any hand-written notation
     * normalized away - decoration like `(D)`/`[Am]`/`(\Dm(7))` (see [stripChordDecorations]), a
     * German/Czech `H` root rewritten as the `B` it is, and the Czech `mi` minor suffix written as
     * the English `m` (`Hmi` -> `Bm`). The chord database and the chord API only know plain English
     * chord names, so a chord written in personal notation has to be looked up by this name; what's
     * *displayed* stays exactly as written (see [transposeChord]).
     */
    fun lookupName(chordName: String): String {
        val undecorated = stripChordDecorations(chordName)
        val englishRoot = if (undecorated.startsWith(GERMAN_B_NATURAL_ROOT, true)) {
            ENGLISH_B_NATURAL_ROOT + undecorated.substring(1)
        } else {
            undecorated
        }

        return CZECH_MINOR_SUFFIX_REGEX.replace(englishRoot, "$1m")
    }

    suspend fun ensureAllChordsDownloaded(chords: List<String>, instrument: Instrument, dataAccess: DataAccess) {
        val chordNames = chords.map { chord -> lookupName(chord) }.filter { it.isNotEmpty() }.distinct()

        // find chords that aren't in the database
        val alreadyDownloadedChords = dataAccess.findAll(chordNames, instrument)
        val chordsToDownload = chordNames.filter { usedChord -> !alreadyDownloadedChords.contains(usedChord) }

        // download
        if (chordsToDownload.isNotEmpty()) {
            BackendConnection.updateChordVariations(chordsToDownload, dataAccess, Instrument.Guitar)
            BackendConnection.updateChordVariations(chordsToDownload, dataAccess, Instrument.Ukulele)
        }
    }

    /**
     * Transpose one chord a specified number of steps up or down. Also converts to the correct form
     * (flats vs sharps)
     *
     * A chord name can carry hand-written notation of its own, which is kept as typed rather than
     * getting in the way of transposition:
     * - a leading `(`, `[`, `{` or `\` (see [splitChordDecorationPrefix]), e.g. `(Am7)` marking an
     *   optional chord change: the decoration is separated off, the chord name inside it is
     *   transposed, and the decoration is put back
     * - a German/Czech `H` root (B natural), e.g. `Hmi`: transposed as the B it is, and written
     *   back as `H` whenever the transposed root is still B natural, so the notation survives
     */
    fun transposeChord(chord: CharSequence, halfSteps: Int, useFlats: Boolean): String {
        val numSteps = abs(halfSteps)
        val up = halfSteps > 0

        val chordParts = chord.split('/').toTypedArray()  // handle chords with a base note like G/B
        for (i in chordParts.indices) {
            val (decoration, writtenName) = splitChordDecorationPrefix(chordParts[i])
            if (writtenName.isEmpty()) {
                continue  // nothing to transpose (e.g. the empty half of "C/", or a bare "()")
            }

            val usesGermanBNatural = writtenName.startsWith(GERMAN_B_NATURAL_ROOT, true)
            var chordName = if (usesGermanBNatural) ENGLISH_B_NATURAL_ROOT + writtenName.substring(1) else writtenName

            if (up) {
                // transpose up
                for (j in 0 until numSteps) {
                    chordName = transposeUp(chordName)
                }
            } else {
                // transpose down
                for (j in 0 until numSteps) {
                    chordName = transposeDown(chordName)
                }
            }
            chordName = useFlats(chordName, useFlats)

            if (usesGermanBNatural && hasBNaturalRoot(chordName)) {
                chordName = GERMAN_B_NATURAL_ROOT + chordName.substring(1)
            }

            chordParts[i] = decoration + chordName
        }

        return chordParts.joinToString("/")
    }

    suspend fun getChord(chord: String, instrument: Instrument, dataAccess: DataAccess) {
        val chordName = lookupName(chord)
        if (chordName.isEmpty()) {
            return
        }

        dataAccess.getChordVariations(chordName, instrument).ifEmpty {
            BackendConnection.updateChordVariations(listOf(chordName), dataAccess, Instrument.Guitar)
            BackendConnection.updateChordVariations(listOf(chordName), dataAccess, Instrument.Ukulele)
        }
    }

    // endregion

    // region private methods

    /**
     * German/Czech notation writes B natural as `H` (and B flat as plain `B`); the app writes both
     * roots in English (`B`/`Bb`), so an `H` root is swapped for its English equivalent before
     * transposing and swapped back after - see [transposeChord].
     */
    private const val GERMAN_B_NATURAL_ROOT = "H"
    private const val ENGLISH_B_NATURAL_ROOT = "B"
    private const val B_FLAT_ROOT = "Bb"

    /**
     * The Czech `mi` minor suffix (`Ami`, `F#mi7`) directly after the root note, captured with the
     * root so [lookupName] can write it as the English `m` the chord database knows. `min` is left
     * alone - it's already an English spelling of the same quality.
     */
    private val CZECH_MINOR_SUFFIX_REGEX = Regex("^([A-G][#b]?)mi(?!n)")

    /**
     * Whether [chordName] is rooted on B natural (`B`, `Bm7`, ...) rather than B flat (`Bb`, ...)
     */
    private fun hasBNaturalRoot(chordName: String): Boolean =
        chordName.startsWith(ENGLISH_B_NATURAL_ROOT, true) && !chordName.startsWith(B_FLAT_ROOT, true)

    /**
     * Helper function to convert chords to the correct form (flats or sharps), depending on user
     * preference
     *
     * @param chordName: The chord name (e.g. A#m7 but not A#m7/G) to convert (e.g. Bbm7)
     * @param useFlats: Whether to convert sharps to flats (true) or flats to sharps (false)
     */
    fun useFlats(chordName: String, useFlats: Boolean): String {
        return when {
            useFlats && chordName.startsWith("A#", true) -> "Bb" + chordName.substring(2)
            !useFlats && chordName.startsWith("Bb", true) -> "A#" + chordName.substring(2)

            useFlats && chordName.startsWith("C#", true) -> "Db" + chordName.substring(2)
            !useFlats && chordName.startsWith("Db", true) -> "C#" + chordName.substring(2)

            useFlats && chordName.startsWith("D#", true) -> "Eb" + chordName.substring(2)
            !useFlats && chordName.startsWith("Eb", true) -> "D#" + chordName.substring(2)

            useFlats && chordName.startsWith("F#", true) -> "Gb" + chordName.substring(2)
            !useFlats && chordName.startsWith("Gb", true) -> "F#" + chordName.substring(2)

            useFlats && chordName.startsWith("G#", true) -> "Ab" + chordName.substring(2)
            !useFlats && chordName.startsWith("Ab", true) -> "G#" + chordName.substring(2)

            else -> chordName  // no change needed
        }
    }

    /**
     * Helper function to transpose a chord name up by one half step
     *
     * @param text: The chord name (e.g. A#m7) to transpose (e.g. Bm7)
     */
    private fun transposeUp(text: String): String {
        return when {
            text.startsWith("A#", true) -> "B" + text.substring(2)
            text.startsWith("Ab", true) -> "A" + text.substring(2)
            text.startsWith("A", true) -> "A#" + text.substring(1)
            text.startsWith("Bb", true) -> "B" + text.substring(2)
            text.startsWith("B", true) -> "C" + text.substring(1)
            text.startsWith("C#", true) -> "D" + text.substring(2)
            text.startsWith("C", true) -> "C#" + text.substring(1)
            text.startsWith("D#", true) -> "E" + text.substring(2)
            text.startsWith("Db", true) -> "D" + text.substring(2)
            text.startsWith("D", true) -> "D#" + text.substring(1)
            text.startsWith("Eb", true) -> "E" + text.substring(2)
            text.startsWith("E", true) -> "F" + text.substring(1)
            text.startsWith("F#", true) -> "G" + text.substring(2)
            text.startsWith("F", true) -> "F#" + text.substring(1)
            text.startsWith("G#", true) -> "A" + text.substring(2)
            text.startsWith("Gb", true) -> "G" + text.substring(2)
            text.startsWith("G", true) -> "G#" + text.substring(1)
            else -> {
                Log.e(TAG, "Weird Chord not transposed: $text")
                text
            }
        }
    }

    /**
     * Helper function to transpose a chord name down by one half step
     *
     * @param text: The chord name (e.g. A#m7) to transpose (e.g. Am7)
     */
    private fun transposeDown(text: String): String {
        return when {
            text.startsWith("A#", true) -> "A" + text.substring(2)
            text.startsWith("Ab", true) -> "G" + text.substring(2)
            text.startsWith("A", true) -> "G#" + text.substring(1)
            text.startsWith("Bb", true) -> "A" + text.substring(2)
            text.startsWith("B", true) -> "A#" + text.substring(1)
            text.startsWith("C#", true) -> "C" + text.substring(2)
            text.startsWith("C", true) -> "B" + text.substring(1)
            text.startsWith("D#", true) -> "D" + text.substring(2)
            text.startsWith("Db", true) -> "C" + text.substring(2)
            text.startsWith("D", true) -> "C#" + text.substring(1)
            text.startsWith("Eb", true) -> "D" + text.substring(2)
            text.startsWith("E", true) -> "D#" + text.substring(1)
            text.startsWith("F#", true) -> "F" + text.substring(2)
            text.startsWith("F", true) -> "E" + text.substring(1)
            text.startsWith("G#", true) -> "G" + text.substring(2)
            text.startsWith("Gb", true) -> "F" + text.substring(2)
            text.startsWith("G", true) -> "F#" + text.substring(1)
            else -> {
                Log.e(TAG, "Weird Chord not transposed: $text")
                text
            }
        }
    }

    // endregion
}