package com.gbros.tabslite.utilities

private val BPM_REGEX = Regex("(\\d+(?:[.,]\\d+)?)\\s*BPM", RegexOption.IGNORE_CASE)
private val TIME_SIGNATURE_REGEX = Regex("(\\d+)\\s*/\\s*\\d+")

/** The speed in a `tempo` header, e.g. 68 from `4/4 (~68 BPM)` or `120 bpm`; null without a BPM figure */
fun parseBpm(tempo: String): Float? = BPM_REGEX.find(tempo)?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull()?.takeIf { it > 0 }

/**
 * A starting autoscroll delay (ms per 1px scroll step) for a song with this `tempo` header, or null
 * without a BPM in it. Model: a lyric line (chords above it) is [linePx] high and takes 8 beats,
 * 12 in triple time (3/4, 6/8 - a line is then usually 4 bars of 3).
 */
fun autoscrollDelayForTempo(tempo: String, linePx: Float): Float? {
    val bpm = parseBpm(tempo) ?: return null
    val beatsPerBar = TIME_SIGNATURE_REGEX.find(tempo)?.groupValues?.get(1)?.toIntOrNull()
    val beatsPerLine = if (beatsPerBar != null && beatsPerBar % 3 == 0) 12f else 8f
    return beatsPerLine * 60_000f / bpm / linePx
}

/** The slider position (0..1) at which the decreasing [sliderToDelay] gives [delay], clamped to the slider's range */
fun sliderForDelay(delay: Float, sliderToDelay: (Float) -> Float): Float {
    var slow = 0f
    var fast = 1f
    repeat(30) {
        val middle = (slow + fast) / 2
        if (sliderToDelay(middle) > delay) slow = middle else fast = middle
    }
    return (slow + fast) / 2
}
