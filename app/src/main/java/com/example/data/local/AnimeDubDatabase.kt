package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProjectEntity::class,
        EpisodeEntity::class,
        AudioStemEntity::class,
        TranscriptSegmentEntity::class,
        DialogueSegmentEntity::class,
        SpeakerProfileEntity::class,
        DictionaryEntryEntity::class,
        VoiceGenerationRecordEntity::class,
        TimelineTrackEntity::class,
        QualityCheckResultEntity::class,
        ModelManifestEntity::class,
        AgentJobRecordEntity::class,
        ExportRecordEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AnimeDubDatabase : RoomDatabase() {
    abstract fun dao(): AnimeDubDao

    companion object {
        @Volatile
        private var INSTANCE: AnimeDubDatabase? = null

        fun getInstance(context: Context): AnimeDubDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AnimeDubDatabase::class.java,
                    "anime_dub_studio.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
