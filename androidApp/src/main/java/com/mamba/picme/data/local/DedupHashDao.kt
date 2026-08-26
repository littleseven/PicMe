package com.mamba.picme.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DedupHashDao {
    @Query("SELECT * FROM dedup_hash WHERE uri IN (:uris)")
    suspend fun getByUris(uris: List<String>): List<DedupHashEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<DedupHashEntity>)

    @Query("DELETE FROM dedup_hash WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)
}
