package com.gbros.tabslite.utilities

/** Whether two transpositions sound the same, i.e. differ by whole octaves (transpose values wrap at ±12) */
fun isSameTransposition(a: Int?, b: Int?): Boolean = a != null && b != null && Math.floorMod(a - b, 12) == 0

private val KEY_ROOT_REGEX = Regex("^\\s*([A-Ha-h])([#b]?)")
private val NATURAL_PITCH_CLASSES = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11, 'H' to 11)

/** Pitch class (0 = C) of a key's root note, e.g. `C#`, `Bb`, `F#m`, or German/Czech `H`; null if unrecognized */
private fun keyPitchClass(key: String): Int? {
    val match = KEY_ROOT_REGEX.find(key) ?: return null
    val natural = NATURAL_PITCH_CLASSES[match.groupValues[1].uppercase().single()] ?: return null
    val accidental = when (match.groupValues[2]) { "#" -> 1; "b" -> -1; else -> 0 }
    return Math.floorMod(natural + accidental, 12)
}

/**
 * Half steps to transpose content written in [fromKey] so it's in [toKey], picking the smaller
 * interval (-5..+6), or null if either key's root note isn't recognized
 */
fun transposeBetweenKeys(fromKey: String, toKey: String): Int? {
    val from = keyPitchClass(fromKey) ?: return null
    val to = keyPitchClass(toKey) ?: return null
    val up = Math.floorMod(to - from, 12)
    return if (up > 6) up - 12 else up
}
