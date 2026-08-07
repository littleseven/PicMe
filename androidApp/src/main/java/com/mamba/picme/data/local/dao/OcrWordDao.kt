package com.mamba.picme.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mamba.picme.data.local.entity.OcrWordEntity
import com.mamba.picme.data.local.entity.OcrWordOccurrence
import com.mamba.picme.data.model.MediaEntity

@Dao
interface OcrWordDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWord(word: OcrWordEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOccurrence(occ: OcrWordOccurrence)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOccurrences(occurrences: List<OcrWordOccurrence>)

    @Query(
        """
        SELECT DISTINCT m.* FROM media_assets m
        INNER JOIN ocr_word_occurrences o ON m.id = o.mediaId
        INNER JOIN ocr_words w ON o.wordId = w.wordId
        WHERE w.normalizedWord = :normalizedWord
        ORDER BY m.captureDate DESC
        """
    )
    suspend fun searchByExactWord(normalizedWord: String): List<MediaEntity>

    @Query(
        """
        SELECT DISTINCT m.* FROM media_assets m
        INNER JOIN ocr_word_occurrences o ON m.id = o.mediaId
        INNER JOIN ocr_words w ON o.wordId = w.wordId
        WHERE w.normalizedWord LIKE :prefix || '%'
        ORDER BY m.captureDate DESC
        """
    )
    suspend fun searchByWordPrefix(prefix: String): List<MediaEntity>

    @Query("SELECT * FROM ocr_words WHERE normalizedWord = :normalized LIMIT 1")
    suspend fun getWordByNormalized(normalized: String): OcrWordEntity?

    @Query("DELETE FROM ocr_word_occurrences WHERE mediaId = :mediaId")
    suspend fun clearWordsForMedia(mediaId: Long)

    @Query(
        """
        DELETE FROM ocr_words WHERE wordId NOT IN
        (SELECT DISTINCT wordId FROM ocr_word_occurrences)
        """
    )
    suspend fun cleanupOrphanWords()

    @Query("SELECT * FROM ocr_words")
    suspend fun getAllWords(): List<OcrWordEntity>

    @Query("SELECT * FROM ocr_word_occurrences")
    suspend fun getAllOccurrences(): List<OcrWordOccurrence>
}
