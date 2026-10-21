package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.WatchedCreator
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchedCreatorDao {
    @Query("SELECT * FROM watched_creators ORDER BY isLive DESC, username ASC")
    fun getAllWatched(): Flow<List<WatchedCreator>>

    @Query("SELECT * FROM watched_creators WHERE autoRecord = 1")
    suspend fun getAutoRecordCreators(): List<WatchedCreator>

    @Query("SELECT * FROM watched_creators WHERE username = :username")
    suspend fun getByUsername(username: String): WatchedCreator?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(creator: WatchedCreator)

    @Update
    suspend fun update(creator: WatchedCreator)

    @Delete
    suspend fun delete(creator: WatchedCreator)

    @Query("DELETE FROM watched_creators WHERE username = :username")
    suspend fun deleteByUsername(username: String)
}
