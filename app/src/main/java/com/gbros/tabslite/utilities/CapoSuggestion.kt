package com.gbros.tabslite.utilities

/** How hard a way of playing a song is on guitar, judged by its chord shapes */
enum class ChordDifficulty { Easy, Medium, Hard }

/**
 * A way to play the song in the key it currently sounds in: chord shapes [transpose]d from the
 * written key with a capo at fret [capo]. [difficulty] is the average chord difficulty per
 * chord played (0 = only open chords).
 */
data class CapoSuggestion(val transpose: Int, val capo: Int, val difficulty: Double) {
    val level: ChordDifficulty get() = when {
        difficulty < 0.5 -> ChordDifficulty.Easy
        difficulty < 1.5 -> ChordDifficulty.Medium
        else -> ChordDifficulty.Hard
    }
}

private val CHORD_TAG_REGEX = Regex("\\{ch:([^}]+?)\\}")

/** Each chord in [content] (`{ch:Am}` tags) with how often it's played */
fun countChords(content: String): Map<String, Int> =
    CHORD_TAG_REGEX.findAll(content).groupingBy { it.groupValues[1] }.eachCount()

private const val OPEN = 0.0
private const val OPEN_VARIANT = 0.2  // open, but a less familiar shape
private const val BARRE = 2.5
private const val ACCIDENTAL_BARRE = 3.0

// open-position shapes; anything else on a natural root counts as a barre chord
private val CHORD_SHAPE_DIFFICULTY: Map<String, Double> =
    listOf("C", "A", "G", "E", "D", "Am", "Em", "Dm").associateWith { OPEN } +
    listOf(
        "E7", "A7", "D7", "G7", "C7", "Am7", "Em7", "Dm7",
        "Cmaj7", "Fmaj7", "Amaj7", "Dmaj7", "Emaj7", "Gmaj7",
        "Asus2", "Asus4", "Dsus2", "Dsus4", "Esus4", "Gsus4", "Csus2", "Csus4",
        "Cadd9", "Gadd9", "Dadd9", "Eadd9", "Aadd9",
        "A6", "D6", "E6", "G6", "C6", "Am6", "Dm6", "Em6", "Esus2", "E9", "Em9",
    ).associateWith { OPEN_VARIANT } +
    mapOf("B7" to 0.5, "F" to 2.0, "Bm" to 2.0, "Fmaj7" to OPEN_VARIANT, "Bm7" to 1.5, "F7" to 2.0, "Fm" to 2.0)

private val CHORD_ROOT_REGEX = Regex("^([A-Ga-g])([#b]?)(.*)")

/**
 * How hard [chordName] is to play on guitar: 0 for an open chord, up to 3 for a barre chord on a
 * sharp/flat root. [chordName] is the English name ([com.gbros.tabslite.data.chord.Chord.lookupName]);
 * a slash chord scores as its upper chord. Null if it isn't a chord name.
 */
fun chordDifficulty(chordName: String): Double? {
    val match = CHORD_ROOT_REGEX.find(chordName.substringBefore('/').trim()) ?: return null
    val (root, accidental, quality) = match.destructured
    if (accidental.isNotEmpty()) return ACCIDENTAL_BARRE
    val shape = root.uppercase() + if (quality == "sus") "sus4" else quality
    return CHORD_SHAPE_DIFFICULTY[shape] ?: BARRE
}

// each capo fret up the neck is slightly less convenient than open position
private const val CAPO_PENALTY = 0.1

/**
 * The [count] easiest ways to play a song in the key it sounds in now - chord shapes transposed
 * [soundingSteps] half steps above the written ones when played without a capo - from capo 0 up
 * to 7, easiest first. [chordCounts] are the written chords with how often each is played
 * (see [countChords]); [transposeChord] and [lookupName] are the app's chord name helpers. Empty
 * when the song has no recognizable chords.
 */
fun suggestCapos(
    chordCounts: Map<String, Int>,
    soundingSteps: Int,
    transposeChord: (chord: String, halfSteps: Int) -> String,
    lookupName: (chord: String) -> String,
    count: Int = 3
): List<CapoSuggestion> {
    if (chordCounts.isEmpty()) return emptyList()
    return (0..7).mapNotNull { capo ->  // cap at fret 7
        val steps = soundingSteps - capo
        var total = 0.0
        var played = 0
        for ((chord, times) in chordCounts) {
            val difficulty = chordDifficulty(lookupName(transposeChord(chord, steps))) ?: continue
            total += difficulty * times
            played += times
        }
        if (played == 0) null
        else {
            // normalize shape transposition to -6..+5 like transpose buttons
            val normalized = when {
                steps >= -6 && steps <= 5 -> steps
                steps > 5 -> steps - 12
                else -> steps + 12
            }
            CapoSuggestion(normalized, capo, total / played)
        }
    }
        .sortedWith(compareBy({ it.difficulty + it.capo * CAPO_PENALTY }, { it.capo }))
        .take(count)
}
