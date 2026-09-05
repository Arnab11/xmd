package com.invictus.xmd.database.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.invictus.xmd.database.entities.Shortcut

@Dao
interface ShortcutDao {

    @Query("SELECT * FROM shortcuts ORDER BY sortOrder ASC, createdAtMs ASC")
    fun observeAll(): LiveData<List<Shortcut>>

    @Query("SELECT * FROM shortcuts ORDER BY sortOrder ASC, createdAtMs ASC")
    suspend fun getAll(): List<Shortcut>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(shortcut: Shortcut)

    @Delete
    suspend fun delete(shortcut: Shortcut)

    @Query("DELETE FROM shortcuts WHERE id = :id")
    suspend fun deleteById(id: String)
}
