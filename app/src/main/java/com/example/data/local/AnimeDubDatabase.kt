package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 5,
    exportSchema = false
)
abstract class AnimeDubDatabase : RoomDatabase() {
    abstract fun dao(): AnimeDubDao

    companion object {
        @Volatile
        private var INSTANCE: AnimeDubDatabase? = null

        private fun addColumnIfMissing(
            db: SupportSQLiteDatabase,
            tableName: String,
            columnName: String,
            columnDef: String
        ) {
            db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIdx >= 0 && cursor.getString(nameIdx) == columnName) {
                        return
                    }
                }
            }
            db.execSQL("ALTER TABLE `$tableName` ADD COLUMN `$columnName` $columnDef")
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "model_manifests", "modelVersion", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "model_manifests", "fileFormat", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "model_manifests", "supportedLanguages", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "model_manifests", "filesManifestJson", "TEXT NOT NULL DEFAULT '[]'")
                addColumnIfMissing(db, "model_manifests", "isSizeKnown", "INTEGER NOT NULL DEFAULT 1")
                addColumnIfMissing(db, "model_manifests", "compatibilitySummary", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "model_manifests", "verifiedFilesCount", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "model_manifests", "totalFilesCount", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "dialogue_segments", "characterId", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "dialogue_segments", "emotionConfidence", "REAL NOT NULL DEFAULT -1.0")
                addColumnIfMissing(db, "dialogue_segments", "speakerConfidence", "REAL NOT NULL DEFAULT -1.0")
                addColumnIfMissing(db, "dialogue_segments", "translationConfidence", "REAL NOT NULL DEFAULT -1.0")
                addColumnIfMissing(db, "dialogue_segments", "sceneContextNote", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "dialogue_segments", "syncStatus", "TEXT NOT NULL DEFAULT 'PENDING'")
                addColumnIfMissing(db, "dialogue_segments", "verificationStatus", "TEXT NOT NULL DEFAULT 'UNVERIFIED'")

                addColumnIfMissing(db, "speaker_profiles", "characterId", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "speaker_profiles", "visualIdentityNote", "TEXT NOT NULL DEFAULT 'Audio-Only Mode (No visual recognition)'")
                addColumnIfMissing(db, "speaker_profiles", "profileConfidence", "REAL NOT NULL DEFAULT -1.0")

                addColumnIfMissing(db, "export_records", "subtitlePathsSummary", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "export_records", "verificationReport", "TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(
                    db,
                    "model_manifests",
                    "verificationLevel",
                    "TEXT NOT NULL DEFAULT 'UNVERIFIED'"
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "dialogue_segments", "sourceDialogueRef", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "dialogue_segments", "timingStatus", "TEXT NOT NULL DEFAULT 'PENDING'")
                addColumnIfMissing(db, "dialogue_segments", "scriptValidationStatus", "TEXT NOT NULL DEFAULT 'UNVALIDATED'")
                addColumnIfMissing(db, "dictionary_entries", "preferredMixedScript", "TEXT NOT NULL DEFAULT ''")
            }
        }

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5
        )

        fun getInstance(context: Context): AnimeDubDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AnimeDubDatabase::class.java,
                    "anime_dub_studio.db"
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
