package com.mamba.picme.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mamba.picme.data.local.entity.LocationHierarchyEntity
import com.mamba.picme.data.local.entity.MediaLocationEntity
import com.mamba.picme.data.model.MediaEntity

@Dao
interface LocationDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLocation(location: LocationHierarchyEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMediaLocation(ml: MediaLocationEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMediaLocations(mls: List<MediaLocationEntity>): List<Long>

    @Query(
        """
        SELECT DISTINCT m.* FROM media_assets m
        INNER JOIN media_locations ml ON m.id = ml.mediaId
        INNER JOIN location_hierarchy l ON ml.locationId = l.locationId
        WHERE l.city LIKE '%' || :query || '%'
           OR l.district LIKE '%' || :query || '%'
           OR l.poi LIKE '%' || :query || '%'
           OR l.province LIKE '%' || :query || '%'
        ORDER BY m.captureDate DESC
        """
    )
    suspend fun searchByPlace(query: String): List<MediaEntity>

    @Query(
        """
        SELECT * FROM location_hierarchy
        WHERE ABS(latitude - :lat) < 0.0001 AND ABS(longitude - :lon) < 0.0001
        LIMIT 1
        """
    )
    suspend fun findByCoordinate(lat: Double, lon: Double): LocationHierarchyEntity?

    @Query(
        """
        SELECT * FROM location_hierarchy
        WHERE city = :city AND province = :province
        LIMIT 1
        """
    )
    suspend fun findByCityProvince(city: String?, province: String?): LocationHierarchyEntity?

    @Query("DELETE FROM media_locations WHERE mediaId = :mediaId")
    suspend fun clearLocationsForMedia(mediaId: Long)

    @Query("SELECT * FROM location_hierarchy ORDER BY city, district")
    suspend fun getAllLocations(): List<LocationHierarchyEntity>

    @Query(
        """
        SELECT m.* FROM media_assets m
        INNER JOIN media_locations ml ON m.id = ml.mediaId
        WHERE ml.locationId = :locationId
        ORDER BY m.captureDate DESC
        """
    )
    suspend fun getMediaByLocation(locationId: Long): List<MediaEntity>

    @Query("SELECT * FROM media_locations")
    suspend fun getAllMediaLocations(): List<MediaLocationEntity>
}
