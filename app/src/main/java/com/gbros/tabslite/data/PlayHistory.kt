package com.gbros.tabslite.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One time a song was played: opened for a while, or autoscrolled */
@Entity(tableName = "play_history", indices = [Index("tab_id")])
data class PlayHistory(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "tab_id") val tabId: String,
    @ColumnInfo(name = "played_at") val playedAt: Long
)

/** When a song was last played */
data class LastPlayed(
    @ColumnInfo(name = "tab_id") val tabId: String,
    @ColumnInfo(name = "last_played") val lastPlayed: Long
)

/** How many times a song was played since the app was installed */
data class PlayCount(
    @ColumnInfo(name = "tab_id") val tabId: String,
    @ColumnInfo(name = "play_count") val playCount: Int
)
