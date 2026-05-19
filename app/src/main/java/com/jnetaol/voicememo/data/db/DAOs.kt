package com.jnetaol.voicememo.data.db

import androidx.room.*
import com.jnetaol.voicememo.data.model.Recording
import com.jnetaol.voicememo.data.model.VoiceTag

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY created_at DESC") suspend fun getAll(): List<Recording>
    @Query("SELECT * FROM recordings WHERE is_favorite = 1 ORDER BY created_at DESC") suspend fun getFavorites(): List<Recording>
    @Query("SELECT * FROM recordings WHERE title LIKE '%' || :query || '%' OR transcription LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%' ORDER BY created_at DESC") suspend fun search(query: String): List<Recording>
    @Query("SELECT * FROM recordings WHERE id = :id") suspend fun getById(id: Long): Recording?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(recording: Recording): Long
    @Update suspend fun update(recording: Recording)
    @Query("UPDATE recordings SET is_favorite = :fav WHERE id = :id") suspend fun toggleFavorite(id: Long, fav: Boolean)
    @Query("UPDATE recordings SET tags = :tags WHERE id = :id") suspend fun updateTags(id: Long, tags: String)
    @Query("UPDATE recordings SET transcription = :text, is_processing = 0 WHERE id = :id") suspend fun updateTranscription(id: Long, text: String)
    @Query("DELETE FROM recordings WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM recordings") suspend fun deleteAll()
    @Query("SELECT COUNT(*) FROM recordings") suspend fun getCount(): Int
}

@Dao
interface VoiceTagDao {
    @Query("SELECT * FROM tags ORDER BY name ASC") suspend fun getAll(): List<VoiceTag>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(tag: VoiceTag): Long
    @Delete suspend fun delete(tag: VoiceTag)
}
