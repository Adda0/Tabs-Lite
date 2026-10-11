package com.gbros.tabslite.view.songlist

import kotlin.random.Random

/** Songs played this recently are only picked when every song was */
const val RECENTLY_PLAYED_MS = 12 * 60 * 60 * 1000L

/**
 * A random one of [songs] (tab IDs), preferring those not played in the last [RECENTLY_PLAYED_MS]
 * according to [lastPlayed]; null for no songs.
 */
fun pickRandomSong(songs: List<String>, lastPlayed: Map<String, Long>, now: Long, random: Random = Random.Default): String? {
    val notRecent = songs.filter { now - (lastPlayed[it] ?: 0L) >= RECENTLY_PLAYED_MS }
    return notRecent.ifEmpty { songs }.randomOrNull(random)
}
