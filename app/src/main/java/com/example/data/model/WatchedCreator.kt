package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watched_creators")
data class WatchedCreator(
    @PrimaryKey
    val username: String, // clean username without @
    val nickname: String = "",
    val avatarUrl: String = "",
    val autoRecord: Boolean = false,
    val isLive: Boolean = false,
    val lastTitle: String = "",
    val lastCheckedTimestamp: Long = 0,
    val lastSeenLiveTimestamp: Long = 0
)
