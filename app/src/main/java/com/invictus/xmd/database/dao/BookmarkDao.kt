package com.invictus.xmd.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import com.invictus.xmd.database.entities.Bookmark
import com.invictus.xmd.repository.BookmarkRepository

@Dao
interface BookmarkDao {

    // Flow, not LiveData -- BookmarkRepository turns this into a StateFlow
    // for collectAsStateWithLifecycle() in Compose (see BookmarkRepository).
    @Query("SELECT * FROM bookmarks ORDER BY createdAtMs DESC")
    fun observeAll(): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks ORDER BY createdAtMs DESC")
    suspend fun getAll(): List<Bookmark>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bookmark: Bookmark)

    @Delete
    suspend fun delete(bookmark: Bookmark)

    @Query("DELETE FROM bookmarks")
    suspend fun clearAll()
}
