package com.jnetaol.voicememo.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.jnetaol.voicememo.data.model.Recording
import com.jnetaol.voicememo.data.model.VoiceTag
import com.jnetaol.voicememo.logger.VoiceLogger

@Database(entities = [Recording::class, VoiceTag::class], version = 1, exportSchema = false)
abstract class VoiceDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
    abstract fun voiceTagDao(): VoiceTagDao

    companion object {
        @Volatile private var INSTANCE: VoiceDatabase? = null
        fun getInstance(context: Context): VoiceDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
        }
        private fun buildDatabase(context: Context): VoiceDatabase = try {
            Room.databaseBuilder(context.applicationContext, VoiceDatabase::class.java, "voicememo.db")
                .fallbackToDestructiveMigration().build()
        } catch (e: Exception) {
            VoiceLogger.e("VoiceDB", "DB creation failed", "VM-DB-001", e)
            Room.databaseBuilder(context.applicationContext, VoiceDatabase::class.java, "voicememo_fallback.db")
                .fallbackToDestructiveMigration().build()
        }
    }
}
