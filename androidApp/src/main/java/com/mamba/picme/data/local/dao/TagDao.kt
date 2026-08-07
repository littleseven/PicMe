package com.mamba.picme.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mamba.picme.data.local.entity.MediaTagCrossRef
import com.mamba.picme.data.local.entity.TagEntity
import com.mamba.picme.data.model.MediaEntity

@Dao
interface TagDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTags(tags: List<TagEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMediaTag(crossRef: MediaTagCrossRef)

    @Query(
        """
        SELECT t.* FROM tags t
        INNER JOIN media_tag_cross_ref m ON t.tagId = m.tagId
        WHERE m.mediaId = :mediaId
        """
    )
    suspend fun getTagsForMedia(mediaId: Long): List<TagEntity>

    @Query(
        """
        SELECT DISTINCT m.* FROM media_assets m
        INNER JOIN media_tag_cross_ref c ON m.id = c.mediaId
        INNER JOIN tags t ON c.tagId = t.tagId
        WHERE t.name LIKE '%' || :query || '%'
        ORDER BY m.captureDate DESC
        """
    )
    suspend fun searchByTagName(query: String): List<MediaEntity>

    @Query(
        """
        SELECT DISTINCT m.* FROM media_assets m
        INNER JOIN media_tag_cross_ref c ON m.id = c.mediaId
        INNER JOIN tags t ON c.tagId = t.tagId
        WHERE t.name = :exactName
        ORDER BY m.captureDate DESC
        """
    )
    suspend fun searchByExactTag(exactName: String): List<MediaEntity>

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun getTagByName(name: String): TagEntity?

    @Query("DELETE FROM media_tag_cross_ref WHERE mediaId = :mediaId")
    suspend fun clearTagsForMedia(mediaId: Long)

    @Query("SELECT * FROM tags ORDER BY name")
    suspend fun getAllTags(): List<TagEntity>
}
